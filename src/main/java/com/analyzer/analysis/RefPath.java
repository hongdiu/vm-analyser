package com.analyzer.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一条“GC Root → 目标对象”的引用链。
 *
 * <p>节点顺序从 GC Root 开始、到嫌疑对象结束；每个节点记录了
 * “上一跳是以什么方式引用到我”的标签（字段名 / 数组下标 / static 字段等），
 * 保证报告里引用链清晰可读。</p>
 */
public final class RefPath {

    /** 引用链上的一个对象节点。 */
    public static final class Node {
        public final int objectId;
        public final String className;
        public final String displayName;
        /** 上一跳对象通过什么引用指向本节点，例如 "elementData[3]"、"static cache"。 */
        public String incomingLabel;
        /** 是否 GC Root（仅首节点可能为 true）。 */
        public boolean gcRoot;
        /** GC Root 类型描述，如 "Thread Object"。 */
        public String gcRootType;

        public Node(int objectId, String className, String displayName) {
            this.objectId = objectId;
            this.className = className;
            this.displayName = displayName;
        }

        /** 供 HTML/JSON 渲染的 "字段名 → 类名" 一行。 */
        public String describe() {
            String label = incomingLabel == null || incomingLabel.isEmpty()
                    ? "" : incomingLabel + " → ";
            return label + className;
        }
    }

    private final List<Node> nodes = new ArrayList<>();

    public void add(Node n) {
        nodes.add(n);
    }

    public List<Node> nodes() {
        return Collections.unmodifiableList(nodes);
    }

    public int size() {
        return nodes.size();
    }

    /** 完整链路的可读文本（用 → 连接）。 */
    public String asText() {
        StringBuilder sb = new StringBuilder();
        for (Node n : nodes) {
            if (sb.length() > 0) {
                sb.append("  →  ");
            }
            sb.append(n.describe());
        }
        return sb.toString();
    }

    /** 链上是否经过某个类名（模式匹配用，如 java.lang.Thread、ThreadLocalMap）。 */
    public boolean passesThroughClass(String classNamePart) {
        for (Node n : nodes) {
            if (n.className != null && n.className.contains(classNamePart)) {
                return true;
            }
        }
        return false;
    }

    /** 链上是否存在“static”标签的边（静态字段持有 → 静态集合泄漏信号）。 */
    public boolean hasStaticEdge() {
        for (Node n : nodes) {
            if (n.incomingLabel != null && n.incomingLabel.startsWith("static")) {
                return true;
            }
        }
        return false;
    }
}
