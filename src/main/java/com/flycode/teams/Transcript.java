package com.flycode.teams;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flycode.conversation.ConversationManager;
import com.flycode.conversation.Message;
import com.flycode.conversation.ToolResultBlock;
import com.flycode.conversation.ToolUseBlock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 队友对话记录的持久化与恢复。
 * 将队友的完整对话历史序列化为 JSON 文件，存储在
 * .flycode/teams/{teamName}/transcripts/{agentId}.json，
 * 用于调试和问题排查。
 */
public final class Transcript {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_EMPTY);

    private Transcript() {}

    // ── 序列化数据结构 ──────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record TranscriptEntry(
            @JsonProperty("role") String role,
            @JsonProperty("content") String content,
            @JsonProperty("tool_uses") List<TranscriptToolUse> toolUses,
            @JsonProperty("tool_results") List<TranscriptToolResult> toolResults
    ) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record TranscriptToolUse(
            @JsonProperty("tool_use_id") String toolUseId,
            @JsonProperty("tool_name") String toolName,
            @JsonProperty("arguments") Map<String, Object> arguments
    ) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record TranscriptToolResult(
            @JsonProperty("tool_use_id") String toolUseId,
            @JsonProperty("content") String content,
            @JsonProperty("is_error") boolean isError
    ) {}

    // ── 序列化/反序列化 ──────────────────────────────────────

    /**
     * 将对话历史序列化为可持久化的条目列表。
     */
    static List<TranscriptEntry> serializeConversation(ConversationManager conv) {
        var entries = new ArrayList<TranscriptEntry>();
        for (Message msg : conv.getMessages()) {
            List<TranscriptToolUse> toolUses = null;
            if (msg.getToolUses() != null && !msg.getToolUses().isEmpty()) {
                toolUses = msg.getToolUses().stream()
                        .map(tu -> new TranscriptToolUse(tu.toolUseId(), tu.toolName(), tu.arguments()))
                        .toList();
            }
            List<TranscriptToolResult> toolResults = null;
            if (msg.getToolResults() != null && !msg.getToolResults().isEmpty()) {
                toolResults = msg.getToolResults().stream()
                        .map(tr -> new TranscriptToolResult(tr.toolUseId(), tr.content(), tr.isError()))
                        .toList();
            }
            entries.add(new TranscriptEntry(msg.getRole(), msg.getContent(), toolUses, toolResults));
        }
        return entries;
    }

    /**
     * 从磁盘格式恢复对话管理器。
     */
    static ConversationManager deserializeConversation(List<TranscriptEntry> entries) {
        var conv = new ConversationManager();
        for (var e : entries) {
            var msg = new Message(e.role(), e.content() != null ? e.content() : "");
            if (e.toolUses() != null && !e.toolUses().isEmpty()) {
                msg.setToolUses(e.toolUses().stream()
                        .map(tu -> new ToolUseBlock(tu.toolUseId(), tu.toolName(), tu.arguments()))
                        .toList());
            }
            if (e.toolResults() != null && !e.toolResults().isEmpty()) {
                msg.setToolResults(e.toolResults().stream()
                        .map(tr -> new ToolResultBlock(tr.toolUseId(), tr.content(), tr.isError()))
                        .toList());
            }
            conv.getMessagesMutable().add(msg);
        }
        return conv;
    }

    // ── 公开 API ────────────────────────────────────────────────

    /**
     * 返回团队的 transcript 存储目录。
     */
    static Path transcriptDir(String teamName) {
        return Path.of(System.getProperty("user.dir"), ".flycode", "teams", teamName, "transcripts");
    }

    /**
     * 将队友的对话历史持久化到磁盘，用于调试和问题排查。
     * 文件路径为 .flycode/teams/{team}/transcripts/{agentId}.json。
     *
     * @return 写入的文件路径
     */
    public static Path saveTranscript(String teamName, String agentId, ConversationManager conv) throws IOException {
        Path dir = transcriptDir(teamName);
        Files.createDirectories(dir);
        Path path = dir.resolve(agentId + ".json");
        var data = serializeConversation(conv);
        Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(data));
        return path;
    }

    /**
     * 从磁盘加载队友的对话历史。
     * 文件不存在或解析失败时返回 null。
     */
    public static ConversationManager loadTranscript(String teamName, String agentId) {
        Path path = transcriptDir(teamName).resolve(agentId + ".json");
        if (!Files.exists(path)) {
            return null;
        }
        try {
            String json = Files.readString(path);
            var entries = MAPPER.readValue(json, new TypeReference<List<TranscriptEntry>>() {});
            return deserializeConversation(entries);
        } catch (IOException e) {
            return null;
        }
    }
}
