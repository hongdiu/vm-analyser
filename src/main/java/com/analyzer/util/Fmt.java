package com.analyzer.util;

import java.nio.charset.StandardCharsets;

/**
 * 零散格式化工具：字节 → MB 文本、HTML 转义等。
 * 独立成类是为了让各 Detector / Reporter 复用同一套展示口径。
 */
public final class Fmt {

    public static final long MB = 1024L * 1024L;

    private Fmt() {
    }

    /** 字节数 → 保留两位小数的 MB 文本。 */
    public static String mb(long bytes) {
        return String.format("%.2f MB", bytes / (double) MB);
    }

    /** 字节数 → MB 数值（评分公式按 MB 计）。 */
    public static double asMb(long bytes) {
        return bytes / (double) MB;
    }

    /** 千分位整数文本。 */
    public static String num(long value) {
        return String.format("%,d", value);
    }

    /** 简易 HTML 转义（报告内容里的类名/字段名可能带尖括号，如 int[]、HashMap<K,V>）。 */
    public static String html(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** 安全获取 UTF-8 字节（仅测试/工具场景使用）。 */
    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
