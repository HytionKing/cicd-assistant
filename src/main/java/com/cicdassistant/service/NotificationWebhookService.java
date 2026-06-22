package com.cicdassistant.service;

import com.cicdassistant.entity.NotificationWebhook;
import com.cicdassistant.mapper.NotificationWebhookMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
public class NotificationWebhookService {

    private final NotificationWebhookMapper mapper;

    public NotificationWebhookService(NotificationWebhookMapper mapper) {
        this.mapper = mapper;
    }

    public List<NotificationWebhook> listAll() { return mapper.findAll(); }

    public NotificationWebhook get(Long id) { return mapper.findById(id); }

    public NotificationWebhook create(NotificationWebhook w) {
        if (w.getEnabled() == null) w.setEnabled(1);
        String n = now();
        w.setCreatedAt(n);
        w.setUpdatedAt(n);
        mapper.insert(w);
        return w;
    }

    public NotificationWebhook update(NotificationWebhook w) {
        if (w.getEnabled() == null) w.setEnabled(1);
        w.setUpdatedAt(now());
        mapper.update(w);
        return mapper.findById(w.getId());
    }

    public void delete(Long id) { mapper.deleteById(id); }

    /** 发一条钉钉测试消息，返回 (success, message) */
    public TestResult sendTest(Long id) {
        NotificationWebhook w = mapper.findById(id);
        if (w == null) return TestResult.fail("webhook 不存在");
        String body = "{\"msgtype\":\"text\",\"text\":{\"content\":\"[CICD Assistant] 测试消息：" + w.getName() + " webhook 联通正常\"}}";
        return postJson(w.getUrl(), body);
    }

    public TestResult postJson(String url, String body) {
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
            if (code >= 200 && code < 300) return TestResult.ok("HTTP " + code + " - " + resp);
            return TestResult.fail("HTTP " + code + " - " + resp);
        } catch (Exception e) {
            log.warn("post webhook failed: {}", e.getMessage());
            return TestResult.fail(e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
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

    public static class TestResult {
        private final boolean success;
        private final String message;
        private TestResult(boolean s, String m) { this.success = s; this.message = m; }
        public static TestResult ok(String m) { return new TestResult(true, m); }
        public static TestResult fail(String m) { return new TestResult(false, m); }
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
    }

    private String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
