package com.robomotion.app;

import org.apache.commons.lang3.SystemUtils;
import org.apache.maven.artifact.versioning.ComparableVersion;

public final class Utils {

	public static class File {
		public static boolean DirExists(String dir) {
			java.io.File file = new java.io.File(dir);
			return file.exists() && file.isDirectory();
		}

		public static boolean FileExists(String dir) {
			java.io.File file = new java.io.File(dir);
			return file.exists() && !file.isDirectory();
		}

		public static String UserHomeDir() {
			if (SystemUtils.IS_OS_WINDOWS) {
				String home = System.getenv("HOMEDRIVE") + System.getenv("HOMEPATH");
				if (home == "") {
					home = System.getenv("USERPROFILE");
				}
				return home;
			}

			return System.getenv("HOME");
		}

		public static String TempDir() {
			String home = UserHomeDir();
			return SystemUtils.IS_OS_WINDOWS ? home + "\\AppData\\Local\\Temp\\Robomotion" : "/tmp/robomotion";
		}
	}

	public static class Version {
		public static boolean IsVersionLessThan(String ver, String other) {
			return (new ComparableVersion(ver)).compareTo(new ComparableVersion(other)) < 0;
		}
	}
}
