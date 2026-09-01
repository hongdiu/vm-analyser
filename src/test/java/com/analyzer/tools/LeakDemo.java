package com.analyzer.tools;

import java.lang.management.ManagementFactory;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 泄漏样本生成器（仅测试辅助用，不属于分析器运行时）。
 *
 * <p>用法：{@code java -cp ... com.analyzer.tools.LeakDemo out.hprof}
 * 在小型 JVM（建议 -Xmx128m）里制造四种典型问题后，
 * 通过 HotSpotDiagnosticMXBean 直接 dump 堆：
 * ① 静态 Map 持续增长；② 大 byte[]；③ 线程池场景下的 ThreadLocal 未 remove；
 * ④ 同型自定义 ClassLoader 多实例。</p>
 */
public final class LeakDemo {

    /** 静态集合泄漏标记（测试用它定位嫌疑对象）。 */
    public static final String STATIC_MAP_MARKER = "jvm-ha-static-map-marker";
    /** 大数组标记字符串。 */
    public static final String BIG_ARRAY_MARKER = "jvm-ha-big-array-contents";
    /** ThreadLocal 泄漏标记。 */
    public static final String THREAD_LOCAL_MARKER = "jvm-ha-threadlocal-value";

    // ① 静态集合：只增不减
    static final HashMap<String, byte[]> GLOBAL_CACHE = new HashMap<>();

    // ③ ThreadLocal：模拟线程池工作线程，用完不 remove
    static final ThreadLocal<List<String>> CTX = new ThreadLocal<>();

    /** ④ 自定义 ClassLoader（同型 new 多个实例，且被静态注册表“遗忘”持有）。 */
    static final class PluginClassLoader extends URLClassLoader {
        PluginClassLoader() {
            super(new URL[0], LeakDemo.class.getClassLoader());
        }

        /** 用给定的字节码在“本加载器”里定义一个独立的类（真实字节码见 SamplePlugin.class）。 */
        Class<?> define(byte[] bytes) {
            return defineClass("com.analyzer.tools.SamplePlugin", bytes, 0, bytes.length);
        }
    }

    /** 模拟插件注册表：加载器实例被静态持有且从不释放 → ClassLoader 泄漏形态。 */
    static final List<ClassLoader> LEAKED_LOADERS = new ArrayList<>();

    /** 并发容器变体（覆盖 CollectionGrowthDetector）。 */
    static final ConcurrentHashMap<String, byte[]> CONCURRENT_HOLDER = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "leak-sample.hprof";

        // ① 静态 Map 塞 60 个条目（每条约 200KB → ~12MB，够触发默认 5MB 阈值）
        for (int i = 0; i < 60; i++) {
            GLOBAL_CACHE.put(STATIC_MAP_MARKER + "-" + i, new byte[200 * 1024]);
        }

        // ② 大 byte[]（8MB，超过大对象 1MB 门槛）
        byte[] big = new byte[8 * 1024 * 1024];
        big[0] = 1;
        // 挂到另一个静态集合上，保证可达
        GLOBAL_CACHE.put(BIG_ARRAY_MARKER, big);

        // ③ ThreadLocal 在本线程构造（main 线程即“工作线程”）
        List<String> ctx = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            ctx.add(THREAD_LOCAL_MARKER + "-" + i);
        }
        CTX.set(ctx);
        // 再开一个线程制造同样的 ThreadLocal 持有时，线程结束后不清理但线程“活着”（park 住），
        // 模拟线程池泄漏；用非守护线程 + join 前 dump。
        Thread leaked = new Thread(() -> {
            CTX.set(new ArrayList<>(ctx));
            try {
                java.util.concurrent.locks.LockSupport.park(); // 阻塞不退出，Thread 保持存活
            } catch (Throwable ignore) {
            }
        }, "leaky-pool-worker-1");
        leaked.setDaemon(false);
        leaked.start();
        Thread.sleep(200); // 等它跑起来

        // ④ 同型 ClassLoader × 3：各自 define 一个独立类并常驻静态注册表
        byte[] pluginBytes;
        try (var in = SamplePlugin.class.getResourceAsStream("/com/analyzer/tools/SamplePlugin.class")) {
            pluginBytes = in.readAllBytes();
        }
        for (int i = 0; i < 3; i++) {
            PluginClassLoader cl = new PluginClassLoader();
            cl.define(pluginBytes); // 独立类对象 → 加载器真实持有类元数据
            LEAKED_LOADERS.add(cl); // “忘记”释放：典型的插件泄漏形态
        }

        // 给集合一个并发容器变体，覆盖 CollectionGrowthDetector（静态持有确保 dump 时存活）
        ConcurrentHashMap<String, byte[]> conc = CONCURRENT_HOLDER;
        for (int i = 0; i < 40; i++) {
            conc.put("k" + i, new byte[150 * 1024]);
        }
        GLOBAL_CACHE.put("conc-" + i(conc), new byte[8]);

        System.gc();
        Thread.sleep(300);

        // 通过 JMX 直接 dump 本进程堆（无需 jmap 附加进程）
        com.sun.management.HotSpotDiagnosticMXBean bean =
                ManagementFactory.getPlatformMXBean(
                        com.sun.management.HotSpotDiagnosticMXBean.class);
        bean.dumpHeap(new java.io.File(out).getAbsolutePath(), true);
        System.out.println("dumped -> " + new java.io.File(out).getAbsolutePath());
        // 非守护线程 leaked 还 park 着，进程不会自然退出——dump 完成后强退即可
        System.exit(0);
    }

    private static String i(Object o) {
        return Integer.toHexString(System.identityHashCode(o));
    }
}
