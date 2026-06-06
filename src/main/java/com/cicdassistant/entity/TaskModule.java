package com.cicdassistant.entity;

import lombok.Data;

@Data
public class TaskModule {
    private Long id;
    private Long taskId;
    private String branch;
    private String moduleName;
    private String modulePath;
    private String status;
    private Integer port;
    private Long pid;
    private Long pgid;
    private String logFile;
    private String buildLogFile;
    private String swaggerUrl;
    private String errorMessage;
    private String keepAliveUntil;
    private String createdAt;
    private String startedAt;
    private String finishedAt;
}
