package com.cicdassistant.service;

import com.cicdassistant.entity.Repo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.Branch;
import org.gitlab4j.api.models.Version;
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
            List<Branch> branches = api.getRepositoryApi().getBranches(normalizeProjectPath(repo.getProjectPath()));
            String prefix = repo.getBranchPrefix();
            return branches.stream()
                    .map(Branch::getName)
                    .filter(n -> StringUtils.isBlank(prefix) || n.startsWith(prefix))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    public TestResult testConnection(Repo repo) {
        if (StringUtils.isBlank(repo.getGitlabHost())) {
            return TestResult.fail("gitlabHost 未配置");
        }
        try (GitLabApi api = buildApi(repo)) {
            Version v;
            try {
                v = api.getVersion();
            } catch (GitLabApiException e) {
                return TestResult.fail(describeApiError("访问 /api/v4/version 失败", e));
            } catch (Exception e) {
                log.warn("testConnection getVersion exception", e);
                return TestResult.fail("访问 /api/v4/version 失败: " + rootMessage(e)
                        + "（请检查 gitlabHost 是否正确、是否能访问到 GitLab，且 Token 有效）");
            }

            if (StringUtils.isBlank(repo.getProjectPath())) {
                return TestResult.ok("已连接，GitLab 版本 " + (v != null ? v.getVersion() : "(unknown)")
                        + "；projectPath 未填，未验证项目");
            }
            String path = normalizeProjectPath(repo.getProjectPath());
            try {
                api.getProjectApi().getProject(path);
                return TestResult.ok("已连接，GitLab 版本 " + (v != null ? v.getVersion() : "(unknown)")
                        + "；项目 " + path + " 可访问");
            } catch (GitLabApiException e) {
                return TestResult.fail(describeApiError("已连接 GitLab，但项目 " + path + " 不可访问", e));
            }
        } catch (Exception e) {
            log.warn("testConnection failed", e);
            return TestResult.fail(rootMessage(e));
        }
    }

    private GitLabApi buildApi(Repo repo) throws GitLabApiException {
        String host = normalizeHost(repo.getGitlabHost());
        if ("PASSWORD".equalsIgnoreCase(repo.getAuthType())) {
            return GitLabApi.oauth2Login(host, repo.getUsername(), repo.getPassword());
        }
        return new GitLabApi(host, repo.getAccessToken());
    }

    private String normalizeHost(String host) {
        if (host == null) return null;
        String h = host.trim();
        while (h.endsWith("/")) h = h.substring(0, h.length() - 1);
        return h;
    }

    private String normalizeProjectPath(String path) {
        if (path == null) return null;
        String p = path.trim();
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.endsWith(".git")) p = p.substring(0, p.length() - 4);
        return p;
    }

    private String describeApiError(String prefix, GitLabApiException e) {
        StringBuilder sb = new StringBuilder(prefix);
        sb.append(": HTTP ").append(e.getHttpStatus());
        String msg = e.getMessage();
        if (StringUtils.isNotBlank(msg)) sb.append(" - ").append(msg);
        if (e.getHttpStatus() == 401) {
            sb.append("（Token 无效或已过期）");
        } else if (e.getHttpStatus() == 404) {
            sb.append("（路径不存在；projectPath 应是 group/repo 形式，区分大小写）");
        } else if (e.getHttpStatus() == 0 || e.getHttpStatus() == 200) {
            sb.append("（响应非 JSON，gitlabHost 可能指向了登录页或非 GitLab 服务）");
        }
        return sb.toString();
    }

    private String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }

    public static class TestResult {
        private final boolean success;
        private final String message;

        private TestResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
        public static TestResult ok(String msg) { return new TestResult(true, msg); }
        public static TestResult fail(String msg) { return new TestResult(false, msg); }
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
    }
}
