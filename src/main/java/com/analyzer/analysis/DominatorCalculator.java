package com.analyzer.analysis;

import com.analyzer.graph.HeapGraph;
import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.model.IClass;

import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

/**
 * 支配树（Dominator Tree）与 Retained Heap 计算。
 *
 * <p>算法（Lengauer-Tarjan 简单形式，全部在 DFS 序空间上运行）：</p>
 * <ol>
 *   <li>从“虚拟根”（对象 id 0，出边 = 全部 GC Roots）出发做迭代式 DFS，
 *       为可达对象编 dfs 号，记录树边父指针与一般前驱表 pred；</li>
 *   <li>按 dfs 号逆序计算半支配点 semi，配合带路径压缩的 eval/link
 *       与 bucket 链表求出直接支配点 idom，最后统一修正；</li>
 *   <li>Retained Heap = Shallow + Σ 子树 Retained：支配点的 dfs 号必小于
 *       被支配点，因此按 dfs 号逆序单趟“retained[idom] += retained[v]”
 *       就等价于自底向上后序累加；</li>
 *   <li>类维度聚合：每个对象的 shallow 记到“直接支配点所属类”账上
 *       （支配点是虚拟根或 Class 对象时记到自身类），得到无重复计数的
 *       按类 Retained Heap（Top 内存占用类表格）。</li>
 * </ol>
 *
 * <p>规模保护：可达对象超过 {@link #LT_OBJECT_LIMIT} 时放弃自建支配树
 * （数组规模随对象数线性放大，继续建会威胁本进程内存），自动降级为
 * MAT 自带的 retained/支配索引按需查询——两模式共用 {@link DominatorResult}
 * 的读取接口，上层检测器无感知。</p>
 */
public final class DominatorCalculator {

    /** 超过该可达对象规模时降级为 MAT 内置 retained 计算。 */
    public static final int LT_OBJECT_LIMIT = 6_000_000;

    /**
     * 计算支配树与 Retained Heap。
     *
     * @param graph   堆引用图（MAT 索引封装）
     * @param verbose 是否打印进度信息
     */
    public DominatorResult compute(HeapGraph graph, boolean verbose) throws SnapshotException {
        long t0 = System.currentTimeMillis();
        ISnapshot snapshot = graph.raw();
        final int cap = graph.objectCapacity();

        // ============ 前置规模保护：超阈值直接走 MAT 内置索引模式 ============
        // 注意必须在分配任何 int[cap]/long[cap] 数组【之前】判断：
        // 几 GB 的转储（数千万对象）若先做 DFS 再降级，DFS 自身就会撑爆分析进程。
        if (snapshot.getSnapshotInfo().getNumberOfObjects() > LT_OBJECT_LIMIT) {
            if (verbose) {
                System.out.println("[dom] object count exceeds limit " + LT_OBJECT_LIMIT
                        + ", use MAT built-in dominator/retained index (no in-process tree)");
            } else {
                System.out.println("[dom] large heap detected -> MAT index mode");
            }
            DominatorResult r = DominatorResult.matMode(snapshot);
            // 可达集合也避免全图 BFS（省三份 int[cap]）：置为“全部已解析对象”的近似口径，
            // isReachable 仅用于报告统计，降级模式下从 GC Root 出发的引用链/retained 由 MAT 索引保证正确。
            BitSet all = new BitSet(cap);
            all.set(1, cap);
            r.setReachable(all, snapshot.getSnapshotInfo().getNumberOfObjects());
            r.buildTopLevelMat(snapshot.getGCRoots());
            fillClassStatsApprox(graph, r);
            return r;
        }

        // ================= 第一步：迭代式 DFS 编号 + 前驱表 =================
        int[] dfnum = new int[cap];   // 对象 id → dfs 号（0 未访问）
        int[] vertex = new int[cap];  // dfs 号 → 对象 id
        int[] parent = new int[cap];  // DFS 树父边（dfs 号空间）
        int[] predHead = new int[cap];
        Arrays.fill(predHead, -1);
        IntEdges pred = new IntEdges(1 << 16);

        int n = 0;
        // 显式栈 DFS：并行数组栈保存 (对象 id, 出边快照, 游标)
        int[] nodeStack = new int[1024];
        int[][] succStack = new int[1024][];
        int[] succPos = new int[1024];
        int sp = 0;

        dfnum[0] = ++n;               // 虚拟根占用 dfs 号 1
        vertex[n] = 0;
        nodeStack[sp] = 0;
        succStack[sp] = snapshot.getGCRoots();
        succPos[sp] = 0;
        sp = 1;

        while (sp > 0) {
            int v = nodeStack[sp - 1];
            int[] succ = succStack[sp - 1];
            int pos = succPos[sp - 1];
            boolean descended = false;
            while (pos < succ.length) {
                int w = succ[pos++];
                succPos[sp - 1] = pos;
                if (w <= 0 || w >= cap) {
                    continue;
                }
                if (dfnum[w] == 0) {  // 树边
                    dfnum[w] = ++n;
                    vertex[n] = w;
                    parent[dfnum[w]] = dfnum[v];
                    if (sp == nodeStack.length) {
                        nodeStack = Arrays.copyOf(nodeStack, sp * 2);
                        succStack = Arrays.copyOf(succStack, sp * 2);
                        succPos = Arrays.copyOf(succPos, sp * 2);
                    }
                    nodeStack[sp] = w;
                    succStack[sp] = graph.outRefs(w);
                    succPos[sp] = 0;
                    sp++;
                    descended = true;
                    break;
                } else {             // 前向/横/反向边：v 也是 w 的前驱
                    pred.add(dfnum[w], dfnum[v], predHead);
                }
            }
            if (!descended) {
                sp--;
            }
            if (verbose && (n & 0xFFFFF) == 0) {
                System.out.println("[dom] dfs visited " + n + " objects...");
            }
        }
        // 树边补进前驱表：parent[i] 是 i 的前驱
        for (int i = 2; i <= n; i++) {
            pred.add(i, parent[i], predHead);
        }

        if (verbose) {
            System.out.println("[dom] reachable=" + n + " objects, pred edges=" + pred.size
                    + ", dfs cost=" + (System.currentTimeMillis() - t0) + "ms");
        }

        // ================= 规模保护：降级为 MAT 内置模式 =================
        if (n > LT_OBJECT_LIMIT) {
            System.out.println("[dom] reachable exceeds " + LT_OBJECT_LIMIT
                    + " objects, fallback to MAT built-in dominator/retained index");
            BitSet reach = new BitSet(cap);
            for (int i = 2; i <= n; i++) {
                reach.set(vertex[i]);
            }
            DominatorResult r = DominatorResult.matMode(snapshot);
            r.setReachable(reach, n - 1);
            r.buildTopLevelMat(snapshot.getGCRoots());
            fillClassStatsApprox(graph, r);
            return r;
        }

        // ================= 第二步：semi / idom =================
        int[] semi = new int[n + 2];
        int[] label = new int[n + 2];
        int[] ancestor = new int[n + 2];
        int[] idom = new int[n + 2];
        int[] bucketHead = new int[n + 2];
        Arrays.fill(bucketHead, -1);
        IntEdges bucket = new IntEdges(1 << 14);
        for (int i = 1; i <= n; i++) {
            semi[i] = i;
            label[i] = i;
        }

        for (int i = n; i >= 2; i--) {
            // 2a. 半支配点：semi[i] = min{ semi[eval(p)] | p ∈ pred(i) }
            for (int e = predHead[i]; e != -1; e = pred.next[e]) {
                int u = eval(pred.to[e], ancestor, label, semi);
                if (semi[u] < semi[i]) {
                    semi[i] = semi[u];
                }
            }
            // 2b. 入桶 + link 到父边集合
            bucket.add(semi[i], i, bucketHead);
            ancestor[i] = parent[i];
            // 2c. 处理父节点桶：idom[w] = semi[eval(w)] < semi[w] ? eval(w) : parent
            int p = parent[i];
            for (int b = bucketHead[p]; b != -1; b = bucket.next[b]) {
                int w = bucket.to[b];
                int u = eval(w, ancestor, label, semi);
                idom[w] = semi[u] < semi[w] ? u : p;
            }
            bucketHead[p] = -1;
        }
        // 2d. idom 修正（bucket 阶段可能存了半支配点而非最终支配点）
        for (int i = 2; i <= n; i++) {
            if (idom[i] != semi[i]) {
                idom[i] = idom[idom[i]];
            }
        }

        // ================= 第三步：转对象 id 空间 + 后序累加 retained =================
        int[] idomOfId = new int[cap];
        Arrays.fill(idomOfId, -1);
        long[] retainedOfId = new long[cap];
        BitSet reachable = new BitSet(cap);

        for (int i = 2; i <= n; i++) {
            int v = vertex[i];
            reachable.set(v);
            retainedOfId[v] = graph.shallowSize(v);
            int dom = idom[i] == 0 ? 1 : idom[i]; // 0（理论不发生）视同虚拟根
            int domId = vertex[dom];
            idomOfId[v] = domId; // 虚拟根记为 0
        }
        // 逆 dfs 号累加 = 支配树后序遍历：retained[idom] += retained[v]
        for (int i = n; i >= 2; i--) {
            int v = vertex[i];
            retainedOfId[idomOfId[v]] += retainedOfId[v];
        }

        // ================= 第四步：类维度统计 =================
        Map<Integer, Long> classShallow = new HashMap<>();
        Map<Integer, Integer> classCount = new HashMap<>();
        Map<Integer, Long> classRetained = new HashMap<>();
        for (int i = 2; i <= n; i++) {
            int v = vertex[i];
            int cid = graph.classOf(v);
            long shallow = retainedOfId[v] < 0 ? 0 : graph.shallowSize(v);
            if (cid >= 0) {
                classCount.merge(cid, 1, Integer::sum);
                classShallow.merge(cid, shallow, Long::sum);
            }
            // 支配点归账（dominator attribution，见类注释）
            int ownerClass;
            if (cid >= 0 && (idomOfId[v] == 0 || graph.isClass(idomOfId[v]))) {
                ownerClass = cid;
            } else {
                ownerClass = graph.classOf(idomOfId[v]);
            }
            if (ownerClass >= 0) {
                classRetained.merge(ownerClass, shallow, Long::sum);
            }
        }

        DominatorResult result = new DominatorResult(idomOfId, retainedOfId);
        result.setReachable(reachable, n - 1);
        result.classShallow().putAll(classShallow);
        result.classCount().putAll(classCount);
        result.classRetained().putAll(classRetained);
        result.buildTopLevelOwn(cap);
        if (verbose) {
            System.out.println("[dom] dominator tree done in "
                    + (System.currentTimeMillis() - t0) + "ms");
        }
        return result;
    }

    /** 降级模式下的类统计近似：用 MAT 直方数据算 Used Heap 与实例数。 */
    private void fillClassStatsApprox(HeapGraph graph, DominatorResult r) {
        try {
            for (IClass c : graph.classes()) {
                r.classShallow().put(c.getObjectId(),
                        (long) c.getNumberOfObjects() * c.getHeapSizePerInstance());
                r.classCount().put(c.getObjectId(), c.getNumberOfObjects());
            }
        } catch (SnapshotException ignore) {
            // 统计失败只影响报告的一张表
        }
    }

    /**
     * eval：不相交集合森林上查询“semi 最小的代表元”（迭代式路径压缩，避免深递归栈溢出）。
     * 入参/返回均为 dfs 号。
     */
    private static int eval(int v, int[] ancestor, int[] label, int[] semi) {
        if (ancestor[v] == 0) {
            return label[v];
        }
        // 沿 ancestor 收集压缩链（chain[0]=v，链尾的 ancestor 为集合根）
        int[] chain = new int[32];
        int k = 0;
        int x = v;
        while (ancestor[x] != 0) {
            if (k == chain.length) {
                chain = Arrays.copyOf(chain, k * 2);
            }
            chain[k++] = x;
            x = ancestor[x];
        }
        // 自链顶（不含根）向下：先修正深层节点，保证 ancestor[node] 已指向集合根
        for (int i = k - 2; i >= 0; i--) {
            int node = chain[i];
            int anc = ancestor[node];
            if (anc != 0 && ancestor[anc] != 0) {
                if (semi[label[anc]] < semi[label[node]]) {
                    label[node] = label[anc];
                }
                ancestor[node] = ancestor[anc];
            }
        }
        // 紧邻根的节点：只做 label 修正（与经典递归版 compress 语义一致）
        if (k >= 1) {
            int node = chain[0];
            int anc = ancestor[node];
            if (anc != 0 && ancestor[anc] == 0 && semi[label[anc]] < semi[label[node]]) {
                label[node] = label[anc];
            }
        }
        return label[v];
    }

    /** 动态扩容 int 边表（pred / bucket 共用），避免逐边对象化。 */
    private static final class IntEdges {
        int[] to;
        int[] next;
        int size;

        IntEdges(int cap) {
            to = new int[cap];
            next = new int[cap];
        }

        /** 把“b 是 a 的前驱 / b 属于桶 a”挂到 head[a] 链表。 */
        void add(int a, int b, int[] head) {
            ensure();
            to[size] = b;
            next[size] = head[a];
            head[a] = size;
            size++;
        }

        private void ensure() {
            if (size == to.length) {
                int newCap = to.length + (to.length >> 1) + 16;
                to = Arrays.copyOf(to, newCap);
                next = Arrays.copyOf(next, newCap);
            }
        }
    }
}
