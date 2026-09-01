package com.analyzer.graph;

import com.analyzer.analysis.RefPath;
import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.snapshot.model.Field;
import org.eclipse.mat.snapshot.model.IArray;
import org.eclipse.mat.snapshot.model.IInstance;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.NamedReference;

import java.util.Arrays;
import java.util.List;

/**
 * GC Root 引用链查找器。
 *
 * <p>算法：从目标对象出发，沿反向引用（inRefs = "谁引用了我"）做 BFS，
 * 最先遇到的 GC Root 即给出边数最少的路径（BFS 最短路径性质）。
 * 重建链路时利用 parent[v]=u 的语义（v 引用了 u）：
 * 从 root 沿 parent 链下走正好得到 root → target 顺序。</p>
 *
 * <p>每一跳再标注引用方式：实例字段名 / static 字段名 / 数组下标，
 * 通过 MAT 对象模型的 {@link IObject#getOutboundReferences()} 获取，
 * 拿不到时退化为通用标签（ref / element[*] / static ref）。</p>
 *
 * <p>性能：int[] 队列 + int[] parent 数组，零装箱；
 * 出队次数设上限防止病态图上的过深搜索。</p>
 */
public final class GcRootPathAnalyzer {

    /** BFS 最多出队的节点数（≈搜索半径保护）。 */
    private static final int MAX_BFS_POLLS = 2_000_000;

    private final HeapGraph graph;

    public GcRootPathAnalyzer(HeapGraph graph) {
        this.graph = graph;
    }

    /**
     * 查找 target 到最近 GC Root 的引用链。
     *
     * @return root → target 的引用链；对象不可达（无任何 GC Root 路径）时返回 null
     */
    public RefPath shortestPathToGcRoot(int targetId) {
        final int capacity = graph.objectCapacity();
        // parent[v] = BFS 中 v 的上一个节点 u（u 引用 v 的“被引用者”方向见类注释）；-2 未访问，-1 起点
        int[] parent = new int[capacity];
        Arrays.fill(parent, -2);
        int[] queue = new int[capacity];
        int head = 0, tail = 0;

        parent[targetId] = -1;
        queue[tail++] = targetId;

        int foundRoot = -1;
        int polls = 0;
        while (head < tail && polls < MAX_BFS_POLLS) {
            int cur = queue[head++];
            polls++;
            if (graph.isGcRoot(cur)) {
                foundRoot = cur;
                break;
            }
            for (int ref : graph.inRefs(cur)) { // ref 引用了 cur
                if (ref > 0 && ref < capacity && parent[ref] == -2) {
                    parent[ref] = cur; // cur 是 ref 的"被引用者"，回溯时从 ref 走向 cur
                    queue[tail++] = ref;
                }
            }
        }
        if (foundRoot < 0) {
            // 未命中任何 GC Root：对象不可达（典型场景：unreachable 大数组）
            return null;
        }

        // 从 foundRoot 沿 parent 回溯到 target，天然就是 root → target 顺序
        RefPath path = new RefPath();
        int cur = foundRoot;
        while (cur != -1) {
            path.add(new RefPath.Node(cur, graph.className(cur), graph.displayName(cur)));
            cur = parent[cur];
        }

        List<RefPath.Node> nodes = path.nodes();
        if (!nodes.isEmpty()) {
            RefPath.Node root = nodes.get(0);
            root.gcRoot = true;
            root.gcRootType = graph.gcRootType(root.objectId);
        }
        // nodes[i-1] 引用 nodes[i]：为后者标注"被前者以什么方式引用"
        for (int i = 1; i < nodes.size(); i++) {
            RefPath.Node child = nodes.get(i);
            RefPath.Node holder = nodes.get(i - 1);
            child.incomingLabel = referenceLabel(holder.objectId, child.objectId);
        }
        return path;
    }

    /**
     * 找出 holder → target 这条边的引用标签。
     */
    private String referenceLabel(int holderId, int targetId) {
        try {
            IObject holder = graph.raw().getObject(holderId);
            String label = findFieldLabel(holder, targetId);
            if (label != null) {
                // Class 对象持有的字段引用都是静态字段
                if (graph.isClass(holderId) && !label.startsWith("static")) {
                    return "static " + label;
                }
                return label;
            }
            if (graph.isClass(holderId)) {
                return "static ref";
            }
            if (holder instanceof IArray) {
                return "element[*]";
            }
            return "ref";
        } catch (SnapshotException | RuntimeException e) {
            return "ref";
        }
    }

    /** 通过对象模型定位“哪个字段/元素指向 targetId”。 */
    private String findFieldLabel(IObject holder, int targetId) throws SnapshotException {
        // NamedReference 已带字段名或数组下标信息
        List<NamedReference> refs = holder.getOutboundReferences();
        if (refs != null) {
            for (NamedReference ref : refs) {
                if (ref.getObjectId() == targetId && ref.getName() != null) {
                    return ref.getName();
                }
            }
        }
        // 退化路径：直接比对实例字段的值（对象地址）
        if (holder instanceof IInstance) {
            List<Field> fields = ((IInstance) holder).getFields();
            long targetAddr;
            try {
                targetAddr = graph.raw().mapIdToAddress(targetId);
            } catch (SnapshotException e) {
                return null;
            }
            for (Field f : fields) {
                Object v = f.getValue();
                if (v instanceof Number && ((Number) v).longValue() == targetAddr) {
                    return f.getName();
                }
            }
        }
        return null;
    }
}
