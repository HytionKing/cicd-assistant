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
        ensureDir("./data");
        ensureDir("./logs");
        ensureDir("./workspace");
        ensureDir("./build-logs");
        SpringApplication.run(CicdAssistantApplication.class, args);
    }

    private static void ensureDir(String path) {
        File f = new File(path);
        if (!f.exists()) {
            f.mkdirs();
        }
    }
}
