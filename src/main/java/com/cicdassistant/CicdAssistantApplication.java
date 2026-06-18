package com.cicdassistant;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.File;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@MapperScan("com.cicdassistant.mapper")
public class CicdAssistantApplication {

    public static void main(String[] args) {
        // Hikari 在 Spring 容器初始化时就会尝试连 SQLite，需要 data 目录已经存在；
        // 自身日志文件也要在 Logback 初始化前就有目录。所以在 SpringApplication.run 之前
        // 先把目录建好。这里只能读系统属性/环境变量（Spring 还没起来），默认值必须与
        // application.yml 里的 app.paths.* 默认值一致。
        ensureDir(resolve("app.paths.data-dir", "APP_PATHS_DATA_DIR", "./data"));
        ensureDir(resolve("app.paths.log-dir", "APP_PATHS_LOG_DIR", "./logs"));
        ensureDir(resolve("app.paths.build-log-dir", "APP_PATHS_BUILD_LOG_DIR", "./build-logs"));
        ensureDir(resolve("app.workspace.root", "APP_WORKSPACE_ROOT", "./workspace"));
        SpringApplication.run(CicdAssistantApplication.class, args);
    }

    private static String resolve(String sysProp, String envVar, String def) {
        String v = System.getProperty(sysProp);
        if (v != null && !v.isEmpty()) return v;
        v = System.getenv(envVar);
        if (v != null && !v.isEmpty()) return v;
        return def;
    }

    private static void ensureDir(String path) {
        File f = new File(path);
        if (!f.exists() && !f.mkdirs()) {
            System.err.println("[WARN] failed to create directory: " + f.getAbsolutePath());
        }
    }
}
