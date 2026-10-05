package com.flycode.teams;

import com.flycode.agent.Agent;
import com.flycode.agent.AgentEvent;
import com.flycode.config.ProviderConfig;
import com.flycode.conversation.ConversationManager;
import com.flycode.llm.LlmClient;
import com.flycode.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

/**
 * Manages multi-agent teams with mailbox-based communication.
 */
public class TeamManager {

    public enum TeamMode { IN_PROCESS, TMUX, ITERM }

    private final Map<String, Team> teams = new LinkedHashMap<>();
    // 每个团队一份共享任务库，落盘在 <团队目录>/tasks.json
    private final Map<String, SharedTaskStore> taskStores = new LinkedHashMap<>();

    public synchronized Team createTeam(String name, TeamMode mode) {
        return createTeam(name, mode, "", "");
    }

    /**
     * 建团队并记下 lead 和描述，随后把配置写进 config.json。
     * 落盘之后队员进程和下一次会话都能靠 {@link #getTeam} 把这个团队捞回来。
     */
    public synchronized Team createTeam(String name, TeamMode mode, String leadAgentId, String description) {
        Team team = new Team(name, mode);
        team.leadAgentId = leadAgentId == null ? "" : leadAgentId;
        team.description = description;
        teams.put(name, team);
        // 新建团队时初始化一份空的共享任务库
        SharedTaskStore store = new SharedTaskStore(teamDir(name).resolve("tasks.json"));
        store.initEmpty();
        taskStores.put(name, store);
        team.persist();
        return team;
    }

    /**
     * 注册一个已经构造好的 Team，不重置共享任务库。供被 tmux/iTerm 拉起的队友进程
     * 接入 lead 建好的团队：只登记团队，tasks.json 按需从磁盘懒加载，避免像 createTeam
     * 那样清空 lead 已写入的任务。
     */
    public synchronized Team createTeamWith(Team team) {
        teams.put(team.getName(), team);
        return team;
    }

    /**
     * 先查内存，未命中再看磁盘上有没有 config.json。
     *
     * <p>从磁盘重建出来的 Team 只带元信息，成员的 Agent 实例和对话都是空的，
     * 够 SendMessage 按名字投递和 UI 展示用；要真正让某个成员跑起来还得重新 spawn。
     */
    public synchronized Team getTeam(String name) {
        Team cached = teams.get(name);
        if (cached != null) {
            return cached;
        }
        TeamFile tf = TeamFile.read(teamConfigPath(name));
        if (tf == null) {
            return null;
        }
        TeamMode mode = TeamMode.IN_PROCESS;
        for (TeamFile.MemberEntry m : tf.members) {
            if (m.backendType != null && !m.backendType.isEmpty()) {
                try {
                    mode = TeamMode.valueOf(m.backendType);
                } catch (IllegalArgumentException ignored) {
                    // 旧配置里的取值对不上枚举就保持默认
                }
            }
        }
        Team team = new Team(tf.name, mode);
        team.leadAgentId = tf.leadAgentId;
        team.description = tf.description;
        team.createdAt = tf.createdAt;
        for (TeamFile.MemberEntry m : tf.members) {
            Member member = new Member(m.name, null, null);
            member.agentId = m.agentId;
            member.agentType = m.agentType;
            member.model = m.model;
            member.worktreePath = m.worktreePath;
            member.joinedAt = m.joinedAt;
            member.active = Boolean.TRUE.equals(m.isActive);
            team.members.put(m.name, member);
        }
        teams.put(name, team);
        return team;
    }

    /**
     * 获取团队的共享任务库；内存无缓存时（例如队友进程）从磁盘 tasks.json 加载。
     */
    public synchronized SharedTaskStore getTaskStore(String teamName) {
        SharedTaskStore cached = taskStores.get(teamName);
        if (cached != null) {
            return cached;
        }
        SharedTaskStore store = new SharedTaskStore(teamDir(teamName).resolve("tasks.json"));
        taskStores.put(teamName, store);
        return store;
    }

    public synchronized void deleteTeam(String name) {
        Team team = teams.remove(name);
        if (team != null) {
            // 解绑该团队成员在全局名称注册表里的映射
            AgentNameRegistry registry = AgentNameRegistry.getInstance();
            for (String member : team.memberNames()) {
                registry.unregister(member);
            }
            team.stopAll();
        }
        taskStores.remove(name);
        // 团队目录里是 config.json、tasks.json 和收件箱，团队没了就一起清掉，
        // 免得下次同名团队捞到上一次的残留
        deleteRecursively(teamDir(name));
    }

    private static void deleteRecursively(Path dir) {
        if (!java.nio.file.Files.exists(dir)) {
            return;
        }
        try (var walk = java.nio.file.Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(pth -> {
                try {
                    java.nio.file.Files.deleteIfExists(pth);
                } catch (java.io.IOException ignored) {
                    // best-effort
                }
            });
        } catch (java.io.IOException ignored) {
            // best-effort
        }
    }

    public synchronized List<String> listTeams() {
        return new ArrayList<>(teams.keySet());
    }

    public synchronized void closeAll() {
        for (Team team : teams.values()) {
            team.stopAll();
        }
        teams.clear();
    }

    public synchronized List<TeammateProgress> getAllTeammateProgress() {
        return teams.values().stream()
                .flatMap(t -> t.getTeammateProgressList().stream())
                .toList();
    }

    /**
     * 后端自动检测：只有当前进程已身处 tmux / iTerm2 会话时才用窗格后端，
     * 否则回退进程内。tmux 和 iTerm2 会自动给会话内进程设上 TMUX / ITERM_SESSION_ID 环境变量，
     * 用户无需手动配置。
     */
    public static TeamMode detectBackend() {
        // Windows 护栏：tmux 窗格 spawn 时用 pwsh 执行 POSIX 命令会 ParserError，一律走进程内。
        String os = System.getProperty("os.name");
        if (os != null && os.toLowerCase().contains("windows")) {
            return TeamMode.IN_PROCESS;
        }
        return detectBackendFromEnv();
    }

    /**
     * 只按环境变量判断后端，不含平台判断，抽出来便于在任意平台单测。
     */
    static TeamMode detectBackendFromEnv() {
        return detectBackendFromEnv(System.getenv("TMUX"), System.getenv("ITERM_SESSION_ID"));
    }

    /**
     * 直接传入环境变量值的可测版本：TMUX 非空 → TMUX，ITERM_SESSION_ID 非空 → ITERM，都无 → 进程内。
     */
    static TeamMode detectBackendFromEnv(String tmux, String itermSessionId) {
        if (tmux != null && !tmux.isEmpty()) {
            return TeamMode.TMUX;
        }
        if (itermSessionId != null && !itermSessionId.isEmpty()) {
            return TeamMode.ITERM;
        }
        return TeamMode.IN_PROCESS;
    }

    // ── Inner classes ──────────────────────────────────────────────────

    /**
     * 所有团队目录的根。放在用户主目录而不是项目目录下，因为窗格队员是独立进程、
     * 工作目录可能被 worktree 换掉，用主目录才能保证队员进程和 Lead 找到同一份团队配置。
     */
    static Path teamsBaseDir() {
        return Path.of(System.getProperty("user.home"), ".flycode", "teams");
    }

    static Path teamDir(String name) {
        return teamsBaseDir().resolve(TeamFile.sanitizeTeamName(name));
    }

    static Path teamConfigPath(String name) {
        return teamDir(name).resolve("config.json");
    }

    public static class Team {
        final String name;
        final TeamMode mode;
        final Map<String, Member> members = new LinkedHashMap<>();
        private final FileMailBox mailBox;

        // 落盘用的团队级元信息
        String leadAgentId = "";
        String description;
        long createdAt;

        public Team(String name, TeamMode mode) {
            this.name = name;
            this.mode = mode;
            this.createdAt = System.currentTimeMillis() / 1000;
            this.mailBox = new FileMailBox(teamDir(name).resolve("inboxes"));
        }

        public String getName() { return name; }
        public TeamMode getMode() { return mode; }

        public FileMailBox getMailBox() { return mailBox; }

        public synchronized Member addMember(String name, LlmClient client, ToolRegistry registry,
                                             String protocol, ProviderConfig cfg) {
            Agent ag = new Agent(client, registry, protocol, cfg);
            Member member = new Member(name, ag, new ConversationManager());
            member.agentId = name;
            member.joinedAt = System.currentTimeMillis() / 1000;
            members.put(name, member);
            persist();
            return member;
        }

        /**
         * 补齐成员的元信息（agent 类型、模型、worktree 路径）并落盘。
         * spawn 流程拿到这些信息的时机晚于 {@link #addMember}，所以分成两步写。
         */
        public synchronized void setMemberMeta(String name, String agentType, String model, String worktreePath) {
            Member member = members.get(name);
            if (member == null) return;
            member.agentType = agentType;
            member.model = model;
            member.worktreePath = worktreePath;
            persist();
        }

        /** 把当前状态导出成可落盘的结构。调用方需要自己持有 Team 的锁。 */
        TeamFile snapshot() {
            TeamFile tf = new TeamFile();
            tf.name = name;
            tf.description = description;
            tf.createdAt = createdAt == 0 ? System.currentTimeMillis() / 1000 : createdAt;
            tf.leadAgentId = leadAgentId;
            for (Member m : members.values()) {
                TeamFile.MemberEntry e = new TeamFile.MemberEntry();
                e.agentId = m.agentId == null ? m.name : m.agentId;
                e.name = m.name;
                e.agentType = m.agentType;
                e.model = m.model;
                e.joinedAt = m.joinedAt;
                e.worktreePath = m.worktreePath;
                e.backendType = mode.name();
                e.isActive = m.active;
                tf.members.add(e);
            }
            return tf;
        }

        /**
         * 把当前状态写回磁盘。写失败不影响内存里的团队继续工作，
         * 落盘是为了跨进程和跨重启，不是运行时的必要条件。
         */
        void persist() {
            snapshot().write(teamConfigPath(name));
        }

        public synchronized BlockingQueue<AgentEvent> startMember(String name, String task) {
            Member member = members.get(name);
            if (member == null) return null;
            member.conv.addUserMessage(task);
            BlockingQueue<AgentEvent> queue = member.agent.run(member.conv);
            member.active = true;
            persist();
            return queue;
        }

        public synchronized void stopMember(String name) {
            Member member = members.get(name);
            if (member != null) {
                member.active = false;
                if (member.thread != null) {
                    member.thread.interrupt();
                }
                persist();
            }
        }

        public synchronized void stopAll() {
            for (Member m : members.values()) {
                m.active = false;
                if (m.thread != null) m.thread.interrupt();
            }
        }

        public synchronized Member getMember(String name) {
            return members.get(name);
        }

        public synchronized boolean hasMember(String name) {
            return members.containsKey(name);
        }

        public synchronized List<String> memberNames() {
            return new ArrayList<>(members.keySet());
        }

        public void sendMessage(String from, String to, String content) {
            mailBox.send(to, new FileMailBox.MailMessage(from, content));
        }

        public List<TeammateProgress> getTeammateProgressList() {
            return members.values().stream()
                    .filter(m -> m.progress != null)
                    .map(m -> m.progress)
                    .toList();
        }
    }

    public static class Member {
        public final String name;
        public final Agent agent;
        public final ConversationManager conv;
        public volatile boolean active;
        public volatile Thread thread;
        public TeammateProgress progress;

        // 以下几个是要落盘的元信息，运行时不参与调度，只在写 config.json
        // 和从磁盘恢复团队时用到。
        public String agentId;
        public String agentType;
        public String model;
        public String worktreePath;
        public long joinedAt;

        public Member(String name, Agent agent, ConversationManager conv) {
            this.name = name;
            this.agent = agent;
            this.conv = conv;
        }

        public String getName() { return name; }
        public boolean isActive() { return active; }
    }

}
