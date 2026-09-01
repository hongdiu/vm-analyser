package com.analyzer.analysis;

import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.snapshot.model.IClass;
import org.eclipse.mat.snapshot.model.IClassLoader;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ClassLoader 泄漏检测：检查是否存在“同一类型 ClassLoader 被 new 出多个实例”。
 *
 * <p>背景：Tomcat 热部署、OSGi 插件、Groovy 脚本引擎等场景反复创建
 * 自定义 ClassLoader，若旧的加载器及其定义的类无法回收，会同时泄漏
 * Metaspace 与堆内对象。</p>
 *
 * <p>实现：用 MAT 的类元数据把每个已加载类归到其 defining ClassLoader，
 * 再按“加载器实现类的类名”分组。JDK 内置的少数加载器（App/CpExt/Platform）
 * 全局本就只有一个实例，天然不会命中；自定义加载器组内实例数 ≥ 2
 * 且合计 Retained 超阈值即输出，附带“该加载器定义了多少类”的明细。</p>
 */
public final class ClassLoaderLeakDetector implements Detector {

    /** 同型加载器至少多少个实例才视为可疑。 */
    private static final int MIN_DUPLICATES = 2;

    @Override
    public String name() {
        return "ClassLoader 异常";
    }

    @Override
    public List<Finding> detect(DetectorContext ctx)
            throws SnapshotException {
        // 1) 每个 defining loader → 该类加载器定义了多少个类（MAT 类元数据口径）
        Map<Integer, Integer> classesPerLoader = new HashMap<>();
        for (IClass c : ctx.graph.classes()) {
            int loaderId = c.getClassLoaderId();
            if (loaderId >= 0) { // -1 是 bootstrap，不参与分组
                classesPerLoader.merge(loaderId, 1, Integer::sum);
            }
        }

        // 2) 按加载器实现类的类名分组（同型多个实例 = 可疑信号）
        Map<String, List<Integer>> byLoaderClass = new LinkedHashMap<>();
        for (Integer loaderId : classesPerLoader.keySet()) {
            String loaderClass = ctx.graph.className(loaderId);
            if (loaderClass == null || loaderClass.startsWith("<")) {
                continue; // 元数据异常的加载器不参与判定
            }
            byLoaderClass.computeIfAbsent(loaderClass, k -> new ArrayList<>()).add(loaderId);
        }

        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> group : byLoaderClass.entrySet()) {
            List<Integer> instances = group.getValue();
            if (instances.size() < MIN_DUPLICATES) {
                continue;
            }
            // 组内合计 retained（近似：各加载器 retained 之和；互相独立不重复计）
            long total = 0;
            for (int id : instances) {
                total += Math.max(0, ctx.dominators.retained(id));
            }
            if (total < ctx.minRetainedBytes) {
                continue;
            }
            // 只报告组内 retained 最大的若干个实例
            instances.sort(Comparator.comparingLong((Integer id) ->
                    ctx.dominators.retained(id)).reversed());
            int shown = Math.min(instances.size(), ctx.top);
            for (int i = 0; i < shown; i++) {
                int id = instances.get(i);
                Finding f = new Finding(id, group.getKey(),
                        ctx.graph.displayName(id),
                        ctx.graph.shallowSize(id),
                        ctx.dominators.retained(id));
                f.detectorName = name();
                f.details.put("同型 ClassLoader 实例数", String.valueOf(instances.size()));
                f.details.put("定义的类数量",
                        String.valueOf(classesPerLoader.getOrDefault(id, 0)));
                f.path = ctx.pathAnalyzer.shortestPathToGcRoot(id);
                f.pattern = LeakPatternMatcher.CLASSLOADER_LEAK;
                f.patternLabel = LeakPatternMatcher.labelOf(f.pattern);
                ctx.patternMatcher.apply(f, ctx);
                // apply 里 classLoader 一定为 true → 会覆盖为 CLASSLOADER_LEAK，双保险
                findings.add(f);
            }
        }
        findings.sort(Comparator.comparingLong((Finding f) -> f.retainedBytes).reversed());
        if (findings.size() > ctx.top) {
            findings = new ArrayList<>(findings.subList(0, ctx.top));
        }
        return findings;
    }
}
