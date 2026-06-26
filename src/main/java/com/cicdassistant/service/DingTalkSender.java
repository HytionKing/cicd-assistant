package com.cicdassistant.service;

import com.cicdassistant.entity.NotificationWebhook;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 钉钉自定义机器人推送。
 *
 * <p>支持两种安全策略：</p>
 * <ul>
 *   <li>关键词 / IP 白名单：webhook URL 直接 POST 即可，secret 留空</li>
 *   <li>加签：URL 上追加 {@code &timestamp=...&sign=...}，sign = HmacSHA256(timestamp+'\n'+secret) → Base64 → URLEncode</li>
 * </ul>
 *
 * <p>所有失败只 WARN，不抛 —— 通知是旁路功能，不能因此让主任务挂掉。</p>
 */
@Slf4j
@Service
public class DingTalkSender {

    /** 发送 markdown 消息。返回值表示 HTTP 是否成功 + 钉钉 errcode 是否 0。 */
    public Result sendMarkdown(NotificationWebhook hook, String title, String text) {
        String body = "{\"msgtype\":\"markdown\",\"markdown\":{"
                + "\"title\":\"" + escape(title) + "\","
                + "\"text\":\"" + escape(text) + "\"}}";
        return post(hook, body);
    }

    /** 发送简单 text 消息（不带格式）。"测试推送"按钮用得上。 */
    public Result sendText(NotificationWebhook hook, String text) {
        String body = "{\"msgtype\":\"text\",\"text\":{\"content\":\"" + escape(text) + "\"}}";
        return post(hook, body);
    }

    private Result post(NotificationWebhook hook, String body) {
        if (hook == null || hook.getUrl() == null || hook.getUrl().isEmpty()) {
            return Result.fail("webhook url 为空");
        }
        String url;
        try {
            url = appendSignIfNeeded(hook.getUrl(), hook.getSecret());
        } catch (Exception e) {
            log.warn("[DING] sign failed: {}", e.getMessage());
            return Result.fail("加签计算失败: " + e.getMessage());
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            String resp = readBody(conn);
            if (code < 200 || code >= 300) {
                return Result.fail("HTTP " + code + " - " + resp);
            }
            // 钉钉成功也是 HTTP 200，看 body 里的 errcode（0=成功）
            if (resp != null && resp.contains("\"errcode\":") && !resp.contains("\"errcode\":0")) {
                return Result.fail("钉钉返回错误：" + resp);
            }
            return Result.ok("HTTP 200 - " + resp);
        } catch (Exception e) {
            log.warn("[DING] post failed: {}", e.getMessage());
            return Result.fail(e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 钉钉加签算法：把 timestamp 和 sign 拼到 URL 上。secret 为空就原样返回。 */
    static String appendSignIfNeeded(String url, String secret) throws Exception {
        if (secret == null || secret.trim().isEmpty()) return url;
        long ts = System.currentTimeMillis();
        String stringToSign = ts + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        String sign = URLEncoder.encode(Base64.getEncoder().encodeToString(signData), "UTF-8");
        String sep = url.contains("?") ? "&" : "?";
        return url + sep + "timestamp=" + ts + "&sign=" + sign;
    }

    private String readBody(HttpURLConnection conn) {
        try {
            java.io.InputStream is = conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream();
            byte[] buf = new byte[4096];
            int n = is.read(buf);
            return n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** 把字符串塞进 JSON string 字面量前的转义。仅处理 markdown 报告里会出现的字符。 */
    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    public static class Result {
        private final boolean success;
        private final String message;
        private Result(boolean s, String m) { this.success = s; this.message = m; }
        public static Result ok(String m) { return new Result(true, m); }
        public static Result fail(String m) { return new Result(false, m); }
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
    }
}
