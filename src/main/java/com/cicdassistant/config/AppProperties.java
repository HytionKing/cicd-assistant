package com.cicdassistant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Auth auth = new Auth();
    private Workspace workspace = new Workspace();
    private Maven maven = new Maven();
    private PortPool portPool = new PortPool();
    private Task task = new Task();
    private HealthCheck healthCheck = new HealthCheck();
    private GitLab gitlab = new GitLab();

    @Data
    public static class Auth {
        private List<User> users = new ArrayList<>();
        private String secretKey = "CodeSearcher2026";
    }

    @Data
    public static class User {
        private String username;
        private String password;
    }

    @Data
    public static class Workspace {
        private String root = "./workspace";
        private int keepAliveMinutes = 5;
        private int logRetentionDays = 7;
    }

    @Data
    public static class Maven {
        private String home = "";
        private String settingsXml = "";
        private String buildArgs = "clean package -DskipTests";
    }

    @Data
    public static class PortPool {
        private int start = 18000;
        private int end = 18999;
    }

    @Data
    public static class Task {
        private int maxConcurrent = 2;
        private int startupTimeoutSeconds = 300;
        private int buildTimeoutSeconds = 1200;
    }

    @Data
    public static class HealthCheck {
        private String actuatorPath = "/actuator/health";
        private List<String> swaggerPaths = new ArrayList<>();
        private int probeIntervalMs = 2000;
    }

    @Data
    public static class GitLab {
        private String defaultBranchPrefix = "";
    }
}
