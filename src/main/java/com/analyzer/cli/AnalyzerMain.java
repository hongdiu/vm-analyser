package com.analyzer.cli;

import com.analyzer.analysis.ClassLoaderLeakDetector;
import com.analyzer.analysis.CollectionGrowthDetector;
import com.analyzer.analysis.Detector;
import com.analyzer.analysis.DetectorContext;
import com.analyzer.analysis.DominatorCalculator;
import com.analyzer.analysis.DominatorResult;
import com.analyzer.analysis.Finding;
import com.analyzer.analysis.LargeObjectDetector;
import com.analyzer.analysis.LeakPatternMatcher;
import com.analyzer.analysis.LeakSuspectDetector;
import com.analyzer.analysis.ThreadLocalLeakDetector;
import com.analyzer.graph.ClassInfo;
import com.analyzer.graph.GcRootPathAnalyzer;
import com.analyzer.graph.HeapGraph;
import com.analyzer.parser.ConsoleProgressListener;
import com.analyzer.parser.HprofParserAdapter;
import com.analyzer.report.HtmlReporter;
import com.analyzer.report.JsonReporter;
import com.analyzer.report.ReportData;
import com.analyzer.util.Fmt;
import org.eclipse.mat.snapshot.SnapshotInfo;
import org.eclipse.mat.snapshot.model.IClass;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 命令行入口。
 *
 * <pre>
 * java -jar jvm-heap-analyzer.jar -i dump.hprof -o report/ \
 *      --top 20 --max-path 8 --min-retained-mb 5
 * </pre>
 *
 * <p>主流程：解析 hprof → 建图 → 支配树/Retained → 依次跑检测器 →
 * 输出 index.html / summary.json / assets。</p>
 */
@Command(name = "jvm-heap-analyzer",
        mixinStandardHelpOptions = true,
        version = "jvm-heap-analyzer 1.0.0",
        description = "Offline JVM hprof heap dump analyzer (powered by Eclipse MAT)")
public final class AnalyzerMain implements Callable<Integer> {

    @Option(names = {"-i", "--input"}, required = true,
            description = "input .hprof heap dump file")
    private File input;

    @Option(names = {"-o", "--output"}, required = true,
            description = "output report directory (created if missing)")
    private File output;

    @Option(names = "--top", defaultValue = "20",
            description = "max entries per detection section (default: ${DEFAULT-VALUE})")
    private int top;

    @Option(names = "--max-path", defaultValue = "8",
            description = "max reference-chain display depth (default: ${DEFAULT-VALUE})")
    private int maxPath;

    @Option(names = "--min-retained-mb", defaultValue = "5",
            description = "min retained heap (MB) for leak suspects (default: ${DEFAULT-VALUE})")
    private double minRetainedMb;

    @Option(names = "--verbose", description = "verbose MAT parse progress")
    private boolean verbose;

    @Override
    public Integer call() {
        long t0 = System.currentTimeMillis();
        if (!input.isFile()) {
            System.err.println("[error] input hprof not found: " + input);
            return 2;
        }
        if (top <= 0 || maxPath <= 0 || minRetainedMb <= 0) {
            System.err.println("[error] --top/--max-path/--min-retained-mb must be positive");
            return 2;
        }

        Path outDir = output.toPath();
        // 阶段心跳：任何长时间静默的阶段（MAT 解析、支配树、检测器、报告）
        // 每 30 秒打印当前阶段与已耗时，使用方始终能判断"在干活还是卡死"。
        java.util.concurrent.atomic.AtomicReference<String> phase =
                new java.util.concurrent.atomic.AtomicReference<>("warmup");
        final long tStart = t0;
        Thread progressBeat = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(30_000L);
                } catch (InterruptedException e) {
                    return;
                }
                System.out.println("[progress] " + phase.get() + " | "
                        + (System.currentTimeMillis() - tStart) / 1000 + "s elapsed");
            }
        }, "phase-heartbeat");
        progressBeat.setDaemon(true);
        progressBeat.start();
        try {
            java.nio.file.Files.createDirectories(outDir);

            phase.set("parsing hprof with Eclipse MAT");
            System.out.println("[1/4] parsing hprof with Eclipse MAT (indexed, may take a while)...");
            System.out.println("      -> " + input.getAbsolutePath()
                    + "  (" + Fmt.mb(input.length()) + " file)");
            DominatorResult domResult;
            ReportData data = new ReportData();
            List<Finding> suspects;
            try (HprofParserAdapter adapter =
                         HprofParserAdapter.open(input, new ConsoleProgressListener(verbose))) {

                HeapGraph graph = new HeapGraph(adapter.snapshot());
        long openMs = System.currentTimeMillis() - t0;
        System.out.println("      parse/open done in " + openMs + "ms");

                phase.set("building dominator tree");
                System.out.println("[2/4] building dominator tree & retained sizes...");
                DominatorCalculator calculator = new DominatorCalculator();
                domResult = calculator.compute(graph, verbose);
                if (!domResult.ownTree()) {
                    System.out.println("      note: MAT index mode - the first retained query"
                            + " will build the dominator index (can take minutes, watch [progress])");
                }

                phase.set("running detectors");
                System.out.println("[3/4] running detectors...");
                GcRootPathAnalyzer pathAnalyzer = new GcRootPathAnalyzer(graph);
                long minRetainedBytes = (long) (minRetainedMb * Fmt.MB);
                DetectorContext ctx = new DetectorContext(graph, domResult, pathAnalyzer,
                        new LeakPatternMatcher(), top, maxPath, minRetainedBytes);

                data.overview = buildOverview(graph, domResult, data);
                data.topClasses = buildTopClasses(graph, domResult, top);

                // 统一接口调度各检测器；单个检测器异常不阻断整体报告
                List<Detector> detectors = List.of(
                        new LeakSuspectDetector(),
                        new LargeObjectDetector(),
                        new CollectionGrowthDetector(),
                        new ThreadLocalLeakDetector(),
                        new ClassLoaderLeakDetector());
                Map<String, List<Finding>> bySection = new LinkedHashMap<>();
                for (Detector det : detectors) {
                    long s0 = System.currentTimeMillis();
                    String detName = det.getClass().getSimpleName();
                    phase.set("detector: " + detName);
                    System.out.println("      > " + detName + " running...");
                    List<Finding> findings;
                    try {
                        findings = det.detect(ctx);
                    } catch (Exception e) {
                        System.out.println("      [warn] detector "
                                + det.getClass().getSimpleName() + " failed: " + e);
                        findings = List.of();
                    }
                    bySection.put(det.name(), findings);
                    // 控制台用 ASCII 标识（中文节名在报告里），避免 GBK 终端乱码
                    System.out.println("      - " + det.getClass().getSimpleName()
                            + ": " + findings.size() + " findings ("
                            + (System.currentTimeMillis() - s0) + "ms)");
                }
                suspects = bySection.getOrDefault("内存泄漏嫌疑", List.of());
                data.leakSuspects = suspects;
                data.largeObjects = bySection.getOrDefault("大对象列表", List.of());
                data.collections = bySection.getOrDefault("可疑集合", List.of());
                data.threadLocals = bySection.getOrDefault("ThreadLocal 持有分析", List.of());
                data.classLoaders = bySection.getOrDefault("ClassLoader 异常", List.of());
            }

            phase.set("writing report");
            System.out.println("[4/4] writing report to " + outDir.toAbsolutePath() + " ...");
            new JsonReporter().write(outDir, data);
            new HtmlReporter(maxPath).write(outDir, data);

            System.out.println();
            System.out.println("== summary ==");
            System.out.println("used heap      : " + Fmt.mb(data.overview.usedHeapBytes));
            System.out.println("objects        : " + Fmt.num(data.overview.objectCount)
                    + " (reachable " + Fmt.num(data.overview.reachableCount) + ")");
            System.out.println("classes        : " + Fmt.num(data.overview.classCount));
            System.out.println("gc roots       : " + Fmt.num(data.overview.gcRootCount));
            System.out.println("leak suspects  : " + suspects.size()
                    + (suspects.isEmpty() ? ""
                    : "  (top score " + String.format("%.1f", suspects.get(0).score) + ")"));
            System.out.println("elapsed        : " + (System.currentTimeMillis() - t0) + "ms");
            System.out.println("open " + outDir.resolve("index.html").toAbsolutePath()
                    + " in a browser.");
            return 0;
        } catch (Exception e) {
            System.err.println("[error] analysis failed: " + e.getMessage());
            e.printStackTrace();
            return 1;
        } finally {
            progressBeat.interrupt();
        }
    }

    // ------------------------------------------------------------------

    private ReportData.Overview buildOverview(HeapGraph graph, DominatorResult dom,
                                               ReportData data) throws Exception {
        SnapshotInfo info = graph.info();
        ReportData.Overview o = data.overview;
        o.inputPath = input.getAbsolutePath();
        o.jvmInfo = trim(info.getJvmInfo(), 300);
        o.dumpTime = info.getCreationDate() == null ? "-"
                : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(info.getCreationDate());
        o.usedHeapBytes = info.getUsedHeapSize();
        o.objectCount = info.getNumberOfObjects();
        o.classCount = info.getNumberOfClasses();
        o.gcRootCount = info.getNumberOfGCRoots();
        o.identifierSize = info.getIdentifierSize();
        o.reachableCount = dom.reachableCount();
        o.dominatorMode = dom.ownTree() ? "own" : "mat-fallback";
        o.generatedAt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        o.options = new LinkedHashMap<>();
        o.options.put("--top", String.valueOf(top));
        o.options.put("--max-path", String.valueOf(maxPath));
        o.options.put("--min-retained-mb", String.valueOf(minRetainedMb));
        return o;
    }

    /** Top 内存占用类：按支配树聚合的类 Retained 降序。 */
    private List<ClassInfo> buildTopClasses(HeapGraph graph, DominatorResult dom, int limit) {
        Map<Integer, Long> retained = dom.classRetained();
        List<ClassInfo> list = new ArrayList<>();
        for (Map.Entry<Integer, Long> e : retained.entrySet()) {
            int classId = e.getKey();
            ClassInfo ci = new ClassInfo(classId, graph.classNameOfClass(classId));
            ci.retainedBytes = e.getValue();
            ci.numberOfObjects = dom.classCount().getOrDefault(classId, 0);
            ci.shallowBytes = dom.classShallow().getOrDefault(classId, 0L);
            // 类对象自身没有 definingLoader 字段时保持空串即可
            try {
                IClass c = graph.raw().getClassOf(classId);
                if (c != null && c.getClassLoaderId() >= 0) {
                    ci.definingLoader = graph.displayName(c.getClassLoaderId());
                }
            } catch (Exception ignore) {
                // 元数据缺失不影响榜单
            }
            list.add(ci);
        }
        list.sort(Comparator.comparingLong((ClassInfo c) -> c.retainedBytes).reversed());
        return list.size() > limit ? new ArrayList<>(list.subList(0, limit)) : list;
    }

    private static String trim(String s, int max) {
        if (s == null) return "-";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    public static void main(String[] args) {
        int code = new CommandLine(new AnalyzerMain()).execute(args);
        System.exit(code);
    }
}
