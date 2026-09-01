package com.analyzer.graph;

/**
 * 类维度的统计信息（Top 内存占用类表格的数据源）。
 *
 * <p>retainedBytes 的口径：支配树聚合——把每个对象的 Shallow Heap
 * 记到"直接支配它的对象所属类"的账上（即该类在支配树子树内吞掉的内存），
 * 与 MAT 直方图 Retained Heap 列的语义一致，不会出现重复计数；
 * 若某类实例被同类实例支配，则计入更上层祖先类的账，属正常现象。</p>
 */
public final class ClassInfo {

    public final int classId;
    public final String className;
    /** 实例数量（类对象本身按 1 个对象计）。 */
    public int numberOfObjects;
    /** 该类所有实例 Shallow Heap 之和（Used Heap）。 */
    public long shallowBytes;
    /** 支配树口径的 Retained Heap（见类注释）。 */
    public long retainedBytes;
    /** 定义该类的 ClassLoader 描述（ClassLoader 泄漏分析用）。 */
    public String definingLoader;

    public ClassInfo(int classId, String className) {
        this.classId = classId;
        this.className = className;
    }

    @Override
    public String toString() {
        return className + " x" + numberOfObjects;
    }
}
