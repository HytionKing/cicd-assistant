package com.cicdassistant.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 简易 schema 迁移器：对存量 SQLite 库做幂等 ALTER。
 * schema.sql 里 CREATE TABLE IF NOT EXISTS 只对全新库添列，老库的列要靠这里加。
 */
@Slf4j
@Component
public class SchemaMigrator implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    public SchemaMigrator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureColumn("repo", "modules", "TEXT");
        // P4：钉钉加签可选
        ensureColumn("notification_webhook", "secret", "TEXT");
        // 每次启动拉到的 commit sha + 描述，便于用户核对是不是最新代码
        ensureColumn("task_module", "commit_sha", "TEXT");
        ensureColumn("task_module", "commit_info", "TEXT");
    }

    private void ensureColumn(String table, String column, String type) {
        try {
            List<Map<String, Object>> info = jdbc.queryForList("PRAGMA table_info(" + table + ")");
            boolean exists = info.stream().anyMatch(r -> column.equalsIgnoreCase(String.valueOf(r.get("name"))));
            if (!exists) {
                jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
                log.info("[SCHEMA] added column {}.{} {}", table, column, type);
            }
        } catch (Exception e) {
            log.warn("[SCHEMA] ensure column {}.{} failed: {}", table, column, e.getMessage());
        }
    }
}
