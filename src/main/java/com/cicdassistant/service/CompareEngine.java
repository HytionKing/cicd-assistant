package com.cicdassistant.service;

import com.cicdassistant.config.AppProperties;
import com.cicdassistant.entity.CompareContext;
import com.cicdassistant.entity.CompareTarget;
import com.cicdassistant.entity.CompareTask;
import com.cicdassistant.entity.Repo;
import com.cicdassistant.mapper.CompareContextMapper;
import com.cicdassistant.mapper.CompareTargetMapper;
import com.cicdassistant.mapper.CompareTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 比对引擎。
 *
 * P1：stub。把每个目标分支当作"已比对、零差异"快速跑完，主要用来跑通编排 + 进度上报流程。
 * P2：接入规则化解析器（Java / Mybatis XML / SQL）。
 * P3：接入 LLM 适配器，支持 RULE / LLM / HYBRID 三种模式。
 *
 * 重要原则：本类对 Git 的所有操作仅限本地 workspace 读写（clone / fetch / reset --hard /
 * checkout / log / diff / show），永不向 origin 写任何内容。
 */
@Slf4j
@Component
public class CompareEngine {

    private final AppProperties appProperties;
    private final CompareTaskMapper taskMapper;
    private final CompareTargetMapper targetMapper;
    private final CompareContextMapper contextMapper;

    public CompareEngine(AppProperties appProperties,
                         CompareTaskMapper taskMapper,
                         CompareTargetMapper targetMapper,
                         CompareContextMapper contextMapper) {
        this.appProperties = appProperties;
        this.taskMapper = taskMapper;
        this.targetMapper = targetMapper;
        this.contextMapper = contextMapper;
    }

    public void runOneTarget(CompareTask task, Repo repo, CompareTarget target, List<Integer> mrIids) {
        log.info("[COMPARE#{}] target={} (mrs={}) START - P1 stub, will just mark SUCCESS",
                task.getId(), target.getTargetBranch(), mrIids);

        target.setStatus("RUNNING");
        target.setStartedAt(now());
        targetMapper.update(target);

        try {
            // P1 stub：模拟工作量
            Thread.sleep(800);

            target.setStatus("SUCCESS");
            target.setErrorCount(0);
            target.setWarnCount(0);
            target.setInfoCount(0);
            target.setFilesScanned(0);
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
        if (csvIds == null || csvIds.trim().isEmpty()) return java.util.Collections.emptyList();
        java.util.Set<Long> wanted = new java.util.HashSet<>();
        for (String s : csvIds.split(",")) {
            try { wanted.add(Long.parseLong(s.trim())); } catch (NumberFormatException ignore) {}
        }
        List<CompareContext> applicable = contextMapper.findApplicable(repoId);
        return applicable.stream().filter(c -> wanted.contains(c.getId())).collect(java.util.stream.Collectors.toList());
    }

    private String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
