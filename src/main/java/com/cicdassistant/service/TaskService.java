package com.cicdassistant.service;

import com.cicdassistant.config.AppProperties;
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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
public class TaskService {

    private final TaskMapper taskMapper;
    private final TaskModuleMapper taskModuleMapper;
    private final RepoService repoService;
    private final BuildLaunchService buildLaunchService;
    private final PortPool portPool;
    private final AppProperties appProperties;

    public TaskService(TaskMapper taskMapper, TaskModuleMapper taskModuleMapper,
                       RepoService repoService, BuildLaunchService buildLaunchService,
                       PortPool portPool, AppProperties appProperties) {
        this.taskMapper = taskMapper;
        this.taskModuleMapper = taskModuleMapper;
        this.repoService = repoService;
        this.buildLaunchService = buildLaunchService;
        this.portPool = portPool;
        this.appProperties = appProperties;
    }

    public Task createTask(Long repoId, List<String> branches, String modules) {
        Repo repo = repoService.findByIdMasked(repoId);
        if (repo == null) throw new IllegalArgumentException("repo not found: " + repoId);
        Task t = new Task();
        t.setRepoId(repoId);
        t.setRepoName(repo.getName());
        t.setBranches(String.join(",", branches));
        t.setModules(modules);
        t.setStatus("PENDING");
        t.setCreatedAt(now());
        taskMapper.insert(t);
        return t;
    }

    public List<Task> listTasks() {
        return taskMapper.findAll();
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
        task.setStatus("RUNNING");
        task.setStartedAt(now());
        taskMapper.update(task);

        Repo repo = repoService.findByIdDecrypted(task.getRepoId());
        String[] branches = task.getBranches().split(",");
        int successCount = 0;
        int totalCount = 0;
        StringBuilder errors = new StringBuilder();

        for (String branch : branches) {
            branch = branch.trim();
            if (branch.isEmpty()) continue;
            try {
                File repoRoot = buildLaunchService.ensureRepoClone(repo, branch);
                List<ModuleScanner.Module> modules = buildLaunchService.scanModules(repoRoot, task.getModules());
                if (modules.isEmpty()) {
                    log.warn("no springboot module found in branch={}", branch);
                    continue;
                }

                Path buildLogPath = buildLaunchService.buildLogPath(taskId, branch);
                Files.createDirectories(buildLogPath.getParent());

                boolean buildOk = buildLaunchService.mvnBuild(repoRoot, modules, buildLogPath.toFile());
                if (!buildOk) {
                    for (ModuleScanner.Module mod : modules) {
                        TaskModule tm = newModuleRow(taskId, branch, mod, buildLogPath.toString());
                        tm.setStatus("FAILED");
                        tm.setErrorMessage("maven build failed");
                        tm.setFinishedAt(now());
                        taskModuleMapper.update(tm);
                        totalCount++;
                    }
                    errors.append("[").append(branch).append("] build failed; ");
                    continue;
                }

                for (ModuleScanner.Module mod : modules) {
                    totalCount++;
                    TaskModule tm = newModuleRow(taskId, branch, mod, buildLogPath.toString());
                    tm.setStatus("RUNNING");
                    tm.setStartedAt(now());
                    taskModuleMapper.update(tm);

                    Integer port = portPool.acquire();
                    if (port == null) {
                        tm.setStatus("FAILED");
                        tm.setErrorMessage("no free port in pool");
                        tm.setFinishedAt(now());
                        taskModuleMapper.update(tm);
                        errors.append("[").append(branch).append("/").append(mod.getName()).append("] no port; ");
                        continue;
                    }
                    tm.setPort(port);

                    Path runLogPath = buildLaunchService.workspaceLogPath(taskId, branch, mod.getName());
                    Files.createDirectories(runLogPath.getParent());
                    tm.setLogFile(runLogPath.toString());
                    taskModuleMapper.update(tm);

                    try {
                        BuildLaunchService.LaunchResult lr = buildLaunchService.launchModule(repo, repoRoot, mod, port, runLogPath.toFile());
                        tm.setPid(lr.getPid());
                        tm.setPgid(lr.getPgid());
                        tm.setPort(lr.getPort());
                        tm.setSwaggerUrl(lr.getSwaggerUrl());
                        if (lr.isSuccess()) {
                            tm.setStatus("SUCCESS");
                            tm.setKeepAliveUntil(LocalDateTime.now()
                                    .plusMinutes(appProperties.getWorkspace().getKeepAliveMinutes())
                                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                            successCount++;
                        } else {
                            tm.setStatus("FAILED");
                            tm.setErrorMessage(lr.getErrorMessage());
                            ProcessManager.killTree(lr.getPid(), lr.getPgid());
                            portPool.release(port);
                            errors.append("[").append(branch).append("/").append(mod.getName()).append("] ").append(lr.getErrorMessage()).append("; ");
                        }
                    } catch (Exception e) {
                        log.error("launch failed", e);
                        tm.setStatus("FAILED");
                        tm.setErrorMessage("exception: " + e.getMessage());
                        portPool.release(port);
                        errors.append("[").append(branch).append("/").append(mod.getName()).append("] ").append(e.getMessage()).append("; ");
                    }
                    tm.setFinishedAt(now());
                    taskModuleMapper.update(tm);
                }
            } catch (Exception e) {
                log.error("branch processing failed: {}", branch, e);
                errors.append("[").append(branch).append("] ").append(e.getMessage()).append("; ");
            }
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
