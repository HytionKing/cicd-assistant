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
        Task t = taskService.createTask(req.getRepoId(), req.getBranches(), req.getModules(),
                req.getKeepAlive(), req.getNotifyWebhookId());
        taskService.runTaskAsync(t.getId());
        return t;
    }

    @PostMapping("/modules/{moduleId}/stop")
    public ResponseEntity<Void> stopModule(@PathVariable Long moduleId) {
        taskService.stopModuleById(moduleId);
        return ResponseEntity.noContent().build();
    }

    // 重试失败的模块：guard 由 service 内部做，不重新 clone/build 只重起进程。
    // 立即返回 202，浏览器靠 4 秒轮询看到 STARTING → SUCCESS/FAILED。
    @PostMapping("/modules/{moduleId}/retry")
    public ResponseEntity<Map<String, Object>> retryModule(@PathVariable Long moduleId) {
        TaskModule m = taskService.getModule(moduleId);
        if (m == null) {
            Map<String, Object> body = new HashMap<>();
            body.put("error", "not_found");
            body.put("message", "模块不存在");
            return ResponseEntity.status(404).body(body);
        }
        if (!"FAILED".equals(m.getStatus())) {
            Map<String, Object> body = new HashMap<>();
            body.put("error", "invalid_state");
            body.put("message", "只有 FAILED 状态可重试，当前=" + m.getStatus());
            return ResponseEntity.badRequest().body(body);
        }
        taskService.retryModuleAsync(moduleId);
        // 前端 api.post 对 202 会尝试 r.json()，返回一个占位 body 免得解析报错
        Map<String, Object> ok = new HashMap<>();
        ok.put("ok", true);
        return ResponseEntity.accepted().body(ok);
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
        /** null 视作 true（老客户端默认保活行为不变）；false 才走"启动通过立即 stop" */
        private Boolean keepAlive;
        /** 任务终态时把汇总结果推的 webhook id；null 不推 */
        private Long notifyWebhookId;
        public Long getRepoId() { return repoId; }
        public void setRepoId(Long repoId) { this.repoId = repoId; }
        public List<String> getBranches() { return branches; }
        public void setBranches(List<String> branches) { this.branches = branches; }
        public String getModules() { return modules; }
        public void setModules(String modules) { this.modules = modules; }
        public Boolean getKeepAlive() { return keepAlive; }
        public void setKeepAlive(Boolean keepAlive) { this.keepAlive = keepAlive; }
        public Long getNotifyWebhookId() { return notifyWebhookId; }
        public void setNotifyWebhookId(Long notifyWebhookId) { this.notifyWebhookId = notifyWebhookId; }
    }
}
