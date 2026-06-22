package com.cicdassistant.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class PageController {

    @GetMapping("/")
    public String index() {
        return "redirect:/repos";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    // ---- 仓库管理 ----
    @GetMapping("/repos")
    public String repos() {
        return "repos";
    }

    // ---- 代码启动 ----
    @GetMapping("/launch")
    public String launch() {
        return "launch";
    }

    @GetMapping("/launch/tasks")
    public String launchTasks() {
        return "tasks";
    }

    @GetMapping("/launch/tasks/{id}")
    public String launchTaskDetail(@PathVariable Long id, Model model) {
        model.addAttribute("taskId", id);
        return "task-detail";
    }

    // 老路径兼容：/tasks 和 /tasks/:id → 302 跳新地址，避免书签失效
    @GetMapping("/tasks")
    public String legacyTasks() {
        return "redirect:/launch/tasks";
    }

    @GetMapping("/tasks/{id}")
    public String legacyTaskDetail(@PathVariable Long id) {
        return "redirect:/launch/tasks/" + id;
    }

    // ---- 合并检测 ----
    @GetMapping("/compare/new")
    public String compareNew() {
        return "compare-new";
    }

    @GetMapping("/compare/tasks")
    public String compareTasks() {
        return "compare-tasks";
    }

    @GetMapping("/compare/tasks/{id}")
    public String compareTaskDetail(@PathVariable Long id, Model model) {
        model.addAttribute("taskId", id);
        return "compare-task-detail";
    }

    @GetMapping("/compare/contexts")
    public String compareContexts() {
        return "compare-contexts";
    }

    @GetMapping("/compare/webhooks")
    public String compareWebhooks() {
        return "compare-webhooks";
    }
}
