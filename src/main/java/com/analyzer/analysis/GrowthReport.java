package com.analyzer.analysis;

import com.analyzer.util.Fmt;

import java.util.ArrayList;
import java.util.List;

/**
 * 两次 dump 的类级增长对比（“增长”唯一硬证据）。
 *
 * <p>单份 dump 只能说明“此刻占用”，无法区分“正常常驻”与“泄漏”。
 * 对比基线后，实例数/浅堆的增量可以直接回答“它是不是在涨”。</p>
 */
public final class GrowthReport {

    public String baselinePath;
    public long baselineUsedHeapBytes;
    public long baselineObjectCount;
    /** 只包含“增长”的类，按浅堆增量降序。 */
    public final List<Row> rows = new ArrayList<>();

    public static final class Row {
        public String className;
        public long beforeCount;
        public long afterCount;
        public long beforeShallow;
        public long afterShallow;

        public long countDelta() {
            return afterCount - beforeCount;
        }

        public long shallowDelta() {
            return afterShallow - beforeShallow;
        }

        /** 实例数增长率（%）；基线为 0 时返回 -1（新出现的类）。 */
        public double countPercent() {
            return beforeCount <= 0 ? -1.0 : countDelta() * 100.0 / beforeCount;
        }

        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("实例 ").append(Fmt.num(beforeCount)).append(" → ").append(Fmt.num(afterCount));
            sb.append("（+").append(Fmt.num(countDelta())).append("）");
            if (countPercent() >= 0) {
                sb.append("，+").append(String.format("%.1f", countPercent())).append("%");
            }
            sb.append("；浅堆 ").append(Fmt.mb(beforeShallow))
              .append(" → ").append(Fmt.mb(afterShallow));
            return sb.toString();
        }
    }
}
