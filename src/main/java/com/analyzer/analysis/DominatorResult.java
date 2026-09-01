package com.analyzer.analysis;

import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.snapshot.ISnapshot;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 支配树计算结果（由 {@link DominatorCalculator} 产出）。
 *
 * <p>两种模式：</p>
 * <ul>
 *   <li>ownTree=true：本项目自建 Lengauer-Tarjan 支配树，retained/idom 全部
 *       预计算为对象 id 索引的数组，读取 O(1)；</li>
 *   <li>ownTree=false：超大堆降级模式，读取时按需查询 MAT 自带的
 *       支配/retained 索引（同样不会全量载入内存）。</li>
 * </ul>
 */
public final class DominatorResult {

    private final int[] idomOfId;      // own 模式：对象 id → 直接支配点（0=虚拟根）
    private final long[] retainedOfId; // own 模式：对象 id → retained 字节
    private final ISnapshot fallbackSnapshot; // mat 模式
    private final Map<Integer, Long> matRetainedCache = new HashMap<>();

    private BitSet reachable = new BitSet();
    private int reachableCount;

    private final Map<Integer, Long> classRetained = new HashMap<>();
    private final Map<Integer, Long> classShallow = new HashMap<>();
    private final Map<Integer, Integer> classCount = new HashMap<>();

    /** 顶层组件（虚拟根的直接孩子），retained 降序的对象 id。 */
    private List<Integer> topLevelIds = new ArrayList<>();

    DominatorResult(int[] idomOfId, long[] retainedOfId) {
        this.idomOfId = idomOfId;
        this.retainedOfId = retainedOfId;
        this.fallbackSnapshot = null;
    }

    private DominatorResult(ISnapshot snapshot) {
        this.idomOfId = null;
        this.retainedOfId = null;
        this.fallbackSnapshot = snapshot;
    }

    static DominatorResult matMode(ISnapshot snapshot) {
        return new DominatorResult(snapshot);
    }

    // ---------- 读接口 ----------

    public boolean ownTree() {
        return fallbackSnapshot == null;
    }

    public int reachableCount() {
        return reachableCount;
    }

    public boolean isReachable(int objectId) {
        return reachable.get(objectId);
    }

    /** 直接支配点的对象 id（0=虚拟根；-1=未知/不可达）。 */
    public int dominator(int objectId) {
        if (ownTree()) {
            return objectId < idomOfId.length ? idomOfId[objectId] : -1;
        }
        try {
            return fallbackSnapshot.getImmediateDominatorId(objectId);
        } catch (SnapshotException e) {
            return -1;
        }
    }

    /** Retained Heap（字节），-1 表示未知。 */
    public long retained(int objectId) {
        if (ownTree()) {
            return objectId < retainedOfId.length ? retainedOfId[objectId] : -1;
        }
        return matRetainedCache.computeIfAbsent(objectId, id -> {
            try {
                return fallbackSnapshot.getRetainedHeapSize(id);
            } catch (SnapshotException e) {
                return -1L;
            }
        });
    }

    public Map<Integer, Long> classRetained() {
        return classRetained;
    }

    public Map<Integer, Long> classShallow() {
        return classShallow;
    }

    public Map<Integer, Integer> classCount() {
        return classCount;
    }

    public List<Integer> topLevelByRetained() {
        return topLevelIds;
    }

    // ---------- 写接口（仅包内使用）----------

    void setReachable(BitSet bits, int count) {
        this.reachable = bits;
        this.reachableCount = count;
    }

    void setTopLevelIds(List<Integer> ids) {
        this.topLevelIds = ids;
    }

    /** own 模式：扫描 idom==0 且可达的顶层组件并按 retained 排序。 */
    void buildTopLevelOwn(int capacity) {
        List<Integer> ids = new ArrayList<>();
        for (int i = 1; i < capacity && i < idomOfId.length; i++) {
            if (idomOfId[i] == 0 && reachable.get(i) && retainedOfId[i] > 0) {
                ids.add(i);
            }
        }
        ids.sort((a, b) -> Long.compare(retainedOfId[b], retainedOfId[a]));
        this.topLevelIds = ids;
    }

    /** mat 模式：以 GC Root 为顶层组件近似排序（降级路径）。 */
    void buildTopLevelMat(int[] gcRoots) {
        List<Integer> ids = new ArrayList<>(gcRoots.length);
        for (int id : gcRoots) {
            ids.add(id);
        }
        ids.sort((a, b) -> Long.compare(retained(b), retained(a)));
        this.topLevelIds = ids;
    }
}
