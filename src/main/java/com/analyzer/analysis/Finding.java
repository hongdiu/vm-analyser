package com.analyzer.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条检测结论（所有 Detector 输出统一 DTO，供报告与 JSON 序列化）。
 *
 * <p>不同检测器填的字段略有差异：大对象列表可能没有引用链（GC Root 已不可达时）；
 * ThreadLocal/ClassLoader 检测会往 details 里放线程名、entry 数等上下文。</p>
 */
public final class Finding {

    public static final String CONFIDENCE_HIGH = "HIGH";
    public static final String CONFIDENCE_MEDIUM = "MEDIUM";
    public static final String CONFIDENCE_LOW = "LOW";

    /** 关联对象 id（-1 表示聚合型结论，如“同型 ClassLoader 共 N 个”）。 */
    public int objectId = -1;
    public String className;
    public String displayName;
    public long shallowBytes;
    public long retainedBytes;

    /** 最短 GC Root 引用链（可为 null）。 */
    public RefPath path;

    /** 泄漏模式代码，见 {@link LeakPatternMatcher}。 */
    public String pattern = LeakPatternMatcher.NONE;
    /** 模式中文标签。 */
    public String patternLabel = "未归类";

    /** 优先级评分（越大越可疑），见评分公式注释。 */
    public double score;

    /** 中文修复建议。 */
    public final List<String> recommendations = new ArrayList<>();

    /** 检测器特定的补充信息（线程数、map 容量、同型 loader 个数等）。 */
    public final Map<String, String> details = new LinkedHashMap<>();

    /** 产出该结论的检测器名字。 */
    public String detectorName;

    // ---------- 证据字段（由 CodeLocationExtractor / 检测器填充） ----------

    /** 代码位置线索（业务帧优先，其后是 Java Local 持有帧、静态字段）。 */
    public final List<CodeLocation> codeLocations = new ArrayList<>();

    /** 原始线程栈帧文本（去掉 "at " 前缀），报告里可点击展开。 */
    public List<String> stackFrames = new ArrayList<>();
    /** 栈里属于业务代码的帧下标（报告高亮用）。 */
    public final List<Integer> appFrameIndexes = new ArrayList<>();
    /** 报告中高亮的帧下标（-1 表示无）。 */
    public int stackFrameIndex = -1;
    /** 所属线程名 / 线程对象 id。 */
    public String threadName;
    public int threadId = -1;

    /** 占整堆比例（%）。 */
    public double percentOfHeap;
    /** 证据置信度：HIGH / MEDIUM / LOW。 */
    public String confidence = CONFIDENCE_LOW;
    /** 向下展开的累积对象（按类聚合），回答“它持有什么、多少”。 */
    public final List<Accumulated> accumulated = new ArrayList<>();

    /** 首条业务代码线索（没有则返回 null）。 */
    public CodeLocation primaryCodeLocation() {
        for (CodeLocation loc : codeLocations) {
            if (loc.appCode && loc.hasSourceLine()) {
                return loc;
            }
        }
        return codeLocations.isEmpty() ? null : codeLocations.get(0);
    }

    public Finding() {
    }

    public Finding(int objectId, String className, String displayName,
                   long shallowBytes, long retainedBytes) {
        this.objectId = objectId;
        this.className = className;
        this.displayName = displayName;
        this.shallowBytes = shallowBytes;
        this.retainedBytes = retainedBytes;
    }
}
