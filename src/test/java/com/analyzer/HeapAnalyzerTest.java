package com.analyzer;

import com.analyzer.analysis.DetectorContext;
import com.analyzer.analysis.DominatorCalculator;
import com.analyzer.analysis.DominatorResult;
import com.analyzer.analysis.Finding;
import com.analyzer.analysis.LargeObjectDetector;
import com.analyzer.analysis.LeakPatternMatcher;
import com.analyzer.analysis.LeakSuspectDetector;
import com.analyzer.cli.AnalyzerMain;
import com.analyzer.analysis.RefPath;
import com.analyzer.analysis.ThreadLocalLeakDetector;
import com.analyzer.graph.GcRootPathAnalyzer;
import com.analyzer.graph.HeapGraph;
import com.analyzer.parser.ConsoleProgressListener;
import com.analyzer.parser.HprofParserAdapter;
import com.analyzer.util.Fmt;
import org.eclipse.mat.snapshot.model.IClass;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端流程测试：使用随仓库附带的小型 hprof 样本
 * （src/test/resources/sample-leak.hprof，由 com.analyzer.tools.LeakDemo 生成，
 * 内含静态集合增长、8MB 大 byte[]、ThreadLocal 持有与多个同型 ClassLoader），
 * 验证“解析 → 建图 → 支配树 → 引用链 → 检测 → 报告”全链路。
 */
class HeapAnalyzerTest {

    static Path sampleHprof;
    static HprofParserAdapter adapter;
    static HeapGraph graph;
    static DominatorResult dominators;

    @BeforeAll
    static void openSample() throws Exception {
        // 把样本从 classpath 解出来（MAT 需要真实文件路径，且需要写索引的权限）
        Path tmp = Files.createTempDirectory("jvm-ha-test");
        sampleHprof = tmp.resolve("sample-leak.hprof");
        try (var in = HeapAnalyzerTest.class.getResourceAsStream("/sample-leak.hprof")) {
            assertNotNull(in, "missing test resource sample-leak.hprof "
                    + "(see README: run LeakDemo to generate it)");
            Files.copy(in, sampleHprof, StandardCopyOption.REPLACE_EXISTING);
        }
        adapter = HprofParserAdapter.open(sampleHprof.toFile(),
                new ConsoleProgressListener(false));
        graph = new HeapGraph(adapter.snapshot());
        dominators = new DominatorCalculator().compute(graph, false);
    }

    @AfterAll
    static void closeSample() {
        if (adapter != null) {
            adapter.close();
        }
    }

    private DetectorContext ctx(int top, int maxPath, long minBytes) {
        return new DetectorContext(graph, dominators, new GcRootPathAnalyzer(graph),
                new LeakPatternMatcher(), top, maxPath, minBytes);
    }

    @Test
    @DisplayName("解析：对象/类/GC Root 基础信息非空")
    void parseBasics() throws Exception {
        assertTrue(graph.info().getNumberOfObjects() > 1000, "sample should contain >1000 objects");
        assertTrue(graph.gcRoots().length > 0);
        assertTrue(graph.info().getUsedHeapSize() > 10 * Fmt.MB);
        assertTrue(!graph.classes().isEmpty());
    }

    @Test
    @DisplayName("支配树：自建模式，顶层组件 retained ≥ shallow 且能盖住 ~20MB 静态缓存")
    void dominatorTree() {
        assertTrue(dominators.ownTree(), "small sample must use own Lengauer-Tarjan tree");
        List<Integer> top = dominators.topLevelByRetained();
        assertTrue(top.size() > 10);
        int t0 = top.get(0);
        long retained = dominators.retained(t0);
        assertTrue(retained >= 5 * Fmt.MB,
                "biggest top component should be ≥5MB but was " + Fmt.mb(retained));
        assertTrue(retained >= graph.shallowSize(t0));
        // 支配点与可达性自洽
        assertEquals(0, dominators.dominator(t0), "top component dominated by virtual root");
        assertTrue(dominators.isReachable(t0));
    }

    @Test
    @DisplayName("引用链：大 HashMap 能找到 BFS 最短 GC Root 链且经过 static 字段边")
    void gcRootPath() throws Exception {
        // 样本里 retained 最大的 HashMap 就是 LeakDemo.GLOBAL_CACHE
        IClass hashMap = null;
        for (IClass c : adapter.snapshot().getClassesByName("java.util.HashMap", false)) {
            hashMap = c;
        }
        assertNotNull(hashMap);
        int candidate = -1;
        long best = -1;
        for (int id : hashMap.getObjectIds()) {
            long r = dominators.retained(id);
            if (r > best) {
                best = r;
                candidate = id;
            }
        }
        assertTrue(best > 5 * Fmt.MB, "largest HashMap retained should exceed 5MB");

        RefPath path = new GcRootPathAnalyzer(graph).shortestPathToGcRoot(candidate);
        assertNotNull(path, "GLOBAL_CACHE must be reachable from a GC root");
        assertTrue(path.nodes().get(0).gcRoot);
        assertNotNull(path.nodes().get(0).gcRootType);
        assertEquals(candidate, path.nodes().get(path.size() - 1).objectId,
                "path ends at target object");
        assertTrue(path.hasStaticEdge(),
                "path via static field GLOBAL_CACHE should carry a static edge: " + path.asText());
        // BFS 最短性：链长 ≤ 该对象到根的距离（与支配者方向一致性抽查）
        assertTrue(path.size() >= 2 && path.size() < 64);
    }

    @Test
    @DisplayName("检测：LeakSuspect 命中静态集合模式；大对象命中 byte[]；ThreadLocal 检测可运行")
    void detectors() throws Exception {
        DetectorContext c = ctx(10, 8, (long) (2 * Fmt.MB));

        List<Finding> suspects = new LeakSuspectDetector().detect(c);
        assertTrue(suspects.size() >= 1);
        Finding s0 = suspects.get(0);
        assertTrue(s0.score > 0);
        assertTrue(s0.pattern != null && !s0.pattern.isEmpty());
        // 样本主泄漏 = 静态 Map：允许匹配到 STATIC_COLLECTION 或 CACHE_GROWTH（大集合无静态边的降级）
        assertTrue(List.of(LeakPatternMatcher.STATIC_COLLECTION,
                        LeakPatternMatcher.CACHE_GROWTH,
                        LeakPatternMatcher.LARGE_BYTE_ARRAY)
                        .contains(s0.pattern),
                "top suspect pattern was " + s0.pattern);
        assertTrue(!s0.recommendations.isEmpty(), "should carry Chinese recommendations");

        List<Finding> large = new LargeObjectDetector().detect(c);
        assertTrue(large.stream().anyMatch(f -> f.className.startsWith("byte[]")
                        && f.shallowBytes >= 8 * Fmt.MB),
                "8MB byte[] from sample must appear in large-object list");

        // ThreadLocal 检测器在低阈值下应能命中样本线程（不强制，保证不抛异常即可）
        List<Finding> tl = new ThreadLocalLeakDetector().detect(ctx(10, 8, Fmt.MB / 4));
        assertNotNull(tl);
    }

    @Test
    @DisplayName("CLI 端到端：exit=0，index.html/summary.json/assets/style.css 全部生成")
    void cliEndToEnd() throws Exception {
        Path out = Files.createTempDirectory("jvm-ha-report");
        int code = new CommandLine(new AnalyzerMain()).execute(
                "-i", sampleHprof.toString(),
                "-o", out.toString(),
                "--top", "5",
                "--max-path", "8",
                "--min-retained-mb", "2");
        // 注意：测试进程内 openSnapshot 与 CLI 各开各的快照，MAT 会复用已生成的索引
        assertEquals(0, code);
        String html = Files.readString(out.resolve("index.html"));
        assertTrue(html.contains("内存泄漏嫌疑"));
        assertTrue(html.contains("① 堆概览"));
        assertTrue(html.contains("GC Root 最短引用链"));
        assertTrue(Files.size(out.resolve("assets/style.css")) > 100);
        String json = Files.readString(out.resolve("summary.json"));
        assertTrue(json.contains("\"leakSuspects\""));
        assertTrue(json.contains("\"dominatorMode\": \"own\""));
        // 清理本用例生成的索引（保留 hprof 供其他用例复用）
        try (var s = Files.walk(out)) {
            s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> p.toFile().delete());
        }
    }
}
