package com.cicdassistant.entity;

import lombok.Data;

@Data
public class NotificationWebhook {
    private Long id;
    private String name;
    private String url;
    /** 钉钉机器人加签 secret，可选（机器人安全设置勾"加签"时填）。空 = 不签名直发。 */
    private String secret;
    private Integer enabled;
    private String createdAt;
    private String updatedAt;
}
