package com.analyzer.cli;

import com.analyzer.analysis.CodeLocation;
import com.analyzer.analysis.Finding;
import com.analyzer.analysis.LeakPatternMatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 首屏结论去重：同一代码位置的多个对象（线程根 / 累积点 / 其大子对象）
 * 只应占一条结论，其余并入证据。
 */
class AnalyzerMainConclusionsTest {

    @Test
    @DisplayName("结论去重：同一调用点的多个对象合并为一条，不同位置的结论补齐名额")
    void dedupByCodeLocation() {
        Finding thread = finding("java.lang.Thread", "http-nio-9430-exec-204", 1,
                184L * 1024 * 1024, 88, "未归类");
        Finding session = finding("org.hibernate.engine.internal.StatefulPersistenceContext",
                "http-nio-9430-exec-204", 1, 96L * 1024 * 1024, 88, "查询结果集全量累积");
        session.pattern = LeakPatternMatcher.RESULT_SET_ACCUMULATION;
        session.recommendations.add("确认查询是否缺少 LIMIT/分页");
        Finding array = finding("java.lang.Object[]",
                "http-nio-9431-exec-205", 2, 64L * 1024 * 1024, 88, "未归类");
        Finding other = finding("com.acme.Cache", null, -1, 32L * 1024 * 1024, 120, "未归类");

        List<Finding> picked = AnalyzerMain.distinctConclusions(
                List.of(thread, session, array, other), 3);

        assertEquals(2, picked.size(), "同一代码位置只保留一条，另一个位置补位");
        assertSame(thread, picked.get(0));
        assertSame(other, picked.get(1));

        String merged = thread.details.get("同一代码位置的其他对象");
        assertNotNull(merged, "被合并的对象信息不能丢");
        assertTrue(merged.contains("StatefulPersistenceContext"));
        assertTrue(merged.contains("查询结果集全量累积"), "被合并对象的模式应保留");
        assertTrue(merged.contains("http-nio-9431-exec-205"), "跨线程合并要标明线程");
        assertEquals("查询结果集全量累积", thread.patternLabel,
                "更具体的模式应提升为保留结论的模式，避免合并后丢掉行动建议");
        assertTrue(thread.recommendations.contains("确认查询是否缺少 LIMIT/分页"));
    }

    @Test
    @DisplayName("结论去重：无名额的重复项不再合并，无代码位置的结论保持独立")
    void keepsUnlocatedFindings() {
        Finding noLocation = new Finding(7, "com.acme.NoStack", "NoStack @ 0x1", 1, 2);
        Finding a = finding("java.lang.Thread", "t-1", 1, 1024, 88, "未归类");
        Finding b = finding("com.acme.Holder", "t-1", 1, 512, 88, "未归类");

        List<Finding> picked = AnalyzerMain.distinctConclusions(
                List.of(noLocation, a, b), 3);

        assertEquals(2, picked.size());
        assertSame(noLocation, picked.get(0));
        assertSame(a, picked.get(1));
        assertTrue(a.details.containsKey("同一代码位置的其他对象"));
        assertTrue(!noLocation.details.containsKey("同一代码位置的其他对象"));
    }

    private static Finding finding(String className, String threadName, int threadId,
                                   long retained, int line, String patternLabel) {
        Finding f = new Finding(1, className, className + " @ 0x0", 4096, retained);
        f.threadName = threadName;
        f.threadId = threadId;
        f.patternLabel = patternLabel;
        CodeLocation loc = new CodeLocation();
        loc.kind = CodeLocation.KIND_THREAD_STACK;
        loc.className = "com.acme.TransLabelService";
        loc.methodName = "findAll";
        loc.file = "TransLabelService.java";
        loc.line = line;
        loc.appCode = true;
        f.codeLocations.add(loc);
        return f;
    }
}
