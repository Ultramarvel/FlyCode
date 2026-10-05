package com.flycode.teams;

import com.flycode.tool.Tool;
import com.flycode.tool.ToolCategory;
import com.flycode.tool.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 团队共享任务板工具：TaskCreate / TaskGet / TaskList / TaskUpdate。
 *
 * <p>这四个工具都挂在同一个团队的 {@link SharedTaskStore} 上，队友之间共享同一份
 * 任务列表，用于在多 Agent 协作时拆分、认领、跟踪任务。
 */
public final class TeamTaskTools {

    private TeamTaskTools() {}

    private static final Set<String> VALID_STATUSES =
            Set.of("pending", "in_progress", "completed", "blocked");

    /** 把工具参数里的字符串列表安全地取出来。 */
    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object raw) {
        if (raw instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object o : list) if (o != null) result.add(o.toString());
            return result;
        }
        return null;
    }

    // ── TaskCreate ─────────────────────────────────────────────────────

    public static class TaskCreateTool implements Tool {
        private final TeamManager teamMgr;
        private final String teamName;
        private final String agentName;

        public TaskCreateTool(TeamManager teamMgr, String teamName, String agentName) {
            this.teamMgr = teamMgr;
            this.teamName = teamName;
            this.agentName = agentName;
        }

        @Override public String name() { return "TaskCreate"; }
        @Override public ToolCategory category() { return ToolCategory.COMMAND; }

        @Override
        public String description() {
            return "Create a shared task in the team's task board. "
                    + "Supports dependency tracking with blocks/blocked_by fields.";
        }

        @Override
        public Map<String, Object> schema() {
            var props = new LinkedHashMap<String, Object>();
            props.put("title", Map.of("type", "string", "description", "Short task title"));
            props.put("description", Map.of("type", "string", "description", "Optional task details"));
            props.put("assignee", Map.of("type", "string", "description", "Teammate name to assign this task to"));
            props.put("blocks", Map.of("type", "array", "items", Map.of("type", "string"),
                    "description", "IDs of tasks that this task blocks"));
            props.put("blocked_by", Map.of("type", "array", "items", Map.of("type", "string"),
                    "description", "IDs of tasks that block this task"));
            return Map.of(
                    "name", name(),
                    "description", description(),
                    "input_schema", Map.of(
                            "type", "object",
                            "properties", props,
                            "required", List.of("title")));
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            String title = (String) args.get("title");
            if (title == null || title.isEmpty()) {
                return ToolResult.error("Error: 'title' is required");
            }
            SharedTaskStore store = teamMgr.getTaskStore(teamName);
            if (store == null) {
                return ToolResult.error("Task store not found for team '%s'".formatted(teamName));
            }
            String description = args.get("description") instanceof String s ? s : "";
            String assignee = args.get("assignee") instanceof String s ? s : "";
            var task = store.create(title, description, assignee,
                    stringList(args.get("blocks")), stringList(args.get("blocked_by")), agentName);
            return ToolResult.success(
                    "Task created:\n  ID: %s\n  Title: %s\n  Status: %s\n  Assignee: %s"
                            .formatted(task.id(), task.title(), task.status(),
                                    task.assignee().isEmpty() ? "(unassigned)" : task.assignee()));
        }
    }

    // ── TaskGet ────────────────────────────────────────────────────────

    public static class TaskGetTool implements Tool {
        private final TeamManager teamMgr;
        private final String teamName;

        public TaskGetTool(TeamManager teamMgr, String teamName) {
            this.teamMgr = teamMgr;
            this.teamName = teamName;
        }

        @Override public String name() { return "TaskGet"; }
        @Override public ToolCategory category() { return ToolCategory.READ; }

        @Override
        public String description() {
            return "Get details of a shared task by ID, including dependency information.";
        }

        @Override
        public Map<String, Object> schema() {
            var props = new LinkedHashMap<String, Object>();
            props.put("task_id", Map.of("type", "string", "description", "ID of the task to fetch"));
            return Map.of(
                    "name", name(),
                    "description", description(),
                    "input_schema", Map.of(
                            "type", "object",
                            "properties", props,
                            "required", List.of("task_id")));
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            String taskId = (String) args.get("task_id");
            if (taskId == null || taskId.isEmpty()) {
                return ToolResult.error("Error: 'task_id' is required");
            }
            SharedTaskStore store = teamMgr.getTaskStore(teamName);
            if (store == null) {
                return ToolResult.error("Task store not found for team '%s'".formatted(teamName));
            }
            var task = store.get(taskId);
            if (task == null) {
                return ToolResult.error("Task '%s' not found".formatted(taskId));
            }
            var lines = new ArrayList<String>();
            lines.add("Task %s:".formatted(task.id()));
            lines.add("  Title:      %s".formatted(task.title()));
            lines.add("  Status:     %s".formatted(task.status()));
            lines.add("  Assignee:   %s".formatted(task.assignee().isEmpty() ? "(unassigned)" : task.assignee()));
            lines.add("  Created by: %s".formatted(task.createdBy().isEmpty() ? "(unknown)" : task.createdBy()));
            if (!task.description().isEmpty()) lines.add("  Description: %s".formatted(task.description()));
            if (!task.blocks().isEmpty()) lines.add("  Blocks:     %s".formatted(String.join(", ", task.blocks())));
            if (!task.blockedBy().isEmpty()) lines.add("  Blocked by: %s".formatted(String.join(", ", task.blockedBy())));
            return ToolResult.success(String.join("\n", lines));
        }
    }

    // ── TaskList ───────────────────────────────────────────────────────

    public static class TaskListTool implements Tool {
        private final TeamManager teamMgr;
        private final String teamName;

        public TaskListTool(TeamManager teamMgr, String teamName) {
            this.teamMgr = teamMgr;
            this.teamName = teamName;
        }

        @Override public String name() { return "TaskList"; }
        @Override public ToolCategory category() { return ToolCategory.READ; }

        @Override
        public String description() {
            return "List all shared tasks in the team's task board. "
                    + "Optionally filter by status (pending/in_progress/completed/blocked) or assignee.";
        }

        @Override
        public Map<String, Object> schema() {
            var props = new LinkedHashMap<String, Object>();
            props.put("status", Map.of("type", "string", "description", "Filter by status"));
            props.put("assignee", Map.of("type", "string", "description", "Filter by assignee name"));
            return Map.of(
                    "name", name(),
                    "description", description(),
                    "input_schema", Map.of(
                            "type", "object",
                            "properties", props,
                            "required", List.of()));
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            SharedTaskStore store = teamMgr.getTaskStore(teamName);
            if (store == null) {
                return ToolResult.error("Task store not found for team '%s'".formatted(teamName));
            }
            String status = args.get("status") instanceof String s ? s : null;
            String assignee = args.get("assignee") instanceof String s ? s : null;
            var tasks = store.listTasks(status, assignee);
            if (tasks.isEmpty()) {
                var filters = new ArrayList<String>();
                if (status != null && !status.isEmpty()) filters.add("status=" + status);
                if (assignee != null && !assignee.isEmpty()) filters.add("assignee=" + assignee);
                String suffix = filters.isEmpty() ? "" : " (filters: %s)".formatted(String.join(", ", filters));
                return ToolResult.success("No tasks found" + suffix);
            }
            var icons = Map.of(
                    "pending", "○",
                    "in_progress", "◐",
                    "completed", "●",
                    "blocked", "✕");
            var lines = new ArrayList<String>();
            lines.add("Tasks (%d):".formatted(tasks.size()));
            for (var t : tasks) {
                String icon = icons.getOrDefault(t.status(), "?");
                String assigneeStr = t.assignee().isEmpty() ? "" : " [%s]".formatted(t.assignee());
                String deps = t.blockedBy().isEmpty() ? "" : " (blocked by: %s)".formatted(String.join(", ", t.blockedBy()));
                lines.add("  %s [%s] %s%s%s".formatted(icon, t.id(), t.title(), assigneeStr, deps));
            }
            return ToolResult.success(String.join("\n", lines));
        }
    }

    // ── TaskUpdate ─────────────────────────────────────────────────────

    public static class TaskUpdateTool implements Tool {
        private final TeamManager teamMgr;
        private final String teamName;

        public TaskUpdateTool(TeamManager teamMgr, String teamName) {
            this.teamMgr = teamMgr;
            this.teamName = teamName;
        }

        @Override public String name() { return "TaskUpdate"; }
        @Override public ToolCategory category() { return ToolCategory.COMMAND; }

        @Override
        public String description() {
            return "Update a shared task's status, assignee, description, or dependencies. "
                    + "Use add_blocks/add_blocked_by to add dependency relations.";
        }

        @Override
        public Map<String, Object> schema() {
            var props = new LinkedHashMap<String, Object>();
            props.put("task_id", Map.of("type", "string", "description", "ID of the task to update"));
            props.put("status", Map.of("type", "string",
                    "description", "New status: pending/in_progress/completed/blocked"));
            props.put("assignee", Map.of("type", "string", "description", "Teammate name to assign"));
            props.put("description", Map.of("type", "string", "description", "New description"));
            props.put("add_blocks", Map.of("type", "array", "items", Map.of("type", "string"),
                    "description", "Task IDs to add to the blocks list"));
            props.put("add_blocked_by", Map.of("type", "array", "items", Map.of("type", "string"),
                    "description", "Task IDs to add to the blocked_by list"));
            return Map.of(
                    "name", name(),
                    "description", description(),
                    "input_schema", Map.of(
                            "type", "object",
                            "properties", props,
                            "required", List.of("task_id")));
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            String taskId = (String) args.get("task_id");
            if (taskId == null || taskId.isEmpty()) {
                return ToolResult.error("Error: 'task_id' is required");
            }
            String status = args.get("status") instanceof String s ? s : null;
            if (status != null && !status.isEmpty() && !VALID_STATUSES.contains(status)) {
                return ToolResult.error("Invalid status '%s'. Must be one of: blocked, completed, in_progress, pending"
                        .formatted(status));
            }
            SharedTaskStore store = teamMgr.getTaskStore(teamName);
            if (store == null) {
                return ToolResult.error("Task store not found for team '%s'".formatted(teamName));
            }
            String assignee = args.get("assignee") instanceof String s ? s : null;
            String description = args.get("description") instanceof String s ? s : null;
            List<String> addBlocks = stringList(args.get("add_blocks"));
            List<String> addBlockedBy = stringList(args.get("add_blocked_by"));

            var task = store.update(taskId, status, assignee, description, addBlocks, addBlockedBy);
            if (task == null) {
                return ToolResult.error("Task '%s' not found".formatted(taskId));
            }
            var changes = new ArrayList<String>();
            if (status != null && !status.isEmpty()) changes.add("status → " + status);
            if (assignee != null) changes.add("assignee → " + (assignee.isEmpty() ? "(unassigned)" : assignee));
            if (description != null) changes.add("description updated");
            if (addBlocks != null && !addBlocks.isEmpty()) changes.add("blocks += " + String.join(", ", addBlocks));
            if (addBlockedBy != null && !addBlockedBy.isEmpty()) changes.add("blocked_by += " + String.join(", ", addBlockedBy));
            return ToolResult.success("Task %s updated: %s".formatted(
                    task.id(), changes.isEmpty() ? "no changes" : String.join("; ", changes)));
        }
    }
}
