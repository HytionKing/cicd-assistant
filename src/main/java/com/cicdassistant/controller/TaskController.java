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
    public Map<String, Object> list(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        Map<String, Object> r = new HashMap<>();
        r.put("items", taskService.page(page, size));
        r.put("total", taskService.total());
        r.put("page", page);
        r.put("size", size);
        return r;
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
                // 日志最多返回末尾 1MB。再大浏览器 <pre> 渲染会明显卡顿。
                // 完整日志保存在服务器的 build-logs/task-N/.../*.log 文件里。
                r.put("content", buildLaunchService.readLog(Paths.get(path), 1_000_000));
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
