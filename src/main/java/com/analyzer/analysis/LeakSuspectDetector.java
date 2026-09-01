package com.analyzer.analysis;

import com.analyzer.graph.HeapInstance;
import com.analyzer.graph.HeapGraph;
import com.analyzer.report.RecommendationBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 泄漏嫌疑检测（Leak Suspects）。
 *
 * <p>思路与 MAT 的 Leak Suspects 报告一致：Retained Heap 的“大头”一定挂在
 * 支配树的顶层组件上，因此候选 = 支配树中虚拟根的直接孩子，按 Retained
 * 降序过滤阈值后取 Top N；再为每个候选求最短 GC Root 引用链，
 * 交给 {@link LeakPatternMatcher} 匹配模式与评分，最后补中文修复建议。</p>
 */
public final class LeakSuspectDetector implements Detector {

    @Override
    public String name() {
        return "内存泄漏嫌疑";
    }

    @Override
    public List<Finding> detect(DetectorContext ctx) {
        HeapGraph graph = ctx.graph;
        List<Finding> findings = new ArrayList<>();
        RecommendationBuilder recBuilder = new RecommendationBuilder();

        for (int topId : ctx.dominators.topLevelByRetained()) {
            if (findings.size() >= ctx.top) {
                break;
            }
            long retained = ctx.dominators.retained(topId);
            if (retained < ctx.minRetainedBytes) {
                // 已按 retained 降序，后面的只会更小
                break;
            }
            HeapInstance inst = new HeapInstance(topId, graph.classOf(topId),
                    graph.className(topId), graph.displayName(topId), graph.shallowSize(topId));
            inst.retainedBytes = retained;
            inst.gcRoot = graph.isGcRoot(topId);
            inst.gcRootType = graph.gcRootType(topId);

            Finding f = new Finding(inst.objectId, inst.className, inst.displayName,
                    inst.shallowBytes, inst.retainedBytes);
            f.detectorName = name();
            // BFS 最短引用链（不可达对象为 null，模式匹配器会降级处理）
            f.path = ctx.pathAnalyzer.shortestPathToGcRoot(topId);
            ctx.patternMatcher.apply(f, ctx);
            recBuilder.apply(f);
            f.details.put("支配树顶层组件", "是");
            findings.add(f);
        }
        // 按评分排序，最可疑的排前面
        findings.sort((a, b) -> Double.compare(b.score, a.score));
        return findings;
    }
}
