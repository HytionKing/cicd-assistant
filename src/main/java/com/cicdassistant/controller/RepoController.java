package com.cicdassistant.controller;

import com.cicdassistant.entity.Repo;
import com.cicdassistant.service.GitLabService;
import com.cicdassistant.service.RepoService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/repos")
public class RepoController {

    private final RepoService repoService;
    private final GitLabService gitLabService;

    public RepoController(RepoService repoService, GitLabService gitLabService) {
        this.repoService = repoService;
        this.gitLabService = gitLabService;
    }

    @GetMapping
    public List<Repo> list() {
        return repoService.listMasked();
    }

    @GetMapping("/{id}")
    public Repo get(@PathVariable Long id) {
        return repoService.findByIdMasked(id);
    }

    @PostMapping
    public Repo create(@RequestBody Repo repo) {
        return repoService.create(repo);
    }

    @PutMapping("/{id}")
    public Repo update(@PathVariable Long id, @RequestBody Repo repo) {
        repo.setId(id);
        return repoService.update(repo);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        repoService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test-connection")
    public Map<String, Object> testConnection(@PathVariable Long id) {
        Repo repo = repoService.findByIdDecrypted(id);
        Map<String, Object> r = new HashMap<>();
        if (repo == null) {
            r.put("success", false);
            r.put("message", "repo not found");
            return r;
        }
        GitLabService.TestResult tr = gitLabService.testConnection(repo);
        r.put("success", tr.isSuccess());
        r.put("message", tr.getMessage());
        return r;
    }

    @GetMapping("/{id}/modules")
    public Map<String, Object> modules(@PathVariable Long id) {
        Repo repo = repoService.findByIdMasked(id);
        Map<String, Object> r = new HashMap<>();
        if (repo == null) {
            r.put("configured", false);
            r.put("modules", java.util.Collections.emptyList());
            return r;
        }
        String raw = repo.getModules();
        java.util.List<String> list = new java.util.ArrayList<>();
        if (raw != null && !raw.trim().isEmpty()) {
            for (String s : raw.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) list.add(t);
            }
        }
        r.put("configured", !list.isEmpty());
        r.put("modules", list);
        return r;
    }

    @GetMapping("/{id}/branches")
    public Map<String, Object> branches(@PathVariable Long id) {
        Repo repo = repoService.findByIdDecrypted(id);
        Map<String, Object> r = new HashMap<>();
        if (repo == null) {
            r.put("success", false);
            r.put("message", "repo not found");
            return r;
        }
        try {
            List<String> branches = gitLabService.listBranches(repo);
            r.put("success", true);
            r.put("branches", branches);
        } catch (Exception e) {
            r.put("success", false);
            r.put("message", e.getMessage());
        }
        return r;
    }
}
