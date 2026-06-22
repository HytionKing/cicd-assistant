package com.cicdassistant.service.compare;

import com.cicdassistant.entity.CompareFinding;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

class DifferUtils {

    static CompareFinding finding(String path, String type, String severity, String summary,
                                  String baselineSnippet, String targetSnippet) {
        CompareFinding f = new CompareFinding();
        f.setFilePath(path);
        f.setDetector("RULE");
        f.setType(type);
        f.setSeverity(severity);
        f.setSummary(summary);
        f.setBaselineSnippet(trimSnippet(baselineSnippet));
        f.setTargetSnippet(trimSnippet(targetSnippet));
        f.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return f;
    }

    static String hash(String s) {
        if (s == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    /** 把 snippet 截断到 4000 字符以内，避免一条 finding 把 SQLite 撑爆。 */
    static String trimSnippet(String s) {
        if (s == null) return null;
        int max = 4000;
        if (s.length() <= max) return s;
        return s.substring(0, max) + "\n... [截断，共 " + s.length() + " 字符]";
    }

    /** 去除注释和空白以减少噪音；用于哈希比较时判断"是否实质相等"。 */
    static String normalize(String s) {
        if (s == null) return "";
        // 简单去掉行尾空白 + 折叠连续空行
        return s.replaceAll("\\r\\n?", "\n")
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }
}
