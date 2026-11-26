package com.analyzer.report;

import com.analyzer.analysis.Finding;
import com.analyzer.analysis.LeakPatternMatcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 修复建议生成器：按匹配到的泄漏模式输出对应的中文修复建议。
 * 建议条目按“模式 → 通用建议”两级组装，供 HTML 报告与 JSON 摘要复用。
 */
public final class RecommendationBuilder {

    /**
     * 为一条结论生成建议列表并回填到 finding.recommendations。
     */
    public void apply(Finding finding) {
        List<String> recs = new ArrayList<>(suggestionsFor(finding.pattern));
        if (finding.pattern.equals(LeakPatternMatcher.NONE)) {
            recs.add("未能自动归类：请结合引用链人工确认，重点检查链上的集合、静态字段与线程持有者。");
        }
        if (finding.path == null && finding.objectId >= 0) {
            recs.add("该对象从 GC Root 不可达，属于待回收垃圾；若数量巨大可推动 JVM 尽早 Full GC 或排查弱/软引用缓存退化。");
        }
        finding.recommendations.clear();
        finding.recommendations.addAll(recs);
    }

    /**
     * 各模式的“如何验证”步骤：告诉用户拿什么证据确认结论，而不是只给建议。
     */
    public static List<String> verificationFor(String pattern) {
        switch (pattern) {
            case LeakPatternMatcher.RESULT_SET_ACCUMULATION:
                return Arrays.asList(
                        "开启 SQL 日志/慢查询日志，确认对应查询是否没有 LIMIT/分页、返回行数是否达到百万级。",
                        "把查询改为分页或流式后生成第二份 dump，对比该结果集/持久化上下文的 retained 是否回落。");
            case LeakPatternMatcher.STATIC_COLLECTION:
                return Arrays.asList(
                        "在代码中定位该静态字段的写入点（put/add 调用），确认是否存在只增不减的路径。",
                        "间隔一段时间再 dump 一次，对比该集合的 size/retained 是否持续增长。");
            case LeakPatternMatcher.THREAD_LOCAL_NOT_CLEANED:
                return Arrays.asList(
                        "检查该线程 ThreadLocalMap 中 Entry.value 是否在请求结束后仍被引用。",
                        "补上 ThreadLocal.remove()（finally 中）后二次 dump，确认 Entry 已清空。");
            case LeakPatternMatcher.CLASSLOADER_LEAK:
                return Arrays.asList(
                        "确认该加载器是否被重复创建（热部署/插件 reload 次数），并核对其加载的类是否为旧版本。",
                        "二次 dump 对比同型加载器实例数是否减少。");
            case LeakPatternMatcher.CACHE_GROWTH:
                return Arrays.asList(
                        "确认该缓存是否配置 maxSize / expireAfterWrite；压测后观察 size 是否持续上涨。",
                        "二次 dump 对比缓存条目数与 retained。");
            case LeakPatternMatcher.LARGE_BYTE_ARRAY:
                return Arrays.asList(
                        "确认大数组的产生路径（一次性 readAllBytes/序列化/文件读取），观察请求量增长时数量是否线性上涨。",
                        "二次 dump 对比同类数组的实例数与总 retained。");
            default:
                return Arrays.asList(
                        "按上面的代码线索复现该路径，间隔若干分钟再生成一份 dump，对比该对象的 retained 是否增长。",
                        "单份 dump 只能证明“此刻占用”，增长证据必须来自两次 dump 对比。");
        }
    }

    /** 各预设模式的标准建议（中文）。 */
    public static List<String> suggestionsFor(String pattern) {        switch (pattern) {
            case LeakPatternMatcher.STATIC_COLLECTION:
                return Arrays.asList(
                        "检查静态 Map/List 是否只增不减，建议增加淘汰策略（定时清理、大小上限）或改用弱引用（WeakHashMap / SoftReference）。",
                        "为集合设计明确的清理时机：任务结束、会话过期或应用关闭时调用 clear()/remove()。",
                        "评估是否可换成有界队列（ArrayBlockingQueue、Disruptor 等），防止无上限堆积。");
            case LeakPatternMatcher.THREAD_LOCAL_NOT_CLEANED:
                return Arrays.asList(
                        "线程池场景下必须在 finally 中调用 ThreadLocal.remove()，否则线程复用会让对象长期存活。",
                        "考虑用阿里 TransmittableThreadLocal / try-with-resources 封装统一清理入口。",
                        "限制 ThreadLocal 存储对象的体积，避免在线程上挂大上下文。");
            case LeakPatternMatcher.LISTENER_NOT_REMOVED:
                return Arrays.asList(
                        "注册监听器时保留句柄，在对象生命周期结束时成对调用 removeListener/unregister。",
                        "优先使用 WeakReference 注册回调，或使用框架自带的生命周期绑定（如 addLifecycleObserver）。",
                        "对第三方事件总线确认是否支持一次性订阅或作用域订阅。");
            case LeakPatternMatcher.CACHE_GROWTH:
                return Arrays.asList(
                        "建议使用 Caffeine/Guava Cache 并设置 maxSize 或 expireAfterWrite，避免手写字典当缓存。",
                        "如必须自管缓存，补充容量上限 + LRU/LFU 淘汰 + 命中率监控告警。",
                        "复核 value 是否过大（可换存引用/存 key 二次查询），压缩缓存整体体积。");
            case LeakPatternMatcher.CLASSLOADER_LEAK:
                return Arrays.asList(
                        "热部署/插件卸载后检查 ClassLoader 是否可回收：停止其持有的线程池、Timer、JDBC Driver、ShutdownHook。",
                        "注销注册到全局（JMX、SPI、静态字段）的类与实例；DriverManager 需显式 deregister。",
                        "升级/重用同一 ClassLoader，避免每次 reload 新建一层；用 -verbose:class 定位重复加载。");
            case LeakPatternMatcher.RESULT_SET_ACCUMULATION:
                return Arrays.asList(
                        "核对这条查询是否一次性把全部结果装进 List：确认 SQL 是否缺少 LIMIT/分页，或 ORM 是否用了会全量加载的查询方法。",
                        "改为分页查询，或使用流式结果（JDBC setFetchSize + 游标 / ORM 的 Stream 结果），避免在内存里堆叠百万行。",
                        "检查事务边界：长事务会让 Hibernate 持久化上下文（entitiesByKey / loadingEntityHolders）持续增长，尽量缩短会话生命周期。");
            case LeakPatternMatcher.LARGE_BYTE_ARRAY:
                return Arrays.asList(
                        "排查大 buffer 复用：避免每请求/每条消息新建大 byte[]，改用池化（Netty PooledByteBuf、ByteArray 池）。",
                        "检查文件/网络读写是否一次性 readAllBytes，改成分块流式处理。",
                        "确认是否缓存了大序列化结果，考虑压缩存储或降低缓存粒度。");
            default:
                return Arrays.asList(
                        "查看引用链最靠近 GC Root 的持有者，确认可否缩短其生命周期。");
        }
    }
}
