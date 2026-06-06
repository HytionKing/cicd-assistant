package com.cicdassistant.service;

import com.cicdassistant.config.AppProperties;
import com.cicdassistant.entity.Repo;
import com.cicdassistant.mapper.RepoMapper;
import com.cicdassistant.util.AesUtil;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class RepoService {

    private static final String MASK = "***";

    private final RepoMapper repoMapper;
    private final AppProperties appProperties;

    public RepoService(RepoMapper repoMapper, AppProperties appProperties) {
        this.repoMapper = repoMapper;
        this.appProperties = appProperties;
    }

    public List<Repo> listMasked() {
        List<Repo> all = repoMapper.findAll();
        for (Repo r : all) mask(r);
        return all;
    }

    public Repo findByIdMasked(Long id) {
        Repo r = repoMapper.findById(id);
        if (r != null) mask(r);
        return r;
    }

    public Repo findByIdDecrypted(Long id) {
        Repo r = repoMapper.findById(id);
        if (r == null) return null;
        String key = appProperties.getAuth().getSecretKey();
        if (r.getAccessToken() != null && !r.getAccessToken().isEmpty()) {
            r.setAccessToken(AesUtil.decrypt(r.getAccessToken(), key));
        }
        if (r.getPassword() != null && !r.getPassword().isEmpty()) {
            r.setPassword(AesUtil.decrypt(r.getPassword(), key));
        }
        return r;
    }

    public Repo create(Repo r) {
        String key = appProperties.getAuth().getSecretKey();
        if (r.getAccessToken() != null && !r.getAccessToken().isEmpty()) {
            r.setAccessToken(AesUtil.encrypt(r.getAccessToken(), key));
        }
        if (r.getPassword() != null && !r.getPassword().isEmpty()) {
            r.setPassword(AesUtil.encrypt(r.getPassword(), key));
        }
        String now = now();
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        repoMapper.insert(r);
        mask(r);
        return r;
    }

    public Repo update(Repo r) {
        String key = appProperties.getAuth().getSecretKey();
        Repo existing = repoMapper.findById(r.getId());
        if (existing == null) return null;
        if (r.getAccessToken() == null || MASK.equals(r.getAccessToken())) {
            r.setAccessToken(null);
        } else if (!r.getAccessToken().isEmpty()) {
            r.setAccessToken(AesUtil.encrypt(r.getAccessToken(), key));
        }
        if (r.getPassword() == null || MASK.equals(r.getPassword())) {
            r.setPassword(null);
        } else if (!r.getPassword().isEmpty()) {
            r.setPassword(AesUtil.encrypt(r.getPassword(), key));
        }
        r.setUpdatedAt(now());
        repoMapper.update(r);
        return findByIdMasked(r.getId());
    }

    public void delete(Long id) {
        repoMapper.deleteById(id);
    }

    private void mask(Repo r) {
        if (r.getAccessToken() != null && !r.getAccessToken().isEmpty()) r.setAccessToken(MASK);
        if (r.getPassword() != null && !r.getPassword().isEmpty()) r.setPassword(MASK);
    }

    private String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
