package com.flycode.toolresult;

import com.flycode.conversation.ToolResultBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ToolResultBudgetTest {

    private static List<ToolResultBlock> batch(int... sizes) {
        return java.util.stream.IntStream.range(0, sizes.length)
                .mapToObj(i -> new ToolResultBlock("t" + (i + 1), "x".repeat(sizes[i]), false))
                .toList();
    }

    private static long totalLen(List<ToolResultBlock> rs) {
        return rs.stream().mapToLong(r -> r.content().length()).sum();
    }

    @Test
    void underLimitUntouched(@TempDir Path dir) {
        var rs = batch(40_000, 40_000);
        var out = ToolResultBudget.apply(rs, ToolResultBudget.spillDir(dir.toString(), null), Set.of());
        assertEquals(rs.get(0).content(), out.get(0).content());
        assertEquals(rs.get(1).content(), out.get(1).content());
    }

    @Test
    void spillsLargestFirst(@TempDir Path dir) throws Exception {
        // 5 条合计 225K+1，只需溢写最大的 t3 即可回到限额内
        var rs = batch(45_000, 45_000, 45_001, 45_000, 45_000);
        var out = ToolResultBudget.apply(rs, ToolResultBudget.spillDir(dir.toString(), null), Set.of());

        assertTrue(totalLen(out) <= ToolResultBudget.MESSAGE_AGGREGATE_LIMIT,
                "aggregate must be within limit");
        long replaced = out.stream()
                .filter(r -> r.content().startsWith("<persisted-output>")).count();
        assertEquals(1, replaced, "exactly one result should be spilled");
        assertTrue(out.get(2).content().startsWith("<persisted-output>"),
                "largest result t3 should be the one spilled");

        Path spillFile = ToolResultBudget.spillDir(dir.toString(), null).resolve("t3.txt");
        assertEquals(45_001, Files.size(spillFile), "spill file must hold the full content");
    }

    @Test
    void exemptSkipped(@TempDir Path dir) {
        var rs = batch(45_000, 45_000, 45_001, 45_000, 45_000);
        var out = ToolResultBudget.apply(rs, ToolResultBudget.spillDir(dir.toString(), null), Set.of("t3"));

        assertFalse(out.get(2).content().startsWith("<persisted-output>"),
                "exempt t3 must not be spilled");
        assertTrue(totalLen(out) <= ToolResultBudget.MESSAGE_AGGREGATE_LIMIT);
    }

    @Test
    void allExemptAcceptsOverage(@TempDir Path dir) {
        var rs = batch(105_000, 105_000);
        var out = ToolResultBudget.apply(rs, ToolResultBudget.spillDir(dir.toString(), null), Set.of("t1", "t2"));
        assertEquals(rs.get(0).content(), out.get(0).content());
        assertEquals(rs.get(1).content(), out.get(1).content());
    }

    @Test
    void deterministicOutput(@TempDir Path dir) {
        var out1 = ToolResultBudget.apply(batch(45_000, 45_000, 45_001, 45_000, 45_000), ToolResultBudget.spillDir(dir.toString(), null), Set.of());
        var out2 = ToolResultBudget.apply(batch(45_000, 45_000, 45_001, 45_000, 45_000), ToolResultBudget.spillDir(dir.toString(), null), Set.of());
        for (int i = 0; i < out1.size(); i++) {
            assertEquals(out1.get(i).content(), out2.get(i).content(),
                    "same input must produce byte-identical output");
        }
    }

    @Test
    void idempotentOnProcessedBatch(@TempDir Path dir) {
        var once = ToolResultBudget.apply(batch(45_000, 45_000, 45_001, 45_000, 45_000), ToolResultBudget.spillDir(dir.toString(), null), Set.of());
        var twice = ToolResultBudget.apply(once, ToolResultBudget.spillDir(dir.toString(), null), Set.of());
        for (int i = 0; i < once.size(); i++) {
            assertEquals(once.get(i).content(), twice.get(i).content(),
                    "re-applying to a processed batch must be a no-op");
        }
    }

    @Test
    void isSpillReadback(@TempDir Path dir) {
        String inside = ToolResultBudget.spillDir(dir.toString(), null).resolve("toolu_abc.txt").toString();
        String outside = dir.resolve("Main.java").toString();

        assertTrue(ToolResultBudget.isSpillReadback("ReadFile", Map.of("file_path", inside), ToolResultBudget.spillDir(dir.toString(), null)));
        assertFalse(ToolResultBudget.isSpillReadback("ReadFile", Map.of("file_path", outside), ToolResultBudget.spillDir(dir.toString(), null)));
        assertFalse(ToolResultBudget.isSpillReadback("Bash", Map.of("file_path", inside), ToolResultBudget.spillDir(dir.toString(), null)));
        assertFalse(ToolResultBudget.isSpillReadback("ReadFile", Map.of(), ToolResultBudget.spillDir(dir.toString(), null)));
    }

    @Test
    void persistLargeResultRoundTrip(@TempDir Path dir) throws Exception {
        String content = "y".repeat(60_000);
        String preview = ToolResultBudget.persistLargeResult(ToolResultBudget.spillDir(dir.toString(), null), "t_big", content);

        assertTrue(preview.startsWith("<persisted-output>"));
        assertTrue(preview.contains("预览（前 2KB）"));
        Path spillFile = ToolResultBudget.spillDir(dir.toString(), null).resolve("t_big.txt");
        assertEquals(60_000, Files.size(spillFile));

        // 再次调用（文件已存在）返回逐字节相同的预览
        assertEquals(preview, ToolResultBudget.persistLargeResult(ToolResultBudget.spillDir(dir.toString(), null), "t_big", content));
    }
}
