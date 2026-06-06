package com.cicdassistant.util;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;

@Slf4j
public class ProcessManager {

    public static long getPid(Process process) {
        try {
            Field f = process.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            return f.getLong(process);
        } catch (Throwable t) {
            try {
                Field f = process.getClass().getDeclaredField("handle");
                f.setAccessible(true);
                long handle = f.getLong(process);
                return handle;
            } catch (Throwable t2) {
                log.warn("Could not get pid via reflection: {}", t2.getMessage());
                return -1;
            }
        }
    }

    public static long getPgid(long pid) {
        if (OsUtil.isWindows() || pid <= 0) return -1;
        try {
            Process p = new ProcessBuilder("ps", "-o", "pgid=", "-p", String.valueOf(pid))
                    .redirectErrorStream(true)
                    .start();
            byte[] buf = new byte[256];
            int n = p.getInputStream().read(buf);
            p.waitFor();
            if (n > 0) {
                String s = new String(buf, 0, n).trim();
                return Long.parseLong(s);
            }
        } catch (Exception e) {
            log.warn("getPgid failed: {}", e.getMessage());
        }
        return -1;
    }

    public static void killTree(Long pid, Long pgid) {
        if (OsUtil.isWindows()) {
            if (pid != null && pid > 0) {
                runQuiet("taskkill", "/T", "/F", "/PID", String.valueOf(pid));
            }
        } else {
            if (pgid != null && pgid > 0) {
                runQuiet("kill", "-9", "--", "-" + pgid);
            } else if (pid != null && pid > 0) {
                runQuiet("kill", "-9", String.valueOf(pid));
            }
        }
    }

    private static void runQuiet(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.waitFor();
        } catch (Exception e) {
            log.warn("kill cmd failed {}: {}", String.join(" ", cmd), e.getMessage());
        }
    }
}
