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

        if (new File(repoDir, ".git").exists()) {
            runGit(repoDir, "fetch", "origin", branch);
            runGit(repoDir, "checkout", "-B", branch, "origin/" + branch);
            runGit(repoDir, "reset", "--hard", "origin/" + branch);
            runGit(repoDir, "clean", "-fd");
        } else {
            File parent = repoDir.getParentFile();
            ProcessBuilder pb = new ProcessBuilder("git", "clone", "-b", branch, gitUrl, repoDir.getAbsolutePath())
                    .directory(parent)
                    .redirectErrorStream(true);
            Process p = pb.start();
            drain(p.getInputStream(), null);
            int code = p.waitFor();
            if (code != 0) throw new IOException("git clone failed, exit=" + code);
        }
        return repoDir;
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

        log.info("mvn build cmd: {} (cwd={})", cmd, repoRoot);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(repoRoot).redirectErrorStream(true);
        Process p = pb.start();
        try (FileOutputStream fos = new FileOutputStream(buildLog)) {
            drain(p.getInputStream(), fos);
        }
        boolean ok = p.waitFor(appProperties.getTask().getBuildTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS);
        if (!ok) {
            p.destroyForcibly();
            return false;
        }
        return p.exitValue() == 0;
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

        log.info("launch cmd: {}", cmd);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(modDir).redirectErrorStream(true);
        Process process = pb.start();
        result.setProcess(process);

        long pid = ProcessManager.getPid(process);
        result.setPid(pid);
        if (!OsUtil.isWindows()) {
            result.setPgid(ProcessManager.getPgid(pid));
        }

        Pattern startedPat = Pattern.compile("Started\\s+\\S+Application\\s+in");
        Pattern portPat = Pattern.compile("Tomcat started on port\\(?s\\)?:?\\s*(\\d+)");

        FileOutputStream fos = new FileOutputStream(logFile);
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        long timeoutMs = appProperties.getTask().getStartupTimeoutSeconds() * 1000L;
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean started = false;
        int actualPort = port;

        Thread tailThread = new Thread(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    fos.write((line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                }
            } catch (IOException ignored) {
            }
        });
        tailThread.setDaemon(true);
        tailThread.start();

        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                result.setErrorMessage("process exited before startup, exit=" + safeExit(process));
                fos.close();
                return result;
            }
            // Tail thread writes log; we read tail to detect status
            String tail = readTail(logFile, 8192);
            if (tail != null) {
                Matcher mp = portPat.matcher(tail);
                while (mp.find()) {
                    try { actualPort = Integer.parseInt(mp.group(1)); } catch (Exception ignore) {}
                }
                if (startedPat.matcher(tail).find()) {
                    started = true;
                    break;
                }
            }
            Thread.sleep(1000);
        }

        if (!started) {
            result.setErrorMessage("startup timeout after " + appProperties.getTask().getStartupTimeoutSeconds() + "s");
            fos.close();
            return result;
        }

        // Probe actuator
        String actuator = StringUtils.defaultIfBlank(repo.getActuatorPath(), appProperties.getHealthCheck().getActuatorPath());
        boolean actuatorOk = probe("http://127.0.0.1:" + actualPort + actuator);

        // Probe swagger paths
        List<String> swaggerPaths = parsePaths(repo.getSwaggerPaths());
        if (swaggerPaths.isEmpty()) swaggerPaths = appProperties.getHealthCheck().getSwaggerPaths();
        String swaggerHit = null;
        for (String sp : swaggerPaths) {
            String url = "http://127.0.0.1:" + actualPort + sp;
            if (probe(url)) {
                swaggerHit = url;
                break;
            }
        }

        if (swaggerHit == null) {
            result.setErrorMessage("swagger not reachable (actuator=" + actuatorOk + ")");
            return result;
        }

        result.setPort(actualPort);
        result.setSwaggerUrl(swaggerHit);
        result.setSuccess(true);
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
        if (!targetDir.exists()) return null;
        File[] files = targetDir.listFiles((d, n) -> n.endsWith(".jar") && !n.endsWith("-sources.jar") && !n.endsWith("-javadoc.jar") && !n.endsWith(".original"));
        if (files == null || files.length == 0) return null;
        File chosen = null;
        for (File f : files) {
            if (chosen == null || f.length() > chosen.length()) {
                chosen = f;
            }
        }
        return chosen;
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
        return Paths.get("./build-logs", "task-" + taskId, branch.replace('/', '_'), module + ".log");
    }

    public Path buildLogPath(Long taskId, String branch) {
        return Paths.get("./build-logs", "task-" + taskId, branch.replace('/', '_'), "build.log");
    }
}
