package com.analyzer.analysis;

import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.snapshot.model.IArray;
import org.eclipse.mat.snapshot.model.IInstance;
import org.eclipse.mat.snapshot.model.IClass;
import org.eclipse.mat.snapshot.model.IObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ThreadLocal 泄漏检测：检查线程对象的 ThreadLocalMap 是否持有大量数据。
 *
 * <p>路径：java.lang.thread.threadLocals（ThreadLocal$ThreadLocalMap）
 * → map.table（ThreadLocalMap$Entry[]）→ Entry.value。</p>
 *
 * <p>实现：遍历所有 Thread 实例，读取 threadLocals 字段；对每个 Entry
 * 解析其 value 引用并按支配树口径累计 Retained；当某线程持有的
 * ThreadLocal 数据总量 ≥ 阈值时输出结论。Entry 数量按线程封顶
 * （{@link #MAX_ENTRIES_PER_THREAD}）避免极端线程病态慢。</p>
 */
public final class ThreadLocalLeakDetector implements Detector {

    /** 每个线程最多解析的 ThreadLocalMap Entry 数（性能保护）。 */
    private static final int MAX_ENTRIES_PER_THREAD = 1024;

    @Override
    public String name() {
        return "ThreadLocal 持有分析";
    }

    @Override
    public List<Finding> detect(DetectorContext ctx) {
        List<Finding> findings = new ArrayList<>();
        List<IClass> threadClasses;
        try {
            threadClasses = new ArrayList<>(
                    ctx.graph.raw().getClassesByName("java.lang.Thread", false));
        } catch (SnapshotException e) {
            return findings;
        }
        for (IClass threadClass : threadClasses) {
            for (int threadId : safeIds(ctx, threadClass)) {
                Analysis a = analyzeThread(ctx, threadId);
                if (a == null || a.totalRetained < ctx.minRetainedBytes) {
                    continue;
                }
                Finding f = new Finding(threadId, "java.lang.Thread",
                        ctx.graph.displayName(threadId),
                        ctx.graph.shallowSize(threadId), a.totalRetained);
                f.detectorName = name();
                f.details.put("线程名", a.threadName == null ? "?" : a.threadName);
                f.details.put("ThreadLocal 条目数", String.valueOf(a.entryCount));
                f.details.put("value 合计 Retained", FmtMb(a.totalRetained));
                f.path = ctx.pathAnalyzer.shortestPathToGcRoot(threadId);
                // 该检测天然就是 ThreadLocal 模式（引用链必经 ThreadLocamMap），直接钉模式
                ctx.patternMatcher.apply(f, ctx);
                if (!LeakPatternMatcher.THREAD_LOCAL_NOT_CLEANED.equals(f.pattern)) {
                    // 兜底：本检测器命中的对象明确按 ThreadLocal 未清理处理
                    f.pattern = LeakPatternMatcher.THREAD_LOCAL_NOT_CLEANED;
                    f.patternLabel = LeakPatternMatcher.labelOf(f.pattern);
                }
                findings.add(f);
            }
        }
        findings.sort(Comparator.comparingLong((Finding f) -> f.retainedBytes).reversed());
        if (findings.size() > ctx.top) {
            findings = new ArrayList<>(findings.subList(0, ctx.top));
        }
        return findings;
    }

    /** 单线程分析结果。 */
    private static final class Analysis {
        String threadName;
        int entryCount;
        long totalRetained;
    }

    /** 解析一个线程对象的 threadLocals → table → Entry.value。 */
    private Analysis analyzeThread(DetectorContext ctx, int threadId) {
        try {
            IObject threadObj = ctx.graph.raw().getObject(threadId);
            if (!(threadObj instanceof IInstance)) {
                return null;
            }
            Analysis a = new Analysis();
            try {
                Object nameVal = threadObj.resolveValue("name");
                if (nameVal instanceof String) {
                    a.threadName = (String) nameVal;
                }
            } catch (RuntimeException ignore) {
            }

            Object map = threadObj.resolveValue("threadLocals");
            if (map == null) {
                return null; // 没有 ThreadLocal，直接跳过
            }
            Object table = ((IObject) map).resolveValue("table");
            if (!(table instanceof IArray)) {
                return null;
            }
            IArray tableArr = (IArray) table;
            long[] entryAddrs = ((org.eclipse.mat.snapshot.model.IObjectArray) tableArr)
                    .getReferenceArray();
            int limit = Math.min(entryAddrs.length, MAX_ENTRIES_PER_THREAD);
            for (int i = 0; i < limit; i++) {
                if (entryAddrs[i] == 0) {
                    continue;
                }
                int entryId;
                try {
                    entryId = ctx.graph.raw().mapAddressToId(entryAddrs[i]);
                } catch (SnapshotException e) {
                    continue;
                }
                // Entry.value 是被强引用的业务对象；Entry 本身 retained 即其 value 占用
                IObject entry = ctx.graph.raw().getObject(entryId);
                Object value = entry.resolveValue("value");
                if (value instanceof IObject) {
                    a.entryCount++;
                    a.totalRetained += Math.max(0,
                            ctx.dominators.retained(((IObject) value).getObjectId()));
                }
            }
            return a;
        } catch (SnapshotException | RuntimeException e) {
            return null;
        }
    }

    private static int[] safeIds(DetectorContext ctx, IClass c) {
        try {
            return c.getObjectIds();
        } catch (SnapshotException e) {
            return new int[0];
        }
    }

    private static String FmtMb(long bytes) {
        return String.format("%.2f MB", bytes / (1024d * 1024d));
    }
}
