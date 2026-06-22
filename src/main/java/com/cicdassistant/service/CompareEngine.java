package com.cicdassistant.service;

import com.cicdassistant.config.AppProperties;
import com.cicdassistant.entity.CompareContext;
import com.cicdassistant.entity.CompareFinding;
import com.cicdassistant.entity.CompareTarget;
import com.cicdassistant.entity.CompareTask;
import com.cicdassistant.entity.Repo;
import com.cicdassistant.mapper.CompareContextMapper;
import com.cicdassistant.mapper.CompareFindingMapper;
import com.cicdassistant.mapper.CompareTargetMapper;
import com.cicdassistant.mapper.CompareTaskMapper;
import com.cicdassistant.service.compare.FileDifferRouter;
import com.cicdassistant.service.compare.GitWorkspaceManager;
import com.cicdassistant.service.compare.LlmClient;
import com.cicdassistant.service.compare.LlmPromptBuilder;
import com.cicdassistant.service.compare.MrFileListFetcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 比对引擎核心：基于 MR 圈定的文件集合，对 baseline 和单个目标分支做语义级 diff。
 *
 * <p>三种模式：</p>
 * <ul>
 *   <li>RULE — 仅跑各类语义 differ</li>
 *   <li>LLM — 跳过 differ，每个文件直接喂给模型评估</li>
 *   <li>HYBRID — 先跑 RULE，对每个 ERROR/WARN 调 LLM 复核（结论保留两份）</li>
 * </ul>
 *
 * <p>Git 操作全部走 {@link GitWorkspaceManager}，严格只读，不污染 origin。</p>
 */
@Slf4j
@Component
public class CompareEngine {

    private final AppProperties appProperties;
    private final CompareTaskMapper taskMapper;
    private final CompareTargetMapper targetMapper;
    private final CompareFindingMapper findingMapper;
    private final CompareContextMapper contextMapper;
    private final GitWorkspaceManager git;
    private final MrFileListFetcher mrFiles;
    private final FileDifferRouter router;
    private final LlmClient llmClient;
    private final LlmPromptBuilder promptBuilder;

    public CompareEngine(AppProperties appProperties,
                         CompareTaskMapper taskMapper,
                         CompareTargetMapper targetMapper,
                         CompareFindingMapper findingMapper,
                         CompareContextMapper contextMapper,
                         GitWorkspaceManager git,
                         MrFileListFetcher mrFiles,
                         FileDifferRouter router,
                         LlmClient llmClient,
                         LlmPromptBuilder promptBuilder) {
        this.appProperties = appProperties;
        this.taskMapper = taskMapper;
        this.targetMapper = targetMapper;
        this.findingMapper = findingMapper;
        this.contextMapper = contextMapper;
        this.git = git;
        this.mrFiles = mrFiles;
        this.router = router;
        this.llmClient = llmClient;
        this.promptBuilder = promptBuilder;
    }

    public void runOneTarget(CompareTask task, Repo repo, CompareTarget target, List<Integer> mrIids) {
        long t0 = System.currentTimeMillis();
        log.info("[COMPARE#{}] target={} mode={} mrs={} START",
                task.getId(), target.getTargetBranch(), task.getMode(), mrIids);

        target.setStatus("RUNNING");
        target.setStartedAt(now());
        targetMapper.update(target);

        try {
            File repoRoot = git.ensureClone(repo);
            git.fetchBranches(repoRoot, Arrays.asList(task.getBaselineBranch(), target.getTargetBranch()));

            // 1) 汇总 MR 修改过的文件
            Set<String> files = new LinkedHashSet<>();
            Map<String, Integer> firstSeenIid = new HashMap<>();
            if (mrIids != null) {
                for (Integer iid : mrIids) {
                    List<String> changedFiles = mrFiles.filesOf(repo, iid);
                    log.info("[COMPARE#{}] target={} MR !{} touches {} files",
                            task.getId(), target.getTargetBranch(), iid, changedFiles.size());
                    for (String p : changedFiles) {
                        files.add(p);
                        firstSeenIid.putIfAbsent(p, iid);
                    }
                }
            }

            String mode = normalizeMode(task.getMode());
            // LLM/HYBRID 模式下提前解析一次上下文，所有文件共用同一份 system prompt
            List<CompareContext> contexts = (mode.equals("LLM") || mode.equals("HYBRID"))
                    ? resolveContexts(task.getRepoId(), task.getContextIds())
                    : Collections.emptyList();
            String systemPrompt = (mode.equals("LLM") || mode.equals("HYBRID"))
                    ? promptBuilder.buildSystem(contexts)
                    : null;

            int errors = 0, warns = 0, infos = 0;
            int scanned = 0;
            for (String file : files) {
                String baseline = git.readFile(repoRoot, task.getBaselineBranch(), file);
                String tgt = git.readFile(repoRoot, target.getTargetBranch(), file);
                if (baseline == null && tgt == null) continue;

                List<CompareFinding> fs = new ArrayList<>();
                if ("LLM".equals(mode)) {
                    String userPrompt = promptBuilder.buildUser(file,
                            task.getBaselineBranch(), target.getTargetBranch(), baseline, tgt);
                    fs.addAll(llmClient.evaluate(file, systemPrompt, userPrompt));
                } else {
                    List<CompareFinding> ruleFs = router.route(file, baseline, tgt);
                    fs.addAll(ruleFs);
                    if ("HYBRID".equals(mode)) {
                        List<CompareFinding> reviewTargets = ruleFs.stream()
                                .filter(f -> "ERROR".equals(f.getSeverity()) || "WARN".equals(f.getSeverity()))
                                .collect(Collectors.toList());
                        if (!reviewTargets.isEmpty()) {
                            String userPrompt = promptBuilder.buildUserForReview(file,
                                    task.getBaselineBranch(), target.getTargetBranch(),
                                    baseline, tgt, reviewTargets);
                            fs.addAll(llmClient.evaluate(file, systemPrompt, userPrompt));
                        }
                    }
                }

                Integer mrIid = firstSeenIid.get(file);
                for (CompareFinding f : fs) {
                    f.setTargetId(target.getId());
                    f.setMrIid(mrIid);
                    findingMapper.insert(f);
                    if ("ERROR".equals(f.getSeverity())) errors++;
                    else if ("WARN".equals(f.getSeverity())) warns++;
                    else infos++;
                }
                scanned++;
            }

            target.setErrorCount(errors);
            target.setWarnCount(warns);
            target.setInfoCount(infos);
            target.setFilesScanned(scanned);
            target.setStatus("SUCCESS");
            log.info("[COMPARE#{}] target={} DONE scanned={} err={} warn={} info={} cost={}ms",
                    task.getId(), target.getTargetBranch(),
                    scanned, errors, warns, infos, System.currentTimeMillis() - t0);
        } catch (Exception e) {
            log.error("[COMPARE#{}] target={} failed", task.getId(), target.getTargetBranch(), e);
            target.setStatus("FAILED");
            target.setErrorMessage(e.getMessage());
        } finally {
            target.setFinishedAt(now());
            targetMapper.update(target);
        }
    }

    public List<CompareContext> resolveContexts(Long repoId, String csvIds) {
        if (csvIds == null || csvIds.trim().isEmpty()) return Collections.emptyList();
        Set<Long> wanted = Arrays.stream(csvIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> { try { return Long.parseLong(s); } catch (Exception e) { return -1L; } })
                .filter(n -> n > 0)
                .collect(Collectors.toSet());
        List<CompareContext> applicable = contextMapper.findApplicable(repoId);
        return applicable.stream().filter(c -> wanted.contains(c.getId())).collect(Collectors.toList());
    }

    private static String normalizeMode(String m) {
        if (m == null) return "RULE";
        String u = m.toUpperCase();
        return ("LLM".equals(u) || "HYBRID".equals(u)) ? u : "RULE";
    }

    private String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
