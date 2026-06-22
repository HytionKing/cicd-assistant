package com.cicdassistant.entity;

import lombok.Data;

@Data
public class CompareFinding {
    private Long id;
    private Long targetId;
    private Integer mrIid;
    private String filePath;
    private String detector;          // RULE | LLM
    private String type;
    private String severity;          // ERROR | WARN | INFO
    private String summary;
    private String baselineSnippet;
    private String targetSnippet;
    private String llmComment;
    private String createdAt;
}
