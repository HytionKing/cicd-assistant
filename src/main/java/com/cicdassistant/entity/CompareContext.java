package com.cicdassistant.entity;

import lombok.Data;

@Data
public class CompareContext {
    private Long id;
    private Long repoId;        // null = 全局
    private String title;
    private String content;
    private Integer enabled;    // 1 / 0
    private String createdAt;
    private String updatedAt;
}
