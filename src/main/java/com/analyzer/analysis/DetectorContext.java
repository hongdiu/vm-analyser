package com.analyzer.analysis;

import com.analyzer.graph.GcRootPathAnalyzer;
import com.analyzer.graph.HeapGraph;

/**
 * 检测器共享上下文：把堆图、支配树结果、命令行阈值和引用链工具打包传入，
 * 避免每个 Detector 重复持有参数列表。
 */
public final class DetectorContext {

    public final HeapGraph graph;
    public final DominatorResult dominators;
    public final GcRootPathAnalyzer pathAnalyzer;
    public final LeakPatternMatcher patternMatcher;

    /** --top：每类结果最多展示条数。 */
    public final int top;
    /** --max-path：引用链展示深度。 */
    public final int maxPath;
    /** --min-retained-mb：嫌疑最小 Retained 阈值（字节）。 */
    public final long minRetainedBytes;

    public DetectorContext(HeapGraph graph,
                           DominatorResult dominators,
                           GcRootPathAnalyzer pathAnalyzer,
                           LeakPatternMatcher patternMatcher,
                           int top, int maxPath, long minRetainedBytes) {
        this.graph = graph;
        this.dominators = dominators;
        this.pathAnalyzer = pathAnalyzer;
        this.patternMatcher = patternMatcher;
        this.top = top;
        this.maxPath = maxPath;
        this.minRetainedBytes = minRetainedBytes;
    }

    /** 报告展示用的 MB 阈值文本。 */
    public String minRetainedMbText() {
        return String.format("%.2f MB", minRetainedBytes / (1024d * 1024d));
    }
}
