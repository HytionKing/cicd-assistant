package com.cicdassistant.service;

import com.cicdassistant.entity.CompareContext;
import com.cicdassistant.mapper.CompareContextMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class CompareContextService {

    private final CompareContextMapper mapper;

    public CompareContextService(CompareContextMapper mapper) {
        this.mapper = mapper;
    }

    public List<CompareContext> listAll() { return mapper.findAll(); }

    public List<CompareContext> listApplicable(Long repoId) { return mapper.findApplicable(repoId); }

    public CompareContext get(Long id) { return mapper.findById(id); }

    public CompareContext create(CompareContext c) {
        if (c.getEnabled() == null) c.setEnabled(1);
        String n = now();
        c.setCreatedAt(n);
        c.setUpdatedAt(n);
        mapper.insert(c);
        return c;
    }

    public CompareContext update(CompareContext c) {
        if (c.getEnabled() == null) c.setEnabled(1);
        c.setUpdatedAt(now());
        mapper.update(c);
        return mapper.findById(c.getId());
    }

    public void delete(Long id) { mapper.deleteById(id); }

    private String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
