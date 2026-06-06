package com.cicdassistant.util;

public class OsUtil {
    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public static String mvnExecutable(String mavenHome) {
        String exe = isWindows() ? "mvn.cmd" : "mvn";
        if (mavenHome == null || mavenHome.trim().isEmpty()) {
            return exe;
        }
        String sep = System.getProperty("file.separator");
        return mavenHome + sep + "bin" + sep + exe;
    }
}
