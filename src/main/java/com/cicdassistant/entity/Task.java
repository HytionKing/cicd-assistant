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
    /** 启动成功后是否保活。false → SUCCESS 立刻 stop 释放端口，用于快速走一遍启动+swagger 验证。 */
    private Boolean keepAlive = Boolean.TRUE;
}
