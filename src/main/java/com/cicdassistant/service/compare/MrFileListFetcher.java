package com.cicdassistant.service.compare;

import com.cicdassistant.entity.Repo;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.models.Diff;
import org.gitlab4j.api.models.MergeRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 拉取 MR 改动的文件列表（只取路径，不读 patch 内容）。
 * 我们后续比对用的是 baseline / target 分支的当前内容；MR 只用来圈定要看的文件集合。
 */
@Slf4j
@Component
public class MrFileListFetcher {

    /**
     * 返回某个 MR 修改/新增/删除的所有文件路径（以新路径为主，重命名取 newPath）。
     */
    public List<String> filesOf(Repo repo, int mrIid) {
        if (repo == null) return new ArrayList<>();
        String host = repo.getGitlabHost();
        String projectPath = repo.getProjectPath();
        if (host == null || host.isEmpty() || projectPath == null || projectPath.isEmpty()) return new ArrayList<>();

        try (GitLabApi api = buildApi(repo)) {
            MergeRequest mr = api.getMergeRequestApi().getMergeRequestChanges(projectPath, mrIid);
            List<Diff> diffs = mr != null ? mr.getChanges() : null;
            if (diffs == null) return new ArrayList<>();
            List<String> files = new ArrayList<>();
            for (Diff d : diffs) {
                String path = d.getNewPath();
                if (path == null || path.isEmpty()) path = d.getOldPath();
                if (path != null && !path.isEmpty()) files.add(path);
            }
            return files;
        } catch (Exception e) {
            log.warn("[COMPARE-MR] failed to fetch changes for MR !{}: {}", mrIid, e.getMessage());
            return new ArrayList<>();
        }
    }

    private GitLabApi buildApi(Repo repo) throws Exception {
        String host = repo.getGitlabHost();
        if (host != null) host = host.trim();
        while (host != null && host.endsWith("/")) host = host.substring(0, host.length() - 1);
        if ("PASSWORD".equalsIgnoreCase(repo.getAuthType())) {
            return GitLabApi.oauth2Login(host, repo.getUsername(), repo.getPassword());
        }
        return new GitLabApi(host, repo.getAccessToken());
    }
}
