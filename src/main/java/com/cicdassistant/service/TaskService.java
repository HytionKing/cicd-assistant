package com.cicdassistant.service;

import com.cicdassistant.config.AppProperties;
import com.cicdassistant.entity.NotificationWebhook;
import com.cicdassistant.entity.Repo;
import com.cicdassistant.entity.Task;
import com.cicdassistant.entity.TaskModule;
import com.cicdassistant.mapper.TaskMapper;
import com.cicdassistant.mapper.TaskModuleMapper;
import com.cicdassistant.util.ModuleScanner;
import com.cicdassistant.util.PortPool;
import com.cicdassistant.util.ProcessManager;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TaskService {

    private final TaskMapper taskMapper;
    private final TaskModuleMapper taskModuleMapper;
    private final RepoService repoService;
    private final BuildLaunchService buildLaunchService;
    private final PortPool portPool;
    private final AppProperties appProperties;
    private final NotificationWebhookService notificationService;
    private final LaunchNotifier launchNotifier;

    public TaskService(TaskMapper taskMapper, TaskModuleMapper taskModuleMapper,
                       RepoService repoService, BuildLaunchService buildLaunchService,
                       PortPool portPool, AppProperties appProperties,
                       NotificationWebhookService notificationService,
                       LaunchNotifier launchNotifier) {
        this.taskMapper = taskMapper;
        this.taskModuleMapper = taskModuleMapper;
        this.repoService = repoService;
        this.buildLaunchService = buildLaunchService;
        this.portPool = portPool;
        this.appProperties = appProperties;
        this.notificationService = notificationService;
        this.launchNotifier = launchNotifier;
    }

    public Task createTask(Long repoId, List<String> branches, String modules, Boolean keepAlive,
                           Long notifyWebhookId) {
        Repo repo = repoService.findByIdMasked(repoId);
        if (repo == null) throw new IllegalArgumentException("repo not found: " + repoId);

        // 同 repo + 同 branch 不允许并发。workspace 是共享的（workspace/<repo>/<branch>/），
        // 两个任务并发对同一目录跑 mvn clean package，jar 会互相覆盖，进程也可能拉起错误版本。
        List<String> normalizedNew = new ArrayList<>();
        for (String b : branches) {
            if (b == null) continue;
            String bt = b.trim();
            if (!bt.isEmpty()) normalizedNew.add(bt);
        }
        List<Task> active = taskMapper.findActiveByRepo(repoId);
        List<String> conflicts = new ArrayList<>();
        for (Task a : active) {
            if (a.getBranches() == null) continue;
            for (String existing : a.getBranches().split(",")) {
                String e = existing.trim();
                if (e.isEmpty()) continue;
                if (normalizedNew.contains(e)) {
                    conflicts.add(e + "（任务 #" + a.getId() + "）");
                }
            }
        }
        if (!conflicts.isEmpty()) {
            throw new IllegalArgumentException(
                "以下分支已有活跃任务在跑，不允许并发（会覆盖 jar / 端口冲突）：" + String.join(", ", conflicts));
        }

        Task t = new Task();
        t.setRepoId(repoId);
        t.setRepoName(repo.getName());
        t.setBranches(String.join(",", branches));
        t.setModules(modules);
        t.setStatus("PENDING");
        t.setKeepAlive(keepAlive == null ? Boolean.TRUE : keepAlive);
        t.setNotifyWebhookId(notifyWebhookId);
        t.setCreatedAt(now());
        taskMapper.insert(t);
        // 提前为每个分支插一行占位，避免详情页空白等到 mvn 编译完
        for (String b : branches) {
            if (b == null || b.trim().isEmpty()) continue;
            TaskModule placeholder = new TaskModule();
            placeholder.setTaskId(t.getId());
            placeholder.setBranch(b.trim());
            placeholder.setModuleName("(pending)");
            placeholder.setStatus("PENDING");
            placeholder.setCreatedAt(now());
            taskModuleMapper.insert(placeholder);
        }
        return t;
    }

    public List<Task> listTasks() {
        return taskMapper.findAll();
    }

    public List<Task> page(int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(Math.max(1, size), 100);
        return taskMapper.findPage((p - 1) * s, s);
    }

    public int total() {
        return taskMapper.count();
    }

    public Task get(Long id) {
        return taskMapper.findById(id);
    }

    public List<TaskModule> listModules(Long taskId) {
        return taskModuleMapper.findByTaskId(taskId);
    }

    public TaskModule getModule(Long moduleId) {
        return taskModuleMapper.findById(moduleId);
    }

    public void deleteTask(Long id) {
        List<TaskModule> mods = taskModuleMapper.findByTaskId(id);
        for (TaskModule m : mods) {
            stopModule(m);
        }
        taskModuleMapper.deleteByTaskId(id);
        taskMapper.deleteById(id);
        // 顺手清 build-logs/task-<id>/ 整目录（含 build.log + 各模块 run 日志）；
        // workspace/<repo>/<branch>/ 是同分支任务共享的不能动，动了会破坏其它任务的重试
        File logDir = Paths.get(appProperties.getPaths().getBuildLogDir(), "task-" + id).toFile();
        if (logDir.exists()) {
            try {
                FileUtils.deleteDirectory(logDir);
                log.info("[TASK#{}] cleaned log dir {}", id, logDir.getAbsolutePath());
            } catch (Exception e) {
                log.warn("[TASK#{}] clean log dir failed: {}", id, e.getMessage());
            }
        }
    }

    public void stopModule(TaskModule m) {
        if ("SUCCESS".equals(m.getStatus()) || "RUNNING".equals(m.getStatus())) {
            ProcessManager.killTree(m.getPid(), m.getPgid());
            portPool.release(m.getPort());
            m.setStatus("STOPPED");
            m.setFinishedAt(now());
            taskModuleMapper.update(m);
        }
    }

    public void stopModuleById(Long moduleId) {
        TaskModule m = taskModuleMapper.findById(moduleId);
        if (m != null) stopModule(m);
    }

    @Async("taskExecutor")
    public void runTaskAsync(Long taskId) {
        Task task = taskMapper.findById(taskId);
        if (task == null) return;
        log.info("[TASK#{}] START repoId={} branches={} modulesFilter={}",
                taskId, task.getRepoId(), task.getBranches(), task.getModules());
        task.setStatus("RUNNING");
        task.setStartedAt(now());
        taskMapper.update(task);

        Repo repo = repoService.findByIdDecrypted(task.getRepoId());
        List<String> branches = new ArrayList<>();
        for (String raw : task.getBranches().split(",")) {
            String b = raw.trim();
            if (!b.isEmpty()) branches.add(b);
        }

        // 跨分支并发：每个 branch 一个 Callable，跑完汇总 successCount / totalCount / errors。
        // build 吃内存，branchConcurrency 默认 2 比 launchConcurrency 保守。
        int branchConcurrency = Math.max(1,
                Math.min(appProperties.getTask().getBranchConcurrency(), branches.size()));
        log.info("[TASK#{}] processing {} branches concurrency={}", taskId, branches.size(), branchConcurrency);
        int successCount = 0;
        int totalCount = 0;
        StringBuilder errors = new StringBuilder();

        ExecutorService branchPool = Executors.newFixedThreadPool(branchConcurrency, r -> {
            Thread th = new Thread(r);
            th.setName("cs-branch-" + taskId + "-" + th.getId());
            th.setDaemon(true);
            return th;
        });
        try {
            List<Callable<BranchOutcome>> jobs = new ArrayList<>();
            for (String branch : branches) {
                final String b = branch;
                jobs.add(() -> processBranch(taskId, task, repo, b));
            }
            List<Future<BranchOutcome>> futures = branchPool.invokeAll(jobs);
            for (Future<BranchOutcome> f : futures) {
                try {
                    BranchOutcome bo = f.get();
                    totalCount += bo.total;
                    successCount += bo.success;
                    if (bo.errors != null && !bo.errors.isEmpty()) errors.append(bo.errors);
                } catch (Exception e) {
                    errors.append("[branch] ").append(e.getMessage()).append("; ");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[TASK#{}] interrupted", taskId, e);
        } finally {
            branchPool.shutdown();
        }

        String finalStatus;
        if (totalCount == 0) {
            finalStatus = "FAILED";
        } else if (successCount == totalCount) {
            finalStatus = "SUCCESS";
        } else if (successCount == 0) {
            finalStatus = "FAILED";
        } else {
            finalStatus = "PARTIAL";
        }
        task.setStatus(finalStatus);
        task.setErrorMessage(errors.length() > 0 ? errors.toString() : null);
        task.setFinishedAt(now());
        taskMapper.update(task);
        log.info("[TASK#{}] DONE status={} success={}/{}", taskId, finalStatus, successCount, totalCount);

        // 任务终态推钉钉：hook 为空/禁用/查不到都静默跳过，不因通知失败影响任务本身状态
        if (task.getNotifyWebhookId() != null) {
            try {
                NotificationWebhook hook = notificationService.get(task.getNotifyWebhookId());
                if (hook != null) {
                    List<TaskModule> modulesForNotify = taskModuleMapper.findByTaskId(taskId);
                    launchNotifier.notifyTaskDone(task, modulesForNotify, hook);
                } else {
                    log.warn("[TASK#{}] notify webhook id={} not found, skip", taskId, task.getNotifyWebhookId());
                }
            } catch (Exception e) {
                log.warn("[TASK#{}] notify failed: {}", taskId, e.getMessage());
            }
        }
    }

    /** 分支一次跑完的统计。errors 是拼好的 "[branch/module] xxx; " 段，直接 append 到任务级 StringBuilder。 */
    private static class BranchOutcome {
        int total;
        int success;
        String errors = "";
    }

    /**
     * 单分支从 clone → build → 并发 launch 的完整流程，作为跨分支并发的 job。
     * 各分支独立 workspace 目录 + 独立 mvn build + 独立进程，线程安全。
     */
    private BranchOutcome processBranch(Long taskId, Task task, Repo repo, String branch) {
        BranchOutcome out = new BranchOutcome();
        StringBuilder errors = new StringBuilder();
        log.info("[TASK#{}] >>> branch={} begin", taskId, branch);
        TaskModule placeholder = taskModuleMapper.findPlaceholder(taskId, branch);
        try {
            if (placeholder != null) {
                placeholder.setStatus("CLONING");
                placeholder.setStartedAt(now());
                taskModuleMapper.update(placeholder);
            }
            File repoRoot = buildLaunchService.ensureRepoClone(repo, branch);
            String[] head = buildLaunchService.readHeadInfo(repoRoot);
            String commitSha = head[0];
            String commitInfo = head[1];
            String commitMrIid = head.length > 2 ? head[2] : null;
            if (commitInfo != null) {
                log.info("[TASK#{}] branch={} HEAD -> {}{}", taskId, branch, commitInfo,
                        commitMrIid != null ? " (!" + commitMrIid + ")" : "");
            }
            if (placeholder != null) {
                placeholder.setStatus("SCANNING");
                placeholder.setCommitSha(commitSha);
                placeholder.setCommitInfo(commitInfo);
                placeholder.setCommitMrIid(commitMrIid);
                taskModuleMapper.update(placeholder);
            }
            List<ModuleScanner.Module> modules = buildLaunchService.scanModules(repoRoot, task.getModules());
            log.info("[TASK#{}] branch={} scanned modules: {}", taskId, branch,
                    modules.stream().map(m -> m.getName() + "(" + m.getRelativePath() + ")").collect(Collectors.toList()));
            if (modules.isEmpty()) {
                log.warn("[TASK#{}] no springboot module found in branch={}", taskId, branch);
                if (placeholder != null) {
                    placeholder.setStatus("FAILED");
                    placeholder.setErrorMessage("no SpringBoot module found");
                    placeholder.setFinishedAt(now());
                    taskModuleMapper.update(placeholder);
                }
                errors.append("[").append(branch).append("] no SpringBoot module found; ");
                out.errors = errors.toString();
                return out;
            }

            Path buildLogPath = buildLaunchService.buildLogPath(taskId, branch);
            Files.createDirectories(buildLogPath.getParent());

            if (placeholder != null) {
                taskModuleMapper.deleteById(placeholder.getId());
                placeholder = null;
            }
            for (ModuleScanner.Module mod : modules) {
                TaskModule pre = newModuleRow(taskId, branch, mod, buildLogPath.toString());
                pre.setStatus("BUILDING");
                pre.setStartedAt(now());
                pre.setCommitSha(commitSha);
                pre.setCommitInfo(commitInfo);
                pre.setCommitMrIid(commitMrIid);
                taskModuleMapper.update(pre);
            }

            boolean buildOk = buildLaunchService.mvnBuild(repoRoot, modules, buildLogPath.toFile());
            if (!buildOk) {
                for (ModuleScanner.Module mod : modules) {
                    TaskModule tm = newModuleRow(taskId, branch, mod, buildLogPath.toString());
                    tm.setStatus("FAILED");
                    tm.setErrorMessage("maven build failed");
                    tm.setFinishedAt(now());
                    tm.setCommitSha(commitSha);
                    tm.setCommitInfo(commitInfo);
                    tm.setCommitMrIid(commitMrIid);
                    taskModuleMapper.update(tm);
                    out.total++;
                }
                errors.append("[").append(branch).append("] build failed; ");
                out.errors = errors.toString();
                return out;
            }

            // 先把所有模块行落成 STARTING 状态，再用线程池并发 launchOne
            // 走到这里说明 mvn build 成功，标记 buildSuccess=true 让"重试"按钮之后可用
            List<TaskModule> tms = new ArrayList<>();
            for (ModuleScanner.Module mod : modules) {
                out.total++;
                TaskModule tm = newModuleRow(taskId, branch, mod, buildLogPath.toString());
                tm.setStatus("STARTING");
                tm.setStartedAt(now());
                tm.setCommitSha(commitSha);
                tm.setCommitInfo(commitInfo);
                tm.setCommitMrIid(commitMrIid);
                tm.setBuildSuccess(Boolean.TRUE);
                taskModuleMapper.update(tm);
                tms.add(tm);
            }

            int concurrency = Math.max(1,
                    Math.min(appProperties.getTask().getLaunchConcurrency(), modules.size()));
            log.info("[TASK#{}] branch={} launching {} modules concurrency={}",
                    taskId, branch, modules.size(), concurrency);
            final File repoRootFinal = repoRoot;
            final boolean keepAlive = !Boolean.FALSE.equals(task.getKeepAlive());
            ExecutorService pool = Executors.newFixedThreadPool(concurrency, r -> {
                Thread t = new Thread(r);
                t.setName("cs-launch-" + taskId + "-" + t.getId());
                t.setDaemon(true);
                return t;
            });
            try {
                List<Callable<String>> jobs = new ArrayList<>();
                for (int i = 0; i < modules.size(); i++) {
                    final ModuleScanner.Module mod = modules.get(i);
                    final TaskModule tm = tms.get(i);
                    jobs.add(() -> launchOne(taskId, repo, repoRootFinal, mod, tm, keepAlive));
                }
                List<Future<String>> futures = pool.invokeAll(jobs);
                for (int i = 0; i < futures.size(); i++) {
                    String err;
                    try {
                        err = futures.get(i).get();
                    } catch (Exception e) {
                        err = e.getMessage();
                    }
                    if (err == null) {
                        out.success++;
                    } else {
                        errors.append("[").append(branch).append("/")
                                .append(modules.get(i).getName()).append("] ")
                                .append(err).append("; ");
                    }
                }
            } finally {
                pool.shutdown();
            }
        } catch (Exception e) {
            log.error("[TASK#{}] branch processing failed: {}", taskId, branch, e);
            errors.append("[").append(branch).append("] ").append(e.getMessage()).append("; ");
            if (placeholder != null && placeholder.getId() != null) {
                TaskModule p = taskModuleMapper.findById(placeholder.getId());
                if (p != null && !"FAILED".equals(p.getStatus())) {
                    p.setStatus("FAILED");
                    p.setErrorMessage(e.getMessage());
                    p.setFinishedAt(now());
                    taskModuleMapper.update(p);
                }
            }
        }
        log.info("[TASK#{}] <<< branch={} done", taskId, branch);
        out.errors = errors.toString();
        return out;
    }

    /**
     * 启动单个模块：申请端口 → 拉真实 launchModule → 回写 SUCCESS/FAILED 状态。
     * 调用前 tm 应已经落成 STARTING 状态（含 commit info 等元信息）。
     * 返回 null 表示成功；返回非 null 是错误摘要，供上层拼进 errors。
     * 线程安全：portPool 是 synchronized 的，taskModuleMapper 通过 Hikari 连接池并发无问题。
     */
    private String launchOne(Long taskId, Repo repo, File repoRoot, ModuleScanner.Module mod, TaskModule tm,
                             boolean keepAlive) {
        Integer port = portPool.acquire();
        if (port == null) {
            tm.setStatus("FAILED");
            tm.setErrorMessage("no free port in pool");
            tm.setFinishedAt(now());
            taskModuleMapper.update(tm);
            return "no port";
        }
        tm.setPort(port);
        try {
            Path runLogPath = buildLaunchService.workspaceLogPath(taskId, tm.getBranch(), mod.getName());
            Files.createDirectories(runLogPath.getParent());
            tm.setLogFile(runLogPath.toString());
            taskModuleMapper.update(tm);

            BuildLaunchService.LaunchResult lr =
                    buildLaunchService.launchModule(repo, repoRoot, mod, port, runLogPath.toFile());
            tm.setPid(lr.getPid());
            tm.setPgid(lr.getPgid());
            tm.setPort(lr.getPort());
            tm.setSwaggerUrl(lr.getSwaggerUrl());
            if (lr.isSuccess()) {
                if (!keepAlive) {
                    // 用户勾了"不保活"：启动 + swagger 探测都过了，走完流程立刻停进程释放端口
                    log.info("[TASK#{}] branch={} module={} success, keep-alive off -> stopping now",
                            taskId, tm.getBranch(), mod.getName());
                    ProcessManager.killTree(lr.getProcess(), lr.getPid(), lr.getPgid());
                    portPool.release(port);
                    tm.setStatus("STOPPED");
                    tm.setKeepAliveUntil(null);
                } else {
                    tm.setStatus("SUCCESS");
                    tm.setKeepAliveUntil(LocalDateTime.now()
                            .plusSeconds(appProperties.getWorkspace().getKeepAliveSeconds())
                            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                }
                tm.setFinishedAt(now());
                taskModuleMapper.update(tm);
                return null;
            } else {
                tm.setStatus("FAILED");
                tm.setErrorMessage(lr.getErrorMessage());
                tm.setFinishedAt(now());
                taskModuleMapper.update(tm);
                ProcessManager.killTree(lr.getProcess(), lr.getPid(), lr.getPgid());
                portPool.release(port);
                return lr.getErrorMessage();
            }
        } catch (Exception e) {
            log.error("launch failed", e);
            tm.setStatus("FAILED");
            tm.setErrorMessage("exception: " + e.getMessage());
            tm.setFinishedAt(now());
            taskModuleMapper.update(tm);
            portPool.release(port);
            return e.getMessage();
        }
    }

    /**
     * 手动重试一个 FAILED 模块。不重新 clone/build，只重新启动进程。
     * jar 还在原 workspace 里就直接复用；工作目录被清就明确报错让用户重新提交任务。
     * 场景：多模块并发启动时其中一个因 OOM 挂掉，等前面吃满内存的模块保活到期后点重试大概率能起。
     */
    @Async("taskExecutor")
    public void retryModuleAsync(Long moduleId) {
        TaskModule tm = taskModuleMapper.findById(moduleId);
        if (tm == null) {
            log.warn("[RETRY] module={} not found", moduleId);
            return;
        }
        if (!"FAILED".equals(tm.getStatus())) {
            log.warn("[RETRY] module={} status={} not FAILED, skip", moduleId, tm.getStatus());
            return;
        }
        // 只有本任务本模块 mvn build 成功过一次才允许重试；否则 target/ 里的 jar
        // 可能是同分支上次任务残留，重试会拉起错误版本
        if (!Boolean.TRUE.equals(tm.getBuildSuccess())) {
            log.warn("[RETRY] module={} build never succeeded, skip", moduleId);
            return;
        }
        Task task = taskMapper.findById(tm.getTaskId());
        if (task == null) return;
        Repo repo = repoService.findByIdDecrypted(task.getRepoId());
        if (repo == null) {
            tm.setErrorMessage("repo not found for retry (id=" + task.getRepoId() + ")");
            taskModuleMapper.update(tm);
            return;
        }
        String wsRoot = appProperties.getWorkspace().getRoot();
        String safeBranch = tm.getBranch().replace('/', '_');
        File repoRoot = new File(wsRoot, repo.getName() + "/" + safeBranch);
        if (!new File(repoRoot, ".git").exists()) {
            tm.setErrorMessage("工作目录已清理，请重新提交任务");
            taskModuleMapper.update(tm);
            log.warn("[RETRY] module={} workspace missing at {}", moduleId, repoRoot.getAbsolutePath());
            return;
        }
        // 复位状态，进入新一轮 STARTING
        tm.setStatus("STARTING");
        tm.setStartedAt(now());
        tm.setFinishedAt(null);
        tm.setErrorMessage(null);
        tm.setPort(null);
        tm.setPid(null);
        tm.setPgid(null);
        tm.setKeepAliveUntil(null);
        tm.setSwaggerUrl(null);
        taskModuleMapper.update(tm);

        ModuleScanner.Module mod = new ModuleScanner.Module(tm.getModuleName(), tm.getModulePath());
        boolean keepAlive = !Boolean.FALSE.equals(task.getKeepAlive());
        log.info("[RETRY] module={} branch={} name={} path={} keepAlive={}",
                moduleId, tm.getBranch(), tm.getModuleName(), tm.getModulePath(), keepAlive);
        String err = launchOne(tm.getTaskId(), repo, repoRoot, mod, tm, keepAlive);
        log.info("[RETRY] module={} done result={}", moduleId, err == null ? "OK" : err);
    }

    private TaskModule newModuleRow(Long taskId, String branch, ModuleScanner.Module mod, String buildLog) {
        List<TaskModule> existing = taskModuleMapper.findByTaskId(taskId);
        for (TaskModule e : existing) {
            if (e.getBranch().equals(branch) && e.getModuleName().equals(mod.getName())) {
                return e;
            }
        }
        TaskModule tm = new TaskModule();
        tm.setTaskId(taskId);
        tm.setBranch(branch);
        tm.setModuleName(mod.getName());
        tm.setModulePath(mod.getRelativePath());
        tm.setStatus("PENDING");
        tm.setBuildLogFile(buildLog);
        tm.setCreatedAt(now());
        taskModuleMapper.insert(tm);
        return tm;
    }

    private String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
