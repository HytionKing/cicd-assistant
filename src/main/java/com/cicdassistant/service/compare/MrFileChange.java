package com.cicdassistant.service.compare;

/**
 * MR 涉及的单个文件改动：路径 + 该文件在 MR 上的 unified patch 文本。
 * patch 用 gitlab4j 的 Diff.getDiff()，原样是以 @@ 开头的 hunk 列表。
 */
public class MrFileChange {
    private final String path;
    private final String patch;

    public MrFileChange(String path, String patch) {
        this.path = path;
        this.patch = patch;
    }

    public String getPath() { return path; }
    public String getPatch() { return patch; }
}
