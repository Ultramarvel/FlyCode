package com.flycode.permission;

import com.flycode.tool.Tool;
import com.flycode.tool.ToolCategory;
import com.flycode.tool.impl.McpCallTool;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PermissionChecker {

    private PermissionMode mode;
    private final Path projectRoot;

    private String planFilePath;

    /** 沙箱保护路径列表：这些路径始终禁止写入，即使用户有写权限 */
    private final List<String> denyWrite;

    /** 沙箱模式开关：开启后命令类工具自动放行（由 OS 级沙箱保护） */
    private boolean sandboxEnabled;

    /** 项目根之外额外放开的目录，例如后台 Agent 要访问的用户级记忆目录 */
    private final List<Path> extraAllowedRoots = new ArrayList<>();

    /** A single parsed rule from a permissions.yaml file. */
    private record PermissionRule(String toolName, String pattern, RuleEffect effect) {
        boolean matches(String toolName, String content) {
            if (!this.toolName.equals(toolName)) {
                return false;
            }
            // 简单通配符匹配：* 匹配任意字符（包括 /），适用于 Bash 命令
            return globMatch(pattern, content);
        }

        private static boolean globMatch(String pattern, String content) {
            String re = "^" + pattern
                    .replace("\\", "\\\\")
                    .replace(".", "\\.")
                    .replace("+", "\\+")
                    .replace("^", "\\^")
                    .replace("$", "\\$")
                    .replace("{", "\\{")
                    .replace("}", "\\}")
                    .replace("(", "\\(")
                    .replace(")", "\\)")
                    .replace("|", "\\|")
                    .replace("[", "\\[")
                    .replace("]", "\\]")
                    .replace("*", ".*")
                    .replace("?", ".") + "$";
            try {
                return content.matches(re);
            } catch (Exception e) {
                return content.equals(pattern);
            }
        }
    }

    private enum RuleEffect {
        ALLOW, DENY, ASK
    }

    private static final Set<String> PLAN_MODE_ALLOWED_TOOLS = Set.of(
            "Agent", "ToolSearch", "AskUserQuestion", "ExitPlanMode"
    );

    private static final List<Pattern> DANGEROUS_PATTERNS = List.of(
            Pattern.compile("rm\\s+-[a-z]*r[a-z]*f[a-z]*\\s+/\\s*$"),
            Pattern.compile("mkfs\\."),
            Pattern.compile("dd\\s+if=.*of=/dev/"),
            Pattern.compile("chmod\\s+-R\\s+777\\s+/"),
            Pattern.compile(":\\(\\)\\{\\s*:\\|:&\\s*\\};:"),
            Pattern.compile("curl\\s+.*\\|\\s*(ba)?sh"),
            Pattern.compile("wget\\s+.*\\|\\s*(ba)?sh"),
            Pattern.compile(">\\s*/dev/sd")
    );

    private static final Map<String, String> CONTENT_FIELDS = Map.of(
            "Bash", "command",
            "ReadFile", "file_path",
            "WriteFile", "file_path",
            "EditFile", "file_path",
            "Glob", "pattern",
            "Grep", "pattern"
    );

    /** 默认受保护的路径：配置文件和权限文件不允许被 AI 写入 */
    private static final List<String> DEFAULT_DENY_WRITE = List.of(
            ".flycode/config.yaml",
            ".flycode/permissions.local.yaml",
            ".flycode/skills/"
    );

    public PermissionChecker(PermissionMode mode, Path projectRoot) {
        this.mode = mode;
        this.projectRoot = projectRoot;

        // 初始化 denyWrite 列表，将相对路径解析为绝对路径
        var resolvedDeny = new ArrayList<String>();
        if (projectRoot != null) {
            for (String rel : DEFAULT_DENY_WRITE) {
                resolvedDeny.add(projectRoot.resolve(rel).toAbsolutePath().normalize().toString());
            }
        }
        this.denyWrite = resolvedDeny;
        this.sandboxEnabled = false;
    }

    public PermissionMode getMode() { return mode; }
    public void setMode(PermissionMode mode) { this.mode = mode; }
    public void setPlanFilePath(String path) { this.planFilePath = path; }

    public boolean isSandboxEnabled() { return sandboxEnabled; }
    public void setSandboxEnabled(boolean enabled) { this.sandboxEnabled = enabled; }

    /**
     * 放开一个项目根之外的目录。后台 Agent 需要读写用户级数据（如用户级记忆目录）时，
     * 由调用方显式声明，沙箱基线本身保持在项目根不变。
     */
    public void allowExtraRoot(Path path) {
        if (path != null) {
            extraAllowedRoots.add(path.toAbsolutePath().normalize());
        }
    }
    public List<String> getDenyWrite() { return Collections.unmodifiableList(denyWrite); }

    public record CheckResult(PermissionMode.Decision decision, String reason) {
        public static CheckResult allow() { return new CheckResult(PermissionMode.Decision.ALLOW, ""); }

        public static CheckResult deny(String reason) { return new CheckResult(PermissionMode.Decision.DENY, reason); }
        public static CheckResult ask() { return new CheckResult(PermissionMode.Decision.ASK, ""); }
        public static CheckResult ask(String reason) { return new CheckResult(PermissionMode.Decision.ASK, reason); }
    }

    public CheckResult check(Tool tool, Map<String, Object> args) {
        String toolName = tool.name();
        String content = extractContent(toolName, args);

        // Layer 0: Plan mode exceptions
        if (mode == PermissionMode.PLAN) {
            if (PLAN_MODE_ALLOWED_TOOLS.contains(toolName)) {
                return CheckResult.allow();
            }
            if ("WriteFile".equals(toolName) || "EditFile".equals(toolName)) {
                String path = stringArg(args, "file_path", "");
                if (path.contains(".flycode/plans/")) {
                    return CheckResult.allow();
                }
            }
        }

        // Layer 1: Safe commands (auto-allow)
        if ("Bash".equals(toolName) && isSafeCommand(content)) {
            return CheckResult.allow();
        }

        // Layer 2: Dangerous command detection
        if ("Bash".equals(toolName)) {
            for (var pattern : DANGEROUS_PATTERNS) {
                if (pattern.matcher(content).find()) {
                    return CheckResult.deny("Dangerous command detected: " + pattern.pattern());
                }
            }
        }

        // Layer 2b: denyWrite 保护路径检查（沙箱保护的敏感路径始终禁止写入）
        if (!content.isEmpty() && isWritePathTool(toolName) && isDeniedPath(content)) {
            return CheckResult.deny("Path is protected by sandbox: " + content);
        }

        // Layer 3: Path sandbox
        if (!content.isEmpty() && isPathTool(toolName)) {
            if (!isPathAllowed(content) && mode != PermissionMode.BYPASS) {
                return CheckResult.ask("Path outside allowed sandbox: " + content);
            }
        }

        // 规则快照按需取一次：安全命令、危险命令这些在前面几层就返回，压根不必碰规则文件；
        // 复合命令逐条检查子命令时共用同一份快照，不重复读盘
        List<PermissionRule> snapshot;

        // Layer 4: 合并三份规则文件后按 deny > ask > allow 裁决。
        // 所有工具都进这一层，包括 content 为空串的：写 Tool(*) 这类通配规则时要能命中。
        snapshot = loadRules();
        PermissionRule matched = evaluateRules(snapshot, toolName, content);
        if (matched != null) {
            return switch (matched.effect) {
                case ALLOW -> CheckResult.allow();
                case DENY -> CheckResult.deny("Denied by rule: " + matched.toolName + "(" + matched.pattern + ")");
                case ASK -> CheckResult.ask();
            };
        }

        // Layer 4b: 沙箱模式下命令类工具默认放行（OS 沙箱已兜底），但仍需
        // 拆分复合命令逐条检查 deny/ask 规则，防止通过命令拼接绕过显式禁令。
        if (sandboxEnabled && tool.category() == ToolCategory.COMMAND) {
            String[] subcommands = content.split("\\s*(?:&&|\\|\\||[;|])\\s*");
            boolean hasAsk = false;
            for (String sub : subcommands) {
                sub = sub.trim();
                if (sub.isEmpty()) continue;
                PermissionRule subMatched = evaluateRules(snapshot, toolName, sub);
                if (subMatched == null) continue;
                if (subMatched.effect == RuleEffect.DENY) {
                    return CheckResult.deny("Permission rule: deny");
                }
                if (subMatched.effect == RuleEffect.ASK) {
                    hasAsk = true;
                }
            }
            if (hasAsk) {
                return CheckResult.ask();
            }
            return CheckResult.allow();
        }

        // Layer 5: Permission mode matrix
        var decision = mode.decide(tool.category());
        return switch (decision) {
            case ALLOW -> CheckResult.allow();
            case DENY -> CheckResult.deny("Denied by permission mode: " + mode);
            case ASK -> CheckResult.ask();
        };
    }

    /**
     * 把三份规则文件的规则合并成一个集合，返回命中规则中最严格的那条。
     * 优先级 deny &gt; ask &gt; allow：规则写在哪一层、写在文件第几行都不影响裁决，
     * 因此一条 deny 无法被其他层的 allow 抵消。没有任何规则命中时返回 null。
     *
     * 每次评估都重新读取规则文件，用户改完配置无需重启即刻生效。
     */
    private PermissionRule evaluateRules(List<PermissionRule> rules, String toolName, String content) {
        PermissionRule hit = null;
        for (PermissionRule rule : rules) {
            if (!rule.matches(toolName, content)) continue;
            // deny 已是最严效果，不可能再被压过，直接返回
            if (rule.effect == RuleEffect.DENY) return rule;
            // ask 压过 allow；allow 只在还没命中更严的效果时记录
            if (rule.effect == RuleEffect.ASK || hit == null) hit = rule;
        }
        return hit;
    }

    // --- Rule loading from YAML files ---

    private static final Pattern RULE_PATTERN = Pattern.compile("^(\\w+)\\((.+)\\)$");

    /**
     * Load permission rules from user-level, project-level, and local YAML files.
     * User-level: ~/.flycode/permissions.yaml
     * Project-level: {projectRoot}/.flycode/permissions.yaml
     * Local-level: {projectRoot}/.flycode/permissions.local.yaml
     *
     * Rules are loaded in order; the last matching rule wins when evaluated.
     */
    private List<PermissionRule> loadRules() {
        var rules = new ArrayList<PermissionRule>();

        // User-level rules
        Path userHome = Path.of(System.getProperty("user.home"));
        Path userFile = userHome.resolve(".flycode").resolve("permissions.yaml");
        rules.addAll(rulesFor(userFile));

        // Project-level rules
        if (projectRoot != null) {
            Path projectFile = projectRoot.resolve(".flycode").resolve("permissions.yaml");
            rules.addAll(rulesFor(projectFile));

            // Local-level rules (gitignored, session-persistent)
            Path localFile = projectRoot.resolve(".flycode").resolve("permissions.local.yaml");
            rules.addAll(rulesFor(localFile));
        }

        return new ArrayList<>(rules);
    }

    /** 单个规则文件的解析结果。mtime + size 一起作为文件是否变动的依据，
     *  只比 mtime 不够：同一时刻的连续改写在部分文件系统上时间戳可能不变。 */
    private record CachedRules(long mtimeNanos, long size, List<PermissionRule> rules) {}

    private final Map<Path, CachedRules> ruleCache = new HashMap<>();

    /**
     * 读取单个规则文件，命中缓存时不读盘也不解析。
     * 文件变动了才重新解析，因此改完规则文件下次评估即刻生效。
     */
    private List<PermissionRule> rulesFor(Path path) {
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(path, BasicFileAttributes.class);
        } catch (IOException e) {
            // 文件不存在或读不到，按空规则处理，同时清掉可能存在的旧缓存
            ruleCache.remove(path);
            return List.of();
        }

        long mtimeNanos = attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS);
        long size = attrs.size();

        CachedRules cached = ruleCache.get(path);
        if (cached != null && cached.mtimeNanos() == mtimeNanos && cached.size() == size) {
            return cached.rules();
        }

        List<PermissionRule> rules = loadRulesFile(path);
        ruleCache.put(path, new CachedRules(mtimeNanos, size, rules));
        return rules;
    }

    public void appendLocalRule(String toolName, String pattern) {
        if (projectRoot == null) return;
        Path localFile = projectRoot.resolve(".flycode").resolve("permissions.local.yaml");
        try {
            Files.createDirectories(localFile.getParent());
            var rules = new ArrayList<>(loadRulesFile(localFile));
            rules.add(new PermissionRule(toolName, pattern, RuleEffect.ALLOW));

            var entries = new ArrayList<Map<String, String>>();
            for (var r : rules) {
                // 按原效果写回，三种取值都要保留
                String effect = switch (r.effect) {
                    case ALLOW -> "allow";
                    case ASK -> "ask";
                    case DENY -> "deny";
                };
                entries.add(Map.of("rule", r.toolName + "(" + r.pattern + ")", "effect", effect));
            }
            var yaml = new Yaml();
            Files.writeString(localFile, yaml.dump(entries));
        } catch (IOException ignored) {}
    }

    /**
     * Parse a single YAML permissions file into a list of rules.
     * Expected format: a YAML list of maps with "rule" and "effect" keys.
     * Example:
     *   - rule: "Bash(git *)"
     *     effect: allow
     *   - rule: "WriteFile(/etc/*)"
     *     effect: deny
     */
    @SuppressWarnings("unchecked")
    private List<PermissionRule> loadRulesFile(Path path) {
        if (!Files.exists(path)) {
            return List.of();
        }

        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            return List.of();
        }

        Yaml yaml = new Yaml();
        Object parsed;
        try {
            parsed = yaml.load(content);
        } catch (Exception e) {
            return List.of();
        }

        if (!(parsed instanceof List<?> entries)) {
            return List.of();
        }

        var rules = new ArrayList<PermissionRule>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            Object ruleObj = map.get("rule");
            Object effectObj = map.get("effect");
            if (!(ruleObj instanceof String ruleStr) || !(effectObj instanceof String effectStr)) {
                continue;
            }

            RuleEffect effect;
            if ("allow".equals(effectStr)) {
                effect = RuleEffect.ALLOW;
            } else if ("deny".equals(effectStr)) {
                effect = RuleEffect.DENY;
            } else if ("ask".equals(effectStr)) {
                effect = RuleEffect.ASK;
            } else {
                continue;
            }

            Matcher m = RULE_PATTERN.matcher(ruleStr.trim());
            if (!m.matches()) {
                continue;
            }
            rules.add(new PermissionRule(m.group(1), m.group(2), effect));
        }
        return rules;
    }

    /**
     * 判断一条命令是不是只读的安全命令。
     *
     * <p>实现搬到了 tool 包的 SafeCommands：并发调度也要用同一份判定（只读命令可以跟
     * 只读工具一起跑），而 tool 包不能反过来依赖 permission 包。
     */
    private boolean isSafeCommand(String command) {
        return com.flycode.tool.SafeCommands.isSafe(command);
    }

    private boolean isPathTool(String toolName) {
        return "ReadFile".equals(toolName) || "WriteFile".equals(toolName) || "EditFile".equals(toolName);
    }

    /** 写入类工具（WriteFile、EditFile），用于 denyWrite 检查 */
    private boolean isWritePathTool(String toolName) {
        return "WriteFile".equals(toolName) || "EditFile".equals(toolName);
    }

    /**
     * 检查路径是否在 denyWrite 保护列表中。
     * 如果目标路径以任何 denyWrite 条目为前缀，则禁止写入。
     */
    private boolean isDeniedPath(String pathStr) {
        try {
            String normalized = Path.of(pathStr).toAbsolutePath().normalize().toString();
            for (String deny : denyWrite) {
                if (normalized.startsWith(deny)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private boolean isPathAllowed(String pathStr) {
        try {
            Path p = Path.of(pathStr).toAbsolutePath().normalize();
            Path root = projectRoot.toAbsolutePath().normalize();
            Path tmp = Path.of("/tmp").toAbsolutePath().normalize();
            if (p.startsWith(root) || p.startsWith(tmp)) {
                return true;
            }
            for (Path extra : extraAllowedRoots) {
                if (p.startsWith(extra)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 抽出规则 pattern 要匹配的那段内容。表外的工具没有可匹配内容，返回空串而不是
     * null——规则层只在内容非 null 时才评估，返回 null 等于让这些工具绕过整个规则
     * 引擎，写了 {@code Agent(*)} 之类的规则也不会生效。空串照样进规则层，命中
     * {@code Tool(*)} 这种通配规则。
     */
    public static String extractContent(String toolName, Map<String, Object> args) {
        // mcp_call 的匹配对象不是某一个参数，而是「要调用哪个 MCP 工具」，
        // 由 server + tool 两段合成 server__tool，规则因此写成 mcp_call(linear__*)
        if (McpCallTool.MCP_CALL_TOOL_NAME.equals(toolName)) {
            return McpCallTool.permissionContent(
                    stringArg(args, "server", ""), stringArg(args, "tool", ""));
        }
        String field = CONTENT_FIELDS.get(toolName);
        if (field == null) return "";
        var v = args.get(field);
        return v instanceof String s ? s : "";
    }

    private static String stringArg(Map<String, Object> args, String key, String def) {
        var v = args.get(key);
        return v instanceof String s ? s : def;
    }

    public String describeToolAction(String toolName, Map<String, Object> args) {
        return switch (toolName) {
            case "Bash" -> "Execute: " + stringArg(args, "command", "");
            case "ReadFile" -> "Read: " + stringArg(args, "file_path", "");
            case "WriteFile" -> "Write: " + stringArg(args, "file_path", "");
            case "EditFile" -> "Edit: " + stringArg(args, "file_path", "");
            case "Glob" -> "Glob: " + stringArg(args, "pattern", "");
            case "Grep" -> "Grep: " + stringArg(args, "pattern", "");
            case "Agent" -> {
                String desc = stringArg(args, "description", "");
                String prompt = stringArg(args, "prompt", "");
                if (!desc.isEmpty()) {
                    yield "Agent: " + desc;
                } else if (!prompt.isEmpty()) {
                    yield "Agent: " + (prompt.length() > 80 ? prompt.substring(0, 77) + "..." : prompt);
                } else {
                    yield "Agent";
                }
            }
            default -> {
                var parts = new java.util.ArrayList<String>();
                for (var entry : args.entrySet()) {
                    String s = String.valueOf(entry.getValue());
                    if (s.length() > 80) s = s.substring(0, 77) + "...";
                    parts.add(entry.getKey() + "=" + s);
                }
                yield parts.isEmpty() ? toolName : String.join(", ", parts);
            }
        };
    }
}
