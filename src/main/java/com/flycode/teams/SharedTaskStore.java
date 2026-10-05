package com.flycode.teams;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 团队共享任务库：以 JSON 文件（tasks.json）落盘，供同一团队的所有成员读写。
 *
 * <p>任务带依赖关系（blocks / blockedBy）和归属（assignee），支持
 * pending / in_progress / completed / blocked 四种状态。每次读操作前会先重新加载
 * 文件，保证跨进程的队友能拿到最新数据。
 */
public class SharedTaskStore {

    /** 单条共享任务。id 为字符串，与其它语言版本保持一致。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SharedTask(
            @JsonProperty("id") String id,
            @JsonProperty("title") String title,
            @JsonProperty("description") String description,
            @JsonProperty("status") String status,
            @JsonProperty("assignee") String assignee,
            @JsonProperty("blocks") List<String> blocks,
            @JsonProperty("blocked_by") List<String> blockedBy,
            @JsonProperty("created_by") String createdBy
    ) {
        public SharedTask {
            // 归一化可空的列表字段，避免下游出现 null
            blocks = blocks == null ? List.of() : blocks;
            blockedBy = blockedBy == null ? List.of() : blockedBy;
        }
    }

    /** tasks.json 的整体结构：下一个可用 id + 任务列表。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StoreData(
            @JsonProperty("next_id") int nextId,
            @JsonProperty("tasks") List<SharedTask> tasks
    ) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path filePath;
    private int nextId = 1;
    private final Map<String, SharedTask> tasks = new LinkedHashMap<>();

    public SharedTaskStore(Path filePath) {
        this.filePath = filePath;
        load();
    }

    /** 从磁盘重新读取任务列表；文件不存在时保持空。 */
    private synchronized void load() {
        if (!Files.exists(filePath)) {
            return;
        }
        try {
            StoreData data = MAPPER.readValue(filePath.toFile(), StoreData.class);
            tasks.clear();
            if (data.tasks() != null) {
                for (SharedTask t : data.tasks()) {
                    tasks.put(t.id(), t);
                }
            }
            nextId = data.nextId() > 0 ? data.nextId() : 1;
        } catch (IOException ignored) {
            // 读取失败时保持内存现状，不影响主流程
        }
    }

    /** 把当前任务列表写回磁盘。 */
    private synchronized void save() {
        try {
            Files.createDirectories(filePath.getParent());
            StoreData data = new StoreData(nextId, new ArrayList<>(tasks.values()));
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(filePath.toFile(), data);
        } catch (IOException ignored) {
        }
    }

    /** 创建一条共享任务，返回新任务。 */
    public synchronized SharedTask create(String title, String description, String assignee,
                                          List<String> blocks, List<String> blockedBy, String createdBy) {
        String id = String.valueOf(nextId++);
        SharedTask task = new SharedTask(
                id, title,
                description == null ? "" : description,
                "pending",
                assignee == null ? "" : assignee,
                blocks == null ? List.of() : List.copyOf(blocks),
                blockedBy == null ? List.of() : List.copyOf(blockedBy),
                createdBy == null ? "" : createdBy);
        tasks.put(id, task);
        save();
        return task;
    }

    /** 按 id 获取任务；读前先 reload 拿最新。 */
    public synchronized SharedTask get(String id) {
        load();
        return tasks.get(id);
    }

    /** 列出任务，可选按状态、归属人过滤。 */
    public synchronized List<SharedTask> listTasks(String status, String assignee) {
        load();
        List<SharedTask> result = new ArrayList<>();
        for (SharedTask t : tasks.values()) {
            if (status != null && !status.isEmpty() && !t.status().equals(status)) continue;
            if (assignee != null && !assignee.isEmpty() && !t.assignee().equals(assignee)) continue;
            result.add(t);
        }
        return result;
    }

    /**
     * 更新任务的状态、归属、描述或依赖；null 参数表示该字段不改。
     * addBlocks / addBlockedBy 追加依赖关系（去重）。任务不存在返回 null。
     */
    public synchronized SharedTask update(String id, String status, String assignee, String description,
                                          List<String> addBlocks, List<String> addBlockedBy) {
        load();
        SharedTask old = tasks.get(id);
        if (old == null) {
            return null;
        }
        String newStatus = status != null ? status : old.status();
        String newAssignee = assignee != null ? assignee : old.assignee();
        String newDescription = description != null ? description : old.description();

        List<String> newBlocks = new ArrayList<>(old.blocks());
        if (addBlocks != null) {
            for (String b : addBlocks) if (!newBlocks.contains(b)) newBlocks.add(b);
        }
        List<String> newBlockedBy = new ArrayList<>(old.blockedBy());
        if (addBlockedBy != null) {
            for (String b : addBlockedBy) if (!newBlockedBy.contains(b)) newBlockedBy.add(b);
        }

        SharedTask updated = new SharedTask(id, old.title(), newDescription, newStatus,
                newAssignee, List.copyOf(newBlocks), List.copyOf(newBlockedBy), old.createdBy());
        tasks.put(id, updated);
        save();
        return updated;
    }

    /** 清空任务库并落盘，用于新建团队时初始化。 */
    public synchronized void initEmpty() {
        tasks.clear();
        nextId = 1;
        save();
    }
}
