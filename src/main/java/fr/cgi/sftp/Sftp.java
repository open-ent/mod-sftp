/*
 * Copyright © WebServices pour l'Éducation, 2014
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.cgi.sftp;

import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.logging.LoggerFactory;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import net.schmizz.sshj.xfer.FileSystemFile;
import org.vertx.java.busmods.BusModBase;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;

import java.io.File;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;

public class Sftp extends BusModBase implements Handler<Message<JsonObject>> {


	private final String KNOWN_HOSTS = "known-hosts";
	private final String HOSTNAME = "hostname";
	private final String PORT = "port";
	private final String USERNAME = "username";
	private final String PASSWORD = "password";
	private final String PASSPHRASE = "passphrase";
	private final String SSHKEY = "sshkey";
	private final String LOCAL_FILE = "local-file";
	private final String DIST_FILE = "dist-file";
	private final String FINGERPRINT = "fingerprint";
	private final String REMOTE_DIR = "remote-dir";
	private final String LOCAL_DIR = "local-dir";
	private final String FILE_PATTERN = "file-pattern";
	private Logger log = LoggerFactory.getLogger(Sftp.class);


	@Override
	public void start(final Promise<Void> promiseStart) throws Exception {
		super.start(promiseStart);
		JsonObject conf = config;
		String address = conf.getString("address", "sftp");
        eb.consumer(address, this);
	}

	@Override
	public void handle(Message<JsonObject> message) {
        logger.info("Message received on SFTP");
		String action = message.body().getString("action", "");
		switch (action) {
			case "send":
				sendSftp(message);
				break;
			case "list":
				listSftp(message);
				break;
			case "get":
				getSftp(message);
				break;
			default:
				sendError(message, "Invalid action.");
		}
	}

	/**
	 * Open an SSH connection and authenticate using either an SSH key or a password.
	 * <p>
	 * Host key verification order: an explicit {@code fingerprint} param is honoured first,
	 * otherwise the {@code known-hosts} file is loaded; if neither is provided the connection
	 * falls back to a promiscuous verifier (logged as a warning) so that an initial flux can
	 * be set up before the host key is pinned.
	 *
	 * @return a connected and authenticated {@link SSHClient} (caller must disconnect)
	 * @throws Exception if the connection or the authentication fails
	 */
	private SSHClient connect(JsonObject params) throws Exception {
		SSHClient ssh = new SSHClient();
		final String fingerprint = params.getString(FINGERPRINT, "");
		final String knownHosts = params.getString(KNOWN_HOSTS, "");
		if (fingerprint != null && !fingerprint.isEmpty()) {
			ssh.addHostKeyVerifier(fingerprint);
		} else if (knownHosts != null && !knownHosts.isEmpty()) {
			ssh.loadKnownHosts(new File(knownHosts));
		} else {
			logger.warn("No known-hosts file nor fingerprint provided — host key verification disabled");
			ssh.addHostKeyVerifier(new PromiscuousVerifier());
		}

		if (params.containsKey(PORT) && params.getInteger(PORT) != null) {
			ssh.connect(params.getString(HOSTNAME), params.getInteger(PORT));
		} else {
			ssh.connect(params.getString(HOSTNAME));
		}

		boolean connected = false;
		if (params.containsKey(SSHKEY) && !params.getString(SSHKEY, "").isEmpty()) {
			KeyProvider kp;
			if (params.containsKey(PASSPHRASE) && !params.getString(PASSPHRASE, "").isEmpty()) {
				kp = ssh.loadKeys(params.getString(SSHKEY), params.getString(PASSPHRASE));
			} else {
				kp = ssh.loadKeys(params.getString(SSHKEY));
			}
			List<KeyProvider> kplist = new LinkedList<>();
			kplist.add(kp);
			ssh.authPublickey(params.getString(USERNAME), kplist);
			connected = true;
		}
		if (!connected && params.containsKey(PASSWORD) && !params.getString(PASSWORD, "").isEmpty()) {
			ssh.authPassword(params.getString(USERNAME), params.getString(PASSWORD));
			connected = true;
		}
		if (!connected) {
			ssh.disconnect();
			throw new IllegalStateException("Could not authenticate to SFTP (no usable sshkey nor password)");
		}
		return ssh;
	}

	/**
	 * Send file through sftp
	 * @param message Message containing params and which will be replied to
	 */
	private void sendSftp(Message<JsonObject> message) {
		JsonObject params = message.body();
		if (!validateConnectionParams(message)) return;
		if (params.getString(LOCAL_FILE, "").isEmpty()) { sendError(message, LOCAL_FILE + " absent"); return; }
		if (params.getString(DIST_FILE, "").isEmpty()) { sendError(message, DIST_FILE + " absent"); return; }

		SSHClient ssh = null;
		SFTPClient sftp = null;
		try {
			ssh = connect(params);
			sftp = ssh.newSFTPClient();
			sftp.put(new FileSystemFile(params.getString(LOCAL_FILE)), params.getString(DIST_FILE));
			sendOK(message);
		} catch (Exception e) {
			String errorMessage = "Error when connecting to sftp server " + e.getMessage();
			logger.error(errorMessage, e);
			sendError(message, errorMessage);
		} finally {
			closeQuietly(ssh, sftp);
		}
	}

	/**
	 * List a remote directory, optionally filtering the (regular) file names by a Java regex.
	 * <p>
	 * Reply payload on success: {@code {status:"ok", files:[{name, path, size, mtime, isDirectory}]}}.
	 *
	 * @param message Message containing connection params, {@code remote-dir} and optional {@code file-pattern}
	 */
	private void listSftp(Message<JsonObject> message) {
		JsonObject params = message.body();
		if (!validateConnectionParams(message)) return;
		final String remoteDir = params.getString(REMOTE_DIR, params.getString(DIST_FILE, "."));
		final String patternStr = params.getString(FILE_PATTERN, "");
		final Pattern pattern = (patternStr != null && !patternStr.isEmpty()) ? Pattern.compile(patternStr) : null;

		SSHClient ssh = null;
		SFTPClient sftp = null;
		try {
			ssh = connect(params);
			sftp = ssh.newSFTPClient();
			List<RemoteResourceInfo> entries = sftp.ls(remoteDir);
			JsonArray files = new JsonArray();
			for (RemoteResourceInfo info : entries) {
				boolean isDir = info.isDirectory();
				if (!isDir && pattern != null && !pattern.matcher(info.getName()).matches()) {
					continue;
				}
				files.add(new JsonObject()
						.put("name", info.getName())
						.put("path", info.getPath())
						.put("size", info.getAttributes() != null ? info.getAttributes().getSize() : 0L)
						.put("mtime", info.getAttributes() != null ? info.getAttributes().getMtime() : 0L)
						.put("isDirectory", isDir));
			}
			sendOK(message, new JsonObject().put("files", files));
		} catch (Exception e) {
			String errorMessage = "Error when listing sftp directory " + e.getMessage();
			logger.error(errorMessage, e);
			sendError(message, errorMessage);
		} finally {
			closeQuietly(ssh, sftp);
		}
	}

	/**
	 * Download a remote file to the local filesystem.
	 * <p>
	 * The destination is {@code local-file} when set, otherwise {@code local-dir} + the remote
	 * file name. Reply payload on success: {@code {status:"ok", "local-file":<absolutePath>}}.
	 *
	 * @param message Message containing connection params, {@code dist-file} and {@code local-file}/{@code local-dir}
	 */
	private void getSftp(Message<JsonObject> message) {
		JsonObject params = message.body();
		if (!validateConnectionParams(message)) return;
		final String distFile = params.getString(DIST_FILE, "");
		if (distFile.isEmpty()) { sendError(message, DIST_FILE + " absent"); return; }

		String localFile = params.getString(LOCAL_FILE, "");
		if (localFile.isEmpty()) {
			final String localDir = params.getString(LOCAL_DIR, "");
			if (localDir.isEmpty()) { sendError(message, LOCAL_FILE + " or " + LOCAL_DIR + " absent"); return; }
			final String name = distFile.contains("/") ? distFile.substring(distFile.lastIndexOf('/') + 1) : distFile;
			localFile = new File(localDir, name).getAbsolutePath();
		}

		SSHClient ssh = null;
		SFTPClient sftp = null;
		try {
			File target = new File(localFile);
			if (target.getParentFile() != null) {
				target.getParentFile().mkdirs();
			}
			ssh = connect(params);
			sftp = ssh.newSFTPClient();
			sftp.get(distFile, new FileSystemFile(target));
			sendOK(message, new JsonObject().put(LOCAL_FILE, target.getAbsolutePath()));
		} catch (Exception e) {
			String errorMessage = "Error when downloading from sftp server " + e.getMessage();
			logger.error(errorMessage, e);
			sendError(message, errorMessage);
		} finally {
			closeQuietly(ssh, sftp);
		}
	}

	private void closeQuietly(SSHClient ssh, SFTPClient sftp) {
		try {
			if (sftp != null) sftp.close();
		} catch (Exception e) {
			logger.warn("Error when closing SFTP client ", e);
		}
		try {
			if (ssh != null) ssh.disconnect();
		} catch (Exception e) {
			logger.warn("Error when disconnecting from SSH ", e);
		}
	}

	/**
	 * Validate the connection params shared by all actions (host, user, and at least one
	 * authentication method). Replies an error to the message and returns {@code false} otherwise.
	 */
	private boolean validateConnectionParams(Message<JsonObject> message) {
		JsonObject params = message.body();
		if (params == null) {
			sendError(message, " params is required");
			return false;
		}
		if (params.getString(HOSTNAME, "").isEmpty()) {
			sendError(message, HOSTNAME + " absent");
			return false;
		}
		if (params.getString(USERNAME, "").isEmpty()) {
			sendError(message, USERNAME + " absent");
			return false;
		}
		if (params.getString(PASSWORD, "").isEmpty() && params.getString(SSHKEY, "").isEmpty()) {
			sendError(message, PASSWORD + " and " + SSHKEY + " absent");
			return false;
		}
		if (params.containsKey(PORT) && params.getInteger(PORT) == null) {
			params.remove(PORT);
			logger.warn("Wrong port format");
		}
		return true;
	}
}
