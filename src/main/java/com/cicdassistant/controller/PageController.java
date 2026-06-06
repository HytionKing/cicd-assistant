package com.cicdassistant.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.ui.Model;

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

    @GetMapping("/repos")
    public String repos() {
        return "repos";
    }

    @GetMapping("/launch")
    public String launch() {
        return "launch";
    }

    @GetMapping("/tasks")
    public String tasks() {
        return "tasks";
    }

    @GetMapping("/tasks/{id}")
    public String taskDetail(@PathVariable Long id, Model model) {
        model.addAttribute("taskId", id);
        return "task-detail";
    }
}
