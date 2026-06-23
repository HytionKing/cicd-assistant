package com.cicdassistant.service.compare;

import com.cicdassistant.entity.CompareFinding;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MR-Patch 驱动的"上线一致性"校验器。
 *
 * <p>核心算法：</p>
 * <ol>
 *   <li>把 MR 的 unified patch 拆成 hunk，提取每个文件的"+ 行"和"- 行"</li>
 *   <li>按文件扩展名过滤掉非语义噪音（空白、单纯花括号、import、注释、纯关闭标签等）</li>
 *   <li>把行规范化（trim + 压缩中间空白）后到 target 当前文件里做子串查找</li>
 *   <li>找不到的"+ 行" → ERROR：MR 引入的改动没在 target 生效</li>
 *   <li>仍找得到的"- 行" → INFO：target 漏删了 MR 已删除的内容（低置信度）</li>
 * </ol>
 *
 * <p>对 fat 等中间分支零依赖，patch 本身就是"feat → env"的事实，不掺杂未上线代码。</p>
 */
@Slf4j
@Component
public class PatchHunkVerifier {

    /** 行规范化后短于此值的不参与匹配（容易碰巧命中）。 */
    private static final int MIN_MATCH_LEN = 8;
    /** 单条 finding snippet 最多列多少行未命中样本。 */
    private static final int SAMPLE_LIMIT = 8;

    /** 行注释 / 单符号 / 闭合标签等噪音，所有语言通用。 */
    private static final Set<String> NOISE_LITERALS = new HashSet<>(Arrays.asList(
            "{", "}", "(", ")", "[", "]", ";", ",", "/*", "*/", "*", "/**", "//",
            "<>", "()", "{}", "[]"
    ));

    public List<CompareFinding> verify(String filePath, String mrPatch, String targetContent, Integer mrIid) {
        List<CompareFinding> out = new ArrayList<>();
        if (mrPatch == null || mrPatch.isEmpty()) return out;

        ParsedPatch parsed;
        try {
            parsed = parsePatch(mrPatch);
        } catch (Exception e) {
            log.warn("[PATCH-VERIFY] parse failed file={} mr=!{} err={}", filePath, mrIid, e.getMessage());
            out.add(make(filePath, mrIid, "PATCH_PARSE_FAILED", "WARN",
                    "MR " + iidStr(mrIid) + " 的 patch 解析失败：" + e.getClass().getSimpleName(),
                    truncate(mrPatch, 1500), null));
            return out;
        }

        String ext = extOf(filePath);
        List<String> essAdds = new ArrayList<>();
        List<String> essDels = new ArrayList<>();
        for (String line : parsed.added) if (isEssential(line, ext)) essAdds.add(line);
        for (String line : parsed.removed) if (isEssential(line, ext)) essDels.add(line);

        // 新文件场景：MR 全是 + 行，target 还根本没有这个文件
        if (essDels.isEmpty() && !essAdds.isEmpty() && (targetContent == null || targetContent.isEmpty())) {
            out.add(make(filePath, mrIid, "MR_FILE_MISSING", "ERROR",
                    "MR " + iidStr(mrIid) + " 新增的文件 " + filePath + " 在目标分支不存在",
                    joinSample(essAdds), null));
            return out;
        }

        String normTarget = normalizeForSearch(targetContent == null ? "" : targetContent);

        // 1) + 行未在 target 出现 → 上线丢失
        List<String> missingAdds = new ArrayList<>();
        for (String line : essAdds) {
            String norm = normalizeLine(line);
            if (norm.length() < MIN_MATCH_LEN) continue;
            if (!normTarget.contains(norm)) missingAdds.add(line);
        }
        // 2) - 行仍在 target 出现 → 残留未清理
        List<String> lingeringDels = new ArrayList<>();
        for (String line : essDels) {
            String norm = normalizeLine(line);
            if (norm.length() < MIN_MATCH_LEN) continue;
            if (normTarget.contains(norm)) lingeringDels.add(line);
        }

        if (!missingAdds.isEmpty()) {
            String severity = severityForMissing(essAdds.size(), missingAdds.size());
            out.add(make(filePath, mrIid, "MR_LINE_MISSING", severity,
                    String.format("MR %s 引入的 %d 行改动在目标分支未生效（共 %d 行实质改动）",
                            iidStr(mrIid), missingAdds.size(), essAdds.size()),
                    joinSample(missingAdds),
                    "（target 当前文件中未匹配到上述行）"));
        }
        if (!lingeringDels.isEmpty()) {
            out.add(make(filePath, mrIid, "MR_LINE_LINGERING", "INFO",
                    String.format("MR %s 已删除的 %d 行在目标分支仍存在", iidStr(mrIid), lingeringDels.size()),
                    joinSample(lingeringDels),
                    "（target 当前文件中仍能搜到，疑似漏删）"));
        }
        return out;
    }

    private static String severityForMissing(int total, int missing) {
        if (total == 0) return "INFO";
        if (missing == total) return "ERROR";
        // 超过 50% 缺失算 ERROR，否则 WARN —— 少量未命中可能只是格式化/换行差异
        return missing * 2 >= total ? "ERROR" : "WARN";
    }

    // ---- patch parsing ----

    static ParsedPatch parsePatch(String patch) {
        ParsedPatch p = new ParsedPatch();
        if (patch == null) return p;
        boolean inHunk = false;
        for (String raw : patch.split("\\r?\\n", -1)) {
            if (raw.startsWith("@@")) { inHunk = true; continue; }
            if (!inHunk) continue;
            // diff 文件头偶尔混在 hunk 之间（多 patch 拼接的情况）—— 安全起见也跳过
            if (raw.startsWith("diff ") || raw.startsWith("index ")
                    || raw.startsWith("--- ") || raw.startsWith("+++ ")
                    || raw.startsWith("new file") || raw.startsWith("deleted file")
                    || raw.startsWith("similarity index") || raw.startsWith("rename ")) {
                inHunk = false;
                continue;
            }
            if (raw.isEmpty()) continue;
            char c = raw.charAt(0);
            String body = raw.substring(1);
            if (c == '+') p.added.add(body);
            else if (c == '-') p.removed.add(body);
            // 上下文行（' ' 开头）忽略
        }
        return p;
    }

    static class ParsedPatch {
        final List<String> added = new ArrayList<>();
        final List<String> removed = new ArrayList<>();
    }

    // ---- normalization & essential filtering ----

    private static boolean isEssential(String raw, String ext) {
        if (raw == null) return false;
        String t = raw.trim();
        if (t.isEmpty()) return false;
        if (NOISE_LITERALS.contains(t)) return false;
        // 通用单行注释
        if (t.startsWith("//")) return false;
        if (t.startsWith("/*") && t.endsWith("*/")) return false;
        if (t.startsWith("*") && (t.length() == 1 || t.charAt(1) == ' ' || t.endsWith("*/"))) return false;

        switch (ext) {
            case "java":
                if (t.startsWith("import ") && t.endsWith(";")) return false;
                if (t.startsWith("package ") && t.endsWith(";")) return false;
                break;
            case "xml":
                // 单纯关闭标签噪音大，意义低
                if (t.matches("</\\w+>")) return false;
                if (t.startsWith("<!--") && t.endsWith("-->")) return false;
                break;
            case "sql":
                if (t.startsWith("--")) return false;
                break;
            case "yml":
            case "yaml":
            case "properties":
                // 纯注释跳过
                if (t.startsWith("#")) return false;
                break;
            default:
        }
        return true;
    }

    /** 行内规范化：trim + 折叠连续空白成单空格。用于匹配判定。 */
    static String normalizeLine(String s) {
        if (s == null) return "";
        return s.trim().replaceAll("\\s+", " ");
    }

    /** 把目标文件整体折叠成连续单空格的大字符串，用于 contains 子串匹配。 */
    static String normalizeForSearch(String content) {
        if (content == null) return "";
        // 不区分行边界：MR 的某行重新折行后，target 上可能跨多行；折叠后还能匹配
        return content.replaceAll("\\s+", " ").trim();
    }

    private static String extOf(String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot < slash) return "";
        return path.substring(dot + 1).toLowerCase();
    }

    // ---- finding helpers ----

    private static String joinSample(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(SAMPLE_LIMIT, lines.size());
        for (int i = 0; i < n; i++) sb.append(lines.get(i)).append('\n');
        if (lines.size() > SAMPLE_LIMIT) sb.append("... 还有 ").append(lines.size() - SAMPLE_LIMIT).append(" 行（共 ")
                .append(lines.size()).append(" 行）\n");
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "\n... [截断]";
    }

    private static String iidStr(Integer iid) {
        return iid == null ? "(未知)" : "!" + iid;
    }

    private static CompareFinding make(String path, Integer mrIid, String type, String severity,
                                       String summary, String baselineSnippet, String targetSnippet) {
        CompareFinding f = new CompareFinding();
        f.setFilePath(path);
        f.setDetector("RULE_PATCH");
        f.setType(type);
        f.setSeverity(severity);
        f.setSummary(summary);
        f.setMrIid(mrIid);
        f.setBaselineSnippet(baselineSnippet);
        f.setTargetSnippet(targetSnippet);
        f.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return f;
    }
}
