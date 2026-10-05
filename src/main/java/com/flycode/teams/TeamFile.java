package com.flycode.teams;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 团队配置在磁盘上的形态，落在 &lt;teamsBaseDir&gt;/&lt;slug&gt;/config.json。
 *
 * <p>内存里的 {@code Member} 挂着 Agent 实例、ConversationManager 和线程句柄，这些都没法序列化，
 * 所以落盘用的是这份独立的纯数据结构，两边靠成员名字对应。
 *
 * <p>这份文件解决的是跨进程和跨重启：窗格队员是独立进程，起来之后要知道自己在哪个团队、
 * 队友都有谁；用户重启 FlyCode 之后也得能接着用之前的团队。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TeamFile {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public String name = "";
    public String description;
    public long createdAt;
    public String leadAgentId = "";
    public List<MemberEntry> members = new ArrayList<>();

    /**
     * 单个成员的元信息。{@code isActive} 用包装类型是为了保住三态语义：
     * null 表示刚注册还没开工，true 表示在跑，false 表示已空闲。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MemberEntry {
        public String agentId = "";
        public String name = "";
        public String agentType;
        public String model;
        public long joinedAt;
        public String worktreePath;
        public String backendType;
        public Boolean isActive;
    }

    /**
     * 把团队名压成可以直接当目录名的形式，非字母数字一律换成连字符再转小写。
     * 团队名是 LLM 起的，可能带空格、中文和标点，不处理会在不同文件系统上炸出各种问题。
     */
    public static String sanitizeTeamName(String name) {
        return name.replaceAll("[^a-zA-Z0-9]", "-").toLowerCase();
    }

    /**
     * 读取团队配置。文件不存在返回 null，让调用方按「没有这个团队」处理，
     * 而不是当成异常往上抛。
     */
    public static TeamFile read(Path configPath) {
        if (!Files.exists(configPath)) {
            return null;
        }
        try {
            return MAPPER.readValue(Files.readString(configPath), TeamFile.class);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 写入团队配置，目录不存在会一并创建。写失败只是让跨进程和跨重启失效，
     * 不影响内存里的团队继续工作，所以这里不往上抛。
     */
    public void write(Path configPath) {
        try {
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath,
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(this));
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
