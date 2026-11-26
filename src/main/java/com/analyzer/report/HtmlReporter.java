package com.analyzer.report;

import com.analyzer.analysis.Accumulated;
import com.analyzer.analysis.CodeLocation;
import com.analyzer.analysis.Finding;
import com.analyzer.analysis.GrowthReport;
import com.analyzer.analysis.RefPath;
import com.analyzer.graph.ClassInfo;
import com.analyzer.util.Fmt;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * HTML 报告生成器（FreeMarker 模板 + 内联 CSS，离线可看）。
 *
 * <p>输出结构：index.html（含内联样式）+ assets/style.css（同一样式源，
 * 方便二次加工）+ summary.json 由 JsonReporter 单独输出。</p>
 */
public final class HtmlReporter {

    /** 样式单一来源：同时用于 <style> 内联与 assets/style.css 落盘。 */
    private static final String CSS = """
            :root{--bg:#0f141a;--panel:#171f28;--panel2:#1d2733;--txt:#dbe4ee;--dim:#8fa1b3;
            --acc:#4fc3f7;--warn:#ffb74d;--bad:#ef5350;--ok:#66bb6a;--line:#2a3745}
            *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--txt);
            font:14px/1.6 "Segoe UI","Microsoft YaHei",sans-serif}
            .wrap{max-width:1180px;margin:0 auto;padding:24px 28px 64px}
            h1{font-size:26px;margin:8px 0 2px}h2{font-size:19px;margin:38px 0 12px;
            padding-left:10px;border-left:4px solid var(--acc)}
            .sub{color:var(--dim)}small{color:var(--dim)}
            .cards{display:flex;flex-wrap:wrap;gap:14px;margin-top:18px}
            .card{flex:1 1 150px;background:var(--panel);border:1px solid var(--line);
            border-radius:10px;padding:14px 16px}
            .card b{display:block;font-size:22px;color:var(--acc);margin-top:4px}
            table{width:100%;border-collapse:collapse;background:var(--panel);
            border:1px solid var(--line);border-radius:10px;overflow:hidden}
            th,td{padding:9px 12px;text-align:left;border-bottom:1px solid var(--line);
            vertical-align:top}th{background:var(--panel2);color:var(--dim);font-weight:600}
            tr:last-child td{border-bottom:none}
            td.num{text-align:right;font-variant-numeric:tabular-nums}
            .tag{display:inline-block;padding:1px 10px;border-radius:999px;font-size:12px;
            background:#26425a;color:var(--acc);margin-right:6px;white-space:nowrap}
            .tag.p{background:#5a2642;color:#f7a8c3}.tag.s{color:var(--warn)}
            .empty{color:var(--dim);background:var(--panel);border:1px dashed var(--line);
            border-radius:10px;padding:18px;text-align:center}
            .path{margin:10px 0 4px;padding:10px 12px;background:#101820;border-radius:8px;
            font-size:12.5px;overflow-x:auto;white-space:nowrap}
            .path .n{display:inline-block;padding:2px 8px;border-radius:6px;background:#22303d;
            border:1px solid var(--line)}
            .path .root{background:#1d3b2b;border-color:#2f5d44}
            .path .tgt{background:#40282c;border-color:#6b3a41}
            .path .via{color:var(--dim);margin:0 6px}
            .rec{margin:6px 0 0;padding-left:18px}.rec li{margin:2px 0}
            ul.det{margin:4px 0 0;padding-left:18px;color:var(--dim);font-size:12.5px}
            details summary{cursor:pointer;color:var(--acc);font-size:12.5px}
            .foot{margin-top:44px;color:var(--dim);font-size:12px;border-top:1px solid var(--line);
            padding-top:14px}
            a{color:var(--acc)}code{color:#f7c873;font-family:Consolas,monospace}
            .concl{background:var(--panel);border:1px solid var(--line);border-left:4px solid var(--acc);
            border-radius:10px;padding:14px 16px;margin:12px 0}
            .concl h3{margin:0 0 10px;font-size:16px;color:var(--txt);font-weight:600}
            .rank{color:var(--acc);margin-right:8px}
            .conf{display:inline-block;padding:1px 9px;border-radius:999px;font-size:11.5px;
            margin-right:8px;background:#26425a;color:var(--acc)}
            .conf.high{background:#1d3b2b;color:#7fd6a0}
            .conf.medium{background:#3d3520;color:var(--warn)}
            .conf.low{background:#3a2a2c;color:var(--bad)}
            .kv{display:flex;gap:10px;margin:7px 0}
            .kv .k{flex:0 0 74px;color:var(--dim);font-size:12.5px;padding-top:2px}
            .kv .v{flex:1;min-width:0}
            pre.frames{margin:8px 0 0;padding:10px 12px;background:#0b1016;border-radius:8px;
            font:12px/1.65 Consolas,"Courier New",monospace;overflow-x:auto;white-space:pre-wrap;
            border:1px solid var(--line)}
            pre.frames .frame{display:block;padding:0 4px;border-radius:4px;color:var(--dim)}
            pre.frames .frame.app{color:var(--acc)}
            pre.frames .frame.hl{background:#40282c;color:#ffd7db;font-weight:600}
            details.stack{margin-top:8px}
            """;

    private final Configuration cfg;
    private final int maxPath;

    public HtmlReporter(int maxPath) {
        this.maxPath = maxPath;
        cfg = new Configuration(Configuration.VERSION_2_3_32);
        cfg.setClassLoaderForTemplateLoading(getClass().getClassLoader(), "");
        cfg.setDefaultEncoding(StandardCharsets.UTF_8.name());
        cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
    }

    /** 渲染并落盘 index.html + assets/style.css。 */
    public void write(Path outDir, ReportData data) throws Exception {
        Files.createDirectories(outDir.resolve("assets"));
        Files.writeString(outDir.resolve("assets/style.css"), CSS, StandardCharsets.UTF_8);

        Template tpl = cfg.getTemplate("report-template.html");
        Map<String, Object> model = buildModel(data);
        try (Writer w = Files.newBufferedWriter(outDir.resolve("index.html"),
                StandardCharsets.UTF_8)) {
            tpl.process(model, w);
        }
    }

    // ------------------------------------------------------------------
    // 数据模型组装：所有动态文本在这里完成转义与格式化，模板只负责排版
    // ------------------------------------------------------------------

    private Map<String, Object> buildModel(ReportData d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("css", CSS);
        m.put("ov", overview(d));
        m.put("conclusions", conclusions(d));
        m.put("topClasses", topClasses(d));
        m.put("suspects", suspectRows(d.leakSuspects));
        m.put("largeObjects", plainRows(d.largeObjects));
        m.put("collections", plainRows(d.collections));
        m.put("threadLocals", plainRows(d.threadLocals));
        m.put("classLoaders", plainRows(d.classLoaders));
        m.put("growth", growthModel(d));
        return m;
    }

    /** ⑧ 基线对比模型。 */
    private Map<String, Object> growthModel(ReportData d) {
        Map<String, Object> m = new LinkedHashMap<>();
        GrowthReport g = d.growth;
        m.put("present", g != null);
        if (g == null) {
            return m;
        }
        m.put("baselinePath", Fmt.html(g.baselinePath));
        m.put("baselineUsedHeap", Fmt.mb(g.baselineUsedHeapBytes));
        m.put("baselineObjects", Fmt.num(g.baselineObjectCount));
        List<Map<String, Object>> rows = new ArrayList<>();
        int index = 1;
        for (GrowthReport.Row row : g.rows) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("index", index++);
            r.put("className", Fmt.html(row.className));
            r.put("countBefore", Fmt.num(row.beforeCount));
            r.put("countAfter", Fmt.num(row.afterCount));
            r.put("countDelta", Fmt.num(row.countDelta()));
            r.put("countPct", row.countPercent() < 0
                    ? "新出现" : String.format("%.1f%%", row.countPercent()));
            r.put("shallowBefore", Fmt.mb(row.beforeShallow));
            r.put("shallowAfter", Fmt.mb(row.afterShallow));
            r.put("shallowDelta", Fmt.mb(row.shallowDelta()));
            rows.add(r);
        }
        m.put("rows", rows);
        return m;
    }

    private Map<String, Object> overview(ReportData d) {
        ReportData.Overview o = d.overview;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("input", Fmt.html(o.inputPath));
        m.put("jvm", Fmt.html(o.jvmInfo));
        m.put("dumpTime", Fmt.html(o.dumpTime));
        m.put("usedHeap", Fmt.mb(o.usedHeapBytes));
        m.put("objects", Fmt.num(o.objectCount));
        m.put("reachable", Fmt.num(o.reachableCount));
        m.put("classes", Fmt.num(o.classCount));
        m.put("gcRoots", Fmt.num(o.gcRootCount));
        m.put("domMode", "own".equals(o.dominatorMode)
                ? "自建支配树（Lengauer-Tarjan）" : "降级：MAT 内置 retained 索引");
        m.put("generatedAt", Fmt.html(o.generatedAt));
        m.put("version", o.toolVersion);
        m.put("options", o.options.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("  ")));
        return m;
    }

    /** 首屏诊断结论：固定 6 段（代码线索 / 证据 / 规模 / 验证 / 建议 / 原始堆栈）。 */
    private List<Map<String, Object>> conclusions(ReportData d) {
        // 首屏用按代码位置去重后的结论，避免同一调用点的多个对象各占一张卡片
        List<Finding> src = d.conclusions != null && !d.conclusions.isEmpty()
                ? d.conclusions : d.leakSuspects;
        if (src == null || src.isEmpty()) {
            src = d.collections;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        int rank = 1;
        if (src != null) {
            for (Finding f : src) {
                if (rows.size() >= 3) {
                    break;
                }
                rows.add(conclusionRow(f, rank++));
            }
        }
        return rows;
    }

    private Map<String, Object> conclusionRow(Finding f, int rank) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("rank", rank);
        row.put("headline", Fmt.html(headline(f)));
        row.put("confidence", confidenceLabel(f.confidence));
        row.put("confClass", f.confidence == null ? "low" : f.confidence.toLowerCase());
        row.put("pattern", Fmt.html(f.patternLabel == null ? "未归类" : f.patternLabel));

        CodeLocation primary = f.primaryCodeLocation();
        row.put("codeLineHtml", primary == null
                ? "<span class=\"sub\">未提取到代码位置：该对象不在线程栈 / 局部变量 / 静态字段的直接持有路径上</span>"
                : "<code>" + Fmt.html(primary.display()) + "</code> <span class=\"sub\">["
                    + kindLabel(primary.kind) + "]</span>");

        List<String> evidence = new ArrayList<>();
        evidence.add("对象：" + brief(f.displayName, 140));
        evidence.add("类：" + f.className + " · Retained " + Fmt.mb(f.retainedBytes)
                + " · Shallow " + Fmt.mb(f.shallowBytes));
        if (f.threadName != null) {
            evidence.add("持有线程：" + f.threadName + "（#" + f.threadId + "）");
        }
        f.details.forEach((k, v) -> {
            if (!"累积对象".equals(k)) {
                evidence.add(k + "：" + v);
            }
        });
        for (Accumulated acc : f.accumulated) {
            evidence.add("累积对象：" + acc.describe()
                    + (acc.sampleDisplay == null ? "" : "，样本 " + acc.sampleDisplay));
        }
        for (CodeLocation loc : f.codeLocations) {
            if (loc != primary && loc.hasSourceLine()) {
                evidence.add("其他代码线索：" + loc.display() + "（" + kindLabel(loc.kind) + "）");
            }
        }
        if (Finding.CONFIDENCE_LOW.equals(f.confidence)) {
            evidence.add("证据不足：仅有引用链，无法确定增长原因；建议补充二次 dump 对比");
        }
        row.put("evidence", evidence.stream().map(Fmt::html).toList());

        row.put("scale", Fmt.html("Retained " + Fmt.mb(f.retainedBytes)
                + "（占整堆 " + String.format("%.1f", f.percentOfHeap) + "%）"
                + " · Shallow " + Fmt.mb(f.shallowBytes)));
        row.put("verify", RecommendationBuilder.verificationFor(f.pattern)
                .stream().map(Fmt::html).toList());
        row.put("recs", f.recommendations.stream().map(Fmt::html).toList());
        row.put("pathHtml", pathHtml(f.path));

        row.put("hasStack", !f.stackFrames.isEmpty());
        row.put("frameCount", f.stackFrames.size());
        row.put("stackTitle", f.threadName == null ? "" : "线程 " + Fmt.html(f.threadName));
        row.put("stackHtml", stackHtml(f));
        return row;
    }

    private String headline(Finding f) {
        String cls = shortName(f.className);
        // Class 对象：展示它代表的类名，而不是没信息量的 java.lang.Class
        if ("java.lang.Class".equals(f.className) && f.displayName != null
                && f.displayName.startsWith("class ")) {
            String represented = f.displayName.substring("class ".length()).trim();
            int at = represented.indexOf(" @ ");
            cls = shortName(at > 0 ? represented.substring(0, at) : represented);
        }
        String pct = String.format("%.1f", f.percentOfHeap);
        if (f.threadName != null) {
            return cls + "（线程 " + f.threadName + "）占堆 " + pct + "%";
        }
        return cls + " 占堆 " + pct + "%";
    }

    private static String confidenceLabel(String confidence) {
        if (Finding.CONFIDENCE_HIGH.equals(confidence)) {
            return "高置信";
        }
        if (Finding.CONFIDENCE_MEDIUM.equals(confidence)) {
            return "中等置信";
        }
        return "低置信";
    }

    private static String kindLabel(String kind) {
        if (CodeLocation.KIND_JAVA_LOCAL.equals(kind)) {
            return "Java Local 帧";
        }
        if (CodeLocation.KIND_STATIC_FIELD.equals(kind)) {
            return "静态字段";
        }
        if (CodeLocation.KIND_THREAD_STACK.equals(kind)) {
            return "线程栈";
        }
        return "证据";
    }

    /** 原始堆栈 HTML：业务帧为蓝色，命中帧高亮。 */
    private String stackHtml(Finding f) {
        if (f.stackFrames == null || f.stackFrames.isEmpty()) {
            return "";
        }
        Set<Integer> app = new HashSet<>(f.appFrameIndexes);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < f.stackFrames.size(); i++) {
            List<String> cls = new ArrayList<>();
            cls.add("frame");
            if (app.contains(i)) {
                cls.add("app");
            }
            if (i == f.stackFrameIndex) {
                cls.add("hl");
            }
            sb.append("<span class=\"").append(String.join(" ", cls)).append("\">")
              .append("#").append(i + 1).append("  ")
              .append(Fmt.html(f.stackFrames.get(i)))
              .append("</span>\n");
        }
        return sb.toString();
    }

    private List<Map<String, Object>> topClasses(ReportData d) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int rank = 1;
        for (ClassInfo c : d.topClasses) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", rank++);
            row.put("name", Fmt.html(c.className));
            row.put("objects", Fmt.num(c.numberOfObjects));
            row.put("shallow", Fmt.mb(c.shallowBytes));
            row.put("retained", Fmt.mb(c.retainedBytes));
            row.put("loader", Fmt.html(c.definingLoader == null ? "" : c.definingLoader));
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> suspectRows(List<Finding> list) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int rank = 1;
        for (Finding f : list) {
            Map<String, Object> row = baseRow(f);
            row.put("rank", rank++);
            row.put("score", String.format("%.1f", f.score));
            row.put("confidence", confidenceLabel(f.confidence));
            row.put("confClass", f.confidence == null ? "low" : f.confidence.toLowerCase());
            row.put("pattern", Fmt.html(f.patternLabel)
                    + " <code>" + Fmt.html(f.pattern) + "</code>");
            row.put("pathHtml", pathHtml(f.path));
            row.put("recs", f.recommendations.stream().map(Fmt::html).toList());
            row.put("details", detailList(f));
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> plainRows(List<Finding> list) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (list == null) {
            return rows;
        }
        for (Finding f : list) {
            Map<String, Object> row = baseRow(f);
            row.put("score", String.format("%.1f", f.score));
            row.put("pattern", Fmt.html(f.patternLabel));
            row.put("pathHtml", pathHtml(f.path));
            row.put("recs", List.of());
            row.put("details", detailList(f));
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> baseRow(Finding f) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("display", Fmt.html(f.displayName));
        row.put("clazz", Fmt.html(f.className));
        row.put("shallow", Fmt.mb(f.shallowBytes));
        row.put("retained", Fmt.mb(f.retainedBytes));
        return row;
    }

    private List<String> detailList(Finding f) {
        List<String> out = new ArrayList<>();
        f.details.forEach((k, v) -> out.add(Fmt.html(k + "：" + v)));
        return out;
    }

    /** 渲染引用链 HTML：GC Root（类型）→ via 字段 → … → 目标；超深截断。 */
    private String pathHtml(RefPath path) {
        if (path == null) {
            return "<div class='path'><span class='sub'>（GC Root 不可达：属于待回收对象）</span></div>";
        }
        StringBuilder sb = new StringBuilder("<div class='path'>");
        List<RefPath.Node> nodes = path.nodes();
        int shown = Math.min(nodes.size(), Math.max(2, maxPath));
        for (int i = 0; i < shown; i++) {
            RefPath.Node n = nodes.get(i);
            if (i > 0) {
                sb.append("<span class='via'>→ ")
                  .append(Fmt.html(n.incomingLabel == null ? "ref" : n.incomingLabel))
                  .append(" →</span>");
            }
            String cls = i == 0 ? "n root" : (i == nodes.size() - 1 ? "n tgt" : "n");
            String text = i == 0
                    ? "GC Root(" + Fmt.html(n.gcRootType == null ? "?" : n.gcRootType) + ") "
                            + Fmt.html(shortName(n.className))
                    : Fmt.html(shortName(n.className));
            sb.append("<span class='").append(cls).append("' title='")
              .append(Fmt.html(brief(n.displayName, 160))).append("'>").append(text).append("</span>");
        }
        if (nodes.size() > shown) {
            sb.append("<span class='via'>→ …（还有 ")
              .append(nodes.size() - shown).append(" 跳，--max-path=").append(maxPath)
              .append(" 截断） →</span>");
            RefPath.Node last = nodes.get(nodes.size() - 1);
            sb.append("<span class='n tgt'>").append(Fmt.html(shortName(last.className)))
              .append("</span>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    private static String brief(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String shortName(String clazz) {
        if (clazz == null) return "?";
        int dot = clazz.lastIndexOf('.');
        return dot > 0 && clazz.length() - dot < 40 ? clazz : clazz;
    }
}
