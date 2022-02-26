package dev.espi.ebackup;

import java.util.logging.Logger;

public class eBackup {
	public static final Logger LOGGER;
	public static String cronTask;

	static {
		LOGGER = Logger.getLogger("eBackup");
		LOGGER.info("Logger created.");
	}
}
