package com.analyzer.analysis;

import com.analyzer.util.Fmt;
import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.model.IClass;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基线对比：按类统计两次 dump 的实例数 / 浅堆增量。
 *
 * <p>只统计“增长”的类（实例数 +100 以上或浅堆 +2MB 以上），
 * 避免把正常波动写进报告。retained 需要为基线再建一次支配树，
 * 代价过高，因此这里只用实例数与浅堆，口径在报告里明确标注。</p>
 */
public final class GrowthAnalyzer {

    private GrowthAnalyzer() {
    }

    public static GrowthReport compare(ISnapshot before, ISnapshot after, int limit)
            throws SnapshotException {
        GrowthReport report = new GrowthReport();
        report.baselineUsedHeapBytes = before.getSnapshotInfo().getUsedHeapSize();
        report.baselineObjectCount = before.getSnapshotInfo().getNumberOfObjects();

        Map<String, long[]> base = histogram(before);
        Map<String, long[]> now = histogram(after);
        Set<String> names = new LinkedHashSet<>(now.keySet());
        names.addAll(base.keySet());

        List<GrowthReport.Row> rows = new ArrayList<>();
        for (String name : names) {
            long[] b = base.getOrDefault(name, new long[]{0, 0});
            long[] a = now.getOrDefault(name, new long[]{0, 0});
            long countDelta = a[0] - b[0];
            long shallowDelta = a[1] - b[1];
            if (countDelta <= 0 && shallowDelta <= 0) {
                continue;
            }
            if (countDelta < 100 && shallowDelta < 2 * Fmt.MB) {
                continue;
            }
            GrowthReport.Row row = new GrowthReport.Row();
            row.className = name;
            row.beforeCount = b[0];
            row.afterCount = a[0];
            row.beforeShallow = b[1];
            row.afterShallow = a[1];
            rows.add(row);
        }
        rows.sort((x, y) -> {
            int byShallow = Long.compare(y.shallowDelta(), x.shallowDelta());
            return byShallow != 0 ? byShallow : Long.compare(y.countDelta(), x.countDelta());
        });
        if (rows.size() > limit) {
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        report.rows.addAll(rows);
        return report;
    }

    /** 类名 → {实例数, 浅堆合计}。 */
    private static Map<String, long[]> histogram(ISnapshot snapshot) throws SnapshotException {
        Map<String, long[]> map = new HashMap<>();
        for (IClass c : snapshot.getClasses()) {
            long count = c.getNumberOfObjects();
            if (count <= 0) {
                continue;
            }
            map.put(c.getName(), new long[]{count, count * c.getHeapSizePerInstance()});
        }
        return map;
    }
}
