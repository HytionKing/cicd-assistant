package com.cicdassistant.service.compare;

import com.cicdassistant.entity.CompareFinding;

import java.util.List;

/**
 * 单个文件级别的语义比对接口。
 * 输入：baseline + target 两个版本的全文（来自不同分支同一路径）。
 * 输出：差异 finding 清单。
 *
 * 实现类按文件扩展名/路径分派（见 {@link FileDifferRouter}）。
 */
public interface FileDiffer {

    /** @return 此 differ 是否能处理该文件，按文件路径判断（如 .java / .xml / .sql）。 */
    boolean supports(String path);

    /**
     * 比对两份内容。
     * @param path 文件路径，仅用于 finding.filePath 标注。
     * @param baseline 基准分支的文件内容；null 表示该路径在基准分支不存在。
     * @param target   目标分支的文件内容；null 表示该路径在目标分支不存在。
     */
    List<CompareFinding> diff(String path, String baseline, String target);
}
