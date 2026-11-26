package com.analyzer.graph;

import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.model.GCRootInfo;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.IStackFrame;
import org.eclipse.mat.snapshot.model.IThreadStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 线程栈索引：把 hprof 里的线程栈（MAT 解析出的 {@code .threads}）读成
 * “线程 → 栈帧 → 帧内局部变量对象”的可查询结构。
 *
 * <p>这是报告“定位到代码”的证据源：栈帧文本自带
 * {@code 类.方法(文件:行号)}，局部变量表能把某个堆对象反查到它所在的栈帧。
 * MAT 的 {@link ISnapshot#getThreadStack(int)} 只在首次访问时读取索引文件，
 * 因此这里做一层按线程缓存，避免同一条结论多次解析。</p>
 */
public final class ThreadStackIndex {

    /** 单个线程最多展示/索引的帧数（栈一般 < 200 帧，这里留足余量）。 */
    public static final int MAX_FRAMES = 64;

    /** 一帧：原始文本（已去掉前缀 "at "）+ 该帧局部变量引用的对象 id。 */
    public static final class Frame {
        public final int index;
        public final String text;
        public final int[] localIds;

        Frame(int index, String text, int[] localIds) {
            this.index = index;
            this.text = text;
            this.localIds = localIds == null ? new int[0] : localIds;
        }
    }

    /** 一个线程的完整栈快照。 */
    public static final class Stack {
        public final int threadId;
        public final String threadName;
        public final List<Frame> frames;
        private Map<Integer, Integer> localToFrame;

        Stack(int threadId, String threadName, List<Frame> frames) {
            this.threadId = threadId;
            this.threadName = threadName;
            this.frames = frames;
        }

        /** 返回持有该对象的帧下标（帧内局部变量表命中），找不到返回 -1。 */
        public int frameOfLocal(int objectId) {
            if (localToFrame == null) {
                Map<Integer, Integer> map = new HashMap<>();
                for (Frame f : frames) {
                    for (int id : f.localIds) {
                        // 同一对象可能出现在多个帧的局部变量表里，保留最靠近栈顶（调用现场）的帧
                        map.putIfAbsent(id, f.index);
                    }
                }
                localToFrame = map;
            }
            Integer idx = localToFrame.get(objectId);
            return idx == null ? -1 : idx;
        }
    }

    private final ISnapshot snapshot;
    private final Map<Integer, Stack> stacks = new HashMap<>();

    public ThreadStackIndex(ISnapshot snapshot) {
        this.snapshot = snapshot;
    }

    /**
     * 该对象是否是“线程对象”GC Root（Thread Object）。
     */
    public boolean isThreadRoot(int objectId) {
        GCRootInfo[] infos = rootInfo(objectId);
        if (infos == null) {
            return false;
        }
        for (GCRootInfo info : infos) {
            if ((info.getType() & GCRootInfo.Type.THREAD_OBJ) != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 该对象是否是 Java Local 根（被某线程栈帧的局部变量持有）。
     *
     * @return 持有它的线程对象 id；不是 Java Local 根时返回 -1
     */
    public int javaLocalThreadId(int objectId) {
        GCRootInfo[] infos = rootInfo(objectId);
        if (infos == null) {
            return -1;
        }
        for (GCRootInfo info : infos) {
            if ((info.getType() & GCRootInfo.Type.JAVA_LOCAL) != 0) {
                return info.getContextId();
            }
        }
        return -1;
    }

    /** 取得线程栈（带缓存）；无栈信息/非线程对象返回 null。 */
    public Stack stackOf(int threadId) {
        if (threadId <= 0) {
            return null;
        }
        if (stacks.containsKey(threadId)) {
            return stacks.get(threadId);
        }
        Stack stack = build(threadId);
        stacks.put(threadId, stack);
        return stack;
    }

    private Stack build(int threadId) {
        try {
            IThreadStack raw = snapshot.getThreadStack(threadId);
            if (raw == null) {
                return null;
            }
            IStackFrame[] frames = raw.getStackFrames();
            if (frames == null || frames.length == 0) {
                return null;
            }
            List<Frame> list = new ArrayList<>();
            int limit = Math.min(frames.length, MAX_FRAMES);
            for (int i = 0; i < limit; i++) {
                String text = normalize(frames[i].getText());
                list.add(new Frame(i, text, frames[i].getLocalObjectsIds()));
            }
            return new Stack(threadId, threadName(threadId), list);
        } catch (SnapshotException | RuntimeException e) {
            // 线程栈解析失败不影响其他证据，按“无线索”处理
            return null;
        }
    }

    /** 去掉 hprof 文本里的 "at " 前缀与首尾空白。 */
    private static String normalize(String text) {
        if (text == null) {
            return "<unknown frame>";
        }
        String s = text.trim();
        if (s.startsWith("at ")) {
            s = s.substring(3).trim();
        }
        return s;
    }

    private String threadName(int threadId) {
        try {
            IObject obj = snapshot.getObject(threadId);
            Object name = obj.resolveValue("name");
            if (name instanceof String && !((String) name).isEmpty()) {
                return (String) name;
            }
            if (name instanceof IObject) {
                // MAT 有时把 String 字段解析成 IObject，displayName 是内容；
                // 若仍是 "java.lang.String @ 0x…" 这种对象描述则继续走兜底
                String value = ((IObject) name).getDisplayName();
                if (value != null && !value.isBlank() && !value.contains(" @ 0x")) {
                    return value.replace("\"", "").trim();
                }
            }
            String display = obj.getDisplayName();
            int sep = display.lastIndexOf("  ");
            return sep > 0 ? display.substring(sep + 2).trim() : display;
        } catch (SnapshotException | RuntimeException e) {
            return "thread#" + threadId;
        }
    }

    private GCRootInfo[] rootInfo(int objectId) {
        if (objectId <= 0) {
            return null;
        }
        try {
            GCRootInfo[] infos = snapshot.getGCRootInfo(objectId);
            return infos == null || infos.length == 0 ? null : infos;
        } catch (SnapshotException | RuntimeException e) {
            return null;
        }
    }
}
