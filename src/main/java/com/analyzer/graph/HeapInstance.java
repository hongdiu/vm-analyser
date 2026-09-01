package com.analyzer.graph;

/**
 * 堆中单个对象实例的分析视图（轻量 DTO）。
 *
 * <p>注意：这不是对象图的邻接表节点——为了不撑爆内存，
 * 引用关系始终通过 {@link HeapGraph} 走 MAT 的按需索引读取。
 * 本类只在"某个候选对象需要完整信息"时才会被构造（例如报告某条泄漏嫌疑）。</p>
 */
public final class HeapInstance {

    /** MAT 内部对象编号（索引 id，1 起）。 */
    public final int objectId;
    /** 所属类在 MAT 中的对象编号。 */
    public final int classId;
    /** 类名（含数组形式，如 byte[]）。 */
    public final String className;
    /** 可读展示名（数组会带长度，如 byte[{1000}] @ 0x4a2f...）。 */
    public final String displayName;
    /** Shallow Heap（对象自身占用）。 */
    public final long shallowBytes;
    /** Retained Heap，计算完成后回填。 */
    public long retainedBytes = -1;
    /** 是否为 GC Root。 */
    public boolean gcRoot;
    /** GC Root 类型描述（如 "Thread Object"）。 */
    public String gcRootType;

    public HeapInstance(int objectId, int classId, String className, String displayName,
                        long shallowBytes) {
        this.objectId = objectId;
        this.classId = classId;
        this.className = className;
        this.displayName = displayName;
        this.shallowBytes = shallowBytes;
    }

    @Override
    public String toString() {
        return displayName + " (shallow=" + shallowBytes + ", retained=" + retainedBytes + ")";
    }
}
