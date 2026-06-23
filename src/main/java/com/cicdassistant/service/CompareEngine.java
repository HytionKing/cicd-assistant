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
import com.cicdassistant.service.compare.MrFileChange;
import com.cicdassistant.service.compare.MrFileListFetcher;
import com.cicdassistant.service.compare.PatchHunkVerifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 比对引擎核心。
 *
 * <h3>模式 ✕ 是否选了 MR 的分支表</h3>
 * <table>
 *   <tr><th>mode</th><th>有 MR</th><th>无 MR（兜底）</th></tr>
 *   <tr><td>RULE</td>  <td>patch verifier（fat 不参与）</td>                <td>file differ 全量对比 baseline/target</td></tr>
 *   <tr><td>LLM</td>   <td>每文件直接喂 LLM（同无 MR）</td>                 <td>同左</td></tr>
 *   <tr><td>HYBRID</td><td>patch verifier + 对 ERROR/WARN 调 LLM 复核</td>   <td>file differ + LLM 复核</td></tr>
 * </table>
 *
 * <p>MR 模式天然免疫 fat 的未上线代码 —— 校验依据是 MR 自己的 patch，
 * 而不是 fat 当前内容。</p>
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
    private final PatchHunkVerifier patchVerifier;
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
                         PatchHunkVerifier patchVerifier,
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
        this.patchVerifier = patchVerifier;
        this.llmClient = llmClient;
        this.promptBuilder = promptBuilder;
    }

    public void runOneTarget(CompareTask task, Repo repo, CompareTarget target, List<Integer> mrIids) throws InterruptedException {
        long t0 = System.currentTimeMillis();
        String mode = normalizeMode(task.getMode());
        boolean hasMrs = mrIids != null && !mrIids.isEmpty();
        log.info("[COMPARE#{}] target={} mode={} mrs={} START",
                task.getId(), target.getTargetBranch(), mode, mrIids);

        target.setStatus("RUNNING");
        target.setStartedAt(now());
        targetMapper.update(target);

        try {
            File repoRoot = git.ensureClone(repo);
            git.fetchBranches(repoRoot, Arrays.asList(task.getBaselineBranch(), target.getTargetBranch()));

            // LLM 需要的上下文一次解析；非 LLM 路径用不到也无副作用
            List<CompareContext> contexts = needsLlm(mode)
                    ? resolveContexts(task.getRepoId(), task.getContextIds())
                    : Collections.emptyList();
            String systemPrompt = needsLlm(mode) ? promptBuilder.buildSystem(contexts) : null;

            Counters c = new Counters();
            Set<String> scannedFiles = new LinkedHashSet<>();

            if (hasMrs && (mode.equals("RULE") || mode.equals("HYBRID"))) {
                runMrPath(task, repo, target, mrIids, mode, systemPrompt, repoRoot, c, scannedFiles);
            } else if (mode.equals("LLM")) {
                runLlmStandalone(task, repo, target, mrIids, systemPrompt, repoRoot, c, scannedFiles);
            } else {
                // 无 MR 时的 RULE/HYBRID：退回老 file-differ 全量对比
                runLegacyFileDiff(task, repo, target, mrIids, mode, systemPrompt, repoRoot, c, scannedFiles);
            }

            target.setErrorCount(c.errors);
            target.setWarnCount(c.warns);
            target.setInfoCount(c.infos);
            target.setFilesScanned(scannedFiles.size());
            target.setStatus("SUCCESS");
            log.info("[COMPARE#{}] target={} DONE scanned={} err={} warn={} info={} cost={}ms",
                    task.getId(), target.getTargetBranch(),
                    scannedFiles.size(), c.errors, c.warns, c.infos, System.currentTimeMillis() - t0);
        } catch (Exception e) {
            log.error("[COMPARE#{}] target={} failed", task.getId(), target.getTargetBranch(), e);
            target.setStatus("FAILED");
            target.setErrorMessage(e.getMessage());
        } finally {
            target.setFinishedAt(now());
            targetMapper.update(target);
        }
    }

    /** MR 驱动：用 patch verifier 验证 MR 的改动是否落在 target 上。 */
    private void runMrPath(CompareTask task, Repo repo, CompareTarget target, List<Integer> mrIids,
                           String mode, String systemPrompt, File repoRoot,
                           Counters c, Set<String> scannedFiles) throws InterruptedException {
        for (Integer mrIid : mrIids) {
            List<MrFileChange> changes = mrFiles.changesOf(repo, mrIid);
            log.info("[COMPARE#{}] target={} MR !{} touches {} files",
                    task.getId(), target.getTargetBranch(), mrIid, changes.size());
            for (MrFileChange ch : changes) {
                String path = ch.getPath();
                String tgt = git.readFile(repoRoot, target.getTargetBranch(), path);
                scannedFiles.add(path);

                List<CompareFinding> fs = patchVerifier.verify(path, ch.getPatch(), tgt, mrIid);

                if ("HYBRID".equals(mode) && llmClient.isReady()) {
                    List<CompareFinding> review = fs.stream()
                            .filter(f -> "ERROR".equals(f.getSeverity()) || "WARN".equals(f.getSeverity()))
                            .collect(Collectors.toList());
                    if (!review.isEmpty()) {
                        String userPrompt = promptBuilder.buildUserForPatchReview(
                                path, mrIid, ch.getPatch(), target.getTargetBranch(), tgt, review);
                        fs.addAll(llmClient.evaluate(path, systemPrompt, userPrompt));
                    }
                }

                persistAll(fs, target, mrIid, c);
            }
        }
    }

    /** 纯 LLM：跳过 differ，每个 MR 涉及文件（或退回到 0 文件）直接喂模型。 */
    private void runLlmStandalone(CompareTask task, Repo repo, CompareTarget target, List<Integer> mrIids,
                                  String systemPrompt, File repoRoot,
                                  Counters c, Set<String> scannedFiles) throws InterruptedException {
        Set<String> files = collectMrFiles(repo, mrIids);
        Map<String, Integer> firstSeen = firstSeenMr(repo, mrIids);
        for (String file : files) {
            String baseline = git.readFile(repoRoot, task.getBaselineBranch(), file);
            String tgt = git.readFile(repoRoot, target.getTargetBranch(), file);
            if (baseline == null && tgt == null) continue;
            scannedFiles.add(file);
            String userPrompt = promptBuilder.buildUser(file,
                    task.getBaselineBranch(), target.getTargetBranch(), baseline, tgt);
            List<CompareFinding> fs = llmClient.evaluate(file, systemPrompt, userPrompt);
            persistAll(fs, target, firstSeen.get(file), c);
        }
    }

    /** 老路径：没选 MR 的兜底，整文件 baseline ↔ target 对比；HYBRID 时 LLM 复核。 */
    private void runLegacyFileDiff(CompareTask task, Repo repo, CompareTarget target, List<Integer> mrIids,
                                   String mode, String systemPrompt, File repoRoot,
                                   Counters c, Set<String> scannedFiles) throws InterruptedException {
        Set<String> files = collectMrFiles(repo, mrIids);
        Map<String, Integer> firstSeen = firstSeenMr(repo, mrIids);
        for (String file : files) {
            String baseline = git.readFile(repoRoot, task.getBaselineBranch(), file);
            String tgt = git.readFile(repoRoot, target.getTargetBranch(), file);
            if (baseline == null && tgt == null) continue;
            scannedFiles.add(file);

            List<CompareFinding> fs = new ArrayList<>(router.route(file, baseline, tgt));
            if ("HYBRID".equals(mode) && llmClient.isReady()) {
                List<CompareFinding> review = fs.stream()
                        .filter(f -> "ERROR".equals(f.getSeverity()) || "WARN".equals(f.getSeverity()))
                        .collect(Collectors.toList());
                if (!review.isEmpty()) {
                    String userPrompt = promptBuilder.buildUserForReview(file,
                            task.getBaselineBranch(), target.getTargetBranch(), baseline, tgt, review);
                    fs.addAll(llmClient.evaluate(file, systemPrompt, userPrompt));
                }
            }
            persistAll(fs, target, firstSeen.get(file), c);
        }
    }

    private Set<String> collectMrFiles(Repo repo, List<Integer> mrIids) {
        Set<String> files = new LinkedHashSet<>();
        if (mrIids == null) return files;
        for (Integer iid : mrIids) {
            try {
                for (MrFileChange ch : mrFiles.changesOf(repo, iid)) {
                    files.add(ch.getPath());
                }
            } catch (Exception e) {
                log.warn("[COMPARE] collect files for MR !{} failed: {}", iid, e.getMessage());
            }
        }
        return files;
    }

    private Map<String, Integer> firstSeenMr(Repo repo, List<Integer> mrIids) {
        Map<String, Integer> firstSeen = new HashMap<>();
        if (mrIids == null) return firstSeen;
        for (Integer iid : mrIids) {
            try {
                for (MrFileChange ch : mrFiles.changesOf(repo, iid)) {
                    firstSeen.putIfAbsent(ch.getPath(), iid);
                }
            } catch (Exception e) {
                // ignore — 上一步已 log
            }
        }
        return firstSeen;
    }

    private void persistAll(List<CompareFinding> fs, CompareTarget target, Integer mrIid, Counters c) {
        for (CompareFinding f : fs) {
            f.setTargetId(target.getId());
            if (f.getMrIid() == null) f.setMrIid(mrIid);
            findingMapper.insert(f);
            if ("ERROR".equals(f.getSeverity())) c.errors++;
            else if ("WARN".equals(f.getSeverity())) c.warns++;
            else c.infos++;
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

    private static boolean needsLlm(String mode) {
        return "LLM".equals(mode) || "HYBRID".equals(mode);
    }

    private static String normalizeMode(String m) {
        if (m == null) return "RULE";
        String u = m.toUpperCase();
        return ("LLM".equals(u) || "HYBRID".equals(u)) ? u : "RULE";
    }

    private String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private static class Counters { int errors, warns, infos; }
}
