package com.analyzer.analysis;

import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.snapshot.model.IArray;
import org.eclipse.mat.snapshot.model.IInstance;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.IClass;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 集合增长检测：找出 Retained Heap 异常的 HashMap / ArrayList / ConcurrentHashMap 等。
 *
 * <p>做法：对常见 JDK 集合类枚举全部实例，取支配树口径的 Retained，
 * 超过 --min-retained-mb 阈值的入选；同时尽力解析“规模”
 * （HashMap/ConcurrentHashMap 读 table.length，ArrayList 读 size 字段），
 * 方便一眼看出是“条目多”还是“单条目大”。</p>
 */
public final class CollectionGrowthDetector implements Detector {

    /** 关注的 JDK 集合实现（接口维度太散，直接盯实现类更准）。 */
    private static final String[] WATCH_CLASSES = {
            "java.util.HashMap",
            "java.util.LinkedHashMap",
            "java.util.TreeMap",
            "java.util.Hashtable",
            "java.util.concurrent.ConcurrentHashMap",
            "java.util.concurrent.ConcurrentSkipListMap",
            "java.util.ArrayList",
            "java.util.LinkedList",
            "java.util.Vector",
            "java.util.Stack",
            "java.util.concurrent.CopyOnWriteArrayList",
            "java.util.HashSet",
            "java.util.LinkedHashSet",
            "java.util.TreeSet",
            "java.util.PriorityQueue",
            "java.util.concurrent.ConcurrentHashMap$CollectionView",
    };

    @Override
    public String name() {
        return "可疑集合";
    }

    @Override
    public List<Finding> detect(DetectorContext ctx) {
        List<Finding> findings = new ArrayList<>();
        for (String cls : WATCH_CLASSES) {
            for (IClass c : classesOf(ctx, cls)) {
                int[] ids;
                try {
                    ids = c.getObjectIds();
                } catch (SnapshotException e) {
                    continue;
                }
                for (int id : ids) {
                    long retained = ctx.dominators.retained(id);
                    if (retained < ctx.minRetainedBytes) {
                        continue;
                    }
                    Finding f = new Finding(id, c.getName(), ctx.graph.displayName(id),
                            ctx.graph.shallowSize(id), retained);
                    f.detectorName = name();
                    f.details.put("集合规模(估计)", estimateSize(ctx, id, c.getName()));
                    f.path = ctx.pathAnalyzer.shortestPathToGcRoot(id);
                    ctx.patternMatcher.apply(f, ctx);
                    f.details.putIfAbsent("持有链根类型",
                            f.path == null ? "不可达" : f.path.nodes().get(0).gcRootType);
                    findings.add(f);
                }
            }
        }
        findings.sort(Comparator.comparingLong((Finding f) -> f.retainedBytes).reversed());
        if (findings.size() > ctx.top) {
            findings = new ArrayList<>(findings.subList(0, ctx.top));
        }
        return findings;
    }

    private List<IClass> classesOf(DetectorContext ctx, String name) {
        try {
            var found = ctx.graph.raw().getClassesByName(name, false);
            return found == null ? List.of() : new ArrayList<>(found);
        } catch (SnapshotException e) {
            return List.of();
        }
    }

    /**
     * 估计集合“规模”：
     * Map 读 table 数组长度（HashMap）或 size/count 字段；List 读 size 字段。
     * 读不到时返回 unknown，不影响主流程。
     */
    private String estimateSize(DetectorContext ctx, int objectId, String className) {
        try {
            IObject obj = ctx.graph.raw().getObject(objectId);
            if (!(obj instanceof IInstance)) {
                return "unknown";
            }
            IInstance inst = (IInstance) obj;
            // List / 多数集合有 size 计数
            try {
                Object size = inst.getFields().stream()
                        .filter(fd -> fd.getName().equals("size") || fd.getName().equals("count"))
                        .map(fd -> fd.getValue())
                        .filter(v -> v instanceof Number)
                        .findFirst().orElse(null);
                if (size != null) {
                    return "size=" + ((Number) size).intValue();
                }
            } catch (RuntimeException ignore) {
            }
            // HashMap 系：table 数组长度是容量（元素数 ≤ 容量）
            Object table = inst.resolveValue("table");
            if (table == null) {
                table = inst.resolveValue("topLevelTable"); // ConcurrentHashMap
            }
            if (table instanceof IArray) {
                return "capacity=" + ((IArray) table).getLength();
            }
            return "unknown";
        } catch (SnapshotException | RuntimeException e) {
            return "unknown";
        }
    }
}
