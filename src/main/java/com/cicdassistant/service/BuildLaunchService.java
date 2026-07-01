package com.cicdassistant.service;

import com.cicdassistant.config.AppProperties;
import com.cicdassistant.entity.Repo;
import com.cicdassistant.entity.TaskModule;
import com.cicdassistant.util.ModuleScanner;
import com.cicdassistant.util.OsUtil;
import com.cicdassistant.util.ProcessManager;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class BuildLaunchService {

    private final AppProperties appProperties;

    public BuildLaunchService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Data
    public static class LaunchResult {
        private boolean success;
        private Long pid;
        private Long pgid;
        private int port;
        private String swaggerUrl;
        private String errorMessage;
        private Process process;
    }

    public File ensureRepoClone(Repo repo, String branch) throws IOException, InterruptedException {
        String wsRoot = appProperties.getWorkspace().getRoot();
        String safeBranch = branch.replace('/', '_');
        File repoDir = new File(wsRoot, repo.getName() + "/" + safeBranch);
        repoDir.getParentFile().mkdirs();

        String gitUrl = buildAuthGitUrl(repo);

        long t0 = System.currentTimeMillis();
        if (new File(repoDir, ".git").exists()) {
            log.info("[GIT] reuse workspace, fetch+reset branch={} dir={}", branch, repoDir.getAbsolutePath());

            // ★ 关键：必须用显式 refspec 强制更新 refs/remotes/origin/<branch>。
            //   `git fetch origin <branch>`（裸 branch 名）在 git 2.x 会把结果只写到 FETCH_HEAD，
            //   不一定同步 refs/remotes/origin/<branch>，接下来 `reset --hard origin/<branch>` 就用了
            //   上一次的旧 ref → 表现为"reuse workspace 永远拿不到新 commit"。这个坑正是用户遇到的现象。
            String refspec = "+refs/heads/" + branch + ":refs/remotes/origin/" + branch;

            String beforeFetch = safeRevParse(repoDir, "refs/remotes/origin/" + branch);
            runGit(repoDir, "fetch", "--prune", "origin", refspec);
            String afterFetch  = safeRevParse(repoDir, "refs/remotes/origin/" + branch);
            String remoteTip   = safeRemoteTip(repoDir, branch);
            log.info("[GIT] origin/{}  before-fetch={}  after-fetch={}  remote-actual={}",
                    branch, beforeFetch, afterFetch, remoteTip);
            if (remoteTip != null && afterFetch != null && !remoteTip.equals(afterFetch)) {
                log.warn("[GIT] fetch 未把 refs/remotes/origin/{} 更新到远端 tip，请查网络/权限/hook", branch);
            }

            runGit(repoDir, "checkout", "-B", branch, "origin/" + branch);
            runGit(repoDir, "reset", "--hard", "origin/" + branch);
            runGit(repoDir, "clean", "-fd");
            String localHead = safeRevParse(repoDir, "HEAD");
            if (localHead != null) {
                log.info("[GIT] local HEAD after reset = {}", localHead);
                if (remoteTip != null && !remoteTip.equals(localHead)) {
                    log.warn("[GIT] MISMATCH! origin says {} but local HEAD is {}", remoteTip, localHead);
                }
            }
        } else {
            log.info("[GIT] clone branch={} into {}", branch, repoDir.getAbsolutePath());
            File parent = repoDir.getParentFile();
            ProcessBuilder pb = new ProcessBuilder("git", "clone", "-b", branch, gitUrl, repoDir.getAbsolutePath())
                    .directory(parent)
                    .redirectErrorStream(true);
            Process p = pb.start();
            drain(p.getInputStream(), null);
            int code = p.waitFor();
            if (code != 0) throw new IOException("git clone failed, exit=" + code);
            String localHead = safeRevParse(repoDir, "HEAD");
            if (localHead != null) log.info("[GIT] cloned HEAD = {}", localHead);
        }
        log.info("[GIT] branch={} ready, cost={}s", branch, (System.currentTimeMillis() - t0) / 1000);
        return repoDir;
    }

    /** 静默调 {@code git ls-remote origin refs/heads/<branch>} 拿远端 tip；失败返回 null 不影响主流程。 */
    private String safeRemoteTip(File repoDir, String branch) {
        try {
            String out = runGitCapture(repoDir, "ls-remote", "origin", "refs/heads/" + branch);
            if (out == null || out.isEmpty()) return null;
            String[] parts = out.trim().split("\\s+", 2);
            return parts.length > 0 ? parts[0] : null;
        } catch (Exception e) {
            log.debug("[GIT] ls-remote failed: {}", e.getMessage());
            return null;
        }
    }

    private String safeRevParse(File repoDir, String ref) {
        try {
            String out = runGitCapture(repoDir, "rev-parse", ref);
            return out == null ? null : out.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private String buildAuthGitUrl(Repo repo) {
        String url = repo.getGitUrl();
        if (url == null) return null;
        if (!url.startsWith("http")) return url;
        try {
            if ("TOKEN".equalsIgnoreCase(repo.getAuthType()) && StringUtils.isNotBlank(repo.getAccessToken())) {
                return url.replaceFirst("://", "://oauth2:" + repo.getAccessToken() + "@");
            } else if ("PASSWORD".equalsIgnoreCase(repo.getAuthType()) && StringUtils.isNotBlank(repo.getUsername())) {
                return url.replaceFirst("://", "://" + repo.getUsername() + ":" + repo.getPassword() + "@");
            }
        } catch (Exception ignore) {
        }
        return url;
    }

    private void runGit(File dir, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true);
        Process p = pb.start();
        drain(p.getInputStream(), null);
        int code = p.waitFor();
        if (code != 0) throw new IOException("git " + String.join(" ", args) + " failed, exit=" + code);
    }

    /**
     * 拉完代码后取一次 HEAD commit 元信息，[sha40, "<sha7> · <msg> · <author> · <relative-time>"]。
     * 全静默失败，读不到就返回 [null, null]，不影响主流程。
     */
    public String[] readHeadInfo(File repoDir) {
        try {
            String line = runGitCapture(repoDir, "log", "-1",
                    "--pretty=format:%H|%h|%s|%an|%cr");
            if (line == null || line.isEmpty()) return new String[]{null, null};
            String[] parts = line.split("\\|", 5);
            String sha = parts.length > 0 ? parts[0] : null;
            String shortSha = parts.length > 1 ? parts[1] : (sha != null && sha.length() >= 7 ? sha.substring(0, 7) : sha);
            String subject = parts.length > 2 ? parts[2] : "";
            String author = parts.length > 3 ? parts[3] : "";
            String when = parts.length > 4 ? parts[4] : "";
            StringBuilder info = new StringBuilder();
            if (shortSha != null) info.append(shortSha);
            if (!subject.isEmpty()) info.append(" · ").append(subject);
            if (!author.isEmpty()) info.append(" · ").append(author);
            if (!when.isEmpty()) info.append(" · ").append(when);
            return new String[]{sha, info.toString()};
        } catch (Exception e) {
            log.warn("[GIT] read HEAD info failed at {}: {}", repoDir.getAbsolutePath(), e.getMessage());
            return new String[]{null, null};
        }
    }

    private String runGitCapture(File dir, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(false);
        Process p = pb.start();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        drain(p.getInputStream(), bo);
        drain(p.getErrorStream(), null);
        int code = p.waitFor();
        if (code != 0) throw new IOException("git " + String.join(" ", args) + " failed, exit=" + code);
        return new String(bo.toByteArray(), StandardCharsets.UTF_8).trim();
    }

    public List<ModuleScanner.Module> scanModules(File repoRoot, String specified) {
        List<ModuleScanner.Module> all = ModuleScanner.scan(repoRoot);
        if (StringUtils.isBlank(specified)) return all;
        Set<String> filter = new LinkedHashSet<>();
        for (String s : specified.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) filter.add(t);
        }
        List<ModuleScanner.Module> result = new ArrayList<>();
        for (ModuleScanner.Module m : all) {
            if (filter.contains(m.getName()) || filter.contains(m.getRelativePath())) {
                result.add(m);
            }
        }
        return result;
    }

    public boolean mvnBuild(File repoRoot, List<ModuleScanner.Module> modules, File buildLog) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(OsUtil.mvnExecutable(appProperties.getMaven().getHome()));

        String settingsXml = appProperties.getMaven().getSettingsXml();
        if (StringUtils.isNotBlank(settingsXml)) {
            cmd.add("-s");
            cmd.add(settingsXml);
        }

        boolean rootOnly = modules.size() == 1 && ".".equals(modules.get(0).getRelativePath());
        if (!rootOnly && !modules.isEmpty()) {
            cmd.add("-pl");
            cmd.add(String.join(",", modules.stream().map(ModuleScanner.Module::getRelativePath).toArray(String[]::new)));
            cmd.add("-am");
        }

        for (String a : appProperties.getMaven().getBuildArgs().split("\\s+")) {
            if (!a.isEmpty()) cmd.add(a);
        }

        log.info("[BUILD] start mvn, modules={}, cwd={}, logFile={}",
                modules.stream().map(ModuleScanner.Module::getRelativePath).collect(Collectors.toList()),
                repoRoot.getAbsolutePath(), buildLog.getAbsolutePath());
        log.info("[BUILD] cmd: {}", String.join(" ", cmd));
        long t0 = System.currentTimeMillis();
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(repoRoot).redirectErrorStream(true);
        Process p = pb.start();
        try (FileOutputStream fos = new FileOutputStream(buildLog)) {
            drain(p.getInputStream(), fos);
        }
        boolean ok = p.waitFor(appProperties.getTask().getBuildTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS);
        long cost = (System.currentTimeMillis() - t0) / 1000;
        if (!ok) {
            log.warn("[BUILD] timeout after {}s, killing process", cost);
            p.destroyForcibly();
            return false;
        }
        int exit = p.exitValue();
        log.info("[BUILD] finished exit={} cost={}s", exit, cost);
        return exit == 0;
    }

    public LaunchResult launchModule(Repo repo, File repoRoot, ModuleScanner.Module module, int port, File logFile) throws Exception {
        LaunchResult result = new LaunchResult();
        result.setPort(port);

        File modDir = ".".equals(module.getRelativePath()) ? repoRoot : new File(repoRoot, module.getRelativePath());
        File targetDir = new File(modDir, "target");
        File jar = findExecutableJar(targetDir);
        if (jar == null) {
            result.setErrorMessage("no executable jar found in " + targetDir);
            return result;
        }

        List<String> cmd = new ArrayList<>();
        if (!OsUtil.isWindows()) {
            cmd.add("setsid");
        }
        cmd.add("java");
        String jvmArgs = repo.getJvmArgs();
        if (StringUtils.isNotBlank(jvmArgs)) {
            for (String a : jvmArgs.split("\\s+")) {
                if (!a.isEmpty()) cmd.add(a);
            }
        }
        cmd.add("-jar");
        cmd.add(jar.getAbsolutePath());
        cmd.add("--server.port=" + port);
        if (StringUtils.isNotBlank(repo.getSpringProfile())) {
            cmd.add("--spring.profiles.active=" + repo.getSpringProfile());
        }

        log.info("[LAUNCH] module={} port={} jar={}", module.getName(), port, jar.getAbsolutePath());
        log.info("[LAUNCH] cmd: {}", String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(modDir).redirectErrorStream(true);
        Process process = pb.start();
        result.setProcess(process);

        // 用 jar 绝对路径作为命令行匹配 token，在 Windows 上从 wmic 拿真 PID
        long pid = ProcessManager.resolvePid(process, jar.getAbsolutePath());
        result.setPid(pid);
        if (!OsUtil.isWindows()) {
            result.setPgid(ProcessManager.getPgid(pid));
        }
        log.info("[LAUNCH] module={} pid={} pgid={} logFile={}",
                module.getName(), pid, result.getPgid(), logFile.getAbsolutePath());

        Pattern startedPat = Pattern.compile(appProperties.getHealthCheck().getStartedPattern());
        Pattern portPat = Pattern.compile(appProperties.getHealthCheck().getPortPattern());

        FileOutputStream fos = new FileOutputStream(logFile);
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        long timeoutMs = appProperties.getTask().getStartupTimeoutSeconds() * 1000L;
        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + timeoutMs;
        int actualPort = port;
        log.info("[LAUNCH] module={} waiting for startup, timeout={}s", module.getName(),
                appProperties.getTask().getStartupTimeoutSeconds());

        // 边写边匹配：避免 "Started" 那一行被后续日志推出 readTail 窗口而漏检。
        java.util.concurrent.atomic.AtomicBoolean startedFlag = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicInteger detectedPort = new java.util.concurrent.atomic.AtomicInteger(port);
        Thread tailThread = new Thread(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    fos.write((line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                    Matcher mp = portPat.matcher(line);
                    if (mp.find()) {
                        try { detectedPort.set(Integer.parseInt(mp.group(1))); } catch (Exception ignore) {}
                    }
                    if (!startedFlag.get() && startedPat.matcher(line).find()) {
                        startedFlag.set(true);
                    }
                }
            } catch (IOException ignored) {
            }
        });
        tailThread.setDaemon(true);
        tailThread.start();

        long nextProgressAt = startedAt + 30_000;
        boolean started = false;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                int exit = safeExit(process);
                log.warn("[LAUNCH] module={} process exited before startup, exit={} cost={}s",
                        module.getName(), exit, (System.currentTimeMillis() - startedAt) / 1000);
                result.setErrorMessage("process exited before startup, exit=" + exit);
                safeClose(fos);
                return result;
            }
            if (startedFlag.get()) {
                started = true;
                actualPort = detectedPort.get();
                break;
            }
            long now = System.currentTimeMillis();
            if (now >= nextProgressAt) {
                long waited = (now - startedAt) / 1000;
                long remain = (deadline - now) / 1000;
                log.info("[LAUNCH] module={} still waiting... waited={}s remain={}s pid={}",
                        module.getName(), waited, remain, result.getPid());
                nextProgressAt = now + 30_000;
            }
            Thread.sleep(1000);
        }

        if (!started) {
            long waited = (System.currentTimeMillis() - startedAt) / 1000;
            log.warn("[LAUNCH] module={} startup TIMEOUT after {}s, killing pid={} pgid={}",
                    module.getName(), waited, result.getPid(), result.getPgid());
            ProcessManager.killTree(process, result.getPid(), result.getPgid());
            result.setErrorMessage("startup timeout after " + waited
                    + "s (limit=" + appProperties.getTask().getStartupTimeoutSeconds()
                    + "s), process killed");
            safeClose(fos);
            return result;
        }
        log.info("[LAUNCH] module={} application started, actualPort={}, cost={}s",
                module.getName(), actualPort, (System.currentTimeMillis() - startedAt) / 1000);

        // Probe actuator
        String actuator = StringUtils.defaultIfBlank(repo.getActuatorPath(), appProperties.getHealthCheck().getActuatorPath());
        String actuatorUrl = "http://127.0.0.1:" + actualPort + actuator;
        boolean actuatorOk = probe(actuatorUrl);
        log.info("[PROBE] module={} actuator {} -> {}", module.getName(), actuatorUrl, actuatorOk ? "OK" : "FAIL");

        // Probe swagger paths (探测必须用 127.0.0.1，本机最快最稳)
        List<String> swaggerPaths = parsePaths(repo.getSwaggerPaths());
        if (swaggerPaths.isEmpty()) swaggerPaths = appProperties.getHealthCheck().getSwaggerPaths();
        String hitPath = null;
        for (String sp : swaggerPaths) {
            String url = "http://127.0.0.1:" + actualPort + sp;
            boolean ok = probe(url);
            log.info("[PROBE] module={} swagger {} -> {}", module.getName(), url, ok ? "OK" : "miss");
            if (ok) {
                hitPath = sp;
                break;
            }
        }

        if (hitPath == null) {
            log.warn("[LAUNCH] module={} swagger not reachable (actuator={})", module.getName(), actuatorOk);
            result.setErrorMessage("swagger not reachable (actuator=" + actuatorOk + ")");
            return result;
        }

        // 展示给用户的链接换成 app.public-host 配的主机，方便从浏览器/外网访问
        String displayHost = StringUtils.defaultIfBlank(appProperties.getPublicHost(), "127.0.0.1");
        String swaggerHit = "http://" + displayHost + ":" + actualPort + hitPath;
        result.setPort(actualPort);
        result.setSwaggerUrl(swaggerHit);
        result.setSuccess(true);
        log.info("[LAUNCH] module={} SUCCESS port={} swagger={}", module.getName(), actualPort, swaggerHit);
        return result;
    }

    private List<String> parsePaths(String csv) {
        List<String> r = new ArrayList<>();
        if (StringUtils.isBlank(csv)) return r;
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) r.add(t);
        }
        return r;
    }

    private File findExecutableJar(File targetDir) {
        if (!targetDir.exists()) {
            log.warn("target dir does not exist: {}", targetDir.getAbsolutePath());
            return null;
        }
        List<File> candidates = new ArrayList<>();
        collectJars(targetDir, candidates, 0, 2);
        if (candidates.isEmpty()) {
            log.warn("no jar candidate under {} (scanned depth=2)", targetDir.getAbsolutePath());
            return null;
        }
        // Prefer Spring Boot executable jars (manifest contains Spring-Boot-Lib / JarLauncher)
        File springBootJar = null;
        File fallback = null;
        for (File f : candidates) {
            if (isSpringBootExecutableJar(f)) {
                if (springBootJar == null || f.length() > springBootJar.length()) springBootJar = f;
            } else if (fallback == null || f.length() > fallback.length()) {
                fallback = f;
            }
        }
        File chosen = springBootJar != null ? springBootJar : fallback;
        log.info("picked jar: {} (springboot={}, size={}MB)",
                chosen.getAbsolutePath(),
                springBootJar != null,
                chosen.length() / (1024 * 1024));
        return chosen;
    }

    private void collectJars(File dir, List<File> out, int depth, int maxDepth) {
        if (depth > maxDepth) return;
        File[] entries = dir.listFiles();
        if (entries == null) return;
        for (File e : entries) {
            if (e.isFile()) {
                String n = e.getName();
                if (n.endsWith(".jar")
                        && !n.endsWith("-sources.jar")
                        && !n.endsWith("-javadoc.jar")
                        && !n.endsWith(".original")) {
                    out.add(e);
                }
            } else if (e.isDirectory()) {
                String n = e.getName();
                if (n.startsWith(".") || "classes".equals(n) || "test-classes".equals(n)
                        || "generated-sources".equals(n) || "generated-test-sources".equals(n)
                        || "maven-status".equals(n) || "maven-archiver".equals(n)
                        || "surefire-reports".equals(n) || "site".equals(n)
                        || "dependency".equals(n) || "lib".equals(n)) {
                    continue;
                }
                collectJars(e, out, depth + 1, maxDepth);
            }
        }
    }

    private boolean isSpringBootExecutableJar(File jar) {
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar)) {
            java.util.jar.Manifest mf = jf.getManifest();
            if (mf == null) return false;
            java.util.jar.Attributes a = mf.getMainAttributes();
            String mainClass = a.getValue("Main-Class");
            String bootLib = a.getValue("Spring-Boot-Lib");
            return bootLib != null
                    || (mainClass != null && mainClass.contains("org.springframework.boot.loader"));
        } catch (IOException e) {
            return false;
        }
    }

    private boolean probe(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return false;
        }
    }

    private String readTail(File f, int maxBytes) {
        if (!f.exists()) return null;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            long start = Math.max(0, len - maxBytes);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private int safeExit(Process p) {
        try { return p.exitValue(); } catch (Exception e) { return -1; }
    }

    private void safeClose(Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (IOException ignore) {}
    }

    private void drain(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (out != null) out.write(buf, 0, n);
        }
    }

    public String readLog(Path path, int maxBytes) throws IOException {
        if (path == null || !Files.exists(path)) return "";
        long len = Files.size(path);
        long start = Math.max(0, len - maxBytes);
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        }
    }

    public Path workspaceLogPath(Long taskId, String branch, String module) {
        return Paths.get(appProperties.getPaths().getBuildLogDir(),
                "task-" + taskId, branch.replace('/', '_'), module + ".log");
    }

    public Path buildLogPath(Long taskId, String branch) {
        return Paths.get(appProperties.getPaths().getBuildLogDir(),
                "task-" + taskId, branch.replace('/', '_'), "build.log");
    }
}
