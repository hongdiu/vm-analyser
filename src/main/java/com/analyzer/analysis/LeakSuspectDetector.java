package com.analyzer.analysis;

import com.analyzer.graph.HeapGraph;
import com.analyzer.graph.HeapInstance;
import com.analyzer.graph.ThreadStackIndex;
import com.analyzer.report.RecommendationBuilder;
import com.analyzer.util.Fmt;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 泄漏嫌疑检测（Leak Suspects）。
 *
 * <p>思路与 MAT 的 Leak Suspects 报告一致：Retained Heap 的“大头”一定挂在
 * 支配树的顶层组件上。但仅停留在顶层组件是不够的：大堆降级模式下顶层组件
 * 退化为 GC Root（线程/ClassLoader），报告只能说“这个线程持有 3.5GB”。
 * 因此候选交给 {@link AccumulationAnalyzer}：</p>
 * <ul>
 *   <li>自建树模式：沿用顶层组件，并向下展开一层给出“累积对象”；</li>
 *   <li>降级模式：沿支配关系有界下钻，用真正的累积点替代线程/加载器本身。</li>
 * </ul>
 * <p>之后仍为每个候选求最短 GC Root 引用链，交给 {@link LeakPatternMatcher}
 * 匹配模式与评分，最后补中文修复建议。</p>
 */
public final class LeakSuspectDetector implements Detector {

    private final AccumulationAnalyzer accumulationAnalyzer;

    public LeakSuspectDetector() {
        this(null);
    }

    /** 传入共享的累积点分析器（避免主流程与检测器各扫一遍大堆）。 */
    public LeakSuspectDetector(AccumulationAnalyzer accumulationAnalyzer) {
        this.accumulationAnalyzer = accumulationAnalyzer;
    }

    @Override
    public String name() {
        return "内存泄漏嫌疑";
    }

    @Override
    public List<Finding> detect(DetectorContext ctx) {
        HeapGraph graph = ctx.graph;
        List<Finding> findings = new ArrayList<>();
        RecommendationBuilder recBuilder = new RecommendationBuilder();
        AccumulationAnalyzer accumulation = accumulationAnalyzer != null
                ? accumulationAnalyzer
                : new AccumulationAnalyzer(graph, ctx.dominators,
                        new ThreadStackIndex(graph.raw()),
                        ctx.minRetainedBytes, ctx.top, false);

        for (AccumulationAnalyzer.Candidate candidate : accumulation.candidates()) {
            if (findings.size() >= ctx.top) {
                break;
            }
            int objectId = candidate.objectId;
            long retained = candidate.retainedBytes;
            if (retained < ctx.minRetainedBytes) {
                // 已按 retained 降序，后面的只会更小
                break;
            }
            HeapInstance inst = new HeapInstance(objectId, graph.classOf(objectId),
                    graph.className(objectId), graph.displayName(objectId), graph.shallowSize(objectId));
            inst.retainedBytes = retained;
            inst.gcRoot = graph.isGcRoot(objectId);
            inst.gcRootType = graph.gcRootType(objectId);

            Finding f = new Finding(inst.objectId, inst.className, inst.displayName,
                    inst.shallowBytes, inst.retainedBytes);
            f.detectorName = name();
            // BFS 最短引用链（不可达对象为 null，模式匹配器会降级处理）
            f.path = ctx.pathAnalyzer.shortestPathToGcRoot(objectId);
            ctx.patternMatcher.apply(f, ctx);
            recBuilder.apply(f);

            if (candidate.isRoot()) {
                f.details.put("支配树顶层组件", "是");
            } else {
                f.details.put("支配树顶层组件", "否（累积点）");
                f.details.put("累积点", "距持有根 " + candidate.depth + " 层；持有根 retained "
                        + Fmt.mb(candidate.rootRetainedBytes));
                f.details.put("持有根", graph.displayName(candidate.rootId));
            }

            // 向下展开：它持有什么、多少个、合计多少
            List<Accumulated> accumulated = accumulation.expand(objectId, retained);
            if (!accumulated.isEmpty()) {
                f.accumulated.addAll(accumulated);
                f.details.put("累积对象", accumulated.stream()
                        .map(Accumulated::describe)
                        .collect(Collectors.joining("；")));
            }
            findings.add(f);
        }
        // 按评分排序，最可疑的排前面
        findings.sort((a, b) -> Double.compare(b.score, a.score));
        return findings;
    }
}
