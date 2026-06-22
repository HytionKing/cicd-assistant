package com.cicdassistant.entity;

import lombok.Data;

@Data
public class NotificationWebhook {
    private Long id;
    private String name;
    private String url;
    private Integer enabled;
    private String createdAt;
    private String updatedAt;
}
