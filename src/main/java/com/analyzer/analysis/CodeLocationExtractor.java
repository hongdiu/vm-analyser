package com.analyzer.analysis;

import com.analyzer.graph.HeapGraph;
import com.analyzer.graph.ThreadStackIndex;
import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.snapshot.model.Field;
import org.eclipse.mat.snapshot.model.IClass;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.ObjectReference;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 从引用链 + 线程栈里提取“能落到代码行”的证据。
 *
 * <p>规则（按可靠性排序）：</p>
 * <ol>
 *   <li>引用链上最靠近嫌疑对象的 Java Local 根 → 反查它所在的栈帧，精确到 {@code 文件:行号}；</li>
 *   <li>引用链根是线程对象 → 取该线程栈，找第一个非框架帧作为业务代码线索；</li>
 *   <li>链上出现 static 字段边 → 输出 {@code 声明类.字段名}。</li>
 * </ol>
 *
 * <p>所有原始栈帧都会原样保留到 {@link Finding#stackFrames}，报告里可点击展开，
 * 让结论能被人用 MAT/源码复核。</p>
 */
public final class CodeLocationExtractor {

    /** 默认视为“框架/中间件”的包前缀（用于从线程栈中挑出业务帧）。 */
    private static final String[] DEFAULT_FRAMEWORK_PREFIXES = {
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "org.springframework.", "org.hibernate.", "org.apache.",
            "com.mysql.", "com.zaxxer.", "com.tongweb.", "oracle.jdbc.",
            "io.netty.", "com.lmax.", "org.jboss.", "com.alibaba.",
            "org.eclipse.", "com.google.", "ch.qos.logback.", "org.slf4j.",
            "kotlin.", "scala."
    };

    private final HeapGraph graph;
    private final ThreadStackIndex stacks;
    private final DominatorResult dominators;
    private final long usedHeapBytes;
    private final List<String> appPrefixes;

    public CodeLocationExtractor(HeapGraph graph, ThreadStackIndex stacks,
                                 DominatorResult dominators, long usedHeapBytes,
                                 List<String> appPrefixes) {
        this.graph = graph;
        this.stacks = stacks;
        this.dominators = dominators;
        this.usedHeapBytes = usedHeapBytes;
        this.appPrefixes = appPrefixes == null ? List.of() : appPrefixes;
    }

    /** 为一条结论补齐代码位置、原始栈帧、占堆比例与置信度。 */
    public void apply(Finding f) {
        if (f == null) {
            return;
        }
        f.percentOfHeap = usedHeapBytes > 0
                ? Math.min(100.0, f.retainedBytes * 100.0 / usedHeapBytes) : 0.0;

        ThreadStackIndex.Stack stack = null;
        if (f.path != null && !f.path.nodes().isEmpty()) {
            List<RefPath.Node> nodes = f.path.nodes();
            // 先看根是不是线程对象（Thread Local 持有的大对象很常见）
            if (stacks.isThreadRoot(nodes.get(0).objectId)) {
                stack = stacks.stackOf(nodes.get(0).objectId);
            }
            // 否则从嫌疑对象往根找最近的 Java Local 根
            if (stack == null) {
                for (int i = nodes.size() - 1; i >= 0; i--) {
                    int threadId = stacks.javaLocalThreadId(nodes.get(i).objectId);
                    if (threadId > 0) {
                        stack = stacks.stackOf(threadId);
                        break;
                    }
                }
            }
        } else if (stacks.isThreadRoot(f.objectId)) {
            stack = stacks.stackOf(f.objectId);
        }

        int localFrameIndex = -1;
        if (stack != null) {
            f.threadId = stack.threadId;
            f.threadName = stack.threadName;
            List<String> frames = new ArrayList<>(stack.frames.size());
            for (ThreadStackIndex.Frame frame : stack.frames) {
                frames.add(frame.text);
                CodeLocation.FrameText ft = CodeLocation.parseFrame(frame.text);
                if (ft != null && !isFramework(ft.className)) {
                    f.appFrameIndexes.add(frame.index);
                }
            }
            f.stackFrames = frames;
            // 精确到帧：从嫌疑对象往根找第一个“被该线程某帧局部变量持有”的节点
            localFrameIndex = findLocalFrame(stack, f);
        }

        Set<String> seen = new HashSet<>();
        // Java Local 持有帧最精确，优先展示
        if (stack != null && localFrameIndex >= 0 && localFrameIndex < stack.frames.size()) {
            CodeLocation local = fromFrame(stack, localFrameIndex, CodeLocation.KIND_JAVA_LOCAL);
            if (local != null) {
                addIfNew(f, local, seen);
                f.stackFrameIndex = localFrameIndex;
            }
        }
        // 业务帧：栈里第一个非框架帧（作为调用现场补充）
        CodeLocation appLoc = stack == null ? null : firstAppFrame(stack);
        if (appLoc != null && !containsLocation(f, appLoc.display())) {
            addIfNew(f, appLoc, seen);
            if (f.stackFrameIndex < 0) {
                f.stackFrameIndex = appLoc.frameIndex;
            }
        }
        // 规则 3：静态字段（优先嫌疑类自身的大静态字段，其次链上的静态边）
        applyStaticFields(f, stack, seen);

        // 置信度：有业务代码行 = 高；有任意代码行/线程栈/静态字段 = 中；否则低
        boolean appLine = f.codeLocations.stream().anyMatch(l -> l.appCode && l.hasSourceLine());
        boolean anyLine = f.codeLocations.stream().anyMatch(CodeLocation::hasSourceLine);
        if (appLine) {
            f.confidence = Finding.CONFIDENCE_HIGH;
        } else if (anyLine || stack != null || !f.codeLocations.isEmpty()) {
            f.confidence = Finding.CONFIDENCE_MEDIUM;
        } else {
            f.confidence = Finding.CONFIDENCE_LOW;
        }
    }

    /**
     * 静态字段线索：
     * <ul>
     *   <li>嫌疑对象是 Class → 直接列出它 retained 最大的静态字段
     *       （如 {@code com.foo.Cache.GLOBAL_CACHE}）；</li>
     *   <li>否则取引用链上最后一个“业务类声明”的 static 边。</li>
     * </ul>
     */
    private void applyStaticFields(Finding f, ThreadStackIndex.Stack stack, Set<String> seen) {
        if (graph.isClass(f.objectId)) {
            List<CodeLocation> fields = staticFieldsOfClass(f.objectId, f.retainedBytes, stack);
            for (CodeLocation loc : fields) {
                addIfNew(f, loc, seen);
            }
            if (!fields.isEmpty()) {
                return;
            }
        }
        if (f.path == null) {
            return;
        }
        List<RefPath.Node> nodes = f.path.nodes();
        CodeLocation fallback = null;
        for (int i = 1; i < nodes.size(); i++) {
            String label = nodes.get(i).incomingLabel;
            if (label == null || !label.startsWith("static")) {
                continue;
            }
            RefPath.Node holder = nodes.get(i - 1);
            String owner = graph.isClass(holder.objectId)
                    ? graph.classNameOfClass(holder.objectId)
                    : holder.className;
            CodeLocation loc = new CodeLocation();
            loc.kind = CodeLocation.KIND_STATIC_FIELD;
            loc.className = owner;
            loc.fieldName = label.substring("static".length()).trim();
            loc.appCode = !isFramework(owner);
            if (stack != null) {
                loc.threadId = stack.threadId;
                loc.threadName = stack.threadName;
            }
            // 越靠近嫌疑对象的 static 边信息量越大；业务类的静态字段优先
            if (loc.appCode) {
                addIfNew(f, loc, seen);
                return;
            }
            fallback = loc;
        }
        if (fallback != null) {
            addIfNew(f, fallback, seen);
        }
    }

    /** 类对象上的大静态字段（按 retained 降序，最多 2 个）。 */
    private List<CodeLocation> staticFieldsOfClass(int classId, long classRetained,
                                                   ThreadStackIndex.Stack stack) {
        List<CodeLocation> result = new ArrayList<>();
        List<Object[]> hits = new ArrayList<>(); // {Field, retained, objectId}
        try {
            IObject obj = graph.raw().getObject(classId);
            if (!(obj instanceof IClass)) {
                return result;
            }
            long floor = Math.max(1L * 1024 * 1024, classRetained / 20);
            for (Field field : ((IClass) obj).getStaticFields()) {
                Object value = field.getValue();
                int refId = -1;
                if (value instanceof Number) {
                    long address = ((Number) value).longValue();
                    if (address == 0) {
                        continue;
                    }
                    try {
                        refId = graph.raw().mapAddressToId(address);
                    } catch (SnapshotException e) {
                        continue;
                    }
                } else if (value instanceof ObjectReference) {
                    // MAT 对引用型字段返回 ObjectReference（既不是 Number 也不是 IObject）
                    try {
                        refId = ((ObjectReference) value).getObjectId();
                    } catch (SnapshotException e) {
                        continue;
                    }
                } else if (value instanceof IObject) {
                    refId = ((IObject) value).getObjectId();
                }
                if (refId <= 0) {
                    continue;
                }
                long retained = dominators.retained(refId);
                if (retained < floor) {
                    continue;
                }
                hits.add(new Object[]{field, retained, refId});
            }
        } catch (SnapshotException | RuntimeException e) {
            return result;
        }
        hits.sort((a, b) -> Long.compare((long) b[1], (long) a[1]));
        for (int i = 0; i < Math.min(2, hits.size()); i++) {
            Field field = (Field) hits.get(i)[0];
            CodeLocation loc = new CodeLocation();
            loc.kind = CodeLocation.KIND_STATIC_FIELD;
            loc.className = graph.classNameOfClass(classId);
            loc.fieldName = field.getName();
            loc.appCode = !isFramework(loc.className);
            loc.rawText = graph.displayName((int) hits.get(i)[2]);
            if (stack != null) {
                loc.threadId = stack.threadId;
                loc.threadName = stack.threadName;
            }
            result.add(loc);
        }
        return result;
    }

    private void addIfNew(Finding f, CodeLocation loc, Set<String> seen) {
        String key = loc.kind + "|" + loc.display();
        if (seen.add(key)) {
            f.codeLocations.add(loc);
        }
    }

    private boolean containsLocation(Finding f, String display) {
        for (CodeLocation loc : f.codeLocations) {
            if (loc.display().equals(display)) {
                return true;
            }
        }
        return false;
    }

    /** 找出该线程栈里持有引用链上某个对象的帧（从嫌疑对象往根方向找）。 */
    private int findLocalFrame(ThreadStackIndex.Stack stack, Finding f) {
        if (f.path == null) {
            return -1;
        }
        List<RefPath.Node> nodes = f.path.nodes();
        for (int i = nodes.size() - 1; i >= 0; i--) {
            int index = stack.frameOfLocal(nodes.get(i).objectId);
            if (index >= 0) {
                return index;
            }
        }
        return -1;
    }

    /** 栈里第一个非框架帧；全是框架帧时返回 null。 */
    private CodeLocation firstAppFrame(ThreadStackIndex.Stack stack) {
        for (ThreadStackIndex.Frame frame : stack.frames) {
            CodeLocation.FrameText ft = CodeLocation.parseFrame(frame.text);
            if (ft == null || isFramework(ft.className)) {
                continue;
            }
            CodeLocation loc = toLocation(frame.index, ft, CodeLocation.KIND_THREAD_STACK);
            loc.appCode = true;
            return loc;
        }
        return null;
    }

    private CodeLocation fromFrame(ThreadStackIndex.Stack stack, int frameIndex, String kind) {
        ThreadStackIndex.Frame frame = stack.frames.get(frameIndex);
        CodeLocation.FrameText ft = CodeLocation.parseFrame(frame.text);
        if (ft == null) {
            CodeLocation loc = new CodeLocation();
            loc.kind = kind;
            loc.rawText = frame.text;
            loc.threadId = stack.threadId;
            loc.threadName = stack.threadName;
            loc.frameIndex = frameIndex;
            loc.appCode = false;
            return loc;
        }
        CodeLocation loc = toLocation(frameIndex, ft, kind);
        loc.threadId = stack.threadId;
        loc.threadName = stack.threadName;
        loc.appCode = !isFramework(ft.className);
        return loc;
    }

    private CodeLocation toLocation(int frameIndex, CodeLocation.FrameText ft, String kind) {
        CodeLocation loc = new CodeLocation();
        loc.kind = kind;
        loc.className = ft.className;
        loc.methodName = ft.methodName;
        loc.file = ft.file;
        loc.line = ft.line;
        loc.rawText = ft.rawText;
        loc.frameIndex = frameIndex;
        return loc;
    }

    /** 是否为框架/中间件帧：显式业务前缀优先，其次默认框架前缀，其余视为业务。 */
    private boolean isFramework(String className) {
        if (className == null) {
            return true;
        }
        for (String prefix : appPrefixes) {
            if (className.startsWith(prefix)) {
                return false;
            }
        }
        for (String prefix : DEFAULT_FRAMEWORK_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 解析 --app-prefix（逗号/分号分隔）。 */
    public static List<String> parsePrefixes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("[,;]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
