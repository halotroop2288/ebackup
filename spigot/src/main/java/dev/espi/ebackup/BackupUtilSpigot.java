package dev.espi.ebackup;

import com.jcraft.jsch.*;
import dev.espi.ebackup.util.AbstractBackupUtil;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipOutputStream;

public final class BackupUtilSpigot extends AbstractBackupUtil {
	public void deleteAfterUpload(File f) {
		if (eBackup.deleteAfterUpload) {
			Bukkit.getScheduler().runTaskAsynchronously(eBackupSpigot.getPlugin(), () -> {
				if (f.delete()) {
					eBackup.LOGGER.info("Successfully deleted " + f.getName() + " after upload.");
				} else {
					eBackup.LOGGER.warning("Unable to delete " + f.getName() + " after upload.");
				}
			});
		}
	}

	@SuppressWarnings("BusyWait")
	public void doBackup(boolean uploadToServer) {
		eBackup.LOGGER.info("Starting backup...");

		// do not back up when plugin is disabled
		if (!eBackupSpigot.getPlugin().isEnabled()) {
			eBackup.LOGGER.warning("Unable to start a backup, because the plugin is disabled by the server!");
			return;
		}

		List<File> tempIgnore = new ArrayList<>();

		// prevent other processes from backing up at the same time
		eBackup.isInBackup.set(true);

		File currentWorkingDirectory = new File(Paths.get(".").toAbsolutePath().normalize().toString());

		try {
			// find plugin data to ignore
			File[] files = new File("plugins").listFiles();
			if (files != null) {
				for (File f : files) {
					if ((!eBackup.backupPluginsAndMods && f.getName().endsWith(".jar")) || (!eBackupSpigot.getPlugin().backupPluginConfs && f.isDirectory())) {
						tempIgnore.add(f);
						eBackup.ignoredFiles.add(f);
					}
				}
			}

			// delete old backups
			checkMaxBackups();

			// zip
			SimpleDateFormat formatter = new SimpleDateFormat(eBackup.backupDateFormat);
			String fileName = eBackup.backupFormat.replace("{DATE}", formatter.format(new Date()));
			FileOutputStream fos = new FileOutputStream(eBackup.backupPath + "/" + fileName + ".zip");
			ZipOutputStream zipOut = new ZipOutputStream(fos);

			// set zip compression level
			zipOut.setLevel(eBackup.compressionLevel);

			// backup worlds first
			for (World w : Bukkit.getWorlds()) {
				File worldFolder = w.getWorldFolder();

				String worldPath = Paths.get(currentWorkingDirectory.toURI()).relativize(Paths.get(worldFolder.toURI())).toString();
				if (worldPath.endsWith("/.")) {// 1.16 world folders end with /. for some reason
					worldPath = worldPath.substring(0, worldPath.length() - 2);
					worldFolder = new File(worldPath);
				}

				// check if world is in ignored list
				boolean skip = false;
				for (File f : eBackup.ignoredFiles) {
					if (f.getCanonicalPath().equals(worldFolder.getCanonicalPath())) {
						skip = true;
						break;
					}
				}
				if (skip) continue;

				// manually trigger world save (needs to be run sync)
				AtomicBoolean saved = new AtomicBoolean(false);
				Bukkit.getScheduler().runTask(eBackupSpigot.getPlugin(), () -> {
					w.save();
					saved.set(true);
				});

				// wait until world save is finished
				while (!saved.get()) Thread.sleep(500);

				w.setAutoSave(false); // make sure auto-save doesn't screw everything over

				eBackup.LOGGER.info("Backing up world " + w.getName() + " " + worldPath + "...");
				zipFile(worldFolder, worldPath, zipOut);

				w.setAutoSave(true);

				// ignore in dfs
				tempIgnore.add(worldFolder);
				eBackup.ignoredFiles.add(worldFolder);
			}

			// dfs all other files
			eBackup.LOGGER.info("Backing up other files...");
			zipFile(currentWorkingDirectory, "", zipOut);
			zipOut.close();
			fos.close();

			// upload to ftp/sftp
			if (uploadToServer && eBackup.ftpEnable) {
				uploadTask(eBackup.backupPath + "/" + fileName + ".zip", false);
			}

		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			for (World w : Bukkit.getWorlds()) {
				w.setAutoSave(true);
			}
			// restore tempIgnore
			for (File f : tempIgnore) {
				eBackup.ignoredFiles.remove(f);
			}

			// unlock
			eBackup.isInBackup.set(false);
		}
		eBackup.LOGGER.info("Local backup complete!");
	}

	@Override
	public void testUpload() {
		try {
			File temp = new File(eBackupSpigot.getPlugin().getDataFolder() + "/uploadtest.txt");
			temp.createNewFile();
			uploadTask(temp.toString(), true);
		} catch (Exception e) {
			e.printStackTrace();
			eBackup.LOGGER.warning("Error creating temporary file.");
		}
	}

	public void uploadTask(String fileName, boolean testing) {
		if (eBackup.isInUpload.get()) {
			eBackup.LOGGER.warning("A upload was scheduled to happen now, but an upload was detected to be in progress. Skipping...");
			return;
		}

		boolean isSFTP = eBackup.ftpType.equals("sftp"), isFTP = eBackup.ftpType.equals("ftp");
		if (!isSFTP && !isFTP) {
			eBackup.LOGGER.warning("Invalid upload type specified (only ftp/sftp accepted). Skipping upload...");
			return;
		}

		eBackup.LOGGER.info(String.format("Starting upload of %s to %s server...", fileName, isSFTP ? "SFTP" : "FTP"));
		Bukkit.getScheduler().runTaskAsynchronously(eBackupSpigot.getPlugin(), () -> {
			try {
				eBackup.isInUpload.set(true);

				File f = new File(fileName);
				if (isSFTP) {
					uploadSFTP(f, testing);
				} else {
					uploadFTP(f, testing);
				}

				// delete testing file
				if (testing) {
					f.delete();
					eBackup.LOGGER.info("Test upload successful!");
				} else {
					eBackup.LOGGER.info("Upload of " + fileName + " has succeeded!");
				}
			} catch (Exception e) {
				e.printStackTrace();
				eBackup.LOGGER.info("Upload of " + fileName + " has failed.");
			} finally {
				eBackup.isInUpload.set(false);
			}
		});
	}

	protected void uploadSFTP(File f, boolean testing) {
		try {
			JSch jsch = new JSch();

			// ssh key auth if enabled
			if (eBackup.useSftpKeyAuth) {
				if (eBackup.sftpPrivateKeyPassword.equals("")) {
					jsch.addIdentity(eBackup.sftpPrivateKeyPath);
				} else {
					jsch.addIdentity(eBackup.sftpPrivateKeyPath, eBackup.sftpPrivateKeyPassword);
				}
			}

			Session session = jsch.getSession(eBackup.ftpUser, eBackup.ftpHost, eBackup.ftpPort);
			// password auth if using password
			if (!eBackup.useSftpKeyAuth) {
				session.setPassword(eBackup.ftpPass);
			}
			session.setConfig("StrictHostKeyChecking", "no");
			session.connect();

			Channel channel = session.openChannel("sftp");
			channel.connect();
			ChannelSftp sftpChannel = (ChannelSftp) channel;
			sftpChannel.put(f.getAbsolutePath(), eBackup.ftpPath);

			if (testing) {
				// delete testing file
				sftpChannel.rm(eBackup.ftpPath + "/" + f.getName());
			}

			sftpChannel.exit();
			session.disconnect();

			if (!testing) {
				eBackup.backupUtil.deleteAfterUpload(f);
			}
		} catch (JSchException | SftpException e) {
			e.printStackTrace();
		}
	}

	protected void uploadFTP(File f, boolean testing) {
		FTPClient ftpClient = new FTPClient();
		try (FileInputStream fio = new FileInputStream(f)) {
			ftpClient.setDataTimeout(180 * 1000);
			ftpClient.setConnectTimeout(180 * 1000);
			ftpClient.setDefaultTimeout(180 * 1000);
			ftpClient.setControlKeepAliveTimeout(60);

			ftpClient.connect(eBackup.ftpHost, eBackup.ftpPort);
			ftpClient.enterLocalPassiveMode();

			ftpClient.login(eBackup.ftpUser, eBackup.ftpPass);
			ftpClient.setUseEPSVwithIPv4(true);

			ftpClient.changeWorkingDirectory(eBackup.ftpPath);
			ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
			ftpClient.setBufferSize(1024 * 1024 * 16);

			if (ftpClient.storeFile(f.getName(), fio)) {
				if (testing) {
					// delete testing file
					ftpClient.deleteFile(f.getName());
				} else {
					eBackup.backupUtil.deleteAfterUpload(f);
				}
			} else {
				// ensure that an error message is printed if the file cannot be stored
				throw new IOException();
			}
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			try {
				ftpClient.disconnect();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	// delete old backups (when limit reached)
	protected void checkMaxBackups() {
		if (eBackup.maxBackups <= 0) return;

		int backups = 0;
		SortedMap<Long, File> m = new TreeMap<>(); // oldest files to newest

		File[] files = eBackup.backupPath.listFiles();
		if (files != null) {
			for (File f : files) {
				if (f.getName().endsWith(".zip")) {
					backups++;
					m.put(f.lastModified(), f);
				}
			}
		}

		// delete old backups
		while (backups-- >= eBackup.maxBackups) {
			m.get(m.firstKey()).delete();
			m.remove(m.firstKey());
		}
	}
}
