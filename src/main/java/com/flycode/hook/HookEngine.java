package com.flycode.hook;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Hook 引擎 —— 管理生命周期钩子的注册、条件匹配和动作执行。
 *   - 9 种事件、4 种动作类型（command / prompt / http / agent）
 *   - != / =~ / =* 操作符，&& / || 复合条件
 *   - once（单次触发）、async（异步执行）、onError 错误策略
 *   - 配置校验、命令超时、环境变量注入、模板变量替换
 */
public class HookEngine {

    /** 命令执行的默认超时（10 分钟） */
    private static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofMinutes(10);

    /** HTTP 请求的默认超时（10 秒） */
    private static final Duration DEFAULT_HTTP_TIMEOUT = Duration.ofSeconds(10);

    // ======== Event names ========

    public enum EventName {
        SESSION_START("session_start"),
        SESSION_END("session_end"),
        TURN_START("turn_start"),
        TURN_END("turn_end"),
        PRE_SEND("pre_send"),
        POST_RECEIVE("post_receive"),
        PRE_TOOL_USE("pre_tool_use"),
        POST_TOOL_USE("post_tool_use"),
        SHUTDOWN("shutdown");

        private final String value;

        EventName(String value) { this.value = value; }

        public String value() { return value; }

        /** 根据字符串查找对应的枚举值，找不到返回 null */
        public static EventName fromString(String s) {
            if (s == null) return null;
            for (EventName e : values()) {
                if (e.value.equals(s)) return e;
            }
            return null;
        }
    }

    // ======== Action types ========

    public enum ActionType {
        COMMAND("command"),
        PROMPT("prompt"),
        HTTP("http"),
        AGENT("agent");

        private final String value;

        ActionType(String value) { this.value = value; }

        public String value() { return value; }

        /** 根据字符串查找对应的枚举值，找不到返回 null */
        public static ActionType fromString(String s) {
            if (s == null) return null;
            for (ActionType t : values()) {
                if (t.value.equals(s)) return t;
            }
            return null;
        }
    }

    // ======== Data records ========

    /**
     * 动作定义，根据 type 不同使用不同字段：
     *   command → command 字段
     *   prompt  → message 字段
     *   http    → url / method / headers / body
     *   agent   → message（或 command 作为 fallback）
     */
    public record Action(
            ActionType type,
            String command,
            String message,
            String url,
            String method,
            Map<String, String> headers,
            String body,
            Duration timeout
    ) {
        /** 简便构造：仅 command/prompt 类型用 */
        public Action(ActionType type, String command, String message) {
            this(type, command, message, null, null, null, null, Duration.ZERO);
        }
    }

    /** 单条 hook 配置 */
    public record Hook(
            String id,
            EventName event,
            String condition,
            Action action,
            boolean reject,
            boolean once,
            boolean async,
            String onError
    ) {
        /** 向后兼容的简便构造 */
        public Hook(String id, EventName event, String condition, Action action, boolean reject) {
            this(id, event, condition, action, reject, false, false, null);
        }
    }

    /**
     * Hook 执行上下文 —— 携带当前事件的所有信息。
     */
    public record HookContext(
            EventName event,
            String toolName,
            Map<String, Object> toolArgs,
            String filePath,
            String message,
            String error
    ) {
        /**
         * 模板变量替换 —— 将字符串中的 ${var} 替换为上下文中的实际值。
         * 支持的变量：event、tool、file_path、message、error、args.xxx
         */
        public String expand(String template) {
            if (template == null || !template.contains("${")) return template;
            String result = template;
            result = result.replace("${event}", event != null ? event.value() : "");
            result = result.replace("${tool}", toolName != null ? toolName : "");
            result = result.replace("${file_path}", filePath != null ? filePath : "");
            result = result.replace("${message}", message != null ? message : "");
            result = result.replace("${error}", error != null ? error : "");
            // 替换 args.xxx 变量
            if (toolArgs != null) {
                for (var entry : toolArgs.entrySet()) {
                    String placeholder = "${args." + entry.getKey() + "}";
                    if (result.contains(placeholder)) {
                        result = result.replace(placeholder,
                                entry.getValue() != null ? String.valueOf(entry.getValue()) : "");
                    }
                }
            }
            return result;
        }
    }

    public record HookResult(String hookId, String output, boolean success, boolean reject) {}

    public record PreToolResult(boolean rejected, String message) {}

    // ======== Engine state ========

    private final List<Hook> hooks = new ArrayList<>();
    private final List<HookResult> notifications = Collections.synchronizedList(new ArrayList<>());
    /** 已触发的 hook ID 集合，用于 once 去重 */
    private final Set<String> fired = Collections.synchronizedSet(new HashSet<>());

    /**
     * Agent 类型 hook 的执行器（可选）。
     * 接收 prompt 和上下文，返回输出字符串。未注册时 agent hook 会返回明确错误。
     */
    private BiFunction<String, HookContext, String> agentRunner;

    // ======== Agent runner ========

    public void setAgentRunner(BiFunction<String, HookContext, String> runner) {
        this.agentRunner = runner;
    }

    // ======== Hook registration ========

    public void addHook(Hook hook) {
        synchronized (hooks) {
            hooks.add(hook);
        }
    }

    public void loadHooks(List<Hook> hookList) {
        synchronized (hooks) {
            hooks.clear();
            hooks.addAll(hookList);
            fired.clear();
        }
    }

    // ======== Config validation ========

    /**
     * 校验 hook 配置列表，提前暴露配置错误。
     * 所有错误聚合返回，不会因第一个错误就中断检查。
     *
     * @return 校验错误列表，为空表示配置合法
     */
    public static List<String> validate(List<Hook> hooks) {
        // 合法事件名集合
        Set<EventName> validEvents = EnumSet.allOf(EventName.class);
        List<String> errors = new ArrayList<>();

        for (int i = 0; i < hooks.size(); i++) {
            Hook h = hooks.get(i);
            String label = (h.id() != null && !h.id().isEmpty())
                    ? String.format("hook[%d] (id=%s)", i, h.id())
                    : String.format("hook[%d]", i);

            // 校验事件名
            if (h.event() == null || !validEvents.contains(h.event())) {
                errors.add(String.format("%s: unknown event \"%s\"", label,
                        h.event() != null ? h.event().value() : "null"));
            }

            // 校验超时
            if (h.action() != null && h.action().timeout() != null
                    && h.action().timeout().isNegative()) {
                errors.add(String.format("%s: action.timeout must be >= 0 (got %s)",
                        label, h.action().timeout()));
            }

            // 校验 action type 及其必填字段
            if (h.action() == null || h.action().type() == null) {
                errors.add(String.format("%s: action.type is required", label));
                continue;
            }

            switch (h.action().type()) {
                case COMMAND -> {
                    if (isBlank(h.action().command())) {
                        errors.add(String.format(
                                "%s: action.command must be non-empty for type \"%s\"",
                                label, h.action().type().value()));
                    }
                }
                case PROMPT -> {
                    if (isBlank(h.action().message())) {
                        errors.add(String.format(
                                "%s: action.message must be non-empty for type \"%s\"",
                                label, h.action().type().value()));
                    }
                }
                case HTTP -> {
                    if (isBlank(h.action().url())) {
                        errors.add(String.format(
                                "%s: action.url must be non-empty for type \"%s\"",
                                label, h.action().type().value()));
                    } else {
                        // 校验 URL 格式
                        try {
                            URI uri = URI.create(h.action().url());
                            String scheme = uri.getScheme();
                            if (!"http".equals(scheme) && !"https".equals(scheme)
                                    || uri.getHost() == null || uri.getHost().isEmpty()) {
                                errors.add(String.format(
                                        "%s: action.url must be a valid http(s) URL (got \"%s\")",
                                        label, h.action().url()));
                            }
                        } catch (Exception e) {
                            errors.add(String.format(
                                    "%s: action.url must be a valid http(s) URL (got \"%s\")",
                                    label, h.action().url()));
                        }
                    }
                }
                case AGENT -> {
                    if (isBlank(h.action().message()) && isBlank(h.action().command())) {
                        errors.add(String.format(
                                "%s: action.message (or action.command as fallback) must be non-empty for type \"%s\"",
                                label, h.action().type().value()));
                    }
                }
            }
        }
        return errors;
    }

    // ======== Hook execution ========

    /**
     * 按事件名匹配并执行所有 hook。
     * async hook 在独立线程中执行，不阻塞调用方。
     */
    public List<HookResult> runHooks(HookContext ctx) {
        List<HookResult> results = new ArrayList<>();
        for (Hook h : snapshotHooks()) {
            if (h.event() != ctx.event()) continue;
            if (!shouldFire(h, ctx)) continue;

            // 异步 hook：在独立线程中执行，立即返回占位结果
            if (h.async()) {
                CompletableFuture.runAsync(() -> {
                    HookResult res = executeAction(h, ctx);
                    notifications.add(res);
                });
                results.add(new HookResult(h.id(), "(async)", true, false));
                continue;
            }

            HookResult result = executeAction(h, ctx);
            results.add(result);
            notifications.add(result);
        }
        return results;
    }

    /**
     * 执行 pre_tool_use 事件的 hook。
     * 支持 reject 配置和 onError=reject 策略。
     * 非 reject 的 hook 也会执行（用于副作用如通知/HTTP）。
     */
    public PreToolResult runPreToolHooks(String toolName, Map<String, Object> args) {
        HookContext ctx = new HookContext(
                EventName.PRE_TOOL_USE, toolName, args, null, null, null);
        for (Hook h : snapshotHooks()) {
            if (h.event() != EventName.PRE_TOOL_USE) continue;
            if (!shouldFire(h, ctx)) continue;

            HookResult result = executeAction(h, ctx);
            notifications.add(result);

            // 配置了 reject 或者 action 失败 + onError=reject 都触发拦截
            if (h.reject() || (!result.success() && "reject".equals(h.onError()))) {
                String msg = result.output();
                if (msg == null || msg.isEmpty()) {
                    msg = "blocked by hook " + h.id();
                }
                return new PreToolResult(true, msg);
            }
        }
        return new PreToolResult(false, "");
    }

    // ======== Notifications ========

    /** 取出并清空累积的通知（线程安全） */
    public List<HookResult> drainNotifications() {
        synchronized (notifications) {
            List<HookResult> result = List.copyOf(notifications);
            notifications.clear();
            return result;
        }
    }

    // ======== 内部方法 ========

    /** 判断 hook 是否应该触发（条件匹配 + once 去重） */
    private boolean shouldFire(Hook h, HookContext ctx) {
        // 条件不满足则跳过
        if (h.condition() != null && !h.condition().isEmpty()
                && !evaluateCondition(h.condition(), ctx)) {
            return false;
        }
        // once: 同一 id 只触发一次
        if (h.once()) {
            if (h.id() != null && !h.id().isEmpty()) {
                if (fired.contains(h.id())) return false;
                fired.add(h.id());
            }
        }
        return true;
    }

    /** 快照当前 hook 列表，避免执行过程中修改导致并发问题 */
    private List<Hook> snapshotHooks() {
        synchronized (hooks) {
            return new ArrayList<>(hooks);
        }
    }

    // ======== Condition evaluation ========

    /**
     * 条件求值器 —— 支持：
     *   - 叶子条件：var == "value"、var != "value"、var =~ /regex/、var =* "glob"
     *   - 复合条件：cond1 && cond2、cond1 || cond2（左到右求值，无括号）
     *   - 取反：!cond
     */
    static boolean evaluateCondition(String condition, HookContext ctx) {
        String cond = condition.strip();
        if (cond.isEmpty()) return true;

        // 尝试拆分复合条件（&& 和 ||）
        List<CompToken> tokens = splitComposite(cond);
        if (tokens != null && tokens.size() > 1) {
            boolean result = evaluateCondition(tokens.get(0).expr, ctx);
            for (int i = 1; i < tokens.size(); i++) {
                boolean rhs = evaluateCondition(tokens.get(i).expr, ctx);
                if ("&&".equals(tokens.get(i).op)) {
                    result = result && rhs;
                } else {
                    result = result || rhs;
                }
            }
            return result;
        }

        // 取反操作符
        if (cond.startsWith("!")) {
            return !evaluateCondition(cond.substring(1).strip(), ctx);
        }

        return evaluateLeaf(cond, ctx);
    }

    /** 复合条件拆分的令牌 */
    private record CompToken(String op, String expr) {}

    /**
     * 将条件字符串按顶层 && 和 || 拆分为令牌列表。
     * 不处理引号内的操作符（hook 条件是用户配置，保持简单）。
     * 返回 null 表示没有拆分（纯叶子条件）。
     */
    private static List<CompToken> splitComposite(String s) {
        List<CompToken> out = new ArrayList<>();
        int start = 0;
        String currentOp = "";
        for (int i = 0; i < s.length() - 1; i++) {
            String pair = s.substring(i, i + 2);
            if ("&&".equals(pair) || "||".equals(pair)) {
                out.add(new CompToken(currentOp, s.substring(start, i).strip()));
                currentOp = pair;
                start = i + 2;
                i++; // 跳过第二个字符
            }
        }
        out.add(new CompToken(currentOp, s.substring(start).strip()));
        // 只有一个元素说明没有拆分
        return out.size() <= 1 ? null : out;
    }

    /**
     * 叶子条件求值 —— 支持四种操作符：
     *   ==  精确相等
     *   !=  不等
     *   =~  正则匹配
     *   =*  glob 模式匹配
     */
    static boolean evaluateLeaf(String condition, HookContext ctx) {
        // 按优先级检查各操作符（!= 必须在 == 之前检查）
        for (String op : new String[]{"!=", "=~", "=*", "=="}) {
            int idx = condition.indexOf(op);
            if (idx >= 0) {
                String left = condition.substring(0, idx).strip();
                String right = stripQuotes(condition.substring(idx + op.length()).strip());
                String val = resolveVar(left, ctx);

                return switch (op) {
                    case "==" -> val.equals(right);
                    case "!=" -> !val.equals(right);
                    case "=~" -> {
                        // 正则匹配：去除 / 分隔符。
                        // 用 find() 而不是 matches()：钩子条件要的是「命中即可」的部分匹配，
                        // matches() 要求整串匹配，写 /error/ 这种模式就永远不成立了
                        String pattern = stripSlashes(right);
                        try {
                            yield Pattern.compile(pattern).matcher(val).find();
                        } catch (PatternSyntaxException e) {
                            yield false;
                        }
                    }
                    case "=*" -> {
                        // glob 匹配：使用 Java NIO PathMatcher
                        try {
                            PathMatcher matcher = FileSystems.getDefault()
                                    .getPathMatcher("glob:" + right);
                            yield matcher.matches(Paths.get(val));
                        } catch (Exception e) {
                            yield false;
                        }
                    }
                    default -> false;
                };
            }
        }
        // 没有操作符 → 变量非空即为 true
        return !resolveVar(condition.strip(), ctx).isEmpty();
    }

    /** 解析变量引用，从 HookContext 中取值 */
    static String resolveVar(String name, HookContext ctx) {
        return switch (name) {
            case "tool" -> ctx.toolName() != null ? ctx.toolName() : "";
            case "event" -> ctx.event() != null ? ctx.event().value() : "";
            case "file_path" -> ctx.filePath() != null ? ctx.filePath() : "";
            case "message" -> ctx.message() != null ? ctx.message() : "";
            default -> {
                // 支持 args.xxx 变量访问
                if (name.startsWith("args.") && ctx.toolArgs() != null) {
                    String key = name.substring("args.".length());
                    Object v = ctx.toolArgs().get(key);
                    yield v != null ? String.valueOf(v) : "";
                }
                yield "";
            }
        };
    }

    /** 去除字符串两端的引号（单引号、双引号、正则斜杠） */
    private static String stripQuotes(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"')
                    || (first == '\'' && last == '\'')
                    || (first == '/' && last == '/')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    /** 去除正则模式两端的斜杠 */
    private static String stripSlashes(String s) {
        if (s.length() >= 2 && s.charAt(0) == '/' && s.charAt(s.length() - 1) == '/') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    // ======== Action execution ========

    /** 根据 action type 分发执行 */
    private HookResult executeAction(Hook h, HookContext ctx) {
        return switch (h.action().type()) {
            case COMMAND -> executeCommand(h, ctx);
            case PROMPT -> new HookResult(h.id(), h.action().message(), true, h.reject());
            case HTTP -> executeHTTP(h, ctx);
            case AGENT -> executeAgent(h, ctx);
        };
    }

    /**
     * 执行 command 类型 action —— 通过 bash -c 运行命令。
     * 注入 FLYCODE_EVENT、FLYCODE_TOOL、FLYCODE_FILE_PATH 环境变量。
     * 支持超时保护，超时后强制终止子进程。
     */
    private HookResult executeCommand(Hook h, HookContext ctx) {
        Duration timeout = h.action().timeout() != null && !h.action().timeout().isZero()
                ? h.action().timeout()
                : DEFAULT_COMMAND_TIMEOUT;

        // 对命令字符串做模板变量替换
        String command = ctx.expand(h.action().command());

        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            Map<String, String> env = pb.environment();
            // 注入环境变量
            env.put("FLYCODE_EVENT", ctx.event() != null ? ctx.event().value() : "");
            env.put("FLYCODE_TOOL", ctx.toolName() != null ? ctx.toolName() : "");
            env.put("FLYCODE_FILE_PATH", ctx.filePath() != null ? ctx.filePath() : "");

            Process proc = pb.start();

            // 异步读取 stdout 和 stderr，防止管道阻塞
            InputStream stdoutStream = proc.getInputStream();
            InputStream stderrStream = proc.getErrorStream();

            boolean finished = proc.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                // 超时：强制终止进程
                proc.destroyForcibly();
                String msg = String.format("command timed out after %s", timeout);
                return new HookResult(h.id(), msg, false, h.reject());
            }

            String stdout = new String(stdoutStream.readAllBytes()).strip();
            String stderr = new String(stderrStream.readAllBytes()).strip();
            int code = proc.exitValue();

            String output = stdout;
            if (!stderr.isEmpty()) {
                output = output.isEmpty() ? stderr : output + "\n" + stderr;
            }
            return new HookResult(h.id(), output, code == 0, h.reject());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new HookResult(h.id(),
                    "Failed to execute hook: " + e.getMessage(), false, h.reject());
        }
    }

    /**
     * 执行 http 类型 action —— 发送 HTTP 请求。
     * 默认方法 POST，默认 Content-Type application/json。
     * 无 body 时自动构建包含上下文信息的 JSON payload。
     */
    private HookResult executeHTTP(Hook h, HookContext ctx) {
        String method = h.action().method() != null && !h.action().method().isEmpty()
                ? h.action().method().toUpperCase() : "POST";
        Duration timeout = h.action().timeout() != null && !h.action().timeout().isZero()
                ? h.action().timeout() : DEFAULT_HTTP_TIMEOUT;

        // 对 URL 做模板变量替换
        String url = ctx.expand(h.action().url());

        // 构建请求体
        String body = h.action().body();
        if (body != null && !body.isEmpty()) {
            body = ctx.expand(body);
        } else {
            // 自动生成包含上下文信息的 JSON
            body = String.format(
                    "{\"event\":\"%s\",\"tool\":\"%s\",\"file_path\":\"%s\",\"message\":\"%s\",\"error\":\"%s\"}",
                    ctx.event() != null ? ctx.event().value() : "",
                    ctx.toolName() != null ? ctx.toolName() : "",
                    ctx.filePath() != null ? ctx.filePath() : "",
                    ctx.message() != null ? escapeJson(ctx.message()) : "",
                    ctx.error() != null ? escapeJson(ctx.error()) : "");
        }

        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout)
                    .method(method, HttpRequest.BodyPublishers.ofString(body));

            // 设置请求头
            boolean hasContentType = false;
            if (h.action().headers() != null) {
                for (var entry : h.action().headers().entrySet()) {
                    reqBuilder.header(entry.getKey(), ctx.expand(entry.getValue()));
                    if ("content-type".equalsIgnoreCase(entry.getKey())) {
                        hasContentType = true;
                    }
                }
            }
            if (!hasContentType && !body.isEmpty()) {
                reqBuilder.header("Content-Type", "application/json");
            }

            HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
            HttpResponse<String> resp = client.send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            boolean ok = resp.statusCode() >= 200 && resp.statusCode() < 300;
            String respBody = resp.body();
            // 限制响应体大小（64KB）
            if (respBody != null && respBody.length() > 65536) {
                respBody = respBody.substring(0, 65536);
            }
            return new HookResult(h.id(),
                    String.format("HTTP %d: %s", resp.statusCode(), respBody != null ? respBody.strip() : ""),
                    ok, h.reject());
        } catch (Exception e) {
            return new HookResult(h.id(), e.getMessage(), false, h.reject());
        }
    }

    /**
     * 执行 agent 类型 action —— 启动子 Agent 处理 hook 任务。
     * 需要先通过 setAgentRunner 注册执行器，否则返回明确的错误提示。
     */
    private HookResult executeAgent(Hook h, HookContext ctx) {
        if (agentRunner == null) {
            return new HookResult(h.id(),
                    "agent-type hook configured but no AgentRunner registered",
                    false, h.reject());
        }
        // message 优先，command 作为 fallback
        String prompt = h.action().message();
        if (prompt == null || prompt.isEmpty()) {
            prompt = h.action().command();
        }
        // 模板变量替换
        prompt = ctx.expand(prompt);

        try {
            String output = agentRunner.apply(prompt, ctx);
            return new HookResult(h.id(), output, true, h.reject());
        } catch (Exception e) {
            return new HookResult(h.id(), e.getMessage(), false, h.reject());
        }
    }

    // ======== Utilities ========

    private static boolean isBlank(String s) {
        return s == null || s.strip().isEmpty();
    }

    /** 简单的 JSON 字符串转义 */
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
