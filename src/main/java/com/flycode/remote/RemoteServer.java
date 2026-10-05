package com.flycode.remote;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flycode.agent.Agent;
import com.flycode.agent.AgentEvent;
import com.flycode.command.Command;
import com.flycode.command.CommandContext;
import com.flycode.command.CommandRegistry;
import com.flycode.compact.ContextCompactor;
import com.flycode.config.HookConfig;
import com.flycode.config.McpServerConfig;
import com.flycode.config.ProviderConfig;
import com.flycode.conversation.ConversationManager;
import com.flycode.filehistory.FileHistory;
import com.flycode.hook.HookEngine;
import com.flycode.llm.LlmClient;
import com.flycode.mcp.McpManager;
import com.flycode.memory.MemoryManager;
import com.flycode.permission.PermissionChecker;
import com.flycode.permission.PermissionMode;
import com.flycode.permission.PermissionResponse;
import com.flycode.plan.PlanFile;
import com.flycode.prompt.PromptBuilder;
import com.flycode.session.SessionManager;
import com.flycode.skill.SkillCatalog;
import com.flycode.subagent.AgentTool;
import com.flycode.subagent.SubAgentTaskManager;
import com.flycode.task.TaskList;
import com.flycode.task.TaskTools;
import com.flycode.teams.TeamManager;
import com.flycode.tool.ToolRegistry;
import com.flycode.tool.impl.AskUserTool;
import com.flycode.tool.impl.ToolSearchTool;
import com.flycode.tui.dialog.AskUserDialog;
import com.flycode.worktree.WorktreeManager;

import io.javalin.Javalin;
import io.javalin.websocket.WsContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Remote Control 服务器，桥接 Agent 事件和 WebSocket 客户端。
 * Remote Control 服务：桥接 Agent 事件到 WebSocket，广播状态、传递用户消息。
 */
public class RemoteServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── 配置 ──────────────────────────────────────────────────────────
    private final List<ProviderConfig> providers;
    private final List<McpServerConfig> mcpConfigs;
    private final List<HookConfig> hookConfigs;
    private final String addr;

    // ── WebSocket 连接池 ──────────────────────────────────────────────
    private final ReentrantLock connLock = new ReentrantLock();
    private final Set<WsContext> connections = ConcurrentHashMap.newKeySet();

    // ── Agent 核心组件 ────────────────────────────────────────────────
    private Agent agent;
    private ConversationManager conversation;
    private ToolRegistry registry;
    private LlmClient client;
    private String sessionId;
    private FileHistory fileHistory;
    private PermissionChecker permChecker;

    // ── 流式状态 ──────────────────────────────────────────────────────
    private volatile boolean streaming;
    private volatile Thread streamThread;
    private BlockingQueue<AgentEvent> agentQueue;

    // ── 权限和 ask_user 的待决响应 ────────────────────────────────────
    private final ReentrantLock pendingPermLock = new ReentrantLock();
    private final Map<String, CompletableFuture<PermissionResponse>> pendingPerms = new ConcurrentHashMap<>();

    private final ReentrantLock pendingAskLock = new ReentrantLock();
    private final Map<String, CompletableFuture<Map<String, String>>> pendingAsks = new ConcurrentHashMap<>();

    // ── 命令和功能模块 ────────────────────────────────────────────────
    private CommandRegistry cmdRegistry;
    private SkillCatalog skillCatalog;
    private MemoryManager memoryManager;
    private McpManager mcpManager;
    private TaskList taskList;
    private SubAgentTaskManager subAgentTaskManager;
    private TeamManager teamManager;
    private AskUserTool askUserTool;
    private HookEngine hookEngine;

    private String instructionsContent = "";
    private String memoryContent = "";
    private volatile String mcpInstructions = "";
    private final boolean enableCoordinatorMode;
    private final boolean forkDisabled;

    public RemoteServer(List<ProviderConfig> providers, List<McpServerConfig> mcpConfigs,
                        List<HookConfig> hookConfigs, String addr, boolean enableCoordinatorMode,
                        boolean forkDisabled) {
        this.providers = providers;
        this.mcpConfigs = mcpConfigs;
        this.hookConfigs = hookConfigs;
        this.addr = addr;
        this.enableCoordinatorMode = enableCoordinatorMode;
        this.forkDisabled = forkDisabled;
    }

    /**
     * 启动 HTTP + WebSocket 服务器。
     * 初始化 Agent 后监听指定地址，阻塞直到服务器关闭。
     */
    public void run() throws Exception {
        // 初始化 Agent（复刻 TUI 的 initializeProvider 流程）
        initAgent();
        // 连接 MCP 服务器
        initMcpServers();

        // 解析监听地址（格式 ":18888" 或 "0.0.0.0:18888"）
        int port = parsePort(addr);

        Javalin app = Javalin.create()
                .get("/", ctx -> {
                    ctx.contentType("text/html; charset=utf-8");
                    ctx.result(WebContent.INDEX_HTML);
                })
                .ws("/ws", ws -> {
                    ws.onConnect(ctx -> {
                        connections.add(ctx);
                        // 新连接推送 session 信息
                        broadcast(Map.of(
                                "type", "connected",
                                "data", Map.of(
                                        "session", sessionId,
                                        "cwd", System.getProperty("user.dir")
                                )
                        ));
                        // 推送命令列表
                        broadcast(Map.of(
                                "type", "commands",
                                "data", buildCommandList()
                        ));
                    });
                    ws.onClose(ctx -> connections.remove(ctx));
                    ws.onMessage(ctx -> handleWsMessage(ctx, ctx.message()));
                })
                .start("0.0.0.0", port);

        System.out.printf("%n  Remote UI: http://localhost:%d%n%n", port);

        // 阻塞主线程，让服务器持续运行
        Thread.currentThread().join();
    }

    // ────────────────────────────────────────────────────────────────────
    // Agent 初始化（镜像 FlyCodeModel.initializeProvider）
    // ────────────────────────────────────────────────────────────────────

    private void initAgent() {
        String workDir = System.getProperty("user.dir");
        ProviderConfig providerCfg = providers.get(0);

        // 记忆管理
        memoryManager = new MemoryManager(workDir);
        instructionsContent = MemoryManager.loadInstructions(workDir);

        // 构建系统提示词
        var env = PromptBuilder.detectEnvironment(providerCfg.getModel());
        var options = new PromptBuilder.BuildOptions();
        String systemPrompt = PromptBuilder.buildSystemPrompt(env, options);

        // 创建 LLM 客户端
        client = LlmClient.create(providerCfg, systemPrompt);
        String protocol = providerCfg.getProtocol();

        // 工具注册
        registry = ToolRegistry.createDefault();
        registry.register(new ToolSearchTool(registry, protocol));
        // mcp_call 必须在连 MCP 之前就注册好：连上之后再往 tools[] 里加，
        // 就是一次中途改动工具数组，缓存前缀照样断
        registry.register(new com.flycode.tool.impl.McpCallTool(registry));

        var exitPlanTool = new com.flycode.tool.impl.ExitPlanModeTool();
        exitPlanTool.setIsPlanMode(() -> permChecker != null && permChecker.getMode() == PermissionMode.PLAN);
        exitPlanTool.setPlanExists(() -> PlanFile.planExists());
        registry.register(exitPlanTool);

        // AskUser 工具：Remote 模式通过事件队列桥接到 WebSocket
        askUserTool = new AskUserTool();
        registry.register(askUserTool);

        // 子 Agent 工具
        var agentToolRef = new AgentTool(client, registry, protocol, providerCfg);
        agentToolRef.setForkDisabled(forkDisabled);
        subAgentTaskManager = new SubAgentTaskManager();
        agentToolRef.setTaskManager(subAgentTaskManager);
        registry.register(agentToolRef);

        // Worktree 工具
        var worktreeManager = new WorktreeManager(workDir, List.of(), 720);
        agentToolRef.setWorktreeManager(worktreeManager);
        sessionId = SessionManager.newId();
        registry.register(new com.flycode.tool.impl.EnterWorktreeTool(worktreeManager, sessionId));
        registry.register(new com.flycode.tool.impl.ExitWorktreeTool(worktreeManager));

        // 任务工具
        taskList = new TaskList("default", workDir);
        registry.register(new TaskTools.TaskCreateTool(taskList));
        registry.register(new TaskTools.TaskGetTool(taskList));
        registry.register(new TaskTools.TaskListTool(taskList));
        registry.register(new TaskTools.TaskUpdateTool(taskList));

        // 团队工具
        teamManager = new TeamManager();
        agentToolRef.setTeamManager(teamManager);
        registry.register(new com.flycode.teams.TeamTools.TeamCreateTool(teamManager));
        registry.register(new com.flycode.teams.TeamTools.TeamDeleteTool(teamManager));
        registry.register(new com.flycode.teams.TaskStopTool(teamManager));
        registry.register(new com.flycode.tool.SyntheticOutputTool());
        registry.register(new com.flycode.teams.TeamTools.SendMessageTool(teamManager, "lead"));

        // 权限检查器
        permChecker = new PermissionChecker(PermissionMode.DEFAULT, Path.of(workDir));

        // 会话和文件历史
        fileHistory = new FileHistory(workDir, sessionId);
        var fileStateCache = new com.flycode.tool.FileStateCache();
        for (var tool : registry.listTools()) {
            if (tool instanceof com.flycode.tool.impl.EditFileTool ef) {
                ef.setFileHistory(fileHistory);
                ef.setFileStateCache(fileStateCache);
            }
            if (tool instanceof com.flycode.tool.impl.WriteFileTool wf) {
                wf.setFileHistory(fileHistory);
                wf.setFileStateCache(fileStateCache);
            }
            if (tool instanceof com.flycode.tool.impl.ReadFileTool rf) {
                rf.setFileStateCache(fileStateCache);
            }
        }

        // 构建 Agent
        conversation = new ConversationManager();
        agent = new Agent(client, registry, protocol, providerCfg);
        agent.setFileHistory(fileHistory);
        agent.setInstructions(instructionsContent);
        agent.setMemoryContent(memoryContent);
        agent.setChecker(permChecker);
        agent.setWorkDir(workDir);
        agent.setSessionId(sessionId);

        // 通知函数：排空团队邮箱和任务通知
        agent.setNotificationFn(() -> {
            var notes = new ArrayList<String>();
            notes.addAll(com.flycode.teams.TeammateRunner.drainLeadMailbox(teamManager));
            if (subAgentTaskManager != null) {
                for (var n : subAgentTaskManager.drainNotifications()) {
                    notes.add("<task-notification>Task %s: %s (%s)</task-notification>"
                            .formatted(n.taskId(), n.name(), n.status()));
                }
            }
            return notes;
        });

        // 工具名过滤器（团队协调模式）
        agent.setToolNameFilter(name -> {
            if (!enableCoordinatorMode) return true;
            return com.flycode.teams.Coordinator.isCoordinatorTool(name);
        });
        agent.setCoordinatorActiveFn(() ->
                com.flycode.teams.Coordinator.isActive(enableCoordinatorMode));

        // 子 Agent 关联
        if (registry.get("Agent") instanceof AgentTool at) {
            at.setProgressListener(progress -> {}); // remote 模式不需要 TUI 进度回调
        }

        // Hook 引擎
        hookEngine = new HookEngine();
        if (hookConfigs != null && !hookConfigs.isEmpty()) {
            List<HookEngine.Hook> hooks = hookConfigs.stream().map(hc -> {
                HookEngine.EventName event = parseEventName(hc.getEvent());
                HookEngine.ActionType actionType = parseActionType(hc.getType());
                Duration timeout = hc.getTimeout() > 0
                        ? Duration.ofSeconds(hc.getTimeout()) : Duration.ZERO;
                var action = new HookEngine.Action(actionType, hc.getCommand(), hc.getMessage(),
                        hc.getUrl(), hc.getMethod(), hc.getHeaders(), hc.getBody(), timeout);
                return new HookEngine.Hook(hc.getId(), event, hc.getCondition(), action,
                        hc.isReject(), hc.isOnce(), hc.isAsync(), hc.getOnError());
            }).toList();
            hookEngine.loadHooks(hooks);
        }
        agent.setHookEngine(hookEngine);

        // Skill 加载
        skillCatalog = new SkillCatalog();
        var skillDir = Path.of(workDir, ".flycode", "skills");
        if (Files.isDirectory(skillDir)) {
            skillCatalog.loadFromDirectory(skillDir);
        }
        // Skill 清单跟着项目走，随首条 system-reminder 注入对话，不进系统提示词
        agent.setSkillSection(skillCatalog.buildSection(workDir));

        // 命令注册
        cmdRegistry = new CommandRegistry();
    }

    // ────────────────────────────────────────────────────────────────────
    // MCP 服务器连接
    // ────────────────────────────────────────────────────────────────────

    private void initMcpServers() {
        if (mcpConfigs == null || mcpConfigs.isEmpty()) return;

        try {
            mcpManager = new McpManager(mcpConfigs);
            var result = mcpManager.connectAll();
            for (var t : result.tools()) registry.register(t);
            for (var e : result.errors()) System.err.println("MCP error: " + e);

            // 构建 MCP 指令（首次用户消息时注入到对话）
            if (!result.servers().isEmpty()) {
                var mcpParts = new ArrayList<String>();
                for (var s : result.servers()) {
                    var sb = new StringBuilder();
                    sb.append("## ").append(s.name()).append("\n");
                    if (s.instructions() != null && !s.instructions().isBlank()) {
                        sb.append(s.instructions()).append("\n");
                    }
                    var toolNames = registry.listTools().stream()
                            .filter(t -> t.name().startsWith(McpManager.mcpToolNamePrefix(s.name())))
                            .map(com.flycode.tool.Tool::name)
                            .toList();
                    if (!toolNames.isEmpty()) {
                        sb.append("\nAvailable tools: ").append(String.join(", ", toolNames));
                    }
                    mcpParts.add(sb.toString());
                }
                mcpInstructions = "# MCP Server Instructions\n\n"
                        + "The following MCP servers are connected. Use their tools when the user asks.\n\n"
                        + String.join("\n\n", mcpParts);
            }

            // 工具都在位了才算得准 schema 总量跟上下文窗口的比例
            var providerCfg = providers.get(0);
            com.flycode.mcp.McpLoadingStrategy.decideAndApply(
                    registry, providerCfg.getBaseUrl(), providerCfg.resolvedContextWindow());
        } catch (Exception e) {
            System.err.println("MCP init failed: " + e.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // WebSocket 消息处理
    // ────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void handleWsMessage(WsContext ctx, String raw) {
        try {
            var msg = MAPPER.readValue(raw, Map.class);
            String type = (String) msg.get("type");
            Object data = msg.get("data");

            switch (type) {
                case "user_message" -> {
                    if (data instanceof Map<?, ?> d) {
                        String content = (String) d.get("content");
                        // 用虚拟线程处理用户消息，避免阻塞 WS 读循环
                        Thread.startVirtualThread(() -> handleUserMessage(content));
                    }
                }
                case "permission_response" -> {
                    if (data instanceof Map<?, ?> d) {
                        String id = (String) d.get("id");
                        String response = (String) d.get("response");
                        handlePermissionResponse(id, response);
                    }
                }
                case "ask_user_response" -> {
                    if (data instanceof Map<?, ?> d) {
                        String id = (String) d.get("id");
                        @SuppressWarnings("unchecked")
                        Map<String, String> answers = (Map<String, String>) d.get("answers");
                        handleAskUserResponse(id, answers);
                    }
                }
                case "cancel" -> {
                    // 中断当前流式响应
                    Thread t = streamThread;
                    if (t != null) t.interrupt();
                }
                case "ping" -> {
                    // 应用层保活：回复 pong
                    broadcast(Map.of("type", "pong"));
                }
            }
        } catch (Exception e) {
            System.err.println("WebSocket message parse error: " + e.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 用户消息处理
    // ────────────────────────────────────────────────────────────────────

    private void handleUserMessage(String content) {
        if (streaming) return;

        content = content != null ? content.strip() : "";
        if (content.isEmpty()) return;

        // 斜杠命令处理
        if (content.startsWith("/")) {
            handleSlashCommand(content);
            return;
        }

        streaming = true;
        streamThread = Thread.currentThread();
        String workDir = System.getProperty("user.dir");
        SessionManager.saveMessage(workDir, sessionId, "user", content);
        conversation.addUserMessage(content);

        // 首次消息时注入 MCP 指令
        if (!mcpInstructions.isEmpty()) {
            conversation.addSystemReminder(mcpInstructions);
            mcpInstructions = "";
        }

        // 启动 Agent 并消费事件
        agentQueue = agent.run(conversation);
        if (askUserTool != null) askUserTool.setEventQueue(agentQueue);

        try {
            consumeAgentEvents();
        } finally {
            streaming = false;
            streamThread = null;
            agentQueue = null;
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 斜杠命令处理
    // ────────────────────────────────────────────────────────────────────

    private void handleSlashCommand(String input) {
        try {
            // 解析命令名和参数
            String trimmed = input.substring(1).strip();
            String name, args;
            int spaceIdx = trimmed.indexOf(' ');
            if (spaceIdx < 0) {
                name = trimmed;
                args = "";
            } else {
                name = trimmed.substring(0, spaceIdx);
                args = trimmed.substring(spaceIdx + 1).strip();
            }

            if (name.isEmpty()) return;

            var cmd = cmdRegistry.find(name);
            if (cmd.isEmpty()) {
                broadcast(Map.of("type", "error", "data",
                        Map.of("message", "Unknown command: /" + name + " -- type /help to see available commands")));
                broadcast(Map.of("type", "command_done"));
                return;
            }

            Command c = cmd.get();
            var ctx = buildCommandContext(args);

            switch (c.type()) {
                case LOCAL -> {
                    String result = cmdRegistry.execute(name, ctx);
                    if (result != null && !result.isEmpty()) {
                        broadcast(Map.of("type", "system", "data", Map.of("message", result)));
                    }
                    broadcast(Map.of("type", "command_done"));
                }
                case LOCAL_UI -> {
                    switch (name) {
                        case "clear" -> {
                            conversation = new ConversationManager();
                            broadcast(Map.of("type", "clear"));
                        }
                        case "compact" -> {
                            handleCompact();
                            return; // compact 自己管 command_done
                        }
                        case "plan" -> handlePlan(args);
                        case "resume" -> {
                            handleResume(args);
                            return; // resume 自己管 command_done
                        }
                        case "rewind" -> broadcast(Map.of("type", "system", "data",
                                Map.of("message", "Rewind is not yet supported in remote mode.")));
                    }
                    broadcast(Map.of("type", "command_done"));
                }
                case PROMPT -> {
                    String prompt = cmdRegistry.execute(name, ctx);
                    if (prompt == null || prompt.isBlank()) return;

                    String displayText = "/" + name;
                    if (!args.isEmpty()) displayText += " " + args;

                    // PROMPT 命令生成 prompt 注入给 Agent
                    streaming = true;
                    streamThread = Thread.currentThread();
                    String workDir = System.getProperty("user.dir");
                    SessionManager.saveMessage(workDir, sessionId, "user", displayText);
                    conversation.addUserMessage(prompt);

                    if (!mcpInstructions.isEmpty()) {
                        conversation.addSystemReminder(mcpInstructions);
                        mcpInstructions = "";
                    }

                    agentQueue = agent.run(conversation);
                    if (askUserTool != null) askUserTool.setEventQueue(agentQueue);

                    try {
                        consumeAgentEvents();
                    } finally {
                        streaming = false;
                        streamThread = null;
                        agentQueue = null;
                    }
                }
            }
        } catch (Exception e) {
            broadcast(Map.of("type", "error", "data",
                    Map.of("message", "Command error: " + e.getMessage())));
        }
    }

    private CommandContext buildCommandContext(String args) {
        String workDir = System.getProperty("user.dir");
        String model = providers.get(0).getModel();
        return new CommandContext(
                args,
                workDir,
                model,
                () -> permChecker != null ? permChecker.getMode().name().toLowerCase() : "default",
                () -> registry != null ? registry.listTools().size() : 0,
                () -> new int[]{0, 0},
                () -> memoryManager != null ? memoryManager.getMemories() : List.of(),
                () -> { if (memoryManager != null) memoryManager.clear(); },
                () -> sessionId != null ? "Session: " + sessionId : "No active session",
                () -> skillCatalog != null
                        ? skillCatalog.list().stream().map(s -> s.name()).toList()
                        : List.of(),
                () -> 0,
                () -> mcpManager != null ? "MCP connected" : "",
                () -> "不可用",
                null
        );
    }

    // ────────────────────────────────────────────────────────────────────
    // 特殊命令处理
    // ────────────────────────────────────────────────────────────────────

    /** /compact 命令：强制压缩对话上下文 */
    private void handleCompact() {
        if (client == null || conversation == null) {
            broadcast(Map.of("type", "error", "data",
                    Map.of("message", "Compact requires an active provider.")));
            broadcast(Map.of("type", "command_done"));
            return;
        }
        broadcast(Map.of("type", "system", "data", Map.of("message", "Compacting conversation...")));
        try {
            String workDir = System.getProperty("user.dir");
            int contextWindow = providers.get(0).resolvedContextWindow();
            var schemas = registry.getAllSchemas(providers.get(0).getProtocol());
            String msg = ContextCompactor.forceCompact(
                    conversation, client, contextWindow, workDir, sessionId,
                    agent.getRecoveryState(), schemas);
            broadcast(Map.of("type", "system", "data", Map.of("message", "Compacted: " + msg)));
        } catch (Exception e) {
            broadcast(Map.of("type", "error", "data",
                    Map.of("message", "Compact failed: " + e.getMessage())));
        }
        broadcast(Map.of("type", "command_done"));
    }

    /** /plan 命令：进入计划模式 */
    private void handlePlan(String args) {
        if (permChecker == null) {
            broadcast(Map.of("type", "error", "data",
                    Map.of("message", "Agent not initialized.")));
            return;
        }
        String workDir = System.getProperty("user.dir");
        permChecker.setMode(PermissionMode.PLAN);
        String planPath = PlanFile.getOrCreatePlanPath(workDir);
        broadcast(Map.of("type", "system", "data",
                Map.of("message", "Entered Plan mode. Plan file: " + planPath
                        + "\nExplore the codebase and design your approach.")));

        // 带参数直接发给 Agent
        if (args != null && !args.isEmpty()) {
            streaming = true;
            streamThread = Thread.currentThread();
            SessionManager.saveMessage(workDir, sessionId, "user", "/plan " + args);
            conversation.addUserMessage(args);

            agentQueue = agent.run(conversation);
            if (askUserTool != null) askUserTool.setEventQueue(agentQueue);

            try {
                consumeAgentEvents();
            } finally {
                streaming = false;
                streamThread = null;
                agentQueue = null;
            }
        }
    }

    /** /resume 命令：恢复历史会话 */
    private void handleResume(String args) {
        String workDir = System.getProperty("user.dir");
        var sessions = SessionManager.listSessions(workDir);

        if (args == null || args.isEmpty()) {
            // 列出可选会话
            if (sessions.isEmpty()) {
                broadcast(Map.of("type", "system", "data", Map.of("message", "No previous sessions found.")));
                broadcast(Map.of("type", "command_done"));
                return;
            }
            var sb = new StringBuilder("Available sessions (%d):\n\n".formatted(sessions.size()));
            int limit = Math.min(sessions.size(), 20);
            for (int i = 0; i < limit; i++) {
                var sess = sessions.get(i);
                String first = sess.firstMessage();
                if (first.length() > 60) first = first.substring(0, 60) + "...";
                sb.append("  %d. [%s] %s (%d msgs)\n".formatted(i + 1, sess.id(), first, sess.messageCount()));
            }
            if (sessions.size() > 20) {
                sb.append("  ... and %d more\n".formatted(sessions.size() - 20));
            }
            sb.append("\nUsage: /resume <number> or /resume <session-id>");
            broadcast(Map.of("type", "system", "data", Map.of("message", sb.toString())));
            broadcast(Map.of("type", "command_done"));
            return;
        }

        // 恢复指定会话
        String targetId = args.strip();
        try {
            int idx = Integer.parseInt(targetId);
            if (idx >= 1 && idx <= sessions.size()) {
                targetId = sessions.get(idx - 1).id();
            }
        } catch (NumberFormatException ignored) {}

        var messages = SessionManager.loadSession(workDir, targetId);
        if (messages.isEmpty()) {
            broadcast(Map.of("type", "error", "data",
                    Map.of("message", "Session '%s' not found or empty.".formatted(targetId))));
            broadcast(Map.of("type", "command_done"));
            return;
        }

        // 重建对话
        conversation = SessionManager.rebuildConversation(messages);
        sessionId = targetId;
        if (agent != null) agent.setSessionId(sessionId);

        // 清除旧 UI 并重放消息
        broadcast(Map.of("type", "clear"));
        var scan = SessionManager.findLastCompactBoundary(messages);
        List<SessionManager.SessionMessage> replay;
        if (scan.found()) {
            replay = new ArrayList<>();
            replay.add(new SessionManager.SessionMessage("user", scan.boundary().summary(), 0));
            for (var k : scan.boundary().keep()) {
                // 保留的尾巴要连同工具块一起回放，否则压缩后恢复会话会缺掉调用链
                replay.add(new SessionManager.SessionMessage(k.role(), null, k.content(), 0,
                        k.toolUses(), k.toolResults()));
            }
            replay.addAll(scan.after());
        } else {
            replay = messages;
        }

        for (var msg : replay) {
            switch (msg.role()) {
                case "user" -> broadcast(Map.of("type", "replay_user", "data",
                        Map.of("content", msg.content())));
                case "assistant" -> broadcast(Map.of("type", "replay_assistant", "data",
                        Map.of("content", msg.content())));
            }
        }

        String restored;
        if (scan.found()) {
            restored = "Session %s restored from compacted state (summary + %d kept + %d newer)."
                    .formatted(targetId, scan.boundary().keep().size(), scan.after().size());
        } else {
            restored = "Session %s restored (%d messages).".formatted(targetId, replay.size());
        }
        broadcast(Map.of("type", "system", "data", Map.of("message", restored)));
        broadcast(Map.of("type", "command_done"));
    }

    // ────────────────────────────────────────────────────────────────────
    // Agent 事件消费
    // ────────────────────────────────────────────────────────────────────

    /** 从 Agent 事件队列中消费所有事件，推送到 WebSocket 客户端 */
    private void consumeAgentEvents() {
        var streamBuf = new StringBuilder();
        long startTime = System.currentTimeMillis();

        while (true) {
            AgentEvent event;
            try {
                event = agentQueue.poll(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            if (event == null) {
                broadcast(Map.of("type", "error", "data", Map.of("message", "Stream timeout")));
                break;
            }

            switch (event) {
                case AgentEvent.StreamText e -> {
                    streamBuf.append(e.text());
                    broadcast(Map.of("type", "stream_text", "data", Map.of("text", e.text())));
                }
                case AgentEvent.ThinkingText e -> {
                    broadcast(Map.of("type", "thinking_text", "data", Map.of("text", e.text())));
                }
                case AgentEvent.ThinkingComplete e -> {
                    // 前端自行处理 thinking 完成状态
                }
                case AgentEvent.ToolUseEvent e -> {
                    broadcast(Map.of("type", "tool_use", "data", Map.of(
                            "toolId", e.toolId(),
                            "toolName", e.toolName(),
                            "args", e.args() != null ? e.args() : Map.of()
                    )));
                }
                case AgentEvent.ToolResultEvent e -> {
                    // 工具结果前先结束当前流式文本
                    if (!streamBuf.isEmpty()) {
                        broadcast(Map.of("type", "stream_end", "data",
                                Map.of("text", streamBuf.toString())));
                        streamBuf.setLength(0);
                    }
                    broadcast(Map.of("type", "tool_result", "data", Map.of(
                            "toolId", e.toolId(),
                            "toolName", e.toolName(),
                            "output", e.output() != null ? e.output() : "",
                            "isError", e.isError(),
                            "elapsed", e.elapsed()
                    )));
                }
                case AgentEvent.PermissionRequestEvent e -> {
                    // 生成唯一 ID，通过 CompletableFuture 桥接前端响应
                    String id = "perm_" + System.nanoTime();
                    pendingPerms.put(id, e.future());
                    broadcast(Map.of("type", "permission_request", "data", Map.of(
                            "id", id,
                            "toolName", e.toolName(),
                            "description", e.description()
                    )));
                }
                case AgentEvent.AskUserRequestEvent e -> {
                    String id = "ask_" + System.nanoTime();
                    pendingAsks.put(id, e.future());
                    // 将 Question 转换为前端可解析的 JSON 结构
                    var questions = e.questions().stream().map(q -> Map.of(
                            "question", q.text() != null ? q.text() : "",
                            "options", q.options() != null
                                    ? q.options().stream().map(o -> Map.of(
                                    "label", o.label() != null ? o.label() : "",
                                    "description", o.description() != null ? o.description() : ""
                            )).toList()
                                    : List.of()
                    )).toList();
                    broadcast(Map.of("type", "ask_user", "data", Map.of(
                            "id", id,
                            "questions", questions
                    )));
                }
                case AgentEvent.TurnComplete e -> {
                    if (!streamBuf.isEmpty()) {
                        broadcast(Map.of("type", "stream_end", "data",
                                Map.of("text", streamBuf.toString())));
                        streamBuf.setLength(0);
                    }
                    broadcast(Map.of("type", "turn_complete", "data", Map.of("turn", e.turn())));
                }
                case AgentEvent.LoopComplete e -> {
                    // 最后一段流式文本持久化到 session
                    if (!streamBuf.isEmpty()) {
                        String workDir = System.getProperty("user.dir");
                        // 助手消息由主循环在进入对话历史时落盘
                        broadcast(Map.of("type", "stream_end", "data",
                                Map.of("text", streamBuf.toString())));
                        streamBuf.setLength(0);
                    }
                    double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
                    broadcast(Map.of("type", "loop_complete", "data", Map.of(
                            "totalTurns", e.totalTurns(),
                            "elapsed", elapsed
                    )));
                    return; // Agent 循环结束
                }
                case AgentEvent.UsageEvent e -> {
                    broadcast(Map.of("type", "usage", "data", Map.of(
                            "inputTokens", e.inputTokens(),
                            "outputTokens", e.outputTokens()
                    )));
                }
                case AgentEvent.ErrorEvent e -> {
                    broadcast(Map.of("type", "error", "data", Map.of("message", e.message())));
                }
                case AgentEvent.CompactEvent e -> {
                    broadcast(Map.of("type", "compact", "data", Map.of("message", e.message())));
                }
                case AgentEvent.RetryEvent e -> {
                    broadcast(Map.of("type", "retry", "data", Map.of(
                            "reason", e.reason(),
                            "waitMs", e.waitMs()
                    )));
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 权限和 AskUser 响应处理
    // ────────────────────────────────────────────────────────────────────

    /** 处理前端权限按钮的回复 */
    private void handlePermissionResponse(String id, String response) {
        var future = pendingPerms.remove(id);
        if (future == null) return;

        PermissionResponse resp = switch (response) {
            case "allow" -> PermissionResponse.ALLOW;
            case "allowAlways" -> PermissionResponse.ALLOW_ALWAYS;
            default -> PermissionResponse.DENY;
        };
        future.complete(resp);
    }

    /** 处理前端 AskUser 对话框的回复 */
    private void handleAskUserResponse(String id, Map<String, String> answers) {
        var future = pendingAsks.remove(id);
        if (future == null) return;
        future.complete(answers != null ? answers : Map.of());
    }

    // ────────────────────────────────────────────────────────────────────
    // 命令列表
    // ────────────────────────────────────────────────────────────────────

    /** 构建命令列表供前端斜杠菜单使用 */
    private List<Map<String, String>> buildCommandList() {
        var list = new ArrayList<Map<String, String>>();
        for (var cmd : cmdRegistry.listVisible()) {
            list.add(Map.of(
                    "name", cmd.name(),
                    "description", cmd.description()
            ));
        }
        return list;
    }

    // ────────────────────────────────────────────────────────────────────
    // WebSocket 广播
    // ────────────────────────────────────────────────────────────────────

    /** 向所有已连接的 WebSocket 客户端广播 JSON 消息 */
    private void broadcast(Map<String, Object> msg) {
        if (connections.isEmpty()) return;
        try {
            String json = MAPPER.writeValueAsString(msg);
            for (var ctx : connections) {
                try {
                    ctx.send(json);
                } catch (Exception e) {
                    System.err.println("[ws] send error: " + e.getMessage());
                }
            }
        } catch (JsonProcessingException e) {
            System.err.println("[ws] JSON serialize error: " + e.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 工具方法
    // ────────────────────────────────────────────────────────────────────

    /** 从地址字符串解析端口号（支持 ":18888" 和 "0.0.0.0:18888" 格式） */
    private static int parsePort(String addr) {
        if (addr == null || addr.isEmpty()) return 18888;
        int colonIdx = addr.lastIndexOf(':');
        if (colonIdx >= 0) {
            try {
                return Integer.parseInt(addr.substring(colonIdx + 1));
            } catch (NumberFormatException e) {
                return 18888;
            }
        }
        try {
            return Integer.parseInt(addr);
        } catch (NumberFormatException e) {
            return 18888;
        }
    }

    // ── Hook 事件名 / 动作类型解析（复刻 FlyCodeModel） ──────────────
    private static HookEngine.EventName parseEventName(String s) {
        if (s == null) return HookEngine.EventName.SESSION_START;
        return switch (s.toLowerCase()) {
            case "session_start" -> HookEngine.EventName.SESSION_START;
            case "session_end" -> HookEngine.EventName.SESSION_END;
            case "turn_start" -> HookEngine.EventName.TURN_START;
            case "turn_end" -> HookEngine.EventName.TURN_END;
            case "pre_send" -> HookEngine.EventName.PRE_SEND;
            case "post_receive" -> HookEngine.EventName.POST_RECEIVE;
            case "pre_tool_use" -> HookEngine.EventName.PRE_TOOL_USE;
            case "post_tool_use" -> HookEngine.EventName.POST_TOOL_USE;
            case "shutdown" -> HookEngine.EventName.SHUTDOWN;
            default -> HookEngine.EventName.SESSION_START;
        };
    }

    private static HookEngine.ActionType parseActionType(String s) {
        if (s == null) return HookEngine.ActionType.COMMAND;
        return switch (s.toLowerCase()) {
            case "command" -> HookEngine.ActionType.COMMAND;
            case "prompt" -> HookEngine.ActionType.PROMPT;
            case "http" -> HookEngine.ActionType.HTTP;
            case "agent" -> HookEngine.ActionType.AGENT;
            default -> HookEngine.ActionType.COMMAND;
        };
    }
}
