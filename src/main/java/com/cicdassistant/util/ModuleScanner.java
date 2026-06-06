package com.cicdassistant.util;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Slf4j
public class ModuleScanner {

    @Data
    public static class Module {
        private String name;
        private String relativePath;

        public Module(String name, String relativePath) {
            this.name = name;
            this.relativePath = relativePath;
        }
    }

    public static List<Module> scan(File repoRoot) {
        List<Module> modules = new ArrayList<>();
        if (repoRoot == null || !repoRoot.exists()) return modules;
        File rootPom = new File(repoRoot, "pom.xml");
        if (rootPom.exists() && hasSpringBootApp(repoRoot)) {
            modules.add(new Module(repoRoot.getName(), "."));
            return modules;
        }
        scanRecursive(repoRoot, repoRoot, modules, 0, 4);
        return modules;
    }

    private static void scanRecursive(File root, File dir, List<Module> modules, int depth, int maxDepth) {
        if (depth > maxDepth) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (!c.isDirectory()) continue;
            String name = c.getName();
            if (name.startsWith(".") || "target".equals(name) || "node_modules".equals(name)) continue;
            File pom = new File(c, "pom.xml");
            if (pom.exists() && hasSpringBootApp(c)) {
                String rel = root.toPath().relativize(c.toPath()).toString().replace('\\', '/');
                modules.add(new Module(c.getName(), rel));
            }
            scanRecursive(root, c, modules, depth + 1, maxDepth);
        }
    }

    private static boolean hasSpringBootApp(File moduleDir) {
        File src = new File(moduleDir, "src/main/java");
        if (!src.exists()) return false;
        try {
            Collection<File> javas = FileUtils.listFiles(src, new String[]{"java"}, true);
            for (File jf : javas) {
                String content = FileUtils.readFileToString(jf, "UTF-8");
                if (content.contains("@SpringBootApplication")) {
                    return true;
                }
            }
        } catch (IOException e) {
            log.warn("scan java files failed for {}: {}", moduleDir, e.getMessage());
        }
        return false;
    }
}
