package com.cicdassistant.service.compare;

import com.cicdassistant.entity.CompareFinding;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.cicdassistant.service.compare.DifferUtils.*;

/**
 * Java 文件语义级比对：导入 / 类/接口 / 方法签名+方法体哈希 / 字段。
 * 解析失败时回退到通用文本比对（GenericDiffer 兜底）。
 */
@Slf4j
@Component
public class JavaSemanticDiffer implements FileDiffer {

    @Override
    public boolean supports(String path) {
        return path != null && path.endsWith(".java");
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
            log.warn("[COMPARE-JAVA] parse failed for {}: {}; falling back to text diff", path, e.getMessage());
            // 语法解析失败，但内容不同 → 上报一条粗粒度差异，让人工/LLM 复核
            out.add(finding(path, "JAVA_PARSE_FAILED", "WARN",
                    "Java 解析失败，两版本内容不同但无法定位差异点：" + e.getClass().getSimpleName(),
                    baseline, target));
            return out;
        }

        // imports
        for (String i : minus(bs.imports, ts.imports)) {
            out.add(finding(path, "IMPORT_MISSING", "WARN",
                    "目标分支缺失 import: " + i, i, null));
        }
        for (String i : minus(ts.imports, bs.imports)) {
            out.add(finding(path, "IMPORT_EXTRA", "INFO",
                    "目标分支多出 import: " + i, null, i));
        }

        // types (class/interface)
        Set<String> bTypes = bs.types.keySet();
        Set<String> tTypes = ts.types.keySet();
        for (String t : minus(bTypes, tTypes)) {
            out.add(finding(path, "TYPE_MISSING", "ERROR",
                    "目标分支缺失类型 " + t, bs.types.get(t), null));
        }
        for (String t : minus(tTypes, bTypes)) {
            out.add(finding(path, "TYPE_EXTRA", "WARN",
                    "目标分支多出类型 " + t, null, ts.types.get(t)));
        }

        // methods
        for (String sig : minus(bs.methods.keySet(), ts.methods.keySet())) {
            out.add(finding(path, "METHOD_MISSING", "ERROR",
                    "目标分支缺失方法 " + sig, bs.methods.get(sig), null));
        }
        for (String sig : minus(ts.methods.keySet(), bs.methods.keySet())) {
            out.add(finding(path, "METHOD_EXTRA", "WARN",
                    "目标分支多出方法 " + sig, null, ts.methods.get(sig)));
        }
        for (String sig : intersect(bs.methods.keySet(), ts.methods.keySet())) {
            String b = bs.methods.get(sig), t = ts.methods.get(sig);
            if (!hash(normalize(b)).equals(hash(normalize(t)))) {
                out.add(finding(path, "METHOD_BODY_DIFF", "WARN",
                        "方法体不一致：" + sig, b, t));
            }
        }

        // fields
        for (String f : minus(bs.fields.keySet(), ts.fields.keySet())) {
            out.add(finding(path, "FIELD_MISSING", "ERROR",
                    "目标分支缺失字段 " + f, bs.fields.get(f), null));
        }
        for (String f : minus(ts.fields.keySet(), bs.fields.keySet())) {
            out.add(finding(path, "FIELD_EXTRA", "WARN",
                    "目标分支多出字段 " + f, null, ts.fields.get(f)));
        }

        return out;
    }

    private Snapshot parse(String source) {
        Snapshot s = new Snapshot();
        CompilationUnit cu = StaticJavaParser.parse(source);
        cu.getImports().forEach(im -> s.imports.add(im.getNameAsString() + (im.isStatic() ? "(static)" : "")));
        for (TypeDeclaration<?> td : cu.getTypes()) {
            String typeName = td.getNameAsString();
            s.types.put(typeName, td.toString());
            if (td instanceof ClassOrInterfaceDeclaration) {
                ClassOrInterfaceDeclaration cd = (ClassOrInterfaceDeclaration) td;
                cd.getMethods().forEach(m -> putMethod(s, typeName, m));
                cd.getFields().forEach(f -> putField(s, typeName, f));
            }
        }
        return s;
    }

    private void putMethod(Snapshot s, String type, MethodDeclaration m) {
        StringBuilder sig = new StringBuilder();
        sig.append(type).append("#").append(m.getNameAsString()).append("(");
        boolean first = true;
        for (com.github.javaparser.ast.body.Parameter p : m.getParameters()) {
            if (!first) sig.append(",");
            first = false;
            sig.append(p.getType().toString());
        }
        sig.append(")");
        s.methods.put(sig.toString(), m.toString());
    }

    private void putField(Snapshot s, String type, FieldDeclaration f) {
        for (VariableDeclarator v : f.getVariables()) {
            String key = type + "#" + v.getNameAsString();
            s.fields.put(key, f.toString());
        }
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
        Set<String> imports = new LinkedHashSet<>();
        Map<String, String> types = new LinkedHashMap<>();
        Map<String, String> methods = new LinkedHashMap<>();
        Map<String, String> fields = new LinkedHashMap<>();
    }
}
