package com.cicdassistant.service.compare;

import com.cicdassistant.entity.Repo;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.models.Diff;
import org.gitlab4j.api.models.MergeRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * 拉取 MR 改动信息。
 *
 * <p>两种粒度：</p>
 * <ul>
 *   <li>{@link #filesOf} — 只要文件路径列表，用于"按 MR 圈定要看的文件集合"老逻辑</li>
 *   <li>{@link #changesOf} — 文件 + 该文件的 unified patch 文本，给 PatchHunkVerifier 用</li>
 * </ul>
 *
 * <p>一次远端调用就能拿到两份信息，{@link #changesOf} 是主接口，
 * {@link #filesOf} 现保留兼容历史调用方。</p>
 */
@Slf4j
@Component
public class MrFileListFetcher {

    /**
     * 返回某个 MR 修改/新增/删除的所有文件路径（以新路径为主，重命名取 newPath）。
     */
    public List<String> filesOf(Repo repo, int mrIid) {
        List<MrFileChange> changes = changesOf(repo, mrIid);
        List<String> files = new ArrayList<>(changes.size());
        for (MrFileChange c : changes) files.add(c.getPath());
        return files;
    }

    /**
     * 返回某 MR 改动的全部文件，含每个文件的 unified patch 文本。
     * patch 是 GitLab 提供的 {@code Diff.getDiff()}，由若干 @@ hunk 组成。
     */
    public List<MrFileChange> changesOf(Repo repo, int mrIid) {
        if (repo == null) return new ArrayList<>();
        String host = repo.getGitlabHost();
        String projectPath = repo.getProjectPath();
        if (host == null || host.isEmpty() || projectPath == null || projectPath.isEmpty()) return new ArrayList<>();

        try (GitLabApi api = buildApi(repo)) {
            MergeRequest mr = api.getMergeRequestApi().getMergeRequestChanges(projectPath, mrIid);
            List<Diff> diffs = mr != null ? mr.getChanges() : null;
            if (diffs == null) return new ArrayList<>();
            // 同一文件可能在 changes 里出现多次（rename + 内容改）— 后出现的合并 patch 文本
            Map<String, MrFileChange> byPath = new LinkedHashMap<>();
            for (Diff d : diffs) {
                String path = d.getNewPath();
                if (path == null || path.isEmpty()) path = d.getOldPath();
                if (path == null || path.isEmpty()) continue;
                String patch = d.getDiff();
                MrFileChange existing = byPath.get(path);
                if (existing != null && patch != null && !patch.isEmpty()) {
                    String merged = (existing.getPatch() == null ? "" : existing.getPatch()) + "\n" + patch;
                    byPath.put(path, new MrFileChange(path, merged));
                } else {
                    byPath.put(path, new MrFileChange(path, patch));
                }
            }
            return new ArrayList<>(byPath.values());
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
