package com.cicdassistant.util;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

@Slf4j
public class ProcessManager {

    private static volatile Long ownPidCache;

    /**
     * 解析子进程真实 PID。
     *
     * Linux JDK 8：ProcessImpl 有 pid 字段 → 反射直接拿
     * Windows JDK 8：ProcessImpl 只有 handle（内核句柄，不是 PID）。
     *   先反射看有没有 pid（JDK 9+ Windows 有），没有就走 wmic：
     *   找父进程是 cicd-assistant 自己、且命令行匹配 matchToken 的子进程。
     *
     * @param matchToken 命令行里能唯一识别该子进程的子串，通常传 jar 绝对路径
     */
    public static long resolvePid(Process process, String matchToken) {
        long pid = readPidField(process);
        if (pid > 0) return pid;
        if (OsUtil.isWindows()) {
            return resolveWindowsPidViaWmic(matchToken);
        }
        long handle = readHandleField(process);
        if (handle > 0) return handle;
        return -1;
    }

    private static long readPidField(Process process) {
        try {
            Field f = process.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            long v = f.getLong(process);
            return v > 0 ? v : -1;
        } catch (Throwable ignore) {
            return -1;
        }
    }

    private static long readHandleField(Process process) {
        try {
            Field f = process.getClass().getDeclaredField("handle");
            f.setAccessible(true);
            return f.getLong(process);
        } catch (Throwable ignore) {
            return -1;
        }
    }

    private static long getOwnPid() {
        if (ownPidCache != null) return ownPidCache;
        try {
            String name = ManagementFactory.getRuntimeMXBean().getName();
            ownPidCache = Long.parseLong(name.split("@")[0]);
        } catch (Exception e) {
            ownPidCache = -1L;
        }
        return ownPidCache;
    }

    private static long resolveWindowsPidViaWmic(String matchToken) {
        long ownPid = getOwnPid();
        if (ownPid <= 0 || matchToken == null || matchToken.isEmpty()) {
            log.warn("[KILL] cannot resolve windows pid (ownPid={}, matchToken={})", ownPid, matchToken);
            return -1;
        }
        // 短暂等一下，让子进程出现在 wmic 视野里（刚 start() 时可能查不到）
        for (int i = 0; i < 8; i++) {
            try { Thread.sleep(250); } catch (InterruptedException ignore) {}
            long pid = queryWmicChild(ownPid, matchToken);
            if (pid > 0) {
                log.info("[KILL] resolved windows child pid={} parent={} match={}", pid, ownPid, matchToken);
                return pid;
            }
        }
        log.warn("[KILL] wmic did not find child of pid={} matching '{}'", ownPid, matchToken);
        return -1;
    }

    private static long queryWmicChild(long ownPid, String matchToken) {
        try {
            Process p = new ProcessBuilder(
                    "wmic", "process", "where",
                    "ParentProcessId=" + ownPid,
                    "get", "ProcessId,CommandLine",
                    "/format:list")
                    .redirectErrorStream(true)
                    .start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String line;
            String pendingCmd = null;
            long pendingPid = -1;
            long lastSeenMatchingPid = -1;
            // /format:list 输出形如：
            //   CommandLine=java -jar ...
            //   ProcessId=12345
            //   (空行)
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    if (pendingCmd != null && pendingCmd.contains(matchToken) && pendingPid > 0) {
                        lastSeenMatchingPid = pendingPid;
                    }
                    pendingCmd = null;
                    pendingPid = -1;
                } else if (trimmed.startsWith("CommandLine=")) {
                    pendingCmd = trimmed.substring("CommandLine=".length());
                } else if (trimmed.startsWith("ProcessId=")) {
                    try {
                        pendingPid = Long.parseLong(trimmed.substring("ProcessId=".length()));
                    } catch (NumberFormatException ignore) {}
                }
            }
            p.waitFor();
            return lastSeenMatchingPid;
        } catch (Exception e) {
            log.warn("wmic query failed: {}", e.getMessage());
            return -1;
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

    /**
     * 杀掉进程树。
     *
     * @param process 子进程对象（可为 null）。非空时会先调 destroyForcibly() 兜底杀直系子进程。
     * @param pid     子进程 PID（Windows 上必须是真 PID，不是 handle）。
     * @param pgid    进程组 ID（仅 Linux 用）。
     */
    public static void killTree(Process process, Long pid, Long pgid) {
        boolean destroyed = false;
        if (process != null) {
            try {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    destroyed = true;
                }
            } catch (Throwable t) {
                log.warn("[KILL] destroyForcibly failed: {}", t.getMessage());
            }
        }
        if (OsUtil.isWindows()) {
            if (pid != null && pid > 0) {
                log.info("[KILL] taskkill /F /T /PID {}", pid);
                runQuiet("taskkill", "/F", "/T", "/PID", String.valueOf(pid));
            } else if (!destroyed) {
                log.warn("[KILL] no valid windows pid and no process handle, cannot kill tree");
            }
        } else {
            if (pgid != null && pgid > 0) {
                log.info("[KILL] kill -9 -- -{} (pgid)", pgid);
                runQuiet("kill", "-9", "--", "-" + pgid);
            } else if (pid != null && pid > 0) {
                log.info("[KILL] kill -9 {} (pid only, may leave children)", pid);
                runQuiet("kill", "-9", String.valueOf(pid));
            }
        }
    }

    /** 兼容旧调用，没有 Process 引用时使用 */
    public static void killTree(Long pid, Long pgid) {
        killTree(null, pid, pgid);
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
