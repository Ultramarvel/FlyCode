package com.flycode.tool.impl;

import com.flycode.tool.Tool;
import com.flycode.tool.ToolCategory;
import com.flycode.tool.ToolResult;
import com.flycode.worktree.WorktreeManager;
import com.flycode.worktree.WorktreeSessionStore;
import com.flycode.worktree.SlugValidator;

import java.security.SecureRandom;
import java.util.Map;

/**
 * Creates an isolated git worktree and switches the session into it.
 */
public class EnterWorktreeTool implements Tool {

    private final WorktreeManager worktreeManager;
    private final String sessionId;
    private static final SecureRandom RANDOM = new SecureRandom();

    public EnterWorktreeTool(WorktreeManager worktreeManager, String sessionId) {
        this.worktreeManager = worktreeManager;
        this.sessionId = sessionId;
    }

    @Override public String name() { return "EnterWorktree"; }
    @Override public ToolCategory category() { return ToolCategory.COMMAND; }

    @Override
    public String description() {
        return "Creates an isolated worktree (via git) and switches the session into it";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "name", Map.of(
                                        "type", "string",
                                        "description", "Optional name for the worktree. Max 64 chars."
                                )
                        )
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        if (WorktreeSessionStore.getCurrentSession() != null) {
            return ToolResult.error("Already in a worktree session");
        }

        String slug = args.containsKey("name") ? String.valueOf(args.get("name")) : null;
        if (slug == null || slug.isBlank()) {
            slug = "wt-" + Integer.toHexString(RANDOM.nextInt());
        }

        try {
            SlugValidator.validate(slug);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }

        try {
            var info = worktreeManager.create(slug, null);

            var session = new com.flycode.worktree.WorktreeSession(
                    System.getProperty("user.dir"),
                    info.path(),
                    slug,
                    info.branch(),
                    "", "", sessionId, 0
            );
            WorktreeSessionStore.restoreSession(session);
            WorktreeSessionStore.save(worktreeManager.getProjectRoot(), session);

            return ToolResult.success(
                    "Created worktree at %s on branch %s. The session is now working in the worktree. Use ExitWorktree to leave mid-session."
                            .formatted(info.path(), info.branch())
            );
        } catch (Exception e) {
            return ToolResult.error("Error creating worktree: " + e.getMessage());
        }
    }
}
