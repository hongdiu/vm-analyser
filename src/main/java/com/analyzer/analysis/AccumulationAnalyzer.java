package com.analyzer.analysis;

import com.analyzer.graph.HeapGraph;
import com.analyzer.graph.ThreadStackIndex;
import com.analyzer.util.Fmt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 累积点分析：在**不建全量支配树**的前提下，沿支配关系向下钻取，
 * 找到真正“吃掉内存”的对象，而不是停留在 GC Root / 顶层组件本身。
 *
 * <p>背景：大堆转储会降级为 MAT 内置 retained 索引，此时原来的“顶层组件”
 * 退化成 GC Root 列表（线程、ClassLoader、JNI Global…），报告只能说
 * “这个线程持有 3.5GB”，说不出持有了什么。本类用
 * {@link org.eclipse.mat.snapshot.ISnapshot#getImmediateDominatorId(int)}
 * 只对少量热点节点做有界展开：</p>
 * <ul>
 *   <li>从 retained 最大的若干个根出发，若某个子节点 retained ≥ 父节点 80%，
 *       说明“故事”在这个子节点身上，继续下钻（最多 3 层）；</li>
 *   <li>被下钻替代的根不再作为嫌疑对象，避免“线程本身”占据头条；</li>
 *   <li>同时记录根下主要子树的“类 → retained / 个数”，供降级模式的
 *       Top 内存占用类表格使用。</li>
 * </ul>
 *
 * <p>所有展开都有节点预算上限，2.5 亿对象的堆上也不会失控。</p>
 */
public final class AccumulationAnalyzer {

    /** 单个候选（可能是根，也可能是根下方的累积点）。 */
    public static final class Candidate {
        /** 作为嫌疑对象展示的对象 id。 */
        public final int objectId;
        /** 归属的 GC Root / 顶层组件 id。 */
        public final int rootId;
        /** 根的 retained。 */
        public final long rootRetainedBytes;
        public final long retainedBytes;
        /** 距离根几层（0 = 根自身）。 */
        public final int depth;

        Candidate(int objectId, int rootId, long rootRetainedBytes,
                  long retainedBytes, int depth) {
            this.objectId = objectId;
            this.rootId = rootId;
            this.rootRetainedBytes = rootRetainedBytes;
            this.retainedBytes = retainedBytes;
            this.depth = depth;
        }

        public boolean isRoot() {
            return depth == 0;
        }
    }

    /** 简单预算计数器：限制支配关系查询总次数。 */
    private static final class Budget {
        private int left;

        Budget(int left) {
            this.left = left;
        }

        boolean take() {
            if (left <= 0) {
                return false;
            }
            left--;
            return true;
        }

        int remaining() {
            return left;
        }
    }

    private static final int MAX_ROOTS = 60;
    private static final int MAX_CHILDREN_SCAN = 20_000;
    private static final int SCAN_BUDGET = 120_000;
    private static final int EXPAND_BUDGET = 20_000;
    private static final int MAX_DEPTH = 3;
    /** 子节点 retained 达到父节点该比例时，认为“故事”在子节点上。 */
    private static final double DRILL_RATIO = 0.8;
    /** 若已下钻出累积点，根自身只在子节点不足根 60% 时才保留。 */
    private static final double KEEP_ROOT_RATIO = 0.6;

    private final HeapGraph graph;
    private final DominatorResult dominators;
    private final ThreadStackIndex stacks;
    private final long minRetainedBytes;
    private final int top;
    private final boolean verbose;

    private List<Candidate> candidates;
    private Map<String, long[]> fallbackClassStats;

    public AccumulationAnalyzer(HeapGraph graph, DominatorResult dominators,
                                ThreadStackIndex stacks, long minRetainedBytes,
                                int top, boolean verbose) {
        this.graph = graph;
        this.dominators = dominators;
        this.stacks = stacks;
        this.minRetainedBytes = minRetainedBytes;
        this.top = top;
        this.verbose = verbose;
    }

    /** 嫌疑候选：自建树模式沿用顶层组件；降级模式用有界下钻得到的累积点。 */
    public synchronized List<Candidate> candidates() {
        if (candidates == null) {
            candidates = dominators.ownTree() ? ownCandidates() : scanFallback();
        }
        return candidates;
    }

    /**
     * 降级模式的类级 retained 统计（类名 → {retained 合计, 子树个数}）。
     * 仅覆盖展开过的热点根，用于保证 Top 类表格不为空，报告会标注近似口径。
     */
    public synchronized Map<String, long[]> fallbackClassStats() {
        if (fallbackClassStats == null) {
            candidates();
        }
        return fallbackClassStats == null ? Map.of() : fallbackClassStats;
    }

    /**
     * 向下展开一层（按类聚合），给出“嫌疑对象持有什么”。
     *
     * @param objectId      嫌疑对象 / 累积点 id
     * @param retainedBytes 该对象 retained（用于过滤噪声）
     */
    public List<Accumulated> expand(int objectId, long retainedBytes) {
        List<Accumulated> out = new ArrayList<>();
        if (retainedBytes <= 0) {
            return out;
        }
        Budget budget = new Budget(EXPAND_BUDGET);
        List<long[]> children = heavyChildren(objectId, budget, 200);
        if (children.isEmpty()) {
            return out;
        }
        long floor = Math.max(Fmt.MB, retainedBytes / 100); // ≥1MB 且 ≥1%
        Map<String, Accumulated> byClass = new LinkedHashMap<>();
        for (long[] child : children) {
            long retained = child[1];
            if (retained < floor) {
                continue;
            }
            int childId = (int) child[0];
            String className = graph.className(childId);
            Accumulated acc = byClass.computeIfAbsent(className, k -> {
                Accumulated a = new Accumulated();
                a.className = k;
                return a;
            });
            acc.count++;
            acc.retainedBytes += retained;
            if (acc.sampleDisplay == null) {
                acc.sampleDisplay = graph.displayName(childId);
            }
        }
        out.addAll(byClass.values());
        out.sort((a, b) -> Long.compare(b.retainedBytes, a.retainedBytes));
        if (out.size() > 8) {
            return new ArrayList<>(out.subList(0, 8));
        }
        return out;
    }

    // ------------------------------------------------------------------

    /**
     * 根的有效 retained 排序：
     * <ul>
     *   <li>普通根用 MAT 的 retained；</li>
     *   <li>线程根额外看“栈帧里最重的局部变量”，取两者较大值
     *       （MAT 不把局部变量计入 Thread retained）。</li>
     * </ul>
     */
    private List<long[]> rankedRoots() {
        List<Integer> roots = dominators.topLevelByRetained();
        Budget budget = new Budget(20_000);
        List<long[]> ranked = new ArrayList<>();
        for (int id : roots) {
            long retained = dominators.retained(id);
            if (stacks != null && retained < minRetainedBytes && budget.remaining() > 0) {
                if (stacks.isThreadRoot(id)) {
                    long heaviestLocal = heaviestLocalRetained(id, budget);
                    retained = Math.max(retained, heaviestLocal);
                }
            }
            if (retained >= minRetainedBytes) {
                ranked.add(new long[]{id, retained});
            }
        }
        ranked.sort((a, b) -> Long.compare(b[1], a[1]));
        return ranked;
    }

    /** 线程各栈帧局部变量里最大的 retained（去重，避免重复计数）。 */
    private long heaviestLocalRetained(int threadId, Budget budget) {
        ThreadStackIndex.Stack stack = stacks.stackOf(threadId);
        if (stack == null) {
            return 0;
        }
        long max = 0;
        Map<Integer, Boolean> seen = new HashMap<>();
        for (ThreadStackIndex.Frame frame : stack.frames) {
            for (int localId : frame.localIds) {
                if (localId <= 0 || seen.put(localId, Boolean.TRUE) != null) {
                    continue;
                }
                if (budget.remaining() <= 0) {
                    return max;
                }
                budget.take();
                long retained = dominators.retained(localId);
                if (retained > max) {
                    max = retained;
                }
            }
        }
        return max;
    }

    private List<Candidate> ownCandidates() {
        List<Integer> topLevel = dominators.topLevelByRetained();
        int limit = Math.min(topLevel.size(), Math.max(top * 3, 20));
        List<Candidate> out = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            int id = topLevel.get(i);
            out.add(new Candidate(id, id, dominators.retained(id), dominators.retained(id), 0));
        }
        return out;
    }

    private List<Candidate> scanFallback() {
        List<Candidate> found = new ArrayList<>();
        Map<String, long[]> stats = new HashMap<>();
        Budget budget = new Budget(SCAN_BUDGET);
        // 注意：MAT 不会把线程栈局部变量算进 Thread 的 retained，
        // 因此不能直接按根 retained 排序截断，否则“大对象挂在线程局部变量里”会被整条漏掉。
        List<long[]> roots = rankedRoots();
        int rootLimit = Math.min(roots.size(), MAX_ROOTS);

        for (int i = 0; i < rootLimit && budget.remaining() > 0; i++) {
            int rootId = (int) roots.get(i)[0];
            long rootRetained = roots.get(i)[1];
            if (rootRetained < minRetainedBytes) {
                break; // rankedRoots 已按有效 retained 降序
            }

            // 1) 沿“最重的支配子节点”下钻，找到真正的累积点
            int cursor = rootId;
            long cursorRetained = rootRetained;
            int depth = 0;
            while (depth < MAX_DEPTH && budget.remaining() > 0) {
                int heavy = heaviestChild(cursor, budget);
                if (heavy < 0) {
                    break;
                }
                long heavyRetained = dominators.retained(heavy);
                if (heavyRetained < cursorRetained * DRILL_RATIO) {
                    break;
                }
                cursor = heavy;
                cursorRetained = heavyRetained;
                depth++;
            }

            if (depth == 0) {
                found.add(new Candidate(rootId, rootId, rootRetained, rootRetained, 0));
            } else {
                found.add(new Candidate(cursor, rootId, rootRetained, cursorRetained, depth));
                // 若累积点只占根的一半左右，根仍有独立信息量，保留根作为候选
                if (cursorRetained < rootRetained * KEEP_ROOT_RATIO) {
                    found.add(new Candidate(rootId, rootId, rootRetained, rootRetained, 0));
                }
            }

            // 2) 累积点 / 根的主要子节点也作为候选（覆盖同一线程下的其他大块）
            int anchor = depth == 0 ? rootId : cursor;
            for (long[] child : heavyChildren(anchor, budget, 6)) {
                long retained = child[1];
                if (retained >= minRetainedBytes) {
                    found.add(new Candidate((int) child[0], rootId, rootRetained, retained, depth + 1));
                }
            }
            // 已下钻时，根下其余大块（比如同一线程的另一个 ResultSet）不能漏
            if (depth > 0) {
                for (long[] child : heavyChildren(rootId, budget, 8)) {
                    int childId = (int) child[0];
                    long retained = child[1];
                    if (childId != cursor && retained >= minRetainedBytes) {
                        found.add(new Candidate(childId, rootId, rootRetained, retained, 1));
                    }
                }
            }

            // 3) 类级统计：记录根下主要子树归属
            for (long[] child : heavyChildren(rootId, budget, 12)) {
                long retained = child[1];
                if (retained < minRetainedBytes) {
                    continue;
                }
                String className = graph.className((int) child[0]);
                long[] value = stats.computeIfAbsent(className, k -> new long[2]);
                value[0] += retained;
                value[1] += 1;
            }
        }
        fallbackClassStats = stats;

        // 去重（同对象保留 retained 最大的那条），按 retained 降序
        Map<Integer, Candidate> unique = new LinkedHashMap<>();
        for (Candidate c : found) {
            Candidate prev = unique.get(c.objectId);
            if (prev == null || c.retainedBytes > prev.retainedBytes) {
                unique.put(c.objectId, c);
            }
        }
        List<Candidate> list = new ArrayList<>(unique.values());
        list.sort((a, b) -> Long.compare(b.retainedBytes, a.retainedBytes));
        int limit = Math.max(top * 3, 10);
        if (list.size() > limit) {
            list = new ArrayList<>(list.subList(0, limit));
        }
        if (verbose) {
            System.out.println("[acc] fallback candidates=" + list.size()
                    + ", class stats=" + stats.size()
                    + ", dominator queries left=" + budget.remaining());
        }
        return list;
    }

    /** 父节点下 retained 最大的支配子节点；没有则 -1。 */
    private int heaviestChild(int parentId, Budget budget) {
        List<long[]> children = heavyChildren(parentId, budget, 1);
        return children.isEmpty() ? -1 : (int) children.get(0)[0];
    }

    /**
     * 父节点下按 retained 降序的直接子节点（最多 limit 个）。
     *
     * <p>线程对象是特例：对象图里线程的 outRefs 并不包含栈帧局部变量，
     * MAT 是用 Java Local 伪边表达的。所以线程根的孩子取“该线程各栈帧的
     * 局部变量对象”，否则大堆里最关键的持有关系会整条漏掉。</p>
     *
     * @return 每项为 {对象 id, retained}
     */
    private List<long[]> heavyChildren(int parentId, Budget budget, int limit) {
        Map<Integer, Long> collected = new LinkedHashMap<>();
        boolean threadRoot = stacks != null && stacks.isThreadRoot(parentId);
        if (threadRoot) {
            ThreadStackIndex.Stack stack = stacks.stackOf(parentId);
            if (stack != null) {
                for (ThreadStackIndex.Frame frame : stack.frames) {
                    for (int localId : frame.localIds) {
                        if (localId <= 0 || localId == parentId || collected.containsKey(localId)) {
                            continue;
                        }
                        long retained = dominators.retained(localId);
                        if (retained > 0) {
                            collected.put(localId, retained);
                        }
                    }
                }
            }
        } else {
            int[] refs = graph.outRefs(parentId);
            int scan = Math.min(refs.length, MAX_CHILDREN_SCAN);
            for (int i = 0; i < scan; i++) {
                if (!budget.take()) {
                    break;
                }
                int child = refs[i];
                if (child <= 0 || child == parentId) {
                    continue;
                }
                if (!isDominatedBy(child, parentId)) {
                    continue;
                }
                long retained = dominators.retained(child);
                if (retained > 0) {
                    collected.put(child, retained);
                }
            }
        }
        List<long[]> children = new ArrayList<>(collected.size());
        collected.forEach((id, retained) -> children.add(new long[]{id, retained}));
        children.sort((a, b) -> Long.compare(b[1], a[1]));
        if (children.size() > limit) {
            return new ArrayList<>(children.subList(0, limit));
        }
        return children;
    }

    private boolean isDominatedBy(int child, int parent) {
        try {
            return dominators.dominator(child) == parent;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
