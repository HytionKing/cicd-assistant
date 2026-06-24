package com.cicdassistant.service.compare;

import java.util.List;

/**
 * 一个 MR 的关键元数据：源分支名 + 涉及文件列表（含每个文件的 patch）。
 * 拆开 source branch 之后，做"三明治 prompt"（patch + source 全文 + env 全文）就有依据了。
 */
public class MrInfo {
    private final String sourceBranch;
    private final List<MrFileChange> changes;

    public MrInfo(String sourceBranch, List<MrFileChange> changes) {
        this.sourceBranch = sourceBranch;
        this.changes = changes;
    }

    public String getSourceBranch() { return sourceBranch; }
    public List<MrFileChange> getChanges() { return changes; }
}
