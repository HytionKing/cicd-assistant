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
        boolean ok = gitLabService.testConnection(repo);
        r.put("success", ok);
        r.put("message", ok ? "connected" : "connection failed");
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
