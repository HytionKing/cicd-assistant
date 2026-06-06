package com.cicdassistant.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    private final AppProperties appProperties;

    public AsyncConfig(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        int max = appProperties.getTask().getMaxConcurrent();
        exec.setCorePoolSize(max);
        exec.setMaxPoolSize(max);
        exec.setQueueCapacity(50);
        exec.setThreadNamePrefix("cs-task-");
        exec.initialize();
        return exec;
    }
}
