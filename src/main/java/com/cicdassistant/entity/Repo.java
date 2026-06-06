package com.cicdassistant.entity;

import lombok.Data;

@Data
public class Repo {
    private Long id;
    private String name;
    private String gitUrl;
    private String gitlabHost;
    private String projectPath;
    private String authType;
    private String accessToken;
    private String username;
    private String password;
    private String branchPrefix;
    private String swaggerPaths;
    private String actuatorPath;
    private String jvmArgs;
    private String springProfile;
    private String createdAt;
    private String updatedAt;
}
