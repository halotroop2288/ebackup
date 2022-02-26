package dev.espi.ebackup.util;

import dev.espi.ebackup.eBackup;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public abstract class AbstractBackupUtil {
	// recursively compress files and directories
	protected static void zipFile(File fileToZip, String fileName, ZipOutputStream zipOut) throws IOException {
		// don't ignore hidden folders
		// if (fileToZip.isHidden() && !fileToZip.getPath().equals(".")) return;

		for (File f : eBackup.ignoredFiles) { // return if it is ignored file
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
		// truncate \. on Windows (from the end of folder names)
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
				eBackup.LOGGER.warning("Error while backing up file " + fileName + ", backup will ignore this file: " + e.getMessage());
			}
		}
	}
	public abstract void deleteAfterUpload(File file);

	public abstract void testUpload();
	public abstract void uploadTask(String fileName, boolean testing);

	// actually do the backup
	// run async please
	public abstract void doBackup(boolean uploadToServer);

	protected abstract void uploadSFTP(File f, boolean testing);
	protected abstract void uploadFTP(File f, boolean testing);

	protected abstract void checkMaxBackups();
}
