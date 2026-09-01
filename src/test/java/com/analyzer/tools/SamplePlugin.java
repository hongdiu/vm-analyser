package com.analyzer.tools;

/**
 * ClassLoader 泄漏样本用的“插件类”：
 * 字节码会被多个 PluginClassLoader 各自 define 一次（每个加载器一份 200KB 静态数据），
 * 保证自定义加载器在堆里真实持有可见数据。
 */
public class SamplePlugin {

    /** 每个副本约 200KB，方便在报告中看到加载器的 Retained。 */
    static final byte[] PAYLOAD = new byte[200 * 1024];

    public String hello() {
        return "sample-plugin@" + Integer.toHexString(System.identityHashCode(this));
    }
}
