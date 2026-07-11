package com.cicdassistant.service;

import com.cicdassistant.entity.TaskModule;
import com.cicdassistant.mapper.TaskModuleMapper;
import com.cicdassistant.util.PortPool;
import com.cicdassistant.util.ProcessManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
public class KeepAliveSweeper {

    private final TaskModuleMapper taskModuleMapper;
    private final PortPool portPool;

    public KeepAliveSweeper(TaskModuleMapper taskModuleMapper, PortPool portPool) {
        this.taskModuleMapper = taskModuleMapper;
        this.portPool = portPool;
    }

    // 用 SpEL 从 AppProperties 拿间隔（秒），乘 1000 转毫秒；默认 5s（保活支持秒级精度）。
    // 老逻辑固定 30s 太粗，keepAliveSeconds=30 会漂到接近 60s。
    @Scheduled(fixedDelayString =
            "#{@appProperties.workspace.keepAliveCheckIntervalSeconds * 1000L}",
            initialDelayString =
            "#{@appProperties.workspace.keepAliveCheckIntervalSeconds * 1000L}")
    public void sweep() {
        List<TaskModule> alive = taskModuleMapper.findAliveCandidates();
        LocalDateTime now = LocalDateTime.now();
        for (TaskModule m : alive) {
            if (m.getKeepAliveUntil() == null) continue;
            try {
                LocalDateTime t = LocalDateTime.parse(m.getKeepAliveUntil(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                if (t.isBefore(now)) {
                    log.info("keep-alive expired, stopping module id={} pid={}", m.getId(), m.getPid());
                    ProcessManager.killTree(m.getPid(), m.getPgid());
                    portPool.release(m.getPort());
                    m.setStatus("STOPPED");
                    m.setFinishedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                    taskModuleMapper.update(m);
                }
            } catch (Exception e) {
                log.warn("sweep parse failed: {}", e.getMessage());
            }
        }
    }
}
