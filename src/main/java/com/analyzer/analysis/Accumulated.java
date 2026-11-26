package com.analyzer.analysis;

/**
 * 一条“累积对象”证据：嫌疑对象向下（支配树子节点）被某个类吃掉的内存。
 *
 * <p>例如：线程的持久化上下文下挂着 5,592,406 个 HashMap$Node，合计 202MB。
 * 这是判断“泄漏的是什么、规模多大”的直接证据。</p>
 */
public final class Accumulated {

    /** 累积对象的类名（数组为 byte[] / Object[] 形式）。 */
    public String className;
    /** 该类直接子节点个数（不是实例总数，避免误导）。 */
    public int count;
    /** 这些子节点 retained 合计。 */
    public long retainedBytes;
    /** 第一个样本对象的展示名（带地址/长度），便于在 MAT 里定位。 */
    public String sampleDisplay;

    public String describe() {
        return className + " × " + count + "（合计 " + com.analyzer.util.Fmt.mb(retainedBytes) + "）";
    }
}
