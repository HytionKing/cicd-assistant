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
import java.util.List;

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
            JsonNode msg = choices.get(0).get("message");
            if (msg == null) return null;
            JsonNode content = msg.get("content");
            return content == null ? null : content.asText();
        } catch (Exception e) {
            return null;
        }
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
}
