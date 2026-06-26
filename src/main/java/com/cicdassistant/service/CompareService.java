package com.cicdassistant.service;

import com.cicdassistant.config.AppProperties;
import com.cicdassistant.entity.CompareFinding;
import com.cicdassistant.entity.CompareTarget;
import com.cicdassistant.entity.CompareTask;
import com.cicdassistant.entity.NotificationWebhook;
import com.cicdassistant.entity.Repo;
import com.cicdassistant.mapper.CompareFindingMapper;
import com.cicdassistant.mapper.CompareTargetMapper;
import com.cicdassistant.mapper.CompareTaskMapper;
import com.cicdassistant.mapper.NotificationWebhookMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
public class CompareService {

    private final CompareTaskMapper taskMapper;
    private final CompareTargetMapper targetMapper;
    private final CompareFindingMapper findingMapper;
    private final NotificationWebhookMapper webhookMapper;
    private final RepoService repoService;
    private final CompareEngine engine;
    private final CompareNotifier notifier;
    private final AppProperties appProperties;

    public CompareService(CompareTaskMapper taskMapper,
                          CompareTargetMapper targetMapper,
                          CompareFindingMapper findingMapper,
                          NotificationWebhookMapper webhookMapper,
                          RepoService repoService,
                          CompareEngine engine,
                          CompareNotifier notifier,
                          AppProperties appProperties) {
        this.taskMapper = taskMapper;
        this.targetMapper = targetMapper;
        this.findingMapper = findingMapper;
        this.webhookMapper = webhookMapper;
        this.repoService = repoService;
        this.engine = engine;
        this.notifier = notifier;
        this.appProperties = appProperties;
    }

    @Data
    public static class CreateRequest {
        private Long repoId;
        private String baselineBranch;
        private List<String> targetBranches;
        private List<MrSelection> mrs;       // [{iid, targetBranch}]
        private String mode;                 // RULE | LLM | HYBRID
        private List<Long> contextIds;
        private Long webhookId;
    }

    @Data
    public static class MrSelection {
        private Integer iid;
        private String targetBranch;
    }

    public CompareTask create(CreateRequest req) {
        if (req.getRepoId() == null) throw new IllegalArgumentException("repoId 必填");
        if (StringUtils.isBlank(req.getBaselineBranch())) throw new IllegalArgumentException("基准分支必填");
        if (req.getTargetBranches() == null || req.getTargetBranches().isEmpty())
            throw new IllegalArgumentException("至少选择一个被对比分支");
        Repo repo = repoService.findByIdMasked(req.getRepoId());
        if (repo == null) throw new IllegalArgumentException("仓库不存在: " + req.getRepoId());

        String mode = normalizeMode(req.getMode());
        if (("LLM".equals(mode) || "HYBRID".equals(mode)) && !llmConfigured()) {
            throw new IllegalArgumentException("LLM 未配置：请在 application.yml 中开启 app.compare.llm.enabled 并填好 base-url");
        }

        CompareTask t = new CompareTask();
        t.setRepoId(req.getRepoId());
        t.setRepoName(repo.getName());
        t.setBaselineBranch(req.getBaselineBranch().trim());
        t.setTargetBranches(String.join(",", req.getTargetBranches()));
        t.setMrSelections(toMrSelectionsJson(req.getMrs()));
        t.setMode(mode);
        t.setContextIds(req.getContextIds() == null ? "" :
                req.getContextIds().stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(""));
        t.setWebhookId(req.getWebhookId());
        t.setStatus("PENDING");
        t.setProgressTotal(req.getTargetBranches().size());
        t.setProgressDone(0);
        t.setProgressPhase("已创建，等待调度");
        t.setCreatedAt(now());
        taskMapper.insert(t);

        for (String tb : req.getTargetBranches()) {
            CompareTarget ct = new CompareTarget();
            ct.setTaskId(t.getId());
            ct.setTargetBranch(tb);
            ct.setStatus("PENDING");
            ct.setErrorCount(0);
            ct.setWarnCount(0);
            ct.setInfoCount(0);
            ct.setFilesScanned(0);
            targetMapper.insert(ct);
        }
        return t;
    }

    public List<CompareTask> page(int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(Math.max(1, size), 100);
        return taskMapper.findPage((p - 1) * s, s);
    }

    public int total() {
        return taskMapper.count();
    }

    public CompareTask get(Long id) {
        return taskMapper.findById(id);
    }

    public List<CompareTarget> listTargets(Long taskId) {
        return targetMapper.findByTaskId(taskId);
    }

    public List<CompareFinding> listFindings(Long targetId) {
        return findingMapper.findByTargetId(targetId);
    }

    public void delete(Long id) {
        List<CompareTarget> targets = targetMapper.findByTaskId(id);
        for (CompareTarget t : targets) {
            findingMapper.deleteByTargetId(t.getId());
        }
        targetMapper.deleteByTaskId(id);
        taskMapper.deleteById(id);
    }

    @Async("taskExecutor")
    public void runAsync(Long taskId) {
        CompareTask task = taskMapper.findById(taskId);
        if (task == null) return;
        log.info("[COMPARE#{}] START repoId={} baseline={} targets=[{}] mode={}",
                taskId, task.getRepoId(), task.getBaselineBranch(), task.getTargetBranches(), task.getMode());
        task.setStatus("RUNNING");
        task.setStartedAt(now());
        task.setProgressPhase("准备运行");
        taskMapper.update(task);

        Repo repo = repoService.findByIdDecrypted(task.getRepoId());
        List<CompareTarget> targets = targetMapper.findByTaskId(taskId);
        Map<String, List<Integer>> mrByBranch = parseMrSelections(task.getMrSelections());

        int total = targets.size();
        int done = 0;
        int succ = 0;
        StringBuilder errors = new StringBuilder();

        for (CompareTarget target : targets) {
            String branch = target.getTargetBranch();
            taskMapper.updateProgress(taskId, total, done, "正在处理 " + branch);
            try {
                List<Integer> mrIids = mrByBranch.getOrDefault(branch, Collections.emptyList());
                engine.runOneTarget(task, repo, target, mrIids);
                if ("SUCCESS".equals(target.getStatus())) succ++;
                else errors.append("[").append(branch).append("] ").append(StringUtils.defaultString(target.getErrorMessage())).append("; ");
            } catch (Exception e) {
                log.error("[COMPARE#{}] target={} unexpected", taskId, branch, e);
                target.setStatus("FAILED");
                target.setErrorMessage(e.getMessage());
                target.setFinishedAt(now());
                targetMapper.update(target);
                errors.append("[").append(branch).append("] ").append(e.getMessage()).append("; ");
            }
            done++;
            taskMapper.updateProgress(taskId, total, done, done < total ? ("已完成 " + done + " / " + total) : "全部完成");
        }

        String finalStatus;
        if (total == 0) finalStatus = "FAILED";
        else if (succ == total) finalStatus = "SUCCESS";
        else if (succ == 0) finalStatus = "FAILED";
        else finalStatus = "PARTIAL";

        task.setStatus(finalStatus);
        task.setErrorMessage(errors.length() > 0 ? errors.toString() : null);
        task.setFinishedAt(now());
        task.setProgressDone(done);
        task.setProgressTotal(total);
        task.setProgressPhase(finalStatus);
        taskMapper.update(task);
        log.info("[COMPARE#{}] DONE status={} ({}/{})", taskId, finalStatus, succ, total);

        // 推送钉钉。任务最终态才发；目标分支列表用 targetMapper 重新查一次拿到最新计数
        if (task.getWebhookId() != null) {
            NotificationWebhook hook = webhookMapper.findById(task.getWebhookId());
            if (hook != null && Integer.valueOf(1).equals(hook.getEnabled())) {
                try {
                    notifier.notifyTaskDone(task, targetMapper.findByTaskId(taskId), hook);
                } catch (Exception e) {
                    log.warn("[COMPARE#{}] notify exception (swallowed): {}", taskId, e.getMessage());
                }
            }
        }
    }

    private String normalizeMode(String m) {
        if (m == null) return "RULE";
        String u = m.toUpperCase();
        if ("RULE".equals(u) || "LLM".equals(u) || "HYBRID".equals(u)) return u;
        return "RULE";
    }

    private boolean llmConfigured() {
        AppProperties.Llm llm = appProperties.getCompare().getLlm();
        return llm.isEnabled() && llm.getBaseUrl() != null && !llm.getBaseUrl().trim().isEmpty();
    }

    private String toMrSelectionsJson(List<MrSelection> mrs) {
        if (mrs == null || mrs.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < mrs.size(); i++) {
            MrSelection m = mrs.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"iid\":").append(m.getIid())
              .append(",\"targetBranch\":\"").append(escape(m.getTargetBranch())).append("\"}");
        }
        sb.append("]");
        return sb.toString();
    }

    /** 把 mr_selections JSON 解析成 branch -> [iids] 索引。P1 不引第三方 JSON 库，手工解析。 */
    private Map<String, List<Integer>> parseMrSelections(String json) {
        Map<String, List<Integer>> map = new LinkedHashMap<>();
        if (json == null || json.length() < 2) return map;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\{\\s*\"iid\"\\s*:\\s*(\\d+)\\s*,\\s*\"targetBranch\"\\s*:\\s*\"([^\"]+)\"\\s*\\}")
                .matcher(json);
        while (m.find()) {
            int iid = Integer.parseInt(m.group(1));
            String branch = m.group(2);
            map.computeIfAbsent(branch, k -> new ArrayList<>()).add(iid);
        }
        return map;
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
