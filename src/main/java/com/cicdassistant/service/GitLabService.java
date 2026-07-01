package com.cicdassistant.service;

import com.cicdassistant.config.AppProperties;
import com.cicdassistant.entity.Repo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.Constants;
import org.gitlab4j.api.models.Branch;
import org.gitlab4j.api.models.MergeRequest;
import org.gitlab4j.api.models.MergeRequestFilter;
import org.gitlab4j.api.models.Version;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class GitLabService {

    private final AppProperties appProperties;

    public GitLabService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * 从配置里拿"今天"参照时区。非法值降级到 Asia/Shanghai 而不是 systemDefault，
     * 避免"服务器 UTC → LocalDate.now() 跨日切"导致北京用户漏 MR 的老坑。
     */
    private ZoneId resolveZone() {
        String tz = appProperties.getCompare().getTimezone();
        if (StringUtils.isBlank(tz)) return ZoneId.of("Asia/Shanghai");
        try { return ZoneId.of(tz.trim()); }
        catch (Exception e) {
            log.warn("[GITLAB] app.compare.timezone={} 无效，回退到 Asia/Shanghai", tz);
            return ZoneId.of("Asia/Shanghai");
        }
    }

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

    /**
     * 拉取某个目标分支最近 N 条已合并 MR。
     * 用于"合并检测"页面 MR 自动补全：用户选完被对比分支后调一次。
     * <p>{@code todayOnly=true} 时只取今天 00:00 以后合并的 MR，常用于"只看今天上线的"场景。</p>
     */
    public List<MergeRequest> listRecentMergedMrs(Repo repo, String targetBranch, int limit, boolean todayOnly) throws GitLabApiException {
        if (StringUtils.isBlank(repo.getGitlabHost()) || StringUtils.isBlank(repo.getProjectPath())
                || StringUtils.isBlank(targetBranch)) {
            return new ArrayList<>();
        }
        try (GitLabApi api = buildApi(repo)) {
            Object projectId = api.getProjectApi().getProject(normalizeProjectPath(repo.getProjectPath())).getId();
            MergeRequestFilter f = new MergeRequestFilter()
                    .withState(Constants.MergeRequestState.MERGED)
                    .withTargetBranch(targetBranch)
                    .withOrderBy(Constants.MergeRequestOrderBy.UPDATED_AT)
                    .withSort(Constants.SortOrder.DESC);
            // gitlab4j-api 4.19 的 projectId 字段是 Integer，先尝试 setter
            setProjectIdReflective(f, projectId);
            // GitLab MR 没有"按 mergedAt 过滤"的服务端参数；用 updated_after 把窗口拉到今天 0 点附近，
            // 服务端先粗筛，本地再按 mergedAt 严格过滤一次（updated_at 可能晚于 mergedAt，但不会早于）
            java.util.Date todayStart = null;
            if (todayOnly) {
                ZoneId zone = resolveZone();
                todayStart = java.util.Date.from(
                        java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant());
                log.info("[GITLAB] mr today-only zone={} cutoff={} (server-local={})",
                        zone, todayStart.toInstant(), todayStart);
                try {
                    java.lang.reflect.Method m = f.getClass().getMethod("withUpdatedAfter", java.util.Date.class);
                    m.invoke(f, todayStart);
                } catch (Throwable t) {
                    log.debug("withUpdatedAfter not available, will filter client-side only");
                }
            }
            List<MergeRequest> raw = api.getMergeRequestApi().getMergeRequests(f, 1, Math.max(1, Math.min(limit, 100)));
            if (!todayOnly || todayStart == null) return raw;
            // 本地按 mergedAt 严格过滤：updated_after 是个 hint，可能放进一些昨天合的但今天有评论的
            java.util.Date cutoff = todayStart;
            List<MergeRequest> filtered = raw.stream()
                    .filter(m -> m.getMergedAt() != null && !m.getMergedAt().before(cutoff))
                    .collect(java.util.stream.Collectors.toList());
            log.info("[GITLAB] mr today-only branch={} raw={} kept={} (dropped={})",
                    targetBranch, raw.size(), filtered.size(), raw.size() - filtered.size());
            return filtered;
        }
    }

    private void setProjectIdReflective(MergeRequestFilter f, Object id) {
        try {
            java.lang.reflect.Method m;
            try { m = f.getClass().getMethod("withProjectId", Integer.class); }
            catch (NoSuchMethodException e) { m = f.getClass().getMethod("withProjectId", Long.class); }
            m.invoke(f, id);
        } catch (Throwable t) {
            log.warn("set MergeRequestFilter projectId failed: {}", t.getMessage());
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
