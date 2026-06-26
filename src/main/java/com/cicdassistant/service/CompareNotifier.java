package com.cicdassistant.service;

import com.cicdassistant.config.AppProperties;
import com.cicdassistant.entity.CompareTarget;
import com.cicdassistant.entity.CompareTask;
import com.cicdassistant.entity.NotificationWebhook;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把一个 compare task 的结果打包成钉钉 markdown 推送出去。
 *
 * <p>消息内容控制：</p>
 * <ul>
 *   <li>title：50 字以内（钉钉会话列表预览用这个）</li>
 *   <li>text：受 {@code app.compare.notify.message-max-chars} 限制，目标分支太多自动截断</li>
 *   <li>详情链接：用 {@code app.public-host} 拼，空时退到 {@code http://localhost:8080}</li>
 * </ul>
 */
@Slf4j
@Component
public class CompareNotifier {

    private final AppProperties appProperties;
    private final DingTalkSender ding;

    public CompareNotifier(AppProperties appProperties, DingTalkSender ding) {
        this.appProperties = appProperties;
        this.ding = ding;
    }

    public void notifyTaskDone(CompareTask task, List<CompareTarget> targets, NotificationWebhook hook) {
        if (hook == null || hook.getUrl() == null || hook.getUrl().isEmpty()) return;
        if (Integer.valueOf(0).equals(hook.getEnabled())) return;
        if (!appProperties.getCompare().getNotify().isDingtalkEnabled()) return;

        String title = buildTitle(task);
        String text = buildMarkdown(task, targets);
        // 截断保护：钉钉单条消息长度上限实际更大，但 4500 字符已能展示 20+ 分支了
        int max = appProperties.getCompare().getNotify().getMessageMaxChars();
        if (text.length() > max) {
            text = text.substring(0, max) + "\n\n... [报告过长，已截断]";
        }

        DingTalkSender.Result r = ding.sendMarkdown(hook, title, text);
        if (r.isSuccess()) {
            log.info("[COMPARE#{}] notify sent to {} ({})", task.getId(), hook.getName(), hook.getId());
        } else {
            log.warn("[COMPARE#{}] notify failed to {} ({}): {}",
                    task.getId(), hook.getName(), hook.getId(), r.getMessage());
        }
    }

    private String buildTitle(CompareTask t) {
        String prefix = statusEmoji(t.getStatus()) + " 代码合并检测";
        String repo = t.getRepoName() == null ? "" : t.getRepoName();
        return prefix + " · " + repo + " #" + t.getId();
    }

    private String buildMarkdown(CompareTask t, List<CompareTarget> targets) {
        String host = publicHost();
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(statusEmoji(t.getStatus())).append(" 代码合并检测报告 #").append(t.getId()).append("\n\n");
        sb.append("- **仓库**：").append(safe(t.getRepoName())).append("\n");
        sb.append("- **基准分支**：`").append(safe(t.getBaselineBranch())).append("`\n");
        sb.append("- **模式**：").append(safe(t.getMode())).append("\n");
        sb.append("- **整体状态**：").append(statusBadge(t.getStatus())).append("\n");
        if (t.getStartedAt() != null && t.getFinishedAt() != null) {
            sb.append("- **完成时间**：").append(safe(t.getFinishedAt())).append("\n");
        }
        sb.append("\n");

        if (targets != null && !targets.isEmpty()) {
            sb.append("**各目标分支结果**：\n\n");
            for (CompareTarget tg : targets) {
                int err = tg.getErrorCount() == null ? 0 : tg.getErrorCount();
                int warn = tg.getWarnCount() == null ? 0 : tg.getWarnCount();
                int info = tg.getInfoCount() == null ? 0 : tg.getInfoCount();
                int files = tg.getFilesScanned() == null ? 0 : tg.getFilesScanned();
                sb.append("- `").append(safe(tg.getTargetBranch())).append("` ")
                  .append(statusBadge(tg.getStatus()))
                  .append(" → ❌ **").append(err).append("** / ⚠️ ").append(warn).append(" / ℹ️ ").append(info)
                  .append(" （").append(files).append(" 文件）");
                if (tg.getErrorMessage() != null && !tg.getErrorMessage().isEmpty()) {
                    String msg = tg.getErrorMessage();
                    if (msg.length() > 200) msg = msg.substring(0, 200) + "...";
                    sb.append("\n  > ").append(msg.replace("\n", " "));
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        sb.append("👉 [查看完整报告](").append(host).append("/compare/tasks/").append(t.getId()).append(")\n");
        return sb.toString();
    }

    private String publicHost() {
        String h = appProperties.getPublicHost();
        if (h == null || h.trim().isEmpty()) return "http://localhost:8080";
        h = h.trim();
        if (!h.startsWith("http://") && !h.startsWith("https://")) h = "http://" + h;
        while (h.endsWith("/")) h = h.substring(0, h.length() - 1);
        return h;
    }

    private static String statusBadge(String status) {
        if (status == null) return "PENDING";
        switch (status) {
            case "SUCCESS": return "✅ SUCCESS";
            case "PARTIAL": return "⚠️ PARTIAL";
            case "FAILED":  return "❌ FAILED";
            case "RUNNING": return "🏃 RUNNING";
            default:        return status;
        }
    }

    private static String statusEmoji(String status) {
        if ("SUCCESS".equals(status)) return "✅";
        if ("PARTIAL".equals(status)) return "⚠️";
        if ("FAILED".equals(status))  return "❌";
        return "🔍";
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
