package com.analyzer.analysis;

/**
 * 一条“代码位置”证据。
 *
 * <p>来源三种：线程栈帧、Java Local 局部变量所在帧、静态字段。
 * 报告首屏与 JSON 都用 {@link #display()} 的形态展示，方便直接跳到源码。</p>
 */
public final class CodeLocation {

    public static final String KIND_THREAD_STACK = "THREAD_STACK";
    public static final String KIND_JAVA_LOCAL = "JAVA_LOCAL";
    public static final String KIND_STATIC_FIELD = "STATIC_FIELD";

    /** 来源类型，取值见常量。 */
    public String kind;
    /** 业务类名（静态字段场景下是声明类）。 */
    public String className;
    /** 方法名；静态字段场景为 null。 */
    public String methodName;
    /** 源文件名（如 OrderService.java）；未知为 null。 */
    public String file;
    /** 行号；-1 表示未知（Native Method/Unknown Source）。 */
    public int line = -1;
    /** 字段名（仅静态字段场景）。 */
    public String fieldName;
    /** 所属线程名（若来自线程栈）。 */
    public String threadName;
    /** 线程对象 id。 */
    public int threadId = -1;
    /** 帧下标（-1 表示未定位到具体帧）。 */
    public int frameIndex = -1;
    /** 原始帧文本（含签名），便于展示与核对。 */
    public String rawText;
    /** 该帧是否落在业务代码（非框架/中间件包）。 */
    public boolean appCode;

    /** 是否给出了可点击定位的“文件:行号”。 */
    public boolean hasSourceLine() {
        return file != null && line > 0;
    }

    /** 完整展示：{@code com.foo.OrderService.queryAll(OrderService.java:88)}。 */
    public String display() {
        if (KIND_STATIC_FIELD.equals(kind)) {
            return (className == null ? "?" : className) + "."
                    + (fieldName == null ? "?" : fieldName);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(className == null ? "?" : className);
        if (methodName != null && !methodName.isEmpty()) {
            sb.append('.').append(methodName);
        }
        if (file != null) {
            sb.append('(').append(file);
            if (line > 0) {
                sb.append(':').append(line);
            }
            sb.append(')');
        }
        return sb.toString();
    }

    /** 简短展示：只保留简单类名 + 文件行号。 */
    public String shortDisplay() {
        if (KIND_STATIC_FIELD.equals(kind)) {
            return simpleName(className) + "." + fieldName;
        }
        String cls = simpleName(className);
        String m = methodName == null ? "" : "." + methodName;
        if (file == null) {
            return className == null ? "?" : className;
        }
        return cls + m + "(" + file + (line > 0 ? ":" + line : "") + ")";
    }

    private static String simpleName(String fqcn) {
        if (fqcn == null) {
            return "?";
        }
        int dot = fqcn.lastIndexOf('.');
        return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
    }

    /**
     * 解析栈帧文本：
     * {@code com.foo.Bar.baz([Ljava/lang/String;)V (Bar.java:88)}。
     * 无法解析的帧（如 {@code <unknown>}）返回 null。
     */
    public static FrameText parseFrame(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.startsWith("at ")) {
            text = text.substring(3).trim();
        }
        int paren = text.lastIndexOf(" (");
        if (paren <= 0 || !text.endsWith(")")) {
            return null;
        }
        String decl = text.substring(0, paren).trim();
        String loc = text.substring(paren + 2, text.length() - 1).trim();

        int methodParen = decl.indexOf('(');
        String owner = methodParen >= 0 ? decl.substring(0, methodParen) : decl;
        int dot = owner.lastIndexOf('.');
        if (dot <= 0 || dot == owner.length() - 1) {
            return null;
        }
        FrameText ft = new FrameText();
        ft.className = owner.substring(0, dot);
        ft.methodName = owner.substring(dot + 1);
        ft.rawText = text;

        if (loc.contains("(Native Method)")) {
            ft.file = loc.substring(0, loc.indexOf('('));
            ft.line = -1;
            return ft;
        }
        int colon = loc.lastIndexOf(':');
        if (colon > 0) {
            ft.file = loc.substring(0, colon);
            try {
                ft.line = Integer.parseInt(loc.substring(colon + 1).trim());
            } catch (NumberFormatException e) {
                ft.line = -1;
            }
        } else {
            ft.file = loc;
            ft.line = -1;
        }
        return ft;
    }

    /** 解析结果（内部使用）。 */
    public static final class FrameText {
        public String className;
        public String methodName;
        public String file;
        public int line = -1;
        public String rawText;
    }
}
