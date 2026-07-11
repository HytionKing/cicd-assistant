package com.cicdassistant.service;

import com.cicdassistant.config.AppProperties;
import com.cicdassistant.entity.NotificationWebhook;
import com.cicdassistant.entity.Task;
import com.cicdassistant.entity.TaskModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 代码启动任务终态时把汇总结果推到钉钉群。跟 {@link CompareNotifier} 结构对齐：
 * <ul>
 *   <li>title：状态 emoji + 仓库名 + 任务 id</li>
 *   <li>text：仓库/任务ID/总状态 + 按分支分组的模块启动结果 + 详情链接</li>
 *   <li>受 {@code app.compare.notify.message-max-chars} 截断（复用同一份上限，两侧行为一致）</li>
 * </ul>
 */
@Slf4j
@Component
public class LaunchNotifier {

    private final AppProperties appProperties;
    private final DingTalkSender ding;
    private final int serverPort;

    public LaunchNotifier(AppProperties appProperties, DingTalkSender ding,
                          @Value("${server.port:8080}") int serverPort) {
        this.appProperties = appProperties;
        this.ding = ding;
        this.serverPort = serverPort;
    }

    public void notifyTaskDone(Task task, List<TaskModule> modules, NotificationWebhook hook) {
        if (hook == null || hook.getUrl() == null || hook.getUrl().isEmpty()) return;
        if (Integer.valueOf(0).equals(hook.getEnabled())) return;
        // 启动侧沿用 compare 的钉钉总开关，一处配置管两处
        if (!appProperties.getCompare().getNotify().isDingtalkEnabled()) return;

        String title = buildTitle(task);
        String text = buildMarkdown(task, modules);
        int max = appProperties.getCompare().getNotify().getMessageMaxChars();
        if (text.length() > max) {
            text = text.substring(0, max) + "\n\n... [报告过长，已截断]";
        }

        DingTalkSender.Result r = ding.sendMarkdown(hook, title, text);
        if (r.isSuccess()) {
            log.info("[LAUNCH#{}] notify sent to {} ({})", task.getId(), hook.getName(), hook.getId());
        } else {
            log.warn("[LAUNCH#{}] notify failed to {} ({}): {}",
                    task.getId(), hook.getName(), hook.getId(), r.getMessage());
        }
    }

    private String buildTitle(Task t) {
        return statusEmoji(t.getStatus()) + " 代码启动 · "
                + (t.getRepoName() == null ? "" : t.getRepoName())
                + " #" + t.getId();
    }

    private String buildMarkdown(Task t, List<TaskModule> modules) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(statusEmoji(t.getStatus())).append(" 代码启动结果 #").append(t.getId()).append("\n\n");
        sb.append("- **仓库**：").append(safe(t.getRepoName())).append("\n");
        sb.append("- **任务 ID**：#").append(t.getId()).append("\n");
        sb.append("- **总状态**：").append(statusBadge(t.getStatus())).append("\n");
        if (t.getFinishedAt() != null) {
            sb.append("- **完成时间**：").append(safe(t.getFinishedAt())).append("\n");
        }
        sb.append("\n");

        // 按分支分组：钉钉 markdown 缩进列表对齐好看，同 CompareNotifier 风格
        Map<String, List<TaskModule>> byBranch = new LinkedHashMap<>();
        List<TaskModule> ordered = modules == null ? new ArrayList<>() : modules;
        for (TaskModule m : ordered) {
            byBranch.computeIfAbsent(m.getBranch(), k -> new ArrayList<>()).add(m);
        }
        if (!byBranch.isEmpty()) {
            sb.append("**分支模块启动结果**：\n\n");
            for (Map.Entry<String, List<TaskModule>> e : byBranch.entrySet()) {
                sb.append("- `").append(safe(e.getKey())).append("`\n");
                for (TaskModule m : e.getValue()) {
                    sb.append("  - ").append(moduleLine(m)).append("\n");
                }
            }
            sb.append("\n");
        }

        if (t.getErrorMessage() != null && !t.getErrorMessage().isEmpty()) {
            String msg = t.getErrorMessage();
            if (msg.length() > 300) msg = msg.substring(0, 300) + "...";
            sb.append("> ⚠️ ").append(msg.replace("\n", " ")).append("\n\n");
        }

        sb.append("👉 [查看任务详情](").append(publicHost()).append("/launch/tasks/").append(t.getId()).append(")\n");
        return sb.toString();
    }

    private String moduleLine(TaskModule m) {
        StringBuilder sb = new StringBuilder();
        sb.append(statusEmoji(m.getStatus())).append(" **").append(safe(m.getModuleName())).append("** ")
          .append(statusBadge(m.getStatus()));
        if (m.getPort() != null) sb.append(" · port ").append(m.getPort());
        if (m.getErrorMessage() != null && !m.getErrorMessage().isEmpty()) {
            String em = m.getErrorMessage();
            if (em.length() > 150) em = em.substring(0, 150) + "...";
            sb.append("\n    > ").append(em.replace("\n", " "));
        }
        return sb.toString();
    }

    /**
     * 拼报告链接的 base。规则跟 CompareNotifier 一模一样（app.public-host 空落回 localhost:port，
     * 光有 host 补 port，完整 URL 原样用）。之前是重复代码，先接受这份重复，后续统一到 Util 里。
     */
    private String publicHost() {
        String h = appProperties.getPublicHost();
        if (h == null || h.trim().isEmpty()) return "http://localhost:" + serverPort;
        h = h.trim();
        boolean hasScheme = h.startsWith("http://") || h.startsWith("https://");
        if (!hasScheme) h = "http://" + h;
        while (h.endsWith("/")) h = h.substring(0, h.length() - 1);
        int schemeEnd = h.indexOf("://") + 3;
        int pathStart = h.indexOf('/', schemeEnd);
        String hostSeg = pathStart < 0 ? h.substring(schemeEnd) : h.substring(schemeEnd, pathStart);
        boolean ipv6 = hostSeg.startsWith("[");
        boolean hasPort = ipv6 ? hostSeg.contains("]:") : hostSeg.contains(":");
        if (hasPort) return h;
        if (pathStart < 0) return h + ":" + serverPort;
        return h.substring(0, pathStart) + ":" + serverPort + h.substring(pathStart);
    }

    private static String statusBadge(String status) {
        if (status == null) return "PENDING";
        switch (status) {
            case "SUCCESS": return "✅ SUCCESS";
            case "PARTIAL": return "⚠️ PARTIAL";
            case "FAILED":  return "❌ FAILED";
            case "STOPPED": return "⏹ STOPPED";
            case "RUNNING": return "🏃 RUNNING";
            case "STARTING": return "🚀 STARTING";
            case "BUILDING": return "🔨 BUILDING";
            default:        return status;
        }
    }

    private static String statusEmoji(String status) {
        if ("SUCCESS".equals(status)) return "✅";
        if ("PARTIAL".equals(status)) return "⚠️";
        if ("FAILED".equals(status))  return "❌";
        if ("STOPPED".equals(status)) return "⏹";
        return "🚀";
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
