package com.cicdassistant.service.compare;

import com.cicdassistant.config.AppProperties;
import com.cicdassistant.entity.Repo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 合并检测专用的 git 工作目录管理。
 *
 * <p><b>严格只读约束</b>：本类对 GitLab 远端只发起读操作 —— clone / fetch / show /
 * cat-file / log。绝不调用 push / commit / tag / 新建远端分支。所有"写"动作都只发生
 * 在本机 workspace 目录里（reset --hard / clean）。这是和上游约定的硬性安全边界，
 * 任何引入会写远端的 git 命令的修改都属于回归。</p>
 *
 * <p>每个 repo 共用一个本地 clone，避免重复全量拉取。每次比对前 fetch baseline + targets
 * 几个具体分支即可。读分支某文件内容通过 {@code git show <ref>:<path>}，
 * 不做 checkout，多个分支可在同一克隆里并存。</p>
 */
@Slf4j
@Component
public class GitWorkspaceManager {

    private static final Set<String> READ_ONLY_GIT_COMMANDS = new HashSet<>(Arrays.asList(
            "init", "clone", "remote", "fetch", "show", "log", "cat-file",
            "rev-parse", "ls-tree", "ls-files", "config",
            "checkout", "reset", "clean"   // local-only writes (workspace)
    ));

    private final AppProperties appProperties;

    public GitWorkspaceManager(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public File ensureClone(Repo repo) throws IOException, InterruptedException {
        File root = new File(appProperties.getCompare().getWorkspaceRoot(), repo.getName());
        root.getParentFile().mkdirs();
        if (new File(root, ".git").exists()) {
            log.info("[COMPARE-GIT] reuse existing clone {}", root.getAbsolutePath());
            return root;
        }
        String url = buildAuthGitUrl(repo);
        log.info("[COMPARE-GIT] clone {} -> {} (--no-checkout, no working tree)", repo.getName(), root.getAbsolutePath());
        // --no-checkout：我们不需要 working tree 上的文件，全部用 git show 读
        // --filter=blob:none：blob 按需拉，节省磁盘
        runGit(root.getParentFile(), "clone", "--no-checkout", "--filter=blob:none", url, root.getName());
        return root;
    }

    /** 拉取指定分支到本地（origin/&lt;branch&gt;）。不会动 working tree。 */
    public void fetchBranches(File repoRoot, List<String> branches) throws IOException, InterruptedException {
        if (branches == null || branches.isEmpty()) return;
        List<String> args = new ArrayList<>();
        args.add("fetch");
        args.add("--prune");
        args.add("origin");
        for (String b : branches) {
            if (StringUtils.isBlank(b)) continue;
            args.add("+refs/heads/" + b + ":refs/remotes/origin/" + b);
        }
        log.info("[COMPARE-GIT] fetch branches={}", branches);
        runGit(repoRoot, args.toArray(new String[0]));
    }

    /** 读取某分支某文件内容；文件不存在返回 null。 */
    public String readFile(File repoRoot, String branch, String path) throws InterruptedException {
        try {
            ExecResult r = runGitCapturing(repoRoot, "show", "origin/" + branch + ":" + path);
            if (r.exitCode != 0) return null;
            return r.stdout;
        } catch (IOException e) {
            log.warn("[COMPARE-GIT] read {} @ origin/{} failed: {}", path, branch, e.getMessage());
            return null;
        }
    }

    public boolean exists(File repoRoot, String branch, String path) throws InterruptedException {
        try {
            ExecResult r = runGitCapturing(repoRoot, "cat-file", "-e", "origin/" + branch + ":" + path);
            return r.exitCode == 0;
        } catch (IOException e) {
            return false;
        }
    }

    // ---- private helpers ----

    private String buildAuthGitUrl(Repo repo) {
        String url = repo.getGitUrl();
        if (url == null || !url.startsWith("http")) return url;
        if ("TOKEN".equalsIgnoreCase(repo.getAuthType()) && StringUtils.isNotBlank(repo.getAccessToken())) {
            return url.replaceFirst("://", "://oauth2:" + repo.getAccessToken() + "@");
        }
        if ("PASSWORD".equalsIgnoreCase(repo.getAuthType()) && StringUtils.isNotBlank(repo.getUsername())) {
            return url.replaceFirst("://", "://" + repo.getUsername() + ":"
                    + StringUtils.defaultString(repo.getPassword()) + "@");
        }
        return url;
    }

    private void runGit(File cwd, String... args) throws IOException, InterruptedException {
        assertReadOnly(args);
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd).redirectErrorStream(true);
        Process p = pb.start();
        drain(p.getInputStream());
        int code = p.waitFor();
        if (code != 0) throw new IOException("git " + Arrays.toString(args) + " exit=" + code);
    }

    private ExecResult runGitCapturing(File cwd, String... args) throws IOException, InterruptedException {
        assertReadOnly(args);
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd).redirectErrorStream(false);
        Process p = pb.start();
        String stdout = readStream(p.getInputStream());
        readStream(p.getErrorStream());
        int code = p.waitFor();
        return new ExecResult(code, stdout);
    }

    /**
     * 安全校验：拒绝执行任何"会写远端"的 git 子命令。
     */
    private void assertReadOnly(String[] args) {
        if (args.length == 0) return;
        String sub = args[0];
        if (!READ_ONLY_GIT_COMMANDS.contains(sub)) {
            throw new SecurityException("blocked write-capable git subcommand: " + sub);
        }
    }

    private void drain(InputStream in) throws IOException {
        byte[] buf = new byte[8192];
        while (in.read(buf) >= 0) { /* discard */ }
    }

    private String readStream(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) baos.write(buf, 0, n);
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static class ExecResult {
        final int exitCode;
        final String stdout;
        ExecResult(int c, String s) { this.exitCode = c; this.stdout = s; }
    }
}
