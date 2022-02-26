package dev.espi.ebackup;

import dev.espi.ebackup.util.AbstractBackupUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public class eBackup {
	public static final Logger LOGGER;
	public static String cronTask;
	public static AbstractBackupUtil backupUtil;

	public static boolean backupPluginsAndMods;

	// FTP
	static String ftpType, ftpHost, ftpUser, ftpPass, ftpPath, sftpPrivateKeyPath, sftpPrivateKeyPassword;
	static int ftpPort;
	static boolean ftpEnable, useSftpKeyAuth;

	// Lock
	public static AtomicBoolean isInBackup = new AtomicBoolean(false);
	public static AtomicBoolean isInUpload = new AtomicBoolean(false);

	// Config Options
	public static String backupFormat, backupDateFormat;
	public static File backupPath;
	static int maxBackups;
	static boolean onlyBackupIfPlayersWereOn;
	static boolean deleteAfterUpload;
	public static int compressionLevel;

	static List<String> filesToIgnore;
	public static List<File> ignoredFiles = new ArrayList<>();

	static {
		LOGGER = Logger.getLogger("eBackup");
	}
}
