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
    /** 该仓库已知的 SpringBoot 模块清单，逗号分隔。留空时启动页提示用户走"自动扫描" */
    private String modules;
    private String createdAt;
    private String updatedAt;
}
