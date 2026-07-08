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

    private String publicHost = "";
    /** 侧栏底部"意见反馈"链接。一般指向在线文档或表单。留空走 GitHub Issues。 */
    private String feedbackUrl = "";
    private Paths paths = new Paths();
    private Auth auth = new Auth();
    private Workspace workspace = new Workspace();
    private Maven maven = new Maven();
    private PortPool portPool = new PortPool();
    private Task task = new Task();
    private HealthCheck healthCheck = new HealthCheck();
    private GitLab gitlab = new GitLab();
    private Compare compare = new Compare();

    @Data
    public static class Compare {
        private String workspaceRoot = "./workspace-compare";
        private int mrFetchDefaultLimit = 20;
        private int asyncPoolSize = 2;
        /**
         * "今天"这个概念的时区参考。默认 Asia/Shanghai（本项目目标用户）。
         * 服务器可能跑在 UTC 上，用户想看的是自己本地时区的"今天"，两者不一致会漏 MR。
         * 有效值：任意 java.time.ZoneId 支持的标识符（如 Asia/Shanghai / UTC / Europe/Berlin）。
         */
        private String timezone = "Asia/Shanghai";
        private Llm llm = new Llm();
        private Notify notify = new Notify();
    }

    @Data
    public static class Llm {
        private boolean enabled = false;
        private String baseUrl = "";
        private String apiKey = "";
        private String model = "qwen2.5-coder-32b-instruct";
        private int timeoutSeconds = 120;
        private int maxTokens = 8192;
    }

    @Data
    public static class Notify {
        private boolean dingtalkEnabled = true;
        private int messageMaxChars = 4500;
    }

    @Data
    public static class Paths {
        private String dataDir = "./data";
        private String logDir = "./logs";
        private String buildLogDir = "./build-logs";
    }

    @Data
    public static class Auth {
        private List<User> users = new ArrayList<>();
        private String secretKey = "CicdAssistant2026";
    }

    @Data
    public static class User {
        private String username;
        private String password;
    }

    @Data
    public static class Workspace {
        private String root = "./workspace";
        /**
         * 启动成功的模块自动 STOP 前的保活时间。默认 3 分钟。
         * 时间到 KeepAliveSweeper 会 killTree + release port，端口被后来者复用。
         * 宿主内存吃紧时可以调更短（1~2 分钟）；如果用户操作节奏慢想多留一会儿可以拉长。
         */
        private int keepAliveMinutes = 3;
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
        private int buildTimeoutSeconds = 1800;
        /**
         * 同一任务内同一分支的模块并发启动数。默认 3。
         * 各模块的 mvn build 仍串行（同一分支同一 workspace），只把最后一步 java -jar 起进程并发化。
         * 宿主机弱可以降到 1（退化到串行）；机器猛可以拉到 5~8，注意 -Xmx 也要跟上。
         */
        private int launchConcurrency = 3;
        /**
         * 启动子进程默认追加的 JVM 参数，放在用户 {@code repo.jvmArgs} 前面。
         * -Xmx / -Xms 这类"后写的覆盖前写的"，用户配了自己的会盖住默认。
         * -XX:+ExitOnOutOfMemoryError 让 OOM 时子进程直接退出而不是残喘半死，方便点重试。
         */
        private String defaultJvmArgs = "-Xmx512m -Xms128m -XX:+ExitOnOutOfMemoryError";
    }

    @Data
    public static class HealthCheck {
        private String actuatorPath = "/actuator/health";
        private List<String> swaggerPaths = new ArrayList<>();
        private int probeIntervalMs = 2000;
        /**
         * 判定服务启动完成的正则。
         * 默认匹配 Spring Boot 标准日志 "Started XxxApplication in 12.34 seconds"，
         * 也兼容自定义 main 类名（不一定以 Application 结尾），\S+ 即可。
         */
        private String startedPattern = "Started\\s+\\S+\\s+in\\s+[\\d.]+\\s+seconds";
        /**
         * 从日志抓真实端口的正则，必须有一个捕获组返回端口数字。
         * 默认同时识别 Tomcat / Undertow / Jetty / Netty / WebServer：
         *   Tomcat started on port(s): 8080
         *   Undertow started on port(s) 8080 (http)
         *   Jetty started on port 8080
         *   Netty started on port 8080
         */
        private String portPattern = "(?:Tomcat|Undertow|Jetty|Netty|WebServer)\\s+started\\s+on\\s+port(?:\\(s\\))?[:\\s]+(\\d+)";
    }

    @Data
    public static class GitLab {
        private String defaultBranchPrefix = "";
    }
}
