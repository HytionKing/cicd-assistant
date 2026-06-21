package com.cicdassistant.controller;

import com.cicdassistant.entity.Task;
import com.cicdassistant.entity.TaskModule;
import com.cicdassistant.service.BuildLaunchService;
import com.cicdassistant.service.TaskService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;
    private final BuildLaunchService buildLaunchService;

    public TaskController(TaskService taskService, BuildLaunchService buildLaunchService) {
        this.taskService = taskService;
        this.buildLaunchService = buildLaunchService;
    }

    @GetMapping
    public List<Task> list() {
        return taskService.listTasks();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        Map<String, Object> r = new HashMap<>();
        r.put("task", taskService.get(id));
        r.put("modules", taskService.listModules(id));
        return r;
    }

    @PostMapping
    public Task create(@RequestBody CreateTaskRequest req) {
        Task t = taskService.createTask(req.getRepoId(), req.getBranches(), req.getModules());
        taskService.runTaskAsync(t.getId());
        return t;
    }

    @PostMapping("/modules/{moduleId}/stop")
    public ResponseEntity<Void> stopModule(@PathVariable Long moduleId) {
        taskService.stopModuleById(moduleId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        taskService.deleteTask(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/modules/{moduleId}/log")
    public Map<String, Object> log(@PathVariable Long moduleId, @RequestParam(defaultValue = "run") String type) {
        TaskModule m = taskService.getModule(moduleId);
        Map<String, Object> r = new HashMap<>();
        if (m == null) {
            r.put("content", "");
            return r;
        }
        try {
            String path = "build".equals(type) ? m.getBuildLogFile() : m.getLogFile();
            if (path == null) {
                r.put("content", "(no log)");
            } else {
                // 启动期间业务服务日志很大（Nacos / Sentinel / bean 初始化），
                // 之前 200KB 经常把头部截掉看不到。给 5MB 上限够用又不至于撑爆浏览器。
                r.put("content", buildLaunchService.readLog(Paths.get(path), 5_000_000));
            }
        } catch (Exception e) {
            r.put("content", "read log failed: " + e.getMessage());
        }
        return r;
    }

    public static class CreateTaskRequest {
        private Long repoId;
        private List<String> branches;
        private String modules;
        public Long getRepoId() { return repoId; }
        public void setRepoId(Long repoId) { this.repoId = repoId; }
        public List<String> getBranches() { return branches; }
        public void setBranches(List<String> branches) { this.branches = branches; }
        public String getModules() { return modules; }
        public void setModules(String modules) { this.modules = modules; }
    }
}
