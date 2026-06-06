package com.cicdassistant.entity;

import lombok.Data;

@Data
public class Task {
    private Long id;
    private Long repoId;
    private String repoName;
    private String branches;
    private String modules;
    private String status;
    private String errorMessage;
    private String createdAt;
    private String startedAt;
    private String finishedAt;
}
