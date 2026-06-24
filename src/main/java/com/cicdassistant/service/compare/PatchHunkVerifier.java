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
    /** 单条 finding snippet 最多字符数。 */
    private static final int MAX_SNIPPET_CHARS = 6000;
    /**
     * 邻居"附近"的容忍半径：target 中 self 命中位置上下各看 N 行找 prev/next。
     * 允许 target 在 MR 的相邻行间插入若干无关行（最常见的：env 又合了别的 MR，
     * 在两个本 MR 新增的方法之间夹了第三方方法），不至于因此整段误报。
     */
    private static final int CONTEXT_WINDOW = 3;

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
                    truncate(mrPatch, 1500)));
            return out;
        }

        String ext = extOf(filePath);
        List<String> targetLines = essentialNormalizedLines(targetContent, ext);

        int totalEssAdds = 0, totalMissAdds = 0;
        int totalEssDels = 0, totalLingerDels = 0;
        List<HunkFlags> missingHunks = new ArrayList<>();
        List<HunkFlags> lingeringHunks = new ArrayList<>();

        for (Hunk h : parsed.hunks) {
            // 先统计 essential add/del 总数，用于摘要里的 "X/Y"
            for (int i = 0; i < h.lines.size(); i++) {
                String l = h.lines.get(i);
                if (l.isEmpty()) continue;
                char c = l.charAt(0);
                String body = l.substring(1);
                if (!isEssential(body, ext)) continue;
                String norm = normalizeLine(body);
                if (norm.length() < MIN_MATCH_LEN) continue;
                if (c == '+') totalEssAdds++;
                else if (c == '-') totalEssDels++;
            }

            boolean[] addFlag = new boolean[h.lines.size()];
            boolean[] delFlag = new boolean[h.lines.size()];
            boolean anyMiss = false, anyLinger = false;

            // 逐行判定。每条 +/- 行用一个 3 行小窗口（prev + self + next）去 target 上找连续匹配。
            // 这样不要求整段 MR 内容一次性 contiguous，避免"target 在 MR 加的方法之间插了别的代码"这种
            // 完全合理的情况被全量误报。
            for (int i = 0; i < h.lines.size(); i++) {
                String l = h.lines.get(i);
                if (l.isEmpty()) continue;
                char c = l.charAt(0);
                if (c != '+' && c != '-') continue;
                String body = l.substring(1);
                if (!isEssential(body, ext)) continue;
                String self = normalizeLine(body);
                if (self.length() < MIN_MATCH_LEN) continue;

                String prev = neighborInView(h, i, -1, c, ext);
                String next = neighborInView(h, i, +1, c, ext);
                // 完全没有邻居 → 等价于"target 里有没有这一行"，对通用短句风险太大。保守跳过。
                if (prev == null && next == null) continue;

                boolean presentInTarget = matchSelfWithNearbyNeighbor(targetLines, self, prev, next);
                if (c == '+' && !presentInTarget) {
                    addFlag[i] = true;
                    totalMissAdds++;
                    anyMiss = true;
                } else if (c == '-' && presentInTarget) {
                    delFlag[i] = true;
                    totalLingerDels++;
                    anyLinger = true;
                }
            }

            if (anyMiss) missingHunks.add(new HunkFlags(h, addFlag, null));
            if (anyLinger) lingeringHunks.add(new HunkFlags(h, null, delFlag));
        }

        // 新文件场景：patch 全是 + 行，target 还根本没有该文件 —— 所有 + 行都标记
        if (totalEssDels == 0 && totalEssAdds > 0 && (targetContent == null || targetContent.isEmpty())) {
            List<HunkFlags> allFlagged = new ArrayList<>();
            for (Hunk h : parsed.hunks) {
                boolean[] addFlag = new boolean[h.lines.size()];
                for (int i = 0; i < h.lines.size(); i++) {
                    String l = h.lines.get(i);
                    if (!l.isEmpty() && l.charAt(0) == '+') addFlag[i] = true;
                }
                allFlagged.add(new HunkFlags(h, addFlag, null));
            }
            out.add(make(filePath, mrIid, "MR_FILE_MISSING", "ERROR",
                    "MR " + iidStr(mrIid) + " 新增的文件 " + filePath + " 在目标分支不存在",
                    formatHunksWithFlags(allFlagged)));
            return out;
        }

        if (!missingHunks.isEmpty()) {
            String severity = severityForMissing(totalEssAdds, totalMissAdds);
            out.add(make(filePath, mrIid, "MR_LINE_MISSING", severity,
                    String.format("MR %s 引入的 %d 行改动在目标分支未生效（共 %d 行实质改动；高亮 ⚠ 标注未命中行）",
                            iidStr(mrIid), totalMissAdds, totalEssAdds),
                    formatHunksWithFlags(missingHunks)));
        }
        if (!lingeringHunks.isEmpty()) {
            out.add(make(filePath, mrIid, "MR_LINE_LINGERING", "INFO",
                    String.format("MR %s 已删除的 %d 行在目标分支仍存在（疑似漏删；高亮 ⚠ 标注残留行）",
                            iidStr(mrIid), totalLingerDels),
                    formatHunksWithFlags(lingeringHunks)));
        }
        return out;
    }

    // ---- per-line match helpers ----

    /**
     * 把 target 文件折成"essential 规范化行列表"，过滤掉空行 / 单符号 / 注释等噪音。
     */
    private static List<String> essentialNormalizedLines(String content, String ext) {
        if (content == null) return java.util.Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String l : content.split("\\r?\\n", -1)) {
            if (!isEssential(l, ext)) continue;
            String norm = normalizeLine(l);
            if (norm.isEmpty()) continue;
            out.add(norm);
        }
        return out;
    }

    /**
     * 找 hunk 内某一行的"同侧视图"上最近的 essential 邻居。
     * <ul>
     *   <li>type = '+': 跳过 '-' 行（它们已删，不在 post-MR 文件里）；保留 '+'/' ' 行</li>
     *   <li>type = '-': 跳过 '+' 行（它们尚未在 pre-MR 文件里）；保留 '-'/' ' 行</li>
     * </ul>
     * direction = -1 往前找，+1 往后找。
     */
    private static String neighborInView(Hunk h, int from, int direction, char type, String ext) {
        int step = direction > 0 ? 1 : -1;
        for (int i = from + step; i >= 0 && i < h.lines.size(); i += step) {
            String l = h.lines.get(i);
            if (l.isEmpty()) continue;
            char c = l.charAt(0);
            if (c != '+' && c != '-' && c != ' ') continue;
            if (type == '+' && c == '-') continue;
            if (type == '-' && c == '+') continue;
            String body = l.substring(1);
            if (!isEssential(body, ext)) continue;
            String norm = normalizeLine(body);
            if (norm.length() < MIN_MATCH_LEN) continue;
            return norm;
        }
        return null;
    }

    /**
     * 判定"self 这一行是否在 target 里以原本上下文存在"。
     *
     * <p>规则：在 target essential-line 序列里找所有命中 self 的位置 k；只要任一命中位置上
     * prev 出现在 target[k-CONTEXT_WINDOW .. k-1] 之中，或 next 出现在 target[k+1 .. k+CONTEXT_WINDOW]
     * 之中，就认定 self 在原始上下文里出现过。</p>
     *
     * <p>±N 而不是严格紧邻：env 可能在 MR 改动行之间插入了来自别的 MR 的代码（再常见不过的场景），
     * 不能因此把 self 判为缺失。N=3 容忍 2 行无关插入。</p>
     */
    private static boolean matchSelfWithNearbyNeighbor(List<String> target, String self,
                                                       String prev, String next) {
        int n = target.size();
        for (int k = 0; k < n; k++) {
            if (!target.get(k).equals(self)) continue;
            if (prev != null) {
                int from = Math.max(0, k - CONTEXT_WINDOW);
                for (int j = from; j < k; j++) {
                    if (target.get(j).equals(prev)) return true;
                }
            }
            if (next != null) {
                int to = Math.min(n - 1, k + CONTEXT_WINDOW);
                for (int j = k + 1; j <= to; j++) {
                    if (target.get(j).equals(next)) return true;
                }
            }
        }
        return false;
    }

    private static String severityForMissing(int total, int missing) {
        if (total == 0) return "INFO";
        if (missing == total) return "ERROR";
        // 超过 50% 缺失算 ERROR，否则 WARN —— 少量未命中可能只是格式化/换行差异
        return missing * 2 >= total ? "ERROR" : "WARN";
    }

    // ---- patch parsing ----

    /**
     * 解析成 hunk 列表，每个 hunk 保留它的 @@ header 和原始 +/-/<space> 行。
     * 保留这些是为了 finding snippet 能完整渲染上下文：
     *  - hunk header 末尾通常含包围方法签名（git diff 自动加），定位代码位置
     *  - 上下文行让 reviewer 知道改动发生在哪个 SQL 标签 / 哪个 if 分支
     */
    static ParsedPatch parsePatch(String patch) {
        ParsedPatch p = new ParsedPatch();
        if (patch == null) return p;
        Hunk current = null;
        for (String raw : patch.split("\\r?\\n", -1)) {
            if (raw.startsWith("@@")) {
                current = new Hunk();
                current.header = raw;
                p.hunks.add(current);
                continue;
            }
            if (current == null) continue;
            // 多 patch 拼接的情况，遇到下一个文件头就重置
            if (raw.startsWith("diff ") || raw.startsWith("index ")
                    || raw.startsWith("--- ") || raw.startsWith("+++ ")
                    || raw.startsWith("new file") || raw.startsWith("deleted file")
                    || raw.startsWith("similarity index") || raw.startsWith("rename ")) {
                current = null;
                continue;
            }
            if (raw.isEmpty()) {
                // hunk 内的纯空白上下文行，按"空上下文"保留以维持视觉行号对齐
                current.lines.add(" ");
                continue;
            }
            char c = raw.charAt(0);
            if (c == '+' || c == '-' || c == ' ') {
                current.lines.add(raw);
            }
            // 其它前缀（如 \ "No newline at end of file"）直接丢弃
        }
        return p;
    }

    static class Hunk {
        String header = "";
        final List<String> lines = new ArrayList<>();

        List<String> addedLines() {
            List<String> r = new ArrayList<>();
            for (String l : lines) if (!l.isEmpty() && l.charAt(0) == '+') r.add(l.substring(1));
            return r;
        }

        List<String> removedLines() {
            List<String> r = new ArrayList<>();
            for (String l : lines) if (!l.isEmpty() && l.charAt(0) == '-') r.add(l.substring(1));
            return r;
        }
    }

    static class ParsedPatch {
        final List<Hunk> hunks = new ArrayList<>();
    }

    /**
     * 把若干 hunk 拼回 snippet 文本，每个 +/-/context 行用 2 字符前缀编码：
     * <pre>
     *   [类型][标记]<原始内容>
     *   类型 ∈ { '+', '-', ' ' }
     *   标记 ∈ { '!', ' ' }    '!' 表示这一行就是触发 finding 的行（未生效 / 漏删）
     * </pre>
     * @@ header 行原样保留（不加前缀）。前端按这套规则解析并对 '!' 行高亮。
     */
    private static String formatHunksWithFlags(List<HunkFlags> hunks) {
        StringBuilder sb = new StringBuilder();
        for (HunkFlags hf : hunks) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(hf.hunk.header).append('\n');
            for (int i = 0; i < hf.hunk.lines.size(); i++) {
                String l = hf.hunk.lines.get(i);
                char type, flag = ' ';
                String body;
                if (l.isEmpty()) {
                    type = ' '; body = "";
                } else {
                    char c = l.charAt(0);
                    if (c == '+' || c == '-' || c == ' ') {
                        type = c;
                        body = l.substring(1);
                    } else {
                        type = ' ';
                        body = l;
                    }
                }
                if (type == '+' && hf.addFlag != null && hf.addFlag[i]) flag = '!';
                if (type == '-' && hf.delFlag != null && hf.delFlag[i]) flag = '!';
                sb.append(type).append(flag).append(body).append('\n');
                if (sb.length() > MAX_SNIPPET_CHARS) {
                    sb.append("... [截断]\n");
                    return sb.toString();
                }
            }
        }
        return sb.toString();
    }

    private static class HunkFlags {
        final Hunk hunk;
        final boolean[] addFlag;
        final boolean[] delFlag;
        HunkFlags(Hunk hunk, boolean[] addFlag, boolean[] delFlag) {
            this.hunk = hunk;
            this.addFlag = addFlag;
            this.delFlag = delFlag;
        }
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

    private static String extOf(String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot < slash) return "";
        return path.substring(dot + 1).toLowerCase();
    }

    // ---- finding helpers ----

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "\n... [截断]";
    }

    private static String iidStr(Integer iid) {
        return iid == null ? "(未知)" : "!" + iid;
    }

    /**
     * patch verifier 的 finding 只用 baselineSnippet 装 unified-diff 文本，targetSnippet 留空。
     * 前端检测到 detector=RULE_PATCH 时按 patch 渲染（@@/+/-），不会再走 LCS diff 的两侧比对。
     */
    private static CompareFinding make(String path, Integer mrIid, String type, String severity,
                                       String summary, String patchSnippet) {
        CompareFinding f = new CompareFinding();
        f.setFilePath(path);
        f.setDetector("RULE_PATCH");
        f.setType(type);
        f.setSeverity(severity);
        f.setSummary(summary);
        f.setMrIid(mrIid);
        f.setBaselineSnippet(patchSnippet);
        f.setTargetSnippet(null);
        f.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return f;
    }
}
