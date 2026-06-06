package com.cicdassistant.util;

import com.cicdassistant.config.AppProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.net.ServerSocket;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PortPool {

    private final AppProperties appProperties;
    private final ConcurrentHashMap<Integer, Boolean> reserved = new ConcurrentHashMap<>();
    private int start;
    private int end;

    public PortPool(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @PostConstruct
    public void init() {
        this.start = appProperties.getPortPool().getStart();
        this.end = appProperties.getPortPool().getEnd();
    }

    public synchronized Integer acquire() {
        for (int p = start; p <= end; p++) {
            if (reserved.containsKey(p)) continue;
            if (isFree(p)) {
                reserved.put(p, Boolean.TRUE);
                return p;
            }
        }
        return null;
    }

    public void release(Integer port) {
        if (port == null) return;
        reserved.remove(port);
    }

    private boolean isFree(int port) {
        try (ServerSocket s = new ServerSocket(port)) {
            s.setReuseAddress(true);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
