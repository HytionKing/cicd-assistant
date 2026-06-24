package com.cicdassistant.service.compare;

import com.cicdassistant.entity.CompareContext;
import com.cicdassistant.entity.CompareFinding;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把"对一个文件做合并风险评估"这件事拼成 OpenAI chat 协议需要的 system + user 两段文本。
 *
 * <p>三个入口分别对应三条业务路径：</p>
 * <ul>
 *   <li>{@link #buildSystem} + {@link #buildUser}/{@link #buildUserForReview} —— 老的"baseline ↔ target 整文件比对"路径（runLlmStandalone / runLegacyFileDiff 用）</li>
 *   <li>{@link #buildSystemForPatchReview} + {@link #buildUserForPatchReview} —— HYBRID + MR 的"patch 落地核验"路径（runMrPath 用）</li>
 * </ul>
 *
 * <p>两套 prompt 分开是因为两个任务的"判定基准"根本不同：前者比较两个文件的状态，
 * 后者验证"MR 引入的具体改动在不在目标分支"。复用同一 system prompt 会让 LLM 误解任务。</p>
 */
@Component
public class LlmPromptBuilder {

    private static final int MAX_SNIPPET_CHARS = 6000;
    /** Patch 文本上限 —— 长 MR 也应能塞下整个 diff。 */
    private static final int MAX_PATCH_CHARS = 8000;
    /** 目标文件按窗口拼出的视图上限 —— 留出空间给 Qwen 32K 上下文 + 响应 token。 */
    private static final int MAX_TARGET_VIEW_CHARS = 20000;
    /** 每个 hunk 在 target 上下各取 N 行作为审阅窗口。 */
    private static final int TARGET_WINDOW_LINES = 30;

    /** 老的整文件比对场景 system prompt。runLlmStandalone / runLegacyFileDiff 仍走它。 */
    public String buildSystem(List<CompareContext> contexts) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是代码合并审计助手。任务背景：开发分支即将合入基准分支，需要判断目标分支相对基准分支是否存在")
          .append("\"上线风险\"——例如代码遗漏、SQL 丢失、关键逻辑被改回旧实现、配置缺失、依赖被删除等。\n\n");
        appendContexts(sb, contexts);
        sb.append("评审输出严格遵循：返回一个 JSON 对象 {\"findings\":[{...}]}，每项含字段 severity、summary、comment。")
          .append("severity 取值仅限 ERROR / WARN / INFO。无任何风险时返回 {\"findings\":[]}。")
          .append("不要返回 markdown 代码块包裹，不要任何解释文字。");
        return sb.toString();
    }

    /**
     * Patch 审阅专用 system prompt。强制要求模型"先去目标分支搜，找不到才报"，且必须引用行号作证据。
     * 这是为了对抗以下三类 LLM 失误：(1) 被规则结论 priming 后照搬复述；(2) 看不全文件就断言"完全缺失"；
     * (3) 等价改写也认作风险。
     */
    public String buildSystemForPatchReview(List<CompareContext> contexts) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是代码上线核验助手。\n\n");
        sb.append("【任务】\n");
        sb.append("每次给你 1 个 MR 在 1 个文件里的 patch（unified diff），以及目标分支（env-*）当前该文件的相关行（带行号）。\n");
        sb.append("你的工作是核实「MR 的改动是否真的合入了目标分支」并输出真实的上线风险。\n\n");

        sb.append("【判定步骤（必须按此执行）】\n");
        sb.append("1. 把 patch 中每条 `+` 行（MR 新增内容）逐条到目标分支文件视图中搜索：\n");
        sb.append("   a. 找到原样 / 仅空白差异的同一行 → 该 + 行已生效，不报\n");
        sb.append("   b. 找到等价改写（同义函数封装、字段重命名、抽取到 helper、catch 换成 Optional 等）→ 已生效，不报\n");
        sb.append("   c. 完全找不到，且找不到承担同一职责的等价代码 → 真正的上线丢失，报 ERROR\n");
        sb.append("2. 对每条 `-` 行（MR 删除内容）同样搜索：\n");
        sb.append("   a. 找不到 → 删除已生效，不报\n");
        sb.append("   b. 仍在原本上下文里出现 → 漏删，报 WARN\n\n");

        sb.append("【证据要求（必须满足，否则视为无效输出）】\n");
        sb.append("- `comment` 字段必须给出目标文件中的具体行号或片段作为证据，形式如：\n");
        sb.append("    \"目标分支第 234~237 行已包含等价实现 userFacade.update(...)\"\n");
        sb.append("    \"目标分支第 500 行附近完全没有 fillUserInfo 方法\"\n");
        sb.append("- 严禁编造未在视图中出现的行号或代码片段。\n");
        sb.append("- 若目标视图窗口未覆盖相关区域（patch 改动的位置不在窗口里），必须说明：\n");
        sb.append("    severity = INFO；summary 以「视图未覆盖」开头；comment 注明 \"提供的目标分支片段未覆盖相关区域，无法核验\"。\n\n");

        sb.append("【对规则结论的态度】\n");
        sb.append("用户可能在 user prompt 末尾附了规则校验器的怀疑点。它的算法是行级模糊匹配，会有较多误报。\n");
        sb.append("规则结论仅供线索，不可照搬。你必须独立核验。若判定规则误报，请单独输出一条 finding：\n");
        sb.append("  severity = INFO；summary 以「AI 否决：」开头；comment 写「规则报 XXX，实际在目标分支第 N 行已存在/已正确删除」。\n\n");

        appendContexts(sb, contexts);

        sb.append("【输出格式】\n");
        sb.append("返回 JSON 对象 {\"findings\":[{severity, summary, comment}]}。\n");
        sb.append("severity ∈ {ERROR, WARN, INFO}。无任何风险且无规则可否决时返回 {\"findings\":[]}。\n");
        sb.append("不要 markdown 代码块包裹，不要解释文字。");
        return sb.toString();
    }

    public String buildUser(String filePath, String baselineBranch, String targetBranch,
                            String baseline, String target) {
        return buildUserBase(filePath, baselineBranch, targetBranch, baseline, target, null);
    }

    public String buildUserForReview(String filePath, String baselineBranch, String targetBranch,
                                     String baseline, String target,
                                     List<CompareFinding> ruleFindings) {
        return buildUserBase(filePath, baselineBranch, targetBranch, baseline, target, ruleFindings);
    }

    /**
     * HYBRID + MR 模式：复核 patch verifier 的发现。
     *
     * <p>关键差异（相比之前版本）：</p>
     * <ul>
     *   <li>target 不再首部截断 —— 改成按 patch 的 @@ 行号在 target 中开窗，每个 hunk 上下各 30 行，
     *       拼成"带行号"的视图。LLM 能看到 MR 实际改动位置周围的代码，而不是文件开头的无关内容。</li>
     *   <li>规则 finding 的 framing 改成"线索，需独立核实"，避免模型照搬。</li>
     * </ul>
     */
    public String buildUserForPatchReview(String filePath, Integer mrIid, String mrPatch,
                                          String targetBranch, String targetContent,
                                          List<CompareFinding> ruleFindings) {
        StringBuilder sb = new StringBuilder();
        sb.append("文件: ").append(filePath).append("\n");
        sb.append("MR: !").append(mrIid == null ? "?" : mrIid).append("\n\n");

        sb.append("【MR 引入的 patch（unified diff）】\n");
        sb.append("```diff\n").append(truncate(mrPatch, MAX_PATCH_CHARS)).append("\n```\n\n");

        sb.append("【目标分支 ").append(targetBranch).append(" 当前该文件的相关行（带行号，按 patch 的 @@ 位置开窗±")
          .append(TARGET_WINDOW_LINES).append(" 行）】\n");
        sb.append("```\n").append(renderTargetWindows(mrPatch, targetContent)).append("\n```\n\n");

        if (ruleFindings != null && !ruleFindings.isEmpty()) {
            sb.append("【规则校验器的怀疑点（仅供线索；规则按行级模糊匹配会误报，请独立到目标视图里核实）】\n");
            int i = 1;
            for (CompareFinding f : ruleFindings) {
                sb.append(i++).append(". [").append(f.getSeverity()).append("] ")
                  .append(f.getType()).append(" — ").append(safeTitle(f.getSummary())).append("\n");
                if (f.getBaselineSnippet() != null && !f.getBaselineSnippet().isEmpty()) {
                    sb.append("   规则标记的片段：\n   ")
                      .append(truncate(f.getBaselineSnippet(), 800).replace("\n", "\n   ")).append("\n");
                }
            }
            sb.append("\n");
        }
        sb.append("请按 system 中规定的判定步骤逐条核实，输出 JSON。");
        return sb.toString();
    }

    // ---- target windowing ----

    /**
     * 按 patch 的 @@ +X,Y @@ 信息，把 target 文件按 hunk 位置开窗，每个 hunk 上下各 {@link #TARGET_WINDOW_LINES} 行。
     * 重叠窗口自动合并，跨窗用 "... [省略 N 行] ..." 分隔。每行带行号前缀，方便 LLM 引用。
     */
    String renderTargetWindows(String mrPatch, String targetContent) {
        if (targetContent == null || targetContent.isEmpty()) return "(文件不存在)";
        String[] lines = targetContent.split("\\r?\\n", -1);
        int total = lines.length;
        List<int[]> hunks = parseHunkRanges(mrPatch);
        if (hunks.isEmpty()) {
            // 没解析到 hunk 头（极少见）→ 退回到首段截断，给 LLM 一点上下文总比啥都没有强
            return truncate(targetContent, MAX_TARGET_VIEW_CHARS);
        }

        // 合并重叠窗口
        List<int[]> windows = new ArrayList<>();
        for (int[] r : hunks) {
            int from = Math.max(1, r[0] - TARGET_WINDOW_LINES);
            int to = Math.min(total, r[1] + TARGET_WINDOW_LINES);
            if (to < from) continue;
            windows.add(new int[]{from, to});
        }
        windows.sort(Comparator.comparingInt(a -> a[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] w : windows) {
            if (!merged.isEmpty()) {
                int[] last = merged.get(merged.size() - 1);
                if (w[0] <= last[1] + 1) { last[1] = Math.max(last[1], w[1]); continue; }
            }
            merged.add(new int[]{w[0], w[1]});
        }

        // 渲染：行号靠右对齐，跨窗插省略
        int width = String.valueOf(total).length();
        StringBuilder sb = new StringBuilder();
        int lastEnd = 0;
        for (int[] w : merged) {
            if (lastEnd == 0 && w[0] > 1) {
                sb.append("... [省略文件开头 ").append(w[0] - 1).append(" 行] ...\n");
            } else if (lastEnd > 0 && w[0] > lastEnd + 1) {
                sb.append("... [省略 ").append(w[0] - lastEnd - 1).append(" 行] ...\n");
            }
            for (int ln = w[0]; ln <= w[1] && ln <= total; ln++) {
                String body = lines[ln - 1];
                sb.append(String.format("%" + width + "d| %s%n", ln, body));
                if (sb.length() > MAX_TARGET_VIEW_CHARS) {
                    sb.append("... [视图截断，原始文件共 ").append(total).append(" 行]\n");
                    return sb.toString();
                }
            }
            lastEnd = w[1];
        }
        if (lastEnd < total) {
            sb.append("... [省略文件尾部 ").append(total - lastEnd).append(" 行]\n");
        }
        return sb.toString();
    }

    /** 解析 @@ -OLD,N +NEW,M @@，返回 target 文件上每个 hunk 的 [起始行, 结束行]（1-based，inclusive）。 */
    static List<int[]> parseHunkRanges(String patch) {
        List<int[]> out = new ArrayList<>();
        if (patch == null) return out;
        Pattern p = Pattern.compile("^@@\\s+-\\d+(?:,\\d+)?\\s+\\+(\\d+)(?:,(\\d+))?\\s+@@");
        for (String line : patch.split("\\r?\\n", -1)) {
            Matcher m = p.matcher(line);
            if (m.find()) {
                int start = Integer.parseInt(m.group(1));
                int count = m.group(2) != null ? Integer.parseInt(m.group(2)) : 1;
                int end = start + Math.max(1, count) - 1;
                out.add(new int[]{start, end});
            }
        }
        return out;
    }

    // ---- shared helpers ----

    private void appendContexts(StringBuilder sb, List<CompareContext> contexts) {
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
    }

    private String buildUserBase(String filePath, String baselineBranch, String targetBranch,
                                 String baseline, String target,
                                 List<CompareFinding> ruleFindings) {
        StringBuilder sb = new StringBuilder();
        sb.append("文件: ").append(filePath).append("\n\n");
        sb.append("基准分支 (").append(baselineBranch).append(") 版本：\n");
        sb.append("```\n").append(truncate(baseline, MAX_SNIPPET_CHARS)).append("\n```\n\n");
        sb.append("目标分支 (").append(targetBranch).append(") 版本：\n");
        sb.append("```\n").append(truncate(target, MAX_SNIPPET_CHARS)).append("\n```\n\n");
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

    private String truncate(String s, int max) {
        if (s == null) return "(文件不存在)";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "\n... [截断，原始 " + s.length() + " 字符]";
    }

    private String safeTitle(String t) {
        return t == null ? "" : t.trim();
    }
}
