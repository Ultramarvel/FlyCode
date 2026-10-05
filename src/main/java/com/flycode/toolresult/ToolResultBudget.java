package com.flycode.toolresult;

import com.flycode.conversation.ToolResultBlock;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Layer 1 工具结果预算：超限的工具结果溢写到磁盘，对话历史里只留预览和
 * 文件路径。所有处理都发生在结果进入对话历史之前，消息一进历史就是终态，
 * 后续轮次发请求时逐字节保持原样，Prompt Cache 前缀天然稳定。
 */
public final class ToolResultBudget {

    /**
     * 单条消息内所有工具结果的总字符数上限。单条结果的大小由
     * {@code ToolRegistry.MAX_OUTPUT_CHARS} 把关，这里管的是聚合：
     * 一轮并行调多个工具时，每条都没超单条阈值，加起来却能撑爆上下文，
     * 这是单条阈值管不到的场景。
     */
    public static final int MESSAGE_AGGREGATE_LIMIT = 200_000;

    private ToolResultBudget() {}

    /**
     * 溢写目录：按会话隔离在 .flycode/sessions/ 会话id /tool-results 下，
     * 会话 id 为空（一次性调用、测试）时落到 default。
     */
    public static Path spillDir(String workDir, String sessionId) {
        String base = (workDir == null || workDir.isBlank()) ? "." : workDir;
        String id = (sessionId == null || sessionId.isBlank()) ? "default" : sessionId;
        return Paths.get(base, ".flycode", "sessions", id, "tool-results");
    }

    /**
     * 在一轮工具结果进入对话历史之前执行聚合预算：整批结果的总字符数
     * 超过 MESSAGE_AGGREGATE_LIMIT 时，从最大的开始逐条溢写到磁盘、
     * 替换成预览，直到总量回到限额内。
     *
     * <p>exemptIds 里的 tool_use_id 不参与溢写：溢写文件的回读结果
     * （再溢写模型就永远看不到全文），以及本轮已经单条溢写过的结果。
     * 全是豁免项时接受超额。
     *
     * @return 处理后的列表（入参不被修改，元素按需替换）
     */
    public static List<ToolResultBlock> apply(
            List<ToolResultBlock> results,
            Path spillDir,
            Set<String> exemptIds
    ) {
        long total = 0;
        for (ToolResultBlock tr : results) {
            total += tr.content().length();
        }
        if (total <= MESSAGE_AGGREGATE_LIMIT) {
            return results;
        }

        List<ToolResultBlock> out = new ArrayList<>(results);

        // 按内容长度降序挑选：先溢写最大的，回到限额内需要动的条数最少。
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) order.add(i);
        order.sort(Comparator.comparingInt((Integer i) -> out.get(i).content().length()).reversed());

        for (int idx : order) {
            if (total <= MESSAGE_AGGREGATE_LIMIT) break;
            ToolResultBlock tr = out.get(idx);
            if (exemptIds != null && exemptIds.contains(tr.toolUseId())) continue;
            if (tr.content().length() <= PREVIEW_CHARS) {
                // 比预览还短的结果，溢写换不回空间。
                continue;
            }
            Path file = writeSpill(spillDir, tr.toolUseId(), tr.content());
            if (file == null) {
                // 写盘失败就保留原文。消息随即定型进历史，不会再有重试。
                continue;
            }
            String preview = buildSpillPreview(tr.content(), file);
            total -= tr.content().length() - preview.length();
            out.set(idx, new ToolResultBlock(tr.toolUseId(), preview, tr.isError()));
        }
        return out;
    }

    /**
     * 判断一次工具调用是不是在读回溢写目录下的文件。这类结果不做溢写：
     * 把模型刚读回来的内容再写盘换成预览，模型就永远看不到全文，还会在
     * 「读回、溢写」之间打转。
     */
    public static boolean isSpillReadback(String toolName, Map<String, Object> args, Path spillDir) {
        if (!"ReadFile".equals(toolName) || args == null) return false;
        Object raw = args.get("file_path");
        if (!(raw instanceof String path) || path.isEmpty()) return false;
        try {
            String abs = Path.of(path).toAbsolutePath().normalize().toString();
            String spill = spillDir.toAbsolutePath().normalize().toString();
            return abs.startsWith(spill);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 将超大工具输出溢写到磁盘，返回预览文本。写盘失败时原样返回内容。
     * agent 在工具结果入对话历史时调用。
     */
    public static String persistLargeResult(Path spillDir, String toolUseId, String content) {
        Path file = writeSpill(spillDir, toolUseId, content);
        if (file == null) return content;
        return buildSpillPreview(content, file);
    }

    /** 存盘预览的最大字符数，取前 2KB 兼顾可读性和空间占用。 */
    private static final int PREVIEW_CHARS = 2_000;

    /**
     * 构造存盘替换文本，包含前 2KB 预览。相同输入产出逐字节相同的字符串，
     * 替换文本进入对话历史后不再改动。
     */
    private static String buildSpillPreview(String content, Path path) {
        int sizeKB = content.length() / 1024;
        String preview = content.length() <= PREVIEW_CHARS
                ? content : content.substring(0, PREVIEW_CHARS);
        boolean hasMore = content.length() > PREVIEW_CHARS;
        StringBuilder sb = new StringBuilder();
        sb.append("<persisted-output>\n");
        sb.append("输出太大（").append(sizeKB).append("KB），完整内容已保存到：\n");
        sb.append(path).append("\n\n");
        sb.append("预览（前 2KB）：\n").append(preview);
        if (hasMore) sb.append("\n...");
        sb.append("\n</persisted-output>");
        return sb.toString();
    }

    /**
     * 落盘工具结果全文。tool_use_id 每次调用唯一且内容确定，文件已存在时
     * 直接复用，不重复写。失败返回 null。
     */
    private static Path writeSpill(Path spillDir, String toolUseId, String content) {
        if (toolUseId == null || toolUseId.isEmpty()) return null;
        try {
            Files.createDirectories(spillDir);
            Path file = spillDir.resolve(toolUseId + ".txt");
            try {
                Files.writeString(file, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException e) {
                // 同一 tool_use_id 已落盘过，直接复用
            }
            return file;
        } catch (IOException e) {
            return null;
        }
    }
}
