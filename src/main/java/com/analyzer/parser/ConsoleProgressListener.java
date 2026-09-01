package com.analyzer.parser;

import org.eclipse.mat.util.IProgressListener;

/**
 * 把 MAT 解析进度输出到终端的轻量监听器。
 * 只做阶段提示（英文输出，避免 Windows GBK 控制台中文乱码），
 * 进度条细节省略，防止超大 dump 刷屏。
 */
public final class ConsoleProgressListener implements IProgressListener {

    private final boolean verbose;
    private volatile boolean canceled;

    public ConsoleProgressListener(boolean verbose) {
        this.verbose = verbose;
    }

    @Override
    public void beginTask(String name, int totalWork) {
        log("[mat] " + name);
    }

    @Override
    public void done() {
        /* 无需清理 */
    }

    @Override
    public boolean isCanceled() {
        return canceled;
    }

    @Override
    public void setCanceled(boolean value) {
        this.canceled = value;
    }

    @Override
    public void subTask(String name) {
        log("[mat]   - " + name);
    }

    @Override
    public void worked(int work) {
        if (verbose) {
            System.out.print(".");
        }
    }

    @Override
    public void sendUserMessage(Severity severity, String message, Throwable error) {
        if (severity != Severity.ERROR || !verbose) {
            System.out.println("[mat][" + severity + "] " + message);
        }
    }

    private void log(String msg) {
        System.out.println(msg);
    }
}
