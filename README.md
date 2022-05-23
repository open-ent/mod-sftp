# About Module SFTP
* Licence : [AGPL v3](http://www.gnu.org/licenses/agpl.txt) - Copyright Régions Ile De France et Nouvelle Aquitaine, Département de Seine et Marne et ville de Paris
* Financer : Régions Ile De France et Nouvelle Aquitaine, Département de Seine et Marne et ville de Paris
* Developer : CGI
* Description : This module allows you to send files through SFTP.

## Configuration
The mod-sftp module takes the following configuration:

	{
		"address": <address>
	}

Where
* address The main address for the module. Every module has a main address. Defaults to `sftp` 

## Operations

The module supports the following operation

### Send

Send a file or directory do a distant server through sftp

To send a file, send a JSON message to the module main address:

	{
		"action" : "send",
		"known-hosts" : <known-hosts>,
		"hostname" : <hostname>,
		"port" : <port>,
		"username" : <username>,
		"password" : <password>,
		"sshkey" : <sshkey>,
		"local-file" : <local-file>,
		"dist-file" : <dist-key>
	}

Where:
* `known-hosts` is the filename of local known_hosts file. It must contains the informations on the host to connect to. This field is mandatory.
* `hostname` is the destination host. This field is mandatory.
* `port` is a number. This field is optionnal, standard port will be used by default.
* `username` is the name of the SFTP user to use. This field is mandatory.
* `password` is the password of the SFTP user to use. Password OR SSH Key is mandatory.
* `sshkey` is the filename of the private RSA key to use for connection. If the file can't be loaded and password is filled, password will be used. Password OR SSH Key is mandatory.
* `local-file` is the local filename of the file ou directory to send
* `dist-file` is the distant path and filename where to send the file or directory

An example would be:

	{
		"action" : "send",
		"known-hosts" : "/home/vertx/.ssh/known_hosts",
		"hostname" : "sftp.example.com",
		"username" : "sftpuser",
		"sshkey" : "/home/vertx/.ssh/id_dsa",
		"local-file" : "src/test/resources/img.jpg",
		"dist-file" : "/vertx/imgsrc.jpg"
	}

When the query complete successfully, a reply message is sent back to the sender with the following data:

	{
		"status": "ok",
	}

If an error occurs in saving the document a reply is returned:

	{
		"status": "error",
		"message": <message>
	}

Where
* `message` is an error message.

## Dependencies

- SSHJ : https://github.com/hierynomus/sshj