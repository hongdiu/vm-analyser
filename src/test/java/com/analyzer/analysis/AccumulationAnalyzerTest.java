package com.analyzer.analysis;

import com.analyzer.graph.HeapGraph;
import com.analyzer.parser.ConsoleProgressListener;
import com.analyzer.parser.HprofParserAdapter;
import com.analyzer.util.Fmt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 降级模式（MAT 内置支配索引）下的累积点分析测试。
 *
 * <p>大堆转储（对象数超过阈值）不会自建支配树，原来的顶层组件退化成
 * GC Root 列表，导致报告只能说“线程持有 N GB”。本用例直接构造 mat 模式的
 * {@link DominatorResult}，验证 {@link AccumulationAnalyzer} 能下钻出
 * 真正的累积点、类级统计非空，且不发生越界/异常。</p>
 */
class AccumulationAnalyzerTest {

    static Path sampleHprof;
    static HprofParserAdapter adapter;
    static HeapGraph graph;

    @BeforeAll
    static void openSample() throws Exception {
        Path tmp = Files.createTempDirectory("jvm-acc-test");
        sampleHprof = tmp.resolve("sample-leak.hprof");
        try (var in = AccumulationAnalyzerTest.class.getResourceAsStream("/sample-leak.hprof")) {
            assertNotNull(in, "missing test resource sample-leak.hprof");
            Files.copy(in, sampleHprof, StandardCopyOption.REPLACE_EXISTING);
        }
        adapter = HprofParserAdapter.open(sampleHprof.toFile(),
                new ConsoleProgressListener(false));
        graph = new HeapGraph(adapter.snapshot());
    }

    @AfterAll
    static void closeSample() {
        if (adapter != null) {
            adapter.close();
        }
    }

    @Test
    @DisplayName("降级模式：能从 GC Root 下钻出累积点，并给出非空类统计")
    void drillsIntoAccumulationPoints() throws Exception {
        DominatorResult fallback = DominatorResult.matMode(adapter.snapshot());
        fallback.buildTopLevelMat(graph.gcRoots());
        assertFalse(fallback.ownTree());

        AccumulationAnalyzer analyzer = new AccumulationAnalyzer(
                graph, fallback, new com.analyzer.graph.ThreadStackIndex(adapter.snapshot()),
                (long) (2 * Fmt.MB), 10, false);
        List<AccumulationAnalyzer.Candidate> candidates = analyzer.candidates();
        assertFalse(candidates.isEmpty(), "fallback candidates must not be empty");
        assertTrue(candidates.stream().anyMatch(c -> c.depth > 0
                        && c.retainedBytes >= 2 * Fmt.MB),
                "should drill below top-level roots to an accumulation point");
        assertFalse(analyzer.fallbackClassStats().isEmpty(),
                "fallback class stats must be non-empty (fixes empty Top classes)");

        AccumulationAnalyzer.Candidate top = candidates.get(0);
        assertTrue(top.retainedBytes > 0);
        assertNotNull(analyzer.expand(top.objectId, top.retainedBytes));
    }
}
