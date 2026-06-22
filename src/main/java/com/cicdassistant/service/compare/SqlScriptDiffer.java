package com.cicdassistant.service.compare;

import com.cicdassistant.entity.CompareFinding;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.cicdassistant.service.compare.DifferUtils.*;

/**
 * SQL 脚本（.sql）比对：按 statement 拆分，DDL 按目标对象名匹配，其它语句用规范化后哈希匹配。
 * 解析失败时整文件作为通用文本对比兜底。
 */
@Slf4j
@Component
public class SqlScriptDiffer implements FileDiffer {

    @Override
    public boolean supports(String path) {
        return path != null && path.toLowerCase().endsWith(".sql");
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
        if (normalize(baseline).equals(normalize(target))) return out;

        Map<String, String> bs, ts;
        try {
            bs = parse(baseline);
            ts = parse(target);
        } catch (Throwable e) {
            log.warn("[COMPARE-SQL] parse failed for {}: {}", path, e.getMessage());
            out.add(finding(path, "SQL_PARSE_FAILED", "WARN",
                    "SQL 解析失败，但两版本内容不同：" + e.getClass().getSimpleName(),
                    baseline, target));
            return out;
        }

        for (String key : minus(bs.keySet(), ts.keySet())) {
            out.add(finding(path, "SQL_STMT_MISSING", "ERROR",
                    "目标分支缺失 SQL 语句：" + key, bs.get(key), null));
        }
        for (String key : minus(ts.keySet(), bs.keySet())) {
            out.add(finding(path, "SQL_STMT_EXTRA", "WARN",
                    "目标分支多出 SQL 语句：" + key, null, ts.get(key)));
        }
        // 同 key 的语句也比内容
        for (String key : intersect(bs.keySet(), ts.keySet())) {
            String b = bs.get(key), t = ts.get(key);
            if (!hash(normalize(b)).equals(hash(normalize(t)))) {
                out.add(finding(path, "SQL_STMT_DIFF", "WARN",
                        "SQL 语句内容不一致：" + key, b, t));
            }
        }
        return out;
    }

    private Map<String, String> parse(String sql) throws Exception {
        Statements stmts = CCJSqlParserUtil.parseStatements(sql);
        Map<String, String> map = new LinkedHashMap<>();
        int seq = 0;
        for (Statement st : stmts.getStatements()) {
            String key = keyOf(st, seq++);
            String body = st.toString();
            // 同一 key 重复时，连号区分
            String finalKey = key;
            int n = 1;
            while (map.containsKey(finalKey)) finalKey = key + "#" + (++n);
            map.put(finalKey, body);
        }
        return map;
    }

    private String keyOf(Statement st, int seq) {
        if (st instanceof CreateTable) {
            CreateTable c = (CreateTable) st;
            return "CREATE TABLE " + safeName(c.getTable() == null ? "" : c.getTable().getName());
        }
        if (st instanceof Alter) {
            Alter a = (Alter) st;
            return "ALTER TABLE " + safeName(a.getTable() == null ? "" : a.getTable().getName());
        }
        // 其它（INSERT/UPDATE/DELETE/SELECT 等）用规范化后的内容哈希做 key，顺序无关比较
        return st.getClass().getSimpleName() + "@" + hash(normalize(st.toString()));
    }

    private String safeName(String s) {
        return s == null ? "?" : s.replace("`", "").replace("\"", "").trim();
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
}
