package com.analyzer.parser;

import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.SnapshotFactory;
import org.eclipse.mat.util.IProgressListener;

import java.io.File;
import java.util.HashMap;

/**
 * HPROF 解析适配层。
 *
 * <p>职责：把 Eclipse MAT 的快照打开/释放流程包装成本项目统一入口，
 * 上层（graph/analysis/report）不直接接触 MAT API 的生命周期细节。</p>
 *
 * <p>大文件策略：MAT 采用"两段式索引解析"——
 * 第一阶段流式扫描 .hprof，把对象/引用/GC Root 信息落盘为 .index 索引文件
 * （outbound.xml、inbound.index、heap.bin 等），第二阶段查询时按需分段读取。
 * 因此本进程内存中不会同时持有整个对象图，解析几百 MB 甚至几 GB 的堆转储
 * 也不会轻易 OOM（索引文件默认生成在 hprof 同目录，需要目录可写）。</p>
 */
public final class HprofParserAdapter implements AutoCloseable {

    private final ISnapshot snapshot;

    private HprofParserAdapter(ISnapshot snapshot) {
        this.snapshot = snapshot;
    }

    /**
     * 打开（必要时先解析）一个 hprof 堆转储文件。
     *
     * @param hprofFile   .hprof 文件
     * @param listener    进度回调（用于终端输出解析进度）
     * @return 包装后的适配器
     */
    public static HprofParserAdapter open(File hprofFile, IProgressListener listener)
            throws Exception {
        // 先加载 Equinox plain 模式的扩展注册表（MAT 的快照读取器通过扩展点注册），
        // 再调用公开工厂入口，保证脱离 OSGi 框架也能定位到 HPROF 解析器。
        MatRuntimeBootstrap.ensureRegistryReady();
        HashMap<String, String> args = new HashMap<>();
        // 不保留不可达对象，减小索引体积；如需要分析 weak/soft 引用残留可改为 true
        args.put("keep_unreachable_objects", "false");
        // 解析心跳：MAT Pass2 在亿级对象上可能长时间无日志（并行流 + 磁盘索引），
        // 每 60 秒打印“已耗时 + 索引文件累计大小”，让使用方可判活（大小在涨=在干活）。
        Thread heartbeat = startHeartbeat(hprofFile);
        try {
            ISnapshot snap = SnapshotFactory.openSnapshot(hprofFile, args, listener);
            return new HprofParserAdapter(snap);
        } finally {
            if (heartbeat != null) {
                heartbeat.interrupt();
            }
        }
    }

    /** 启动解析心跳线程（守护线程，openSnapshot 返回后立即终止）。 */
    private static Thread startHeartbeat(File hprofFile) {
        final File dir = hprofFile.getParentFile();
        final String prefix = hprofFile.getName();
        final long t0 = System.currentTimeMillis();
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(60_000L);
                } catch (InterruptedException e) {
                    return;
                }
                long bytes = 0L;
                File[] files = dir == null ? null
                        : dir.listFiles((d, n) -> n.startsWith(prefix + ".")
                                && (n.endsWith(".index") || n.endsWith(".bin")
                                    || n.endsWith(".threads")));
                if (files != null) {
                    for (File f : files) {
                        bytes += f.length();
                    }
                }
                System.out.println("[heartbeat] parsing... elapsed "
                        + (System.currentTimeMillis() - t0) / 1000 + "s, index written "
                        + String.format("%.1f MB", bytes / 1048576.0)
                        + " (growing = still working, be patient)");
            }
        }, "mat-parse-heartbeat");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** 返回底层 MAT 快照，供图构建与各分析模块使用。 */
    public ISnapshot snapshot() {
        return snapshot;
    }

    @Override
    public void close() {
        // 释放快照占用的索引文件句柄（不会删除已生成的索引缓存）
        try {
            SnapshotFactory.dispose(snapshot);
        } catch (Exception ignore) {
            // 释放失败不影响结果
        }
    }
}
