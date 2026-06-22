package com.cicdassistant.entity;

import lombok.Data;

@Data
public class CompareTask {
    private Long id;
    private Long repoId;
    private String repoName;
    private String baselineBranch;
    private String targetBranches;        // CSV
    private String mrSelections;          // JSON: [{iid, targetBranch}]
    private String mode;                  // RULE | LLM | HYBRID
    private String contextIds;            // CSV of compare_context ids
    private Long webhookId;
    private String status;
    private String errorMessage;
    private Integer progressTotal;
    private Integer progressDone;
    private String progressPhase;
    private String createdAt;
    private String startedAt;
    private String finishedAt;
}
