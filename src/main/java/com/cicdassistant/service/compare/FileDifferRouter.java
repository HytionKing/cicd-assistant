package com.cicdassistant.service.compare;

import com.cicdassistant.entity.CompareFinding;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class FileDifferRouter {

    private final JavaSemanticDiffer javaDiffer;
    private final MybatisXmlDiffer xmlDiffer;
    private final SqlScriptDiffer sqlDiffer;
    private final GenericTextDiffer textDiffer;

    public FileDifferRouter(JavaSemanticDiffer javaDiffer,
                            MybatisXmlDiffer xmlDiffer,
                            SqlScriptDiffer sqlDiffer,
                            GenericTextDiffer textDiffer) {
        this.javaDiffer = javaDiffer;
        this.xmlDiffer = xmlDiffer;
        this.sqlDiffer = sqlDiffer;
        this.textDiffer = textDiffer;
    }

    public List<CompareFinding> route(String path, String baseline, String target) {
        if (javaDiffer.supports(path)) return javaDiffer.diff(path, baseline, target);
        if (sqlDiffer.supports(path))  return sqlDiffer.diff(path, baseline, target);
        if (xmlDiffer.supports(path))  return xmlDiffer.diff(path, baseline, target);
        return textDiffer.diff(path, baseline, target);
    }
}
