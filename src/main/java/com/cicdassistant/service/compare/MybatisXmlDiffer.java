package com.cicdassistant.service.compare;

import com.cicdassistant.entity.CompareFinding;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.*;

import static com.cicdassistant.service.compare.DifferUtils.*;

/**
 * Mybatis XML 比对：按 namespace + 顶级 SQL 标签的 id 分组，逐条比对内容文本。
 * 顶级 SQL 标签：select / insert / update / delete / sql / resultMap。
 */
@Slf4j
@Component
public class MybatisXmlDiffer implements FileDiffer {

    private static final Set<String> SQL_TAGS = new HashSet<>(Arrays.asList(
            "select", "insert", "update", "delete", "sql", "resultMap"));

    @Override
    public boolean supports(String path) {
        if (path == null) return false;
        if (!path.endsWith(".xml")) return false;
        // 启发式：路径里通常含 mapper / mybatis 字样；为了不漏判，凡是 .xml 文件都先试
        // 解析失败/无 SQL 标签 → 在 diff 里走回退分支，不会报错
        return true;
    }

    @Override
    public List<CompareFinding> diff(String path, String baseline, String target) {
        List<CompareFinding> out = new ArrayList<>();
        if (baseline == null && target == null) return out;
        if (baseline == null) {
            out.add(finding(path, "FILE_ONLY_IN_TARGET", "WARN",
                    "目标分支多了文件 " + path + "（基准没有）", null, trimSnippet(target)));
            return out;
        }
        if (target == null) {
            out.add(finding(path, "FILE_MISSING_IN_TARGET", "ERROR",
                    "目标分支缺失文件 " + path, trimSnippet(baseline), null));
            return out;
        }
        if (normalize(baseline).equals(normalize(target))) return out;

        Snapshot bs, ts;
        try {
            bs = parse(baseline);
            ts = parse(target);
        } catch (Throwable e) {
            log.warn("[COMPARE-XML] parse failed for {}: {}", path, e.getMessage());
            out.add(finding(path, "XML_PARSE_FAILED", "WARN",
                    "XML 解析失败，但两版本内容不同：" + e.getClass().getSimpleName(),
                    baseline, target));
            return out;
        }

        // 非 Mybatis XML（没识别到 SQL 标签）：当作通用文本对比，报一条粗粒度差异
        if (bs.statements.isEmpty() && ts.statements.isEmpty()) {
            out.add(finding(path, "XML_TEXT_DIFF", "WARN",
                    "XML 文件内容不一致", baseline, target));
            return out;
        }

        for (String id : minus(bs.statements.keySet(), ts.statements.keySet())) {
            out.add(finding(path, "SQL_TAG_MISSING", "ERROR",
                    "目标分支缺失 SQL 元素 " + id, bs.statements.get(id), null));
        }
        for (String id : minus(ts.statements.keySet(), bs.statements.keySet())) {
            out.add(finding(path, "SQL_TAG_EXTRA", "WARN",
                    "目标分支多出 SQL 元素 " + id, null, ts.statements.get(id)));
        }
        for (String id : intersect(bs.statements.keySet(), ts.statements.keySet())) {
            String b = bs.statements.get(id), t = ts.statements.get(id);
            if (!hash(normalize(b)).equals(hash(normalize(t)))) {
                out.add(finding(path, "SQL_TAG_DIFF", "WARN",
                        "SQL 元素内容不一致：" + id, b, t));
            }
        }
        return out;
    }

    private Snapshot parse(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setValidating(false);
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        // 忽略 mybatis DTD
        db.setEntityResolver((p, s) -> new InputSource(new StringReader("")));
        Document doc = db.parse(new InputSource(new StringReader(xml)));
        Snapshot snap = new Snapshot();
        Element root = doc.getDocumentElement();
        if (root == null) return snap;
        String namespace = root.getAttribute("namespace");
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            String tag = el.getTagName();
            if (!SQL_TAGS.contains(tag)) continue;
            String id = el.getAttribute("id");
            if (id == null || id.isEmpty()) id = "(no-id-" + i + ")";
            String key = namespace + "::" + tag + "::" + id;
            snap.statements.put(key, el.getTextContent().trim());
        }
        return snap;
    }

    private static <T> Set<T> minus(Set<T> a, Set<T> b) {
        Set<T> r = new LinkedHashSet<>(a);
        r.removeAll(b);
        return r;
    }

    private static <T> Set<T> intersect(Set<T> a, Set<T> b) {
        Set<T> r = new LinkedHashSet<>(a);
        r.retainAll(b);
        return r;
    }

    private static class Snapshot {
        Map<String, String> statements = new LinkedHashMap<>();
    }
}
