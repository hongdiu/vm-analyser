package com.analyzer.analysis;

import java.util.List;

/**
 * 统一检测器接口：所有泄漏/异常检测规则实现本接口，
 * 主流程以同一种方式调度，新增规则零成本（实现 + 注册即可）。
 */
public interface Detector {

    /** 检测器名称（进入报告的 section 标题与 JSON 的 section key）。 */
    String name();

    /**
     * 执行检测。
     *
     * @param ctx 共享上下文（堆图、支配树结果、阈值、引用链工具等）
     * @return 检测结论列表（已按各自规则排序、条数不超过 ctx.top）
     */
    List<Finding> detect(DetectorContext ctx) throws Exception;
}
