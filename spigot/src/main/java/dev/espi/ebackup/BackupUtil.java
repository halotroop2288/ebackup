package dev.espi.ebackup;

import com.jcraft.jsch.*;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.*;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/*
   Copyright 2020 EspiDev

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

public class BackupUtil {

    // delete old backups (when limit reached)
    private static void checkMaxBackups() {
        if (eBackupSpigot.getPlugin().maxBackups <= 0) return;

        int backups = 0;
        SortedMap<Long, File> m = new TreeMap<>(); // oldest files to newest

        File[] files = eBackupSpigot.getPlugin().backupPath.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().endsWith(".zip")) {
                    backups++;
                    m.put(f.lastModified(), f);
                }
            }
        }

        // delete old backups
        while (backups-- >= eBackupSpigot.getPlugin().maxBackups) {
            m.get(m.firstKey()).delete();
            m.remove(m.firstKey());
        }
    }

    // actually do the backup
    // run async please
    @SuppressWarnings("BusyWait")
    public static void doBackup(boolean uploadToServer) {
        List<File> tempIgnore = new ArrayList<>();
        eBackupSpigot.getPlugin().getLogger().info("Starting backup...");

        // do not backup when plugin is disabled
        if (!eBackupSpigot.getPlugin().isEnabled()) {
            eBackupSpigot.getPlugin().getLogger().warning("Unable to start a backup, because the plugin is disabled by the server!");
            return;
        }

        // prevent other processes from backing up at the same time
        eBackupSpigot.getPlugin().isInBackup.set(true);

        File currentWorkingDirectory = new File(Paths.get(".").toAbsolutePath().normalize().toString());

        try {
            // find plugin data to ignore
            File[] files = new File("plugins").listFiles();
            if (files != null) {
                for (File f : files) {
                    if ((!eBackupSpigot.getPlugin().backupPluginJars && f.getName().endsWith(".jar")) || (!eBackupSpigot.getPlugin().backupPluginConfs && f.isDirectory())) {
                        tempIgnore.add(f);
                        eBackupSpigot.getPlugin().ignoredFiles.add(f);
                    }
                }
            }

            // delete old backups
            checkMaxBackups();

            // zip
            SimpleDateFormat formatter = new SimpleDateFormat(eBackupSpigot.getPlugin().backupDateFormat);
            String fileName = eBackupSpigot.getPlugin().backupFormat.replace("{DATE}", formatter.format(new Date()));
            FileOutputStream fos = new FileOutputStream(eBackupSpigot.getPlugin().backupPath + "/" + fileName + ".zip");
            ZipOutputStream zipOut = new ZipOutputStream(fos);

            // set zip compression level
            zipOut.setLevel(eBackupSpigot.getPlugin().compressionLevel);

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
                for (File f : eBackupSpigot.getPlugin().ignoredFiles) {
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

                w.setAutoSave(false); // make sure autosave doesn't screw everything over

                eBackupSpigot.getPlugin().getLogger().info("Backing up world " + w.getName() + " " + worldPath + "...");
                zipFile(worldFolder, worldPath, zipOut);

                w.setAutoSave(true);

                // ignore in dfs
                tempIgnore.add(worldFolder);
                eBackupSpigot.getPlugin().ignoredFiles.add(worldFolder);
            }

            // dfs all other files
            eBackupSpigot.getPlugin().getLogger().info("Backing up other files...");
            zipFile(currentWorkingDirectory, "", zipOut);
            zipOut.close();
            fos.close();

            // upload to ftp/sftp
            if (uploadToServer && eBackupSpigot.getPlugin().ftpEnable) {
                uploadTask(eBackupSpigot.getPlugin().backupPath + "/" + fileName + ".zip", false);
            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            for (World w : Bukkit.getWorlds()) {
                w.setAutoSave(true);
            }
            // restore tempignore
            for (File f : tempIgnore) {
                eBackupSpigot.getPlugin().ignoredFiles.remove(f);
            }

            // unlock
            eBackupSpigot.getPlugin().isInBackup.set(false);
        }
        eBackupSpigot.getPlugin().getLogger().info("Local backup complete!");
    }

    public static void testUpload() {
        try {
            File temp = new File(eBackupSpigot.getPlugin().getDataFolder() + "/uploadtest.txt");
            temp.createNewFile();
            uploadTask(temp.toString(), true);
        } catch (Exception e) {
            e.printStackTrace();
            eBackupSpigot.getPlugin().getLogger().warning("Error creating temporary file.");
        }
    }

    private static void uploadTask(String fileName, boolean testing) {
        if (eBackupSpigot.getPlugin().isInUpload.get()) {
            eBackupSpigot.getPlugin().getLogger().warning("A upload was scheduled to happen now, but an upload was detected to be in progress. Skipping...");
            return;
        }

        boolean isSFTP = eBackupSpigot.getPlugin().ftpType.equals("sftp"), isFTP = eBackupSpigot.getPlugin().ftpType.equals("ftp");
        if (!isSFTP && !isFTP) {
            eBackupSpigot.getPlugin().getLogger().warning("Invalid upload type specified (only ftp/sftp accepted). Skipping upload...");
            return;
        }

        eBackupSpigot.getPlugin().getLogger().info(String.format("Starting upload of %s to %s server...", fileName, isSFTP ? "SFTP" : "FTP"));
        Bukkit.getScheduler().runTaskAsynchronously(eBackupSpigot.getPlugin(), () -> {
            try {
                eBackupSpigot.getPlugin().isInUpload.set(true);

                File f = new File(fileName);
                if (isSFTP) {
                    uploadSFTP(f, testing);
                } else {
                    uploadFTP(f, testing);
                }

                // delete testing file
                if (testing) {
                    f.delete();
                    eBackupSpigot.getPlugin().getLogger().info("Test upload successful!");
                } else {
                    eBackupSpigot.getPlugin().getLogger().info("Upload of " + fileName + " has succeeded!");
                }
            } catch (Exception e) {
                e.printStackTrace();
                eBackupSpigot.getPlugin().getLogger().info("Upload of " + fileName + " has failed.");
            } finally {
                eBackupSpigot.getPlugin().isInUpload.set(false);
            }
        });
    }

    private static void uploadSFTP(File f, boolean testing) throws JSchException, SftpException {
        JSch jsch = new JSch();

        // ssh key auth if enabled
        if (eBackupSpigot.getPlugin().useSftpKeyAuth) {
            if (eBackupSpigot.getPlugin().sftpPrivateKeyPassword.equals("")) {
                jsch.addIdentity(eBackupSpigot.getPlugin().sftpPrivateKeyPath);
            } else {
                jsch.addIdentity(eBackupSpigot.getPlugin().sftpPrivateKeyPath, eBackupSpigot.getPlugin().sftpPrivateKeyPassword);
            }
        }

        Session session = jsch.getSession(eBackupSpigot.getPlugin().ftpUser, eBackupSpigot.getPlugin().ftpHost, eBackupSpigot.getPlugin().ftpPort);
        // password auth if using password
        if (!eBackupSpigot.getPlugin().useSftpKeyAuth) {
            session.setPassword(eBackupSpigot.getPlugin().ftpPass);
        }
        session.setConfig("StrictHostKeyChecking", "no");
        session.connect();

        Channel channel = session.openChannel("sftp");
        channel.connect();
        ChannelSftp sftpChannel = (ChannelSftp) channel;
        sftpChannel.put(f.getAbsolutePath(), eBackupSpigot.getPlugin().ftpPath);

        if (testing) {
            // delete testing file
            sftpChannel.rm(eBackupSpigot.getPlugin().ftpPath + "/" + f.getName());
        }

        sftpChannel.exit();
        session.disconnect();

        if (!testing) {
            deleteAfterUpload(f);
        }
    }

    private static void uploadFTP(File f, boolean testing) throws IOException {
        FTPClient ftpClient = new FTPClient();
        try (FileInputStream fio = new FileInputStream(f)) {
            ftpClient.setDataTimeout(180 * 1000);
            ftpClient.setConnectTimeout(180 * 1000);
            ftpClient.setDefaultTimeout(180 * 1000);
            ftpClient.setControlKeepAliveTimeout(60);

            ftpClient.connect(eBackupSpigot.getPlugin().ftpHost, eBackupSpigot.getPlugin().ftpPort);
            ftpClient.enterLocalPassiveMode();

            ftpClient.login(eBackupSpigot.getPlugin().ftpUser, eBackupSpigot.getPlugin().ftpPass);
            ftpClient.setUseEPSVwithIPv4(true);

            ftpClient.changeWorkingDirectory(eBackupSpigot.getPlugin().ftpPath);
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
            ftpClient.setBufferSize(1024 * 1024 * 16);

            if (ftpClient.storeFile(f.getName(), fio)) {
                if (testing) {
                    // delete testing file
                    ftpClient.deleteFile(f.getName());
                } else {
                    deleteAfterUpload(f);
                }
            } else {
                // ensure that an error message is printed if the file cannot be stored
                throw new IOException();
            }
        } finally {
            try {
                ftpClient.disconnect();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static void deleteAfterUpload(File f) {
        if (eBackupSpigot.getPlugin().deleteAfterUpload) {
            Bukkit.getScheduler().runTaskAsynchronously(eBackupSpigot.getPlugin(), () -> {
                if (f.delete()) {
                    eBackupSpigot.getPlugin().getLogger().info("Successfully deleted " + f.getName() + " after upload.");
                } else {
                    eBackupSpigot.getPlugin().getLogger().warning("Unable to delete " + f.getName() + " after upload.");
                }
            });
        }
    }

    // recursively compress files and directories
    private static void zipFile(File fileToZip, String fileName, ZipOutputStream zipOut) throws IOException {
        // don't ignore hidden folders
        // if (fileToZip.isHidden() && !fileToZip.getPath().equals(".")) return;

        for (File f : eBackupSpigot.getPlugin().ignoredFiles) { // return if it is ignored file
            if (f.getCanonicalPath().equals(fileToZip.getCanonicalPath())) return;
        }

        // fix windows archivers not being able to see files because they don't support / (root) for zip files
        if (fileName.startsWith("/") || fileName.startsWith("\\")) {
            fileName = fileName.substring(1);
        }
        // make sure there won't be a "." folder
        if (fileName.startsWith("./") || fileName.startsWith(".\\")) {
            fileName = fileName.substring(2);
        }
        // truncate \. on windows (from the end of folder names)
        if (fileName.endsWith("/.") || fileName.endsWith("\\.")) {
            fileName = fileName.substring(0, fileName.length()-2);
        }

        if (fileToZip.isDirectory()) { // if it's a directory, recursively search
            if (fileName.endsWith("/")) {
                zipOut.putNextEntry(new ZipEntry(fileName));
            } else {
                zipOut.putNextEntry(new ZipEntry(fileName + "/"));
            }
            zipOut.closeEntry();
            File[] children = fileToZip.listFiles();
            if (children != null) {
                for (File childFile : children) {
                    zipFile(childFile, fileName + "/" + childFile.getName(), zipOut);
                }
            }
        } else { // if it's a file, store
            try {
                FileInputStream fis = new FileInputStream(fileToZip);
                ZipEntry zipEntry = new ZipEntry(fileName);
                zipOut.putNextEntry(zipEntry);
                byte[] bytes = new byte[1024];
                int length;
                while ((length = fis.read(bytes)) >= 0) {
                    zipOut.write(bytes, 0, length);
                }
                fis.close();
            } catch (IOException e) {
                eBackupSpigot.getPlugin().getLogger().warning("Error while backing up file " + fileName + ", backup will ignore this file: " + e.getMessage());
            }
        }
    }
}
