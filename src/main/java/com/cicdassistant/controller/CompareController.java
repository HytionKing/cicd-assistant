package com.cicdassistant.controller;

import com.cicdassistant.config.AppProperties;
import com.cicdassistant.entity.CompareTask;
import com.cicdassistant.entity.Repo;
import com.cicdassistant.service.CompareService;
import com.cicdassistant.service.GitLabService;
import com.cicdassistant.service.RepoService;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.models.MergeRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/compare")
public class CompareController {

    private final CompareService compareService;
    private final RepoService repoService;
    private final GitLabService gitLabService;
    private final AppProperties appProperties;

    public CompareController(CompareService compareService,
                             RepoService repoService,
                             GitLabService gitLabService,
                             AppProperties appProperties) {
        this.compareService = compareService;
        this.repoService = repoService;
        this.gitLabService = gitLabService;
        this.appProperties = appProperties;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        AppProperties.Compare cc = appProperties.getCompare();
        AppProperties.Llm llm = cc.getLlm();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("mrFetchDefaultLimit", cc.getMrFetchDefaultLimit());
        r.put("mrFetchMaxLimit", 100);
        r.put("llmEnabled", llm.isEnabled() && llm.getBaseUrl() != null && !llm.getBaseUrl().trim().isEmpty());
        return r;
    }

    @GetMapping("/tasks")
    public Map<String, Object> listTasks(@RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        Map<String, Object> r = new HashMap<>();
        r.put("items", compareService.page(page, size));
        r.put("total", compareService.total());
        r.put("page", page);
        r.put("size", size);
        return r;
    }

    @GetMapping("/tasks/{id}")
    public Map<String, Object> getTask(@PathVariable Long id) {
        Map<String, Object> r = new HashMap<>();
        r.put("task", compareService.get(id));
        r.put("targets", compareService.listTargets(id));
        return r;
    }

    @GetMapping("/tasks/{taskId}/targets/{targetId}/findings")
    public List<?> listFindings(@PathVariable Long taskId, @PathVariable Long targetId) {
        return compareService.listFindings(targetId);
    }

    @PostMapping("/tasks")
    public CompareTask create(@RequestBody CompareService.CreateRequest req) {
        CompareTask t = compareService.create(req);
        compareService.runAsync(t.getId());
        return t;
    }

    @DeleteMapping("/tasks/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        compareService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 用户选完被对比分支后调一次，按分支分组返回每个分支最近 N 条已合并 MR。
     */
    @GetMapping("/recent-mrs")
    public Map<String, Object> recentMrs(@RequestParam Long repoId,
                                         @RequestParam String targetBranches,
                                         @RequestParam(required = false) Integer limit) {
        int lim = limit != null && limit > 0
                ? Math.min(limit, 100)
                : appProperties.getCompare().getMrFetchDefaultLimit();
        Repo repo = repoService.findByIdDecrypted(repoId);
        Map<String, Object> r = new HashMap<>();
        if (repo == null) {
            r.put("success", false);
            r.put("message", "仓库不存在");
            r.put("groups", Collections.emptyList());
            return r;
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        StringBuilder errors = new StringBuilder();
        for (String tb : targetBranches.split(",")) {
            String branch = tb.trim();
            if (branch.isEmpty()) continue;
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("targetBranch", branch);
            try {
                List<MergeRequest> mrs = gitLabService.listRecentMergedMrs(repo, branch, lim);
                List<Map<String, Object>> items = new ArrayList<>();
                for (MergeRequest mr : mrs) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("iid", mr.getIid());
                    m.put("title", mr.getTitle());
                    m.put("author", mr.getAuthor() != null ? mr.getAuthor().getName() : null);
                    m.put("sourceBranch", mr.getSourceBranch());
                    m.put("mergedAt", mr.getMergedAt() != null ? mr.getMergedAt().toString() : null);
                    m.put("webUrl", mr.getWebUrl());
                    items.add(m);
                }
                g.put("mrs", items);
            } catch (Exception e) {
                log.warn("recent-mrs failed for branch={}: {}", branch, e.getMessage());
                g.put("mrs", Collections.emptyList());
                g.put("error", e.getMessage());
                errors.append("[").append(branch).append("] ").append(e.getMessage()).append("; ");
            }
            groups.add(g);
        }
        r.put("success", errors.length() == 0);
        if (errors.length() > 0) r.put("message", errors.toString());
        r.put("groups", groups);
        return r;
    }
}
