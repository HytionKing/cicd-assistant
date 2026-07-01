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
    /** 本次拉到的 git commit sha（40 位全量）。让用户一眼确认拉的是不是最新提交。 */
    private String commitSha;
    /** 本次拉到的 commit 首行 message + 作者 + 相对时间，纯展示用。 */
    private String commitInfo;
}
