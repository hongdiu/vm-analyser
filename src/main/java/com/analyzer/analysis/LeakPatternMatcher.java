package com.analyzer.analysis;

import com.analyzer.util.Fmt;

/**
 * 泄漏模式匹配与评分。
 *
 * <p>预设模式见常量；匹配依据是“嫌疑对象自身特征 + GC Root 引用链上下文”：</p>
 * <ul>
 *   <li>链上出现 ThreadLocalMap → THREAD_LOCAL_NOT_CLEANED</li>
 *   <li>嫌疑对象是 ClassLoader 实例 → CLASSLOADER_LEAK</li>
 *   <li>链上有 static 字段边 + 集合类型 → STATIC_COLLECTION</li>
 *   <li>集合类型且类名含 cache → CACHE_GROWTH</li>
 *   <li>byte[] 且自身占用大 → LARGE_BYTE_ARRAY</li>
 *   <li>类名呈监听器特征且被集合/静态持有 → LISTENER_NOT_REMOVED</li>
 * </ul>
 *
 * <p>评分公式（数值越大优先级越高）：<br>
 * score = Retained(MB) + 20×(含静态字段引用) + 15×(被线程持有)
 * + 25×(大集合且 Retained&gt;10MB) + 10×(ClassLoader)。</p>
 */
public final class LeakPatternMatcher {

    public static final String NONE = "NONE";
    public static final String STATIC_COLLECTION = "STATIC_COLLECTION";
    public static final String THREAD_LOCAL_NOT_CLEANED = "THREAD_LOCAL_NOT_CLEANED";
    public static final String LISTENER_NOT_REMOVED = "LISTENER_NOT_REMOVED";
    public static final String CACHE_GROWTH = "CACHE_GROWTH";
    public static final String CLASSLOADER_LEAK = "CLASSLOADER_LEAK";
    public static final String LARGE_BYTE_ARRAY = "LARGE_BYTE_ARRAY";

    /** 模式代码 → 中文标签（报告展示用）。 */
    public static String labelOf(String pattern) {
        switch (pattern) {
            case STATIC_COLLECTION: return "静态集合持续增长";
            case THREAD_LOCAL_NOT_CLEANED: return "ThreadLocal 未清理";
            case LISTENER_NOT_REMOVED: return "监听器/回调未注销";
            case CACHE_GROWTH: return "缓存无淘汰策略";
            case CLASSLOADER_LEAK: return "类加载器泄漏";
            case LARGE_BYTE_ARRAY: return "大字节数组堆积";
            default: return "未归类";
        }
    }

    /**
     * 为一条嫌疑结论匹配模式并计算评分，结果直接回填到 finding。
     */
    public void apply(Finding finding, DetectorContext ctx) {
        RefPath path = finding.path;
        String cls = finding.className == null ? "" : finding.className;

        boolean staticEdge = path != null && path.hasStaticEdge();
        boolean heldByThread = path != null
                && (path.passesThroughClass("java.lang.Thread")
                    || (path.nodes().get(0).gcRootType != null
                        && path.nodes().get(0).gcRootType.contains("Thread")));
        boolean threadLocalMap = path != null && path.passesThroughClass("ThreadLocalMap");
        boolean classLoader = cls.contains("ClassLoader");
        boolean collection = isCollectionLike(cls);
        boolean bigCollection = collection
                && Fmt.asMb(finding.retainedBytes) > 10.0; // 大集合阈值 10MB
        boolean byteArray = cls.startsWith("byte[]");

        // ---- 模式判定（优先级从特殊到一般）----
        String pattern;
        if (threadLocalMap || (cls.contains("ThreadLocal") && heldByThread)) {
            pattern = THREAD_LOCAL_NOT_CLEANED;
        } else if (classLoader) {
            pattern = CLASSLOADER_LEAK;
        } else if (byteArray && finding.shallowBytes > Fmt.MB) {
            pattern = LARGE_BYTE_ARRAY;
        } else if (staticEdge && collection) {
            pattern = STATIC_COLLECTION;
        } else if (collection && cls.toLowerCase().contains("cache")) {
            pattern = CACHE_GROWTH;
        } else if (staticEdge && looksLikeListener(cls)) {
            pattern = LISTENER_NOT_REMOVED;
        } else if (collection && bigCollection) {
            // 无静态边的大集合：增长但根因不明，按缓存无淘汰处理
            pattern = CACHE_GROWTH;
        } else if (collection && (staticEdge || heldByThread)) {
            // 被静态/线程长期持有的容器：优先怀疑“只增不减”
            pattern = staticEdge ? STATIC_COLLECTION : CACHE_GROWTH;
        } else if (staticEdge) {
            pattern = STATIC_COLLECTION;
        } else if (heldByThread && looksLikeListener(cls)) {
            pattern = LISTENER_NOT_REMOVED;
        } else {
            pattern = NONE;
        }

        finding.pattern = pattern;
        finding.patternLabel = labelOf(pattern);

        // ---- 评分公式 ----
        double score = Fmt.asMb(finding.retainedBytes);
        if (staticEdge) score += 20;
        if (heldByThread) score += 15;
        if (bigCollection) score += 25;
        if (classLoader) score += 10;
        finding.score = score;
    }

    /** 是否“集合类嫌疑对象”。 */
    public static boolean isCollectionLike(String className) {
        return className.contains("java.util.") && (className.contains("Map")
                || className.contains("List") || className.contains("Set")
                || className.contains("Vector") || className.contains("Collection"))
                || className.contains("ConcurrentHashMap")
                || className.contains("CopyOnWriteArrayList")
                || className.endsWith("[]");
    }

    /** 类名呈监听器/回调特征。 */
    private static boolean looksLikeListener(String className) {
        String c = className.toLowerCase();
        return c.contains("listener") || c.contains("callback") || c.contains("observer")
                || c.contains("handler") || c.contains("watcher");
    }
}
