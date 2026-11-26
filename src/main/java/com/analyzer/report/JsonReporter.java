package com.analyzer.report;

import com.analyzer.analysis.CodeLocation;
import com.analyzer.analysis.Finding;
import com.analyzer.analysis.GrowthReport;
import com.analyzer.analysis.RefPath;
import com.analyzer.graph.ClassInfo;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * summary.json 结构化摘要输出（便于 CI/脚本二次消费）。
 */
public final class JsonReporter {

    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .disableHtmlEscaping()
            .create();

    public void write(Path outDir, ReportData data) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("meta", meta(data));
        root.put("topClasses", topClasses(data));
        List<Map<String, Object>> suspects = findings(data.leakSuspects);
        List<Finding> conclusionSrc = data.conclusions != null && !data.conclusions.isEmpty()
                ? data.conclusions : data.leakSuspects;
        List<Map<String, Object>> conclusions = findings(conclusionSrc);
        root.put("conclusions", conclusions.subList(0, Math.min(3, conclusions.size())));
        root.put("leakSuspects", suspects);
        root.put("largeObjects", findings(data.largeObjects));
        root.put("suspiciousCollections", findings(data.collections));
        root.put("threadLocalHoldings", findings(data.threadLocals));
        root.put("classLoaderAnomalies", findings(data.classLoaders));
        root.put("growth", growth(data.growth));

        try (Writer w = Files.newBufferedWriter(outDir.resolve("summary.json"),
                StandardCharsets.UTF_8)) {
            gson.toJson(root, w);
        }
    }

    private Map<String, Object> meta(ReportData d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("input", d.overview.inputPath);
        m.put("jvm", d.overview.jvmInfo);
        m.put("dumpTime", d.overview.dumpTime);
        m.put("usedHeapBytes", d.overview.usedHeapBytes);
        m.put("objects", d.overview.objectCount);
        m.put("reachableObjects", d.overview.reachableCount);
        m.put("classes", d.overview.classCount);
        m.put("gcRoots", d.overview.gcRootCount);
        m.put("dominatorMode", d.overview.dominatorMode);
        m.put("generatedAt", d.overview.generatedAt);
        m.put("tool", "jvm-heap-analyzer " + d.overview.toolVersion);
        m.put("options", d.overview.options);
        return m;
    }

    private List<Map<String, Object>> topClasses(ReportData d) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ClassInfo c : d.topClasses) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("class", c.className);
            row.put("objects", c.numberOfObjects);
            row.put("shallowBytes", c.shallowBytes);
            row.put("retainedBytes", c.retainedBytes);
            rows.add(row);
        }
        return rows;
    }

    /** 基线对比（增长证据）；未提供 --baseline 时为 null。 */
    private Map<String, Object> growth(GrowthReport g) {
        if (g == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("baseline", g.baselinePath);
        m.put("baselineUsedHeapBytes", g.baselineUsedHeapBytes);
        m.put("baselineObjects", g.baselineObjectCount);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (GrowthReport.Row row : g.rows) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("class", row.className);
            r.put("countBefore", row.beforeCount);
            r.put("countAfter", row.afterCount);
            r.put("countDelta", row.countDelta());
            r.put("countPercent", row.countPercent() < 0 ? null : row.countPercent());
            r.put("shallowBeforeBytes", row.beforeShallow);
            r.put("shallowAfterBytes", row.afterShallow);
            r.put("shallowDeltaBytes", row.shallowDelta());
            rows.add(r);
        }
        m.put("growingClasses", rows);
        return m;
    }

    private List<Map<String, Object>> findings(List<Finding> list) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (list == null) {
            return rows;
        }
        for (Finding f : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("detector", f.detectorName);
            row.put("objectId", f.objectId);
            row.put("class", f.className);
            row.put("display", f.displayName);
            row.put("shallowBytes", f.shallowBytes);
            row.put("retainedBytes", f.retainedBytes);
            row.put("pattern", f.pattern);
            row.put("patternLabel", f.patternLabel);
            row.put("score", Math.round(f.score * 100) / 100.0);
            row.put("confidence", f.confidence);
            row.put("percentOfHeap", Math.round(f.percentOfHeap * 100) / 100.0);
            row.put("codeLocations", codeLocations(f));
            row.put("accumulated", accumulated(f));
            if (f.threadName != null) {
                Map<String, Object> thread = new LinkedHashMap<>();
                thread.put("id", f.threadId);
                thread.put("name", f.threadName);
                thread.put("frameCount", f.stackFrames.size());
                thread.put("matchedFrameIndex", f.stackFrameIndex);
                thread.put("frames", f.stackFrames);
                thread.put("appFrameIndexes", f.appFrameIndexes);
                row.put("thread", thread);
            }
            row.put("recommendations", f.recommendations);
            row.put("details", f.details);
            row.put("path", path(f.path));
            rows.add(row);
        }
        return rows;
    }

    /** 累积对象（按类聚合的支配子节点）。 */
    private List<Map<String, Object>> accumulated(Finding f) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (com.analyzer.analysis.Accumulated acc : f.accumulated) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("class", acc.className);
            m.put("count", acc.count);
            m.put("retainedBytes", acc.retainedBytes);
            m.put("sample", acc.sampleDisplay);
            list.add(m);
        }
        return list;
    }

    /** 代码位置证据（线程栈 / Java Local 帧 / 静态字段）。 */
    private List<Map<String, Object>> codeLocations(Finding f) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (CodeLocation loc : f.codeLocations) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", loc.kind);
            m.put("display", loc.display());
            m.put("class", loc.className);
            m.put("method", loc.methodName);
            m.put("file", loc.file);
            m.put("line", loc.line);
            m.put("appCode", loc.appCode);
            m.put("threadName", loc.threadName);
            m.put("frameIndex", loc.frameIndex);
            list.add(m);
        }
        return list;
    }

    private Object path(RefPath p) {        if (p == null) {
            return null;
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (RefPath.Node n : p.nodes()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("objectId", n.objectId);
            m.put("class", n.className);
            m.put("display", n.displayName);
            m.put("via", n.incomingLabel);
            if (n.gcRoot) {
                m.put("gcRootType", n.gcRootType);
            }
            nodes.add(m);
        }
        return nodes;
    }
}
