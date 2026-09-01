package com.analyzer.analysis;

import org.eclipse.mat.snapshot.model.IClass;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 大对象检测：找出 Shallow Heap 超过阈值的大对象。
 *
 * <p>重点关注 byte[] / char[] / Object[]（业务堆里最常见的“大块内存”），
 * 同时扫全部数组类型；普通实例类只按类维度提示，不逐个扫描，
 * 避免在百万级实例上做无谓的点查。</p>
 *
 * <p>阈值策略：大对象线 = max(1MB, --min-retained-mb 的 20%)，
 * 让默认参数下 5MB 阈值对应 1MB 的浅堆门槛。</p>
 */
public final class LargeObjectDetector implements Detector {

    /** 每个类最多探测的实例数上限（大堆保护：超限时该类只报告样本）。 */
    private static final int PER_CLASS_SCAN_LIMIT = 200_000;

    @Override
    public String name() {
        return "大对象列表";
    }

    @Override
    public List<Finding> detect(DetectorContext ctx) throws Exception {
        final long threshold = Math.max(1L * 1024 * 1024, ctx.minRetainedBytes / 5);
        List<Finding> hits = new ArrayList<>();
        long scanned = 0;

        for (IClass clazz : ctx.graph.raw().getClasses()) {
            if (!clazz.isArrayType() && !isPriorityClass(clazz.getName())) {
                continue; // 非数组且非重点类：跳过逐个实例扫描
            }
            int[] ids = clazz.getObjectIds();
            int limit = Math.min(ids.length, PER_CLASS_SCAN_LIMIT);
            for (int i = 0; i < limit; i++) {
                int id = ids[i];
                long shallow = ctx.graph.shallowSize(id);
                scanned++;
                if (shallow >= threshold) {
                    Finding f = new Finding(id, clazz.getName(),
                            ctx.graph.displayName(id), shallow,
                            ctx.dominators.retained(id));
                    f.detectorName = name();
                    f.path = ctx.pathAnalyzer.shortestPathToGcRoot(id);
                    ctx.patternMatcher.apply(f, ctx);
                    hits.add(f);
                }
            }
        }
        // 取 shallow 最大的 Top N
        hits.sort(Comparator.comparingLong((Finding f) -> f.shallowBytes).reversed());
        if (hits.size() > ctx.top) {
            hits = new ArrayList<>(hits.subList(0, ctx.top));
        }
        // 附带扫描信息，方便判断样本是否有截断
        for (Finding f : hits) {
            f.details.putIfAbsent("扫描对象数", String.valueOf(scanned));
        }
        return hits;
    }

    /** 非数组但值得逐个看的“大对象温床”类。 */
    private static boolean isPriorityClass(String name) {
        return name.equals("java.lang.String")
                || name.equals("java.lang.Thread")
                || name.equals("java.util.HashMap$Node[]");
    }
}
