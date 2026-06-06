package com.cicdassistant.service;

import com.cicdassistant.entity.Repo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.Branch;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class GitLabService {

    public List<String> listBranches(Repo repo) throws GitLabApiException {
        if (StringUtils.isBlank(repo.getGitlabHost()) || StringUtils.isBlank(repo.getProjectPath())) {
            return new ArrayList<>();
        }
        try (GitLabApi api = buildApi(repo)) {
            List<Branch> branches = api.getRepositoryApi().getBranches(repo.getProjectPath());
            String prefix = repo.getBranchPrefix();
            return branches.stream()
                    .map(Branch::getName)
                    .filter(n -> StringUtils.isBlank(prefix) || n.startsWith(prefix))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    public boolean testConnection(Repo repo) {
        try (GitLabApi api = buildApi(repo)) {
            api.getProjectApi().getProject(repo.getProjectPath());
            return true;
        } catch (Exception e) {
            log.warn("testConnection failed: {}", e.getMessage());
            return false;
        }
    }

    private GitLabApi buildApi(Repo repo) throws GitLabApiException {
        String host = repo.getGitlabHost();
        if ("PASSWORD".equalsIgnoreCase(repo.getAuthType())) {
            return GitLabApi.oauth2Login(host, repo.getUsername(), repo.getPassword());
        }
        return new GitLabApi(host, repo.getAccessToken());
    }
}
