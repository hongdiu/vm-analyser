package com.analyzer.graph;

import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.SnapshotInfo;
import org.eclipse.mat.snapshot.model.GCRootInfo;
import org.eclipse.mat.snapshot.model.IClass;
import org.eclipse.mat.snapshot.model.IObject;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对象引用图（对 MAT 索引的按需访问封装）。
 *
 * <p>设计取舍：不把全堆对象和引用一次性载入内存（几百 MB~几 GB 的 dump
 * 必然 OOM），而是保留 outRefs / inRefs / GC Roots 三类“图视图”入口，
 * 每次调用都落到 MAT 已落盘的索引（outbound/inbound 索引、heap.bin）上
 * 做定点读取，并缓存少量热点元数据（类名、GC Root 集合）。</p>
 *
 * <p>因此本类的对外语义就是一张标准的堆引用图：
 * outRefs(x) = x 引用了谁；inRefs(x) = 谁引用了 x；gcRoots = 根集合。</p>
 */
public final class HeapGraph {

    private final ISnapshot snapshot;
    private final int objectCapacity;

    /** GC Root 编号 → 类型描述（"Thread Object, Java Local" 之类）。 */
    private final Map<Integer, String> gcRootTypes = new HashMap<>();
    private final BitSet gcRootBits;

    /** classObjectId → 类名 的缓存（类数量有限，缓存收益高、内存占用小）。 */
    private final Map<Integer, String> classNameCache = new ConcurrentHashMap<>();

    public HeapGraph(ISnapshot snapshot) throws SnapshotException {
        this.snapshot = snapshot;
        SnapshotInfo info = snapshot.getSnapshotInfo();
        // MAT 的对象 id 从 1 开始且连续，capacity 留 2 的余量给虚拟根等哨兵值
        this.objectCapacity = info.getNumberOfObjects() + 2;
        this.gcRootBits = new BitSet(objectCapacity);
        for (int rootId : snapshot.getGCRoots()) {
            gcRootBits.set(rootId);
        }
    }

    public ISnapshot raw() {
        return snapshot;
    }

    public SnapshotInfo info() {
        return snapshot.getSnapshotInfo();
    }

    /** 图上对象 id 的上界（含），用于给 BFS/支配树分配 int[]。 */
    public int objectCapacity() {
        return objectCapacity;
    }

    public int[] gcRoots() throws SnapshotException {
        return snapshot.getGCRoots();
    }

    public boolean isGcRoot(int objectId) {
        return objectId >= 0 && objectId < objectCapacity && gcRootBits.get(objectId);
    }

    /** GC Root 类型描述；非 GC Root 返回 null。 */
    public String gcRootType(int objectId) {
        if (!isGcRoot(objectId)) {
            return null;
        }
        return gcRootTypes.computeIfAbsent(objectId, id -> {
            try {
                GCRootInfo[] infos = snapshot.getGCRootInfo(id);
                return infos == null || infos.length == 0
                        ? "GC Root" : GCRootInfo.getTypeSetAsString(infos);
            } catch (SnapshotException e) {
                return "GC Root";
            }
        });
    }

    /** 正向引用：该对象引用到的对象 id 集合（按索引读取）。 */
    public int[] outRefs(int objectId) {
        try {
            int[] refs = snapshot.getOutboundReferentIds(objectId);
            return refs == null ? new int[0] : refs;
        } catch (SnapshotException e) {
            return new int[0]; // 少量脏数据不影响整体分析
        }
    }

    /** 反向引用：引用了该对象的对象 id 集合（按索引读取）。 */
    public int[] inRefs(int objectId) {
        try {
            int[] refs = snapshot.getInboundRefererIds(objectId);
            return refs == null ? new int[0] : refs;
        } catch (SnapshotException e) {
            return new int[0];
        }
    }

    /** 对象自身浅堆占用（字节）。 */
    public long shallowSize(int objectId) {
        try {
            return snapshot.getHeapSize(objectId);
        } catch (SnapshotException e) {
            return 0L;
        }
    }

    /** 该对象所属类的 MAT 对象 id（找不到时返回 -1）。 */
    public int classOf(int objectId) {
        try {
            IClass clazz = snapshot.getClassOf(objectId);
            return clazz == null ? -1 : clazz.getObjectId();
        } catch (SnapshotException e) {
            return -1;
        }
    }

    /** 类名（数组为 byte[] / java.lang.Object[] 形式）。 */
    public String className(int objectId) {
        int classId = classOf(objectId);
        if (classId < 0) {
            return "<unknown>";
        }
        return classNameOfClass(classId);
    }

    public String classNameOfClass(int classId) {
        return classNameCache.computeIfAbsent(classId, id -> {
            try {
                IObject o = snapshot.getObject(id);
                if (o instanceof IClass) {
                    return ((IClass) o).getName();
                }
                return o.getDisplayName();
            } catch (SnapshotException e) {
                return "<class#" + id + ">";
            }
        });
    }

    public boolean isClass(int objectId) {
        return snapshot.isClass(objectId);
    }

    /** 展示名（带数组长度/地址），失败时退化为类名#id。 */
    public String displayName(int objectId) {
        try {
            return snapshot.getObject(objectId).getDisplayName();
        } catch (SnapshotException e) {
            return className(objectId) + "@" + objectId;
        }
    }

    public Collection<IClass> classes() throws SnapshotException {
        return snapshot.getClasses();
    }

    /**
     * 从 GC Roots 出发的可达性分析（BFS）。
     *
     * @return 可达对象集合的位图；支配树与泄漏候选都基于它做过滤
     */
    public BitSet reachableFromGcRoots() throws SnapshotException {
        BitSet visited = new BitSet(objectCapacity);
        // 用原始类型数组做队列，避免在大堆上产生数以百万计的 Integer 装箱
        int[] queue = new int[objectCapacity];
        int head = 0, tail = 0;
        for (int root : gcRoots()) {
            if (!visited.get(root)) {
                visited.set(root);
                queue[tail++] = root;
            }
        }
        // 类对象即使不是显式 GC Root 也参与引用（堆中有对 Class 的强引用链），
        // 从根出发沿 outRefs 扩散即可覆盖活对象图。
        while (head < tail) {
            int cur = queue[head++];
            for (int next : outRefs(cur)) {
                if (next > 0 && next < objectCapacity && !visited.get(next)) {
                    visited.set(next);
                    queue[tail++] = next;
                }
            }
        }
        return visited;
    }
}
