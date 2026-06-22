package com.cicdassistant.entity;

import lombok.Data;

@Data
public class CompareTarget {
    private Long id;
    private Long taskId;
    private String targetBranch;
    private String status;
    private String errorMessage;
    private Integer errorCount;
    private Integer warnCount;
    private Integer infoCount;
    private Integer filesScanned;
    private String startedAt;
    private String finishedAt;
}
