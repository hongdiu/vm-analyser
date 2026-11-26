package com.analyzer.report;

import com.analyzer.analysis.Finding;
import com.analyzer.graph.ClassInfo;

import java.util.List;
import java.util.Map;

/**
 * 报告数据总线：主流程把各模块结果装进这一个对象，
 * HtmlReporter / JsonReporter 只负责渲染，互不依赖对方。
 */
public final class ReportData {

    /** 堆概览（①）。 */
    public static final class Overview {
        public String inputPath;
        public String jvmInfo;
        public String dumpTime;
        public long usedHeapBytes;
        public int objectCount;
        public int classCount;
        public int gcRootCount;
        public int identifierSize;
        /** 可达对象数（GC Roots 出发）。 */
        public long reachableCount;
        /** 支配树实现模式：own / mat-fallback。 */
        public String dominatorMode;
        public String generatedAt;
        public String toolVersion = "1.0.0";
        /** 命令行参数回显。 */
        public Map<String, String> options;
    }

    public Overview overview = new Overview();
    /** ② Top 内存占用类（Retained 降序）。 */
    public List<ClassInfo> topClasses;
    /** ③ 内存泄漏嫌疑。 */
    public List<Finding> leakSuspects;
    /** 首屏诊断结论：按代码位置去重后的嫌疑（同一调用点只留一条）。 */
    public List<Finding> conclusions;
    /** ④ 大对象列表。 */
    public List<Finding> largeObjects;
    /** ⑤ 可疑集合。 */
    public List<Finding> collections;
    /** ⑥ ThreadLocal 持有分析。 */
    public List<Finding> threadLocals;
    /** ⑦ ClassLoader 异常。 */
    public List<Finding> classLoaders;
    /** ⑧ 基线对比（提供 --baseline 时才有）。 */
    public com.analyzer.analysis.GrowthReport growth;
}
