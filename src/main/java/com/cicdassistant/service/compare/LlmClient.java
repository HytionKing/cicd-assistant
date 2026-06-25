package com.cicdassistant.service.compare;

import com.cicdassistant.config.AppProperties;
import com.cicdassistant.entity.CompareFinding;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenAI 兼容 chat completions 客户端。
 *
 * <p>选 JDK 原生 HttpURLConnection 不引第三方库，保持依赖清单干净；项目其它地方也是这个套路。
 * 响应 format 强制 json_object，由 prompt 约束模型只返回 {"findings":[...]}。</p>
 */
@Slf4j
@Component
public class LlmClient {

    private final AppProperties appProperties;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmClient(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public boolean isReady() {
        AppProperties.Llm llm = appProperties.getCompare().getLlm();
        return llm.isEnabled() && llm.getBaseUrl() != null && !llm.getBaseUrl().trim().isEmpty();
    }

    /**
     * 调一次模型，返回多条 finding（已填好 detector=LLM、severity、summary、llmComment、createdAt、filePath）。
     * targetId / mrIid 由调用方再补。
     *
     * <p>错误处理原则：本方法吞掉 IO/解析异常，把失败也当成一条 WARN finding 返回，
     * 让用户在结果页能看到 LLM 出了什么问题，而不是整个目标分支直接 FAILED。</p>
     */
    public List<CompareFinding> evaluate(String filePath, String systemPrompt, String userPrompt) {
        List<CompareFinding> out = new ArrayList<>();
        AppProperties.Llm cfg = appProperties.getCompare().getLlm();

        String raw;
        try {
            raw = callChatCompletion(cfg, systemPrompt, userPrompt);
        } catch (Exception e) {
            log.warn("[LLM] call failed file={} err={}", filePath, e.getMessage());
            out.add(errorFinding(filePath, "LLM_CALL_FAILED",
                    "调用模型失败: " + e.getMessage(), null));
            return out;
        }

        String content = extractMessageContent(raw);
        if (content == null) {
            out.add(errorFinding(filePath, "LLM_PARSE_FAILED",
                    "模型响应缺少 choices[0].message.content", truncateForComment(raw)));
            return out;
        }

        // content 应该是一个 JSON 对象 {"findings":[{severity,summary,comment}]}
        // 容错：剥掉可能存在的 ```json ... ``` 包裹
        String json = stripCodeFence(content);
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode findings = root.has("findings") ? root.get("findings") : root;
            if (findings.isArray()) {
                for (JsonNode n : findings) {
                    String sev = normalizeSeverity(text(n, "severity"));
                    String summary = text(n, "summary");
                    String comment = text(n, "comment");
                    if ((summary == null || summary.isEmpty()) && (comment == null || comment.isEmpty())) continue;
                    out.add(makeFinding(filePath, "LLM_FINDING", sev,
                            summary == null ? "AI 标记的风险点" : summary,
                            comment));
                }
            }
        } catch (Exception e) {
            log.warn("[LLM] parse failed file={} err={} raw={}", filePath, e.getMessage(),
                    truncateForLog(content));
            out.add(errorFinding(filePath, "LLM_PARSE_FAILED",
                    "模型返回 JSON 解析失败: " + e.getMessage(), truncateForComment(content)));
        }
        return out;
    }

    private String callChatCompletion(AppProperties.Llm cfg, String systemPrompt, String userPrompt) throws Exception {
        String base = cfg.getBaseUrl().trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        URL url = new URL(base + "/v1/chat/completions");

        ObjectNode body = mapper.createObjectNode();
        body.put("model", cfg.getModel());
        body.put("temperature", 0.1);
        body.put("max_tokens", cfg.getMaxTokens());
        // 兼容的 OpenAI server 一般都支持；不支持的也会忽略
        ObjectNode rf = body.putObject("response_format");
        rf.put("type", "json_object");
        ArrayNode messages = body.putArray("messages");
        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content", systemPrompt);
        ObjectNode usr = messages.addObject();
        usr.put("role", "user");
        usr.put("content", userPrompt);

        byte[] payload = mapper.writeValueAsBytes(body);

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(Math.max(10, cfg.getTimeoutSeconds()) * 1000);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "application/json");
        if (cfg.getApiKey() != null && !cfg.getApiKey().isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + cfg.getApiKey());
        }
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload);
        }

        int code = conn.getResponseCode();
        InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String resp = readAll(is);
        if (code < 200 || code >= 300) {
            throw new RuntimeException("HTTP " + code + ": " + truncateForLog(resp));
        }
        return resp;
    }

    private String extractMessageContent(String raw) {
        try {
            JsonNode root = mapper.readTree(raw);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.size() == 0) return null;
            JsonNode choice = choices.get(0);
            JsonNode msg = choice.get("message");
            if (msg == null) return null;
            JsonNode content = msg.get("content");
            String text = content == null ? null : content.asText();
            if (text == null) return null;
            String finishReason = choice.hasNonNull("finish_reason") ? choice.get("finish_reason").asText("") : "";
            if ("length".equals(finishReason)) {
                // 输出被 max_tokens 截断了。一字未动地丢给 Jackson 必然抛 "expected close marker"，
                // 这是我们看到的真实 bug。尝试用括号配平补全 JSON，让大部分已生成的 finding 仍可用。
                log.warn("[LLM] response truncated (finish_reason=length, max-tokens 太小?). 尝试补齐 JSON.");
                String salvaged = salvageTruncatedJson(text);
                if (salvaged != null) return salvaged;
            }
            return text;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 给被 max_tokens 截断的 JSON 补尾。
     * <p>策略：找到最后一个完整的 '}'，把它之后被切断的半截扔掉；然后扫一遍前缀里没闭合的
     * '{'/'[' 数量，按缺多少补多少。已完整的对象都能保留，只是丢掉残缺的最后一项。</p>
     */
    static String salvageTruncatedJson(String text) {
        if (text == null) return null;
        String stripped = stripCodeFence(text);
        if (stripped == null || stripped.isEmpty()) return "{\"findings\":[]}";
        int lastClose = stripped.lastIndexOf('}');
        if (lastClose < 0) {
            // 还没生成出第一个完整对象就截了
            return "{\"findings\":[]}";
        }
        String head = stripped.substring(0, lastClose + 1);
        int braces = 0, brackets = 0;
        boolean inString = false, escape = false;
        for (int i = 0; i < head.length(); i++) {
            char c = head.charAt(i);
            if (escape) { escape = false; continue; }
            if (inString) {
                if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{') braces++;
            else if (c == '}') braces--;
            else if (c == '[') brackets++;
            else if (c == ']') brackets--;
        }
        StringBuilder sb = new StringBuilder(head);
        for (int i = 0; i < brackets; i++) sb.append(']');
        for (int i = 0; i < braces; i++) sb.append('}');
        return sb.toString();
    }

    private static String stripCodeFence(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            if (firstNl > 0) t = t.substring(firstNl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t.trim();
    }

    private static String text(JsonNode n, String field) {
        if (n == null || !n.hasNonNull(field)) return null;
        return n.get(field).asText();
    }

    private static String normalizeSeverity(String s) {
        if (s == null) return "INFO";
        String u = s.trim().toUpperCase();
        if ("ERROR".equals(u) || "WARN".equals(u) || "INFO".equals(u)) return u;
        if ("WARNING".equals(u)) return "WARN";
        if ("CRITICAL".equals(u) || "HIGH".equals(u)) return "ERROR";
        return "INFO";
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static String truncateForLog(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }

    private static String truncateForComment(String s) {
        if (s == null) return null;
        return s.length() > 3500 ? s.substring(0, 3500) + "\n... [截断]" : s;
    }

    private static CompareFinding makeFinding(String path, String type, String severity,
                                              String summary, String comment) {
        CompareFinding f = new CompareFinding();
        f.setFilePath(path);
        f.setDetector("LLM");
        f.setType(type);
        f.setSeverity(severity);
        f.setSummary(summary);
        f.setLlmComment(comment);
        f.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return f;
    }

    private static CompareFinding errorFinding(String path, String type, String summary, String rawDump) {
        return makeFinding(path, type, "WARN", summary, rawDump);
    }

    // ---- 后处理：从 AI comment 里抽行号引用，到对应文件抽片段写回 finding ----

    /**
     * AI 评语里常出现"目标分支第 234~237 行已包含..."，detail 弹窗只看到一行文字定位起来很麻烦。
     * 这里把这些行号引用提取出来，回到 source/target 文件抽真实代码片段，分别填到
     * baselineSnippet（源分支片段）/ targetSnippet（目标分支片段）上，让前端能直接展示。
     *
     * <p>已有 snippet 的不覆盖（patch verifier 自己产的 finding 走 RULE_PATCH detector，
     * 不会经过这里；但是为防万一加保护）。</p>
     */
    public static void populateCitedSnippet(CompareFinding f,
                                            String sourceContent, String targetContent) {
        if (f == null || !"LLM".equals(f.getDetector())) return;
        String comment = f.getLlmComment();
        if (comment == null || comment.isEmpty()) return;

        if (f.getTargetSnippet() == null || f.getTargetSnippet().isEmpty()) {
            String tgtSnip = extractCitedLines(comment, targetContent, /*forSide=*/"目标");
            if (tgtSnip != null) f.setTargetSnippet(tgtSnip);
        }
        if (f.getBaselineSnippet() == null || f.getBaselineSnippet().isEmpty()) {
            String srcSnip = extractCitedLines(comment, sourceContent, /*forSide=*/"源");
            if (srcSnip != null) f.setBaselineSnippet(srcSnip);
        }
    }

    // 同时匹配"第 234~237 行"/"第 234-237 行"/"第 234～237 行"/"第 234 至 237 行"等
    private static final Pattern RANGE_PAT = Pattern.compile("第\\s*(\\d+)\\s*[~～\\-–至到]\\s*(\\d+)\\s*行");
    // 单行："第 234 行"，可在范围模式抓完之后捡漏（已被范围覆盖的会被去重）
    private static final Pattern SINGLE_PAT = Pattern.compile("第\\s*(\\d+)\\s*行");

    /**
     * 在 comment 中"靠近 {目标|源}分支"那几句话里找行号，去对应内容里抽行（±2 行上下文）。
     * 用"side 关键字"过滤是因为 comment 可能同时引用源/目标两侧，避免串味儿。
     * <p>找不到任何行号引用或 content 为空就返回 null。</p>
     */
    static String extractCitedLines(String comment, String content, String forSide) {
        if (comment == null || content == null || content.isEmpty()) return null;
        List<int[]> ranges = parseSideAnchoredRanges(comment, forSide);
        if (ranges.isEmpty()) return null;

        String[] lines = content.split("\\r?\\n", -1);
        int total = lines.length;
        if (total == 0) return null;

        // 范围 + ±2 行上下文，合并重叠
        int CONTEXT = 2;
        List<int[]> windows = new ArrayList<>();
        for (int[] r : ranges) {
            int from = Math.max(1, r[0] - CONTEXT);
            int to = Math.min(total, r[1] + CONTEXT);
            if (to < from) continue;
            windows.add(new int[]{from, to});
        }
        if (windows.isEmpty()) return null;
        windows.sort(Comparator.comparingInt(a -> a[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] w : windows) {
            if (!merged.isEmpty()) {
                int[] last = merged.get(merged.size() - 1);
                if (w[0] <= last[1] + 1) { last[1] = Math.max(last[1], w[1]); continue; }
            }
            merged.add(new int[]{w[0], w[1]});
        }

        int width = String.valueOf(total).length();
        StringBuilder sb = new StringBuilder();
        int lastEnd = 0;
        for (int[] w : merged) {
            if (lastEnd > 0 && w[0] > lastEnd + 1) {
                sb.append("... [省略 ").append(w[0] - lastEnd - 1).append(" 行] ...\n");
            }
            for (int ln = w[0]; ln <= w[1]; ln++) {
                sb.append(String.format("%" + width + "d| %s%n", ln, lines[ln - 1]));
            }
            lastEnd = w[1];
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * 取 comment 中 "{forSide}分支..." 紧随其后的若干行号引用。
     * 简化策略：把 comment 按 "{forSide}分支" 切分，对每段后续 60 字符做行号正则匹配。
     * 这样 "目标分支第 234 行" 和 "源分支第 88 行" 不会互相串。
     */
    static List<int[]> parseSideAnchoredRanges(String comment, String forSide) {
        Set<int[]> dedup = new LinkedHashSet<>();
        if (comment == null || forSide == null) return new ArrayList<>(dedup);
        String anchor = forSide + "分支";
        int idx = 0;
        while ((idx = comment.indexOf(anchor, idx)) >= 0) {
            int from = idx + anchor.length();
            int to = Math.min(comment.length(), from + 80);
            // 滑窗里若再次出现 "分支" 关键字（说明跨到了另一侧），就在那里截止，
            // 防止 "目标分支第 235 行...而源分支第 100 行" 这种把 100 也算到目标侧
            int nextBranch = comment.indexOf("分支", from);
            if (nextBranch >= 0 && nextBranch < to) to = nextBranch;
            String slice = comment.substring(from, to);
            // 范围
            Matcher m1 = RANGE_PAT.matcher(slice);
            while (m1.find()) {
                try {
                    int a = Integer.parseInt(m1.group(1));
                    int b = Integer.parseInt(m1.group(2));
                    if (a > 0 && b >= a) addUnique(dedup, new int[]{a, b});
                } catch (NumberFormatException ignored) {}
            }
            // 单行（去重）
            Matcher m2 = SINGLE_PAT.matcher(slice);
            while (m2.find()) {
                try {
                    int n = Integer.parseInt(m2.group(1));
                    if (n > 0 && !inAny(dedup, n)) addUnique(dedup, new int[]{n, n});
                } catch (NumberFormatException ignored) {}
            }
            idx = to;
        }
        return new ArrayList<>(dedup);
    }

    private static boolean inAny(Set<int[]> ranges, int n) {
        for (int[] r : ranges) if (n >= r[0] && n <= r[1]) return true;
        return false;
    }

    private static void addUnique(Set<int[]> set, int[] r) {
        for (int[] x : set) if (x[0] == r[0] && x[1] == r[1]) return;
        set.add(r);
    }
}
