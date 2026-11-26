package com.analyzer.cli;

import com.analyzer.analysis.AccumulationAnalyzer;
import com.analyzer.analysis.ClassLoaderLeakDetector;
import com.analyzer.analysis.CodeLocation;
import com.analyzer.analysis.CodeLocationExtractor;
import com.analyzer.analysis.CollectionGrowthDetector;
import com.analyzer.analysis.Detector;
import com.analyzer.analysis.DetectorContext;
import com.analyzer.analysis.DominatorCalculator;
import com.analyzer.analysis.DominatorResult;
import com.analyzer.analysis.Finding;
import com.analyzer.analysis.GrowthAnalyzer;
import com.analyzer.analysis.GrowthReport;
import com.analyzer.analysis.LargeObjectDetector;
import com.analyzer.analysis.LeakPatternMatcher;
import com.analyzer.analysis.LeakSuspectDetector;
import com.analyzer.analysis.ThreadLocalLeakDetector;
import com.analyzer.graph.ClassInfo;
import com.analyzer.graph.GcRootPathAnalyzer;
import com.analyzer.graph.HeapGraph;
import com.analyzer.graph.ThreadStackIndex;
import com.analyzer.parser.ConsoleProgressListener;
import com.analyzer.parser.HprofParserAdapter;
import com.analyzer.report.HtmlReporter;
import com.analyzer.report.JsonReporter;
import com.analyzer.report.ReportData;
import com.analyzer.util.Fmt;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.SnapshotInfo;
import org.eclipse.mat.snapshot.model.IClass;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    @Option(names = "--app-prefix", defaultValue = "",
            description = "comma-separated business class prefixes; used to pick app frames "
                    + "from thread stacks (default: ${DEFAULT-VALUE})")
    private String appPrefix;

    @Option(names = "--baseline",
            description = "optional earlier hprof; compares per-class instance/shallow growth "
                    + "(the only hard evidence of a leak)")
    private String baselinePath;

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
                data.overview = buildOverview(graph, domResult, data);

                long minRetainedBytes = (long) (minRetainedMb * Fmt.MB);
                DetectorContext ctx = new DetectorContext(graph, domResult, pathAnalyzer,
                        new LeakPatternMatcher(), top, maxPath, minRetainedBytes);
                // 大堆降级时，用有界支配下钻找出真正的累积点（替代“线程/ClassLoader 本身”）
                ThreadStackIndex stackIndex = new ThreadStackIndex(adapter.snapshot());
                AccumulationAnalyzer accumulation = new AccumulationAnalyzer(
                        graph, domResult, stackIndex, minRetainedBytes, top, verbose);

                // 统一接口调度各检测器；单个检测器异常不阻断整体报告
                List<Detector> detectors = List.of(
                        new LeakSuspectDetector(accumulation),
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

                // 证据补全：把引用链上的 Java Local / 线程栈 / 静态字段翻译成“代码位置”，
                // 并保留原始栈帧供报告展开核对。必须在快照关闭前完成。
                CodeLocationExtractor locationExtractor = new CodeLocationExtractor(
                        graph, stackIndex, domResult, data.overview.usedHeapBytes,
                        CodeLocationExtractor.parsePrefixes(appPrefix));
                for (List<Finding> list : bySection.values()) {
                    for (Finding f : list) {
                        locationExtractor.apply(f);
                    }
                }
                long withCodeLine = bySection.values().stream()
                        .flatMap(List::stream)
                        .filter(f -> {
                            com.analyzer.analysis.CodeLocation loc = f.primaryCodeLocation();
                            return loc != null && loc.hasSourceLine();
                        })
                        .count();
                System.out.println("      - evidence: " + withCodeLine
                        + " findings with source-code location");

                // 首屏结论去重：累积点会给出“线程根 → 累积点 → 其大子对象”同一链上的多个对象，
                // 它们共用同一个业务帧，直接取前 3 条会让报告看起来是重复结论。
                data.conclusions = distinctConclusions(data.leakSuspects, 3);

                // Top 类榜单放在检测之后：降级模式没有类 retained，
                // 需要借用累积点下钻得到的近似类统计，避免报告②整块为空。
                data.topClasses = buildTopClasses(graph, domResult, top, accumulation);

                // 基线对比：提供第二份更早的 dump 时，给出“哪些类在涨”的硬证据
                compareBaseline(adapter.snapshot(), data, bySection);
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

    /**
     * 首屏结论去重：同一代码位置只保留排名最高的一条，其余并入证据。
     *
     * <p>原因：累积点分析对同一个线程会给出“线程根 → 下钻累积点 → 其大子对象”
     * 一链上的多个对象（如 Thread、StatefulPersistenceContext、Object[]），
     * 它们共用同一个线程栈业务帧。若直接取前 3 条，首屏就会出现三张
     * “代码线索完全相同”的卡片。这里按 {@code 位置类型 + display()}
     * 归并（跨线程同样归并，合并说明里保留线程名），腾出的名额留给
     * 后面真正不同位置的结论。</p>
     */
    static List<Finding> distinctConclusions(List<Finding> suspects, int limit) {
        List<Finding> out = new ArrayList<>();
        if (suspects == null) {
            return out;
        }
        Map<String, Finding> byLocation = new HashMap<>();
        for (Finding f : suspects) {
            if (out.size() >= limit) {
                break;
            }
            String key = codeLocationKey(f);
            if (key == null) {
                out.add(f);
                continue;
            }
            Finding kept = byLocation.get(key);
            if (kept == null) {
                byLocation.put(key, f);
                out.add(f);
            } else {
                kept.details.merge("同一代码位置的其他对象", describeSameLocation(f),
                        (a, b) -> a + "；" + b);
                // 保留更具体的模式与建议（如线程根未归类、其累积点命中“结果集全量累积”），
                // 否则合并后会丢掉最有行动价值的那条诊断。
                if (LeakPatternMatcher.NONE.equals(kept.pattern)
                        && !LeakPatternMatcher.NONE.equals(f.pattern)) {
                    kept.pattern = f.pattern;
                    kept.patternLabel = f.patternLabel;
                    for (String rec : f.recommendations) {
                        if (!kept.recommendations.contains(rec)) {
                            kept.recommendations.add(rec);
                        }
                    }
                }
            }
        }
        return out;
    }

    /** 去重键：位置类型 + 代码位置；无法定位到代码位置的结论不参与归并。 */
    private static String codeLocationKey(Finding f) {
        CodeLocation loc = f.primaryCodeLocation();
        if (loc == null || loc.className == null || loc.className.isEmpty()) {
            return null;
        }
        return loc.kind + "|" + loc.display();
    }

    /** 被归并对象的简述，写进保留结论的证据里，避免信息丢失。 */
    private static String describeSameLocation(Finding f) {
        StringBuilder sb = new StringBuilder(f.className);
        if (f.threadName != null) {
            sb.append("（线程 ").append(f.threadName).append("）");
        }
        sb.append("，Retained ").append(Fmt.mb(f.retainedBytes));
        if (f.patternLabel != null && !"未归类".equals(f.patternLabel)) {
            sb.append("，模式：").append(f.patternLabel);
        }
        return sb.toString();
    }

    /**
     * 基线对比：打开更早的 dump，按类给出实例数/浅堆增量，并把增量证据
     * 挂到对应结论的 details 上。失败只告警，不影响主报告。
     */
    private void compareBaseline(ISnapshot current, ReportData data,
                                 Map<String, List<Finding>> bySection) {
        if (baselinePath == null || baselinePath.isBlank()) {
            return;
        }
        Path bp = Path.of(baselinePath).toAbsolutePath();
        if (!Files.exists(bp)) {
            System.err.println("[warn] baseline hprof not found: " + bp);
            return;
        }
        System.out.println("[growth] comparing against baseline " + bp + " ...");
        boolean sameFile = bp.equals(input.toPath().toAbsolutePath());
        HprofParserAdapter baselineAdapter = null;
        try {
            ISnapshot baselineSnapshot = current;
            if (!sameFile) {
                baselineAdapter = HprofParserAdapter.open(
                        bp.toFile(), new ConsoleProgressListener(verbose));
                baselineSnapshot = baselineAdapter.snapshot();
            }
            GrowthReport report = GrowthAnalyzer.compare(baselineSnapshot, current, 20);
            report.baselinePath = bp.toString();
            data.growth = report;
            System.out.println("      - baseline: " + report.rows.size() + " growing classes");

            Map<String, GrowthReport.Row> byName = new HashMap<>();
            for (GrowthReport.Row row : report.rows) {
                byName.put(row.className, row);
            }
            Set<Finding> seen = new HashSet<>();
            for (List<Finding> list : bySection.values()) {
                for (Finding f : list) {
                    if (!seen.add(f)) {
                        continue;
                    }
                    GrowthReport.Row row = byName.get(f.className);
                    if (row != null) {
                        f.details.put("基线对比", row.describe());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[warn] baseline compare failed: " + e.getMessage());
        } finally {
            if (baselineAdapter != null) {
                baselineAdapter.close();
            }
        }
    }

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
        o.options.put("--app-prefix", appPrefix == null || appPrefix.isBlank() ? "(未指定)" : appPrefix);
        o.options.put("--baseline", baselinePath == null || baselinePath.isBlank() ? "(未提供)" : baselinePath);
        return o;
    }

    /** Top 内存占用类：按支配树聚合的类 Retained 降序；降级模式用累积点统计近似。 */
    private List<ClassInfo> buildTopClasses(HeapGraph graph, DominatorResult dom, int limit,
                                            AccumulationAnalyzer accumulation) {
        Map<Integer, Long> retained = dom.classRetained();
        if (retained.isEmpty()) {
            return buildTopClassesFallback(graph, accumulation, limit);
        }
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

    /**
     * 降级模式的 Top 类：retained 来自“热点根下直接子树”的近似归账，
     * 实例数/Shallow 用 MAT 类元数据补齐，保证表格有内容且口径可解释。
     */
    private List<ClassInfo> buildTopClassesFallback(HeapGraph graph,
                                                    AccumulationAnalyzer accumulation,
                                                    int limit) {
        List<ClassInfo> list = new ArrayList<>();
        if (accumulation == null) {
            return list;
        }
        for (Map.Entry<String, long[]> e : accumulation.fallbackClassStats().entrySet()) {
            ClassInfo ci = new ClassInfo(-1, e.getKey());
            ci.retainedBytes = e.getValue()[0];
            ci.numberOfObjects = (int) Math.min(Integer.MAX_VALUE, e.getValue()[1]);
            try {
                var classes = graph.raw().getClassesByName(e.getKey(), false);
                if (classes != null && !classes.isEmpty()) {
                    IClass c = classes.iterator().next();
                    ci.numberOfObjects = c.getNumberOfObjects();
                    ci.shallowBytes = c.getNumberOfObjects() * c.getHeapSizePerInstance();
                }
            } catch (Exception ignore) {
                // 类名取不到元数据时保留子树计数
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
