package com.cicdassistant.service.compare;

import com.cicdassistant.entity.CompareContext;
import com.cicdassistant.entity.CompareFinding;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 把"对一个文件做合并风险评估"这件事拼成 OpenAI chat 协议需要的 system + user 两段文本。
 *
 * <p>HYBRID 模式下复用同一套 prompt，只是 user prompt 末尾追加"已知规则发现"上下文，
 * 让 LLM 在规则结论基础上判断是否真有上线风险（消除噪音/确认漏检）。</p>
 */
@Component
public class LlmPromptBuilder {

    /**
     * 单文件 baseline / target 各自截断到 6000 字符，粗略对应 ~2000 tokens。
     * 两段加起来加 system + 模板 ≈ 12k 字符，留足 4k 给响应仍在 32k 上下文窗口里。
     */
    private static final int MAX_SNIPPET_CHARS = 6000;

    /**
     * 拼 system prompt：场景说明 + 上下文条目（全局 + 仓库相关）。
     */
    public String buildSystem(List<CompareContext> contexts) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是代码合并审计助手。任务背景：开发分支即将合入基准分支，需要判断目标分支相对基准分支是否存在")
          .append("\"上线风险\"——例如代码遗漏、SQL 丢失、关键逻辑被改回旧实现、配置缺失、依赖被删除等。\n\n");

        List<CompareContext> globals = new ArrayList<>();
        List<CompareContext> repos = new ArrayList<>();
        if (contexts != null) {
            for (CompareContext c : contexts) {
                if (c == null || c.getContent() == null || c.getContent().trim().isEmpty()) continue;
                if (c.getRepoId() == null) globals.add(c);
                else repos.add(c);
            }
        }

        if (!globals.isEmpty()) {
            sb.append("【全局业务上下文】\n");
            int i = 1;
            for (CompareContext c : globals) {
                sb.append(i++).append(". ").append(safeTitle(c.getTitle())).append("\n")
                  .append(c.getContent().trim()).append("\n\n");
            }
        }
        if (!repos.isEmpty()) {
            sb.append("【仓库相关上下文】\n");
            int i = 1;
            for (CompareContext c : repos) {
                sb.append(i++).append(". ").append(safeTitle(c.getTitle())).append("\n")
                  .append(c.getContent().trim()).append("\n\n");
            }
        }

        sb.append("评审输出严格遵循：返回一个 JSON 对象 {\"findings\":[{...}]}，每项含字段 severity、summary、comment。")
          .append("severity 取值仅限 ERROR / WARN / INFO。无任何风险时返回 {\"findings\":[]}。")
          .append("不要返回 markdown 代码块包裹，不要任何解释文字。");
        return sb.toString();
    }

    /**
     * 纯 LLM 模式：仅传 baseline / target 两版本源码，让模型自由判断。
     */
    public String buildUser(String filePath, String baselineBranch, String targetBranch,
                            String baseline, String target) {
        return buildUserBase(filePath, baselineBranch, targetBranch, baseline, target, null);
    }

    /**
     * HYBRID 模式：附带规则引擎已发现的 ERROR/WARN，要求 LLM 复核（确认/驳回/补充）。
     */
    public String buildUserForReview(String filePath, String baselineBranch, String targetBranch,
                                     String baseline, String target,
                                     List<CompareFinding> ruleFindings) {
        return buildUserBase(filePath, baselineBranch, targetBranch, baseline, target, ruleFindings);
    }

    /**
     * HYBRID + MR 模式：复核 patch verifier 的发现。
     * 给模型看的是 MR 的 patch（事实依据）和 target 当前文件，让它判断未命中是"真上线丢失"还是"等价改写/重构改名"。
     */
    public String buildUserForPatchReview(String filePath, Integer mrIid, String mrPatch,
                                          String targetBranch, String targetContent,
                                          List<CompareFinding> ruleFindings) {
        StringBuilder sb = new StringBuilder();
        sb.append("文件: ").append(filePath).append("\n");
        sb.append("MR: !").append(mrIid == null ? "?" : mrIid).append("\n\n");
        sb.append("MR 引入的 patch（unified diff）：\n```diff\n").append(truncate(mrPatch)).append("\n```\n\n");
        sb.append("目标分支 (").append(targetBranch).append(") 当前该文件内容：\n");
        sb.append("```\n").append(truncate(targetContent)).append("\n```\n\n");
        if (ruleFindings != null && !ruleFindings.isEmpty()) {
            sb.append("规则校验器认为以下改动可能未在目标分支生效，请逐条判断它们是真正的上线丢失，")
              .append("还是等价改写/重构改名等可接受情况：\n");
            int i = 1;
            for (CompareFinding f : ruleFindings) {
                sb.append(i++).append(". [").append(f.getSeverity()).append("] ")
                  .append(f.getType()).append(" — ").append(safeTitle(f.getSummary())).append("\n");
                if (f.getBaselineSnippet() != null && !f.getBaselineSnippet().isEmpty()) {
                    sb.append("   未命中样本：\n   ").append(f.getBaselineSnippet().replace("\n", "\n   ")).append("\n");
                }
            }
            sb.append("\n");
        }
        sb.append("按 system 中指定的 JSON 格式返回。无任何真实风险时返回 {\"findings\":[]}。");
        return sb.toString();
    }

    private String buildUserBase(String filePath, String baselineBranch, String targetBranch,
                                 String baseline, String target,
                                 List<CompareFinding> ruleFindings) {
        StringBuilder sb = new StringBuilder();
        sb.append("文件: ").append(filePath).append("\n\n");
        sb.append("基准分支 (").append(baselineBranch).append(") 版本：\n");
        sb.append("```\n").append(truncate(baseline)).append("\n```\n\n");
        sb.append("目标分支 (").append(targetBranch).append(") 版本：\n");
        sb.append("```\n").append(truncate(target)).append("\n```\n\n");

        if (ruleFindings != null && !ruleFindings.isEmpty()) {
            sb.append("规则引擎已发现以下疑似问题，请复核它们是真风险还是噪音；如有遗漏请补充。\n");
            int i = 1;
            for (CompareFinding f : ruleFindings) {
                sb.append(i++).append(". [").append(f.getSeverity()).append("] ")
                  .append(f.getType()).append(" — ").append(safeTitle(f.getSummary())).append("\n");
            }
            sb.append("\n");
        }

        sb.append("请判断目标分支是否存在上线风险，按 system 中指定的 JSON 格式返回。");
        return sb.toString();
    }

    private String truncate(String s) {
        if (s == null) return "(文件不存在)";
        if (s.length() <= MAX_SNIPPET_CHARS) return s;
        return s.substring(0, MAX_SNIPPET_CHARS) + "\n... [截断，原始 " + s.length() + " 字符]";
    }

    private String safeTitle(String t) {
        return t == null ? "" : t.trim();
    }
}
