package com.cicdassistant.service.compare;

import com.cicdassistant.entity.CompareFinding;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.cicdassistant.service.compare.DifferUtils.*;

/**
 * 兜底 differ：其它专项 differ 都不认识的文件，用纯文本比对。
 * 内容相等不上报；不相等只报一条 WARN，附两端片段。
 */
@Component
public class GenericTextDiffer implements FileDiffer {

    @Override
    public boolean supports(String path) {
        return true;   // 永远 supports，但 router 只在没有匹配时调用
    }

    @Override
    public List<CompareFinding> diff(String path, String baseline, String target) {
        List<CompareFinding> out = new ArrayList<>();
        if (baseline == null && target == null) return out;
        if (baseline == null) {
            out.add(finding(path, "FILE_ONLY_IN_TARGET", "WARN",
                    "目标分支多了文件 " + path, null, trimSnippet(target)));
            return out;
        }
        if (target == null) {
            out.add(finding(path, "FILE_MISSING_IN_TARGET", "ERROR",
                    "目标分支缺失文件 " + path, trimSnippet(baseline), null));
            return out;
        }
        if (!normalize(baseline).equals(normalize(target))) {
            out.add(finding(path, "TEXT_DIFF", "WARN",
                    "文件内容不一致", baseline, target));
        }
        return out;
    }
}
