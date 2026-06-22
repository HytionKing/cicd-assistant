package com.cicdassistant.controller;

import com.cicdassistant.entity.NotificationWebhook;
import com.cicdassistant.service.NotificationWebhookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/compare/webhooks")
public class NotificationWebhookController {

    private final NotificationWebhookService service;

    public NotificationWebhookController(NotificationWebhookService service) {
        this.service = service;
    }

    @GetMapping
    public List<NotificationWebhook> list() { return service.listAll(); }

    @GetMapping("/{id}")
    public NotificationWebhook get(@PathVariable Long id) { return service.get(id); }

    @PostMapping
    public NotificationWebhook create(@RequestBody NotificationWebhook w) { return service.create(w); }

    @PutMapping("/{id}")
    public NotificationWebhook update(@PathVariable Long id, @RequestBody NotificationWebhook w) {
        w.setId(id);
        return service.update(w);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable Long id) {
        NotificationWebhookService.TestResult r = service.sendTest(id);
        Map<String, Object> m = new HashMap<>();
        m.put("success", r.isSuccess());
        m.put("message", r.getMessage());
        return m;
    }
}
