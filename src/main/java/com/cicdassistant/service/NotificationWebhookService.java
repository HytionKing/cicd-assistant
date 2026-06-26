package com.cicdassistant.service;

import com.cicdassistant.entity.NotificationWebhook;
import com.cicdassistant.mapper.NotificationWebhookMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
public class NotificationWebhookService {

    private final NotificationWebhookMapper mapper;
    private final DingTalkSender ding;

    public NotificationWebhookService(NotificationWebhookMapper mapper, DingTalkSender ding) {
        this.mapper = mapper;
        this.ding = ding;
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

    /** 发一条钉钉测试消息，返回 (success, message)。走 DingTalkSender 同款路径以验证加签是否正确。 */
    public TestResult sendTest(Long id) {
        NotificationWebhook w = mapper.findById(id);
        if (w == null) return TestResult.fail("webhook 不存在");
        String text = "[CICD Assistant] 测试消息：" + w.getName() + " webhook 联通正常";
        DingTalkSender.Result r = ding.sendText(w, text);
        return r.isSuccess() ? TestResult.ok(r.getMessage()) : TestResult.fail(r.getMessage());
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
