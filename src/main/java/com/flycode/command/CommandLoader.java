package com.flycode.command;

import com.flycode.command.Command.CommandType;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Function;

/**
 * 从 .flycode/commands/ 目录加载自定义 Markdown 命令文件。
 * 实现 LoadDir / LoadUserCommands 的等价逻辑。
 *
 * <p>每个 .md 文件被解析为一个 PROMPT 类型命令。命令名由文件相对路径决定：
 * 子目录用 ':' 连接（如 git/log.md → "git:log"）。文件可包含可选的 YAML
 * frontmatter（description、argument-hint、aliases 字段）。
 */
public final class CommandLoader {

    private CommandLoader() {}

    /**
     * 合并用户全局和项目级的文件命令。
     * 搜索路径：1. ~/.flycode/commands/  2. $workDir/.flycode/commands/
     * 后者覆盖前者同名命令。
     */
    public static List<Command> loadUserCommands(String workDir) {
        List<String> dirs = new ArrayList<>();

        String home = System.getProperty("user.home");
        if (home != null && !home.isEmpty()) {
            dirs.add(Path.of(home, ".flycode", "commands").toString());
        }
        dirs.add(Path.of(workDir, ".flycode", "commands").toString());

        // 按名称去重，后来的覆盖先来的
        Map<String, CommandWithHandler> merged = new LinkedHashMap<>();
        for (String dir : dirs) {
            for (var entry : loadDir(dir)) {
                merged.put(entry.cmd.name(), entry);
            }
        }
        return List.copyOf(merged.values().stream().map(e -> e.cmd).toList());
    }

    /**
     * 将加载到的文件命令注册到 registry，跳过与已有命令冲突的条目。
     */
    public static void registerUserCommands(CommandRegistry registry, String workDir) {
        List<String> dirs = new ArrayList<>();

        String home = System.getProperty("user.home");
        if (home != null && !home.isEmpty()) {
            dirs.add(Path.of(home, ".flycode", "commands").toString());
        }
        dirs.add(Path.of(workDir, ".flycode", "commands").toString());

        // 按名称去重，后来的覆盖先来的
        Map<String, CommandWithHandler> merged = new LinkedHashMap<>();
        for (String dir : dirs) {
            for (var entry : loadDir(dir)) {
                merged.put(entry.cmd.name(), entry);
            }
        }

        for (var entry : merged.values()) {
            // 冲突检测：跳过与内置命令冲突的文件命令
            if (registry.hasConflict(entry.cmd)) {
                continue;
            }
            registry.register(entry.cmd, entry.handler);
        }
    }

    // ── 内部实现 ───────────────────────────────────────────────────────

    private record CommandWithHandler(Command cmd, Function<CommandContext, String> handler) {}

    /**
     * 递归扫描目录下所有 .md 文件，每个文件解析为一个命令。
     */
    private static List<CommandWithHandler> loadDir(String dir) {
        Path dirPath = Path.of(dir);
        if (!Files.isDirectory(dirPath)) {
            return List.of();
        }

        List<CommandWithHandler> results = new ArrayList<>();
        try {
            Files.walkFileTree(dirPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!file.toString().endsWith(".md")) {
                        return FileVisitResult.CONTINUE;
                    }
                    var entry = parseCommandFile(dirPath, file);
                    if (entry != null) {
                        results.add(entry);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // 目录不可读则跳过
        }
        return results;
    }

    /**
     * 解析单个 .md 命令文件。命令名由相对路径计算：
     * sub/dir/foo.md → "sub:dir:foo"，全部小写。
     */
    private static CommandWithHandler parseCommandFile(Path baseDir, Path file) {
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            return null;
        }

        // 计算命令名：相对路径去掉 .md 后缀，路径分隔符换成 ':'
        Path rel = baseDir.relativize(file);
        String relStr = rel.toString();
        if (relStr.toLowerCase().endsWith(".md")) {
            relStr = relStr.substring(0, relStr.length() - 3);
        }
        // 路径分隔符统一替换为 ':'，空格替换为 '-'
        String[] parts = relStr.split("[/\\\\]");
        StringBuilder nameBuilder = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) nameBuilder.append(':');
            nameBuilder.append(parts[i].toLowerCase().replace(' ', '-'));
        }
        String name = nameBuilder.toString();
        if (name.isEmpty()) {
            return null;
        }

        // 分离 frontmatter 和 body
        var parsed = splitFrontmatter(content);
        String body = parsed.body.strip();
        String description = parsed.meta.description;
        String[] aliases = parsed.meta.aliases != null ? parsed.meta.aliases : new String[0];

        // 如果没有描述，取 body 中第一个非空非标题行
        if (description == null || description.isBlank()) {
            description = firstNonHeaderLine(body);
        }
        if (description == null) {
            description = "";
        }

        Command cmd = new Command(name, description, aliases, CommandType.PROMPT, false);
        // 构建 handler：支持 $ARGUMENTS 替换
        Function<CommandContext, String> handler = promptHandler(body);

        return new CommandWithHandler(cmd, handler);
    }

    /**
     * 生成命令 handler：body 中有 $ARGUMENTS 则替换，
     * 否则将参数追加到 "## User Request" 段落。
     */
    private static Function<CommandContext, String> promptHandler(String body) {
        return ctx -> {
            String args = ctx.args();
            if (body.contains("$ARGUMENTS")) {
                return body.replace("$ARGUMENTS", args != null ? args : "");
            }
            if (args == null || args.isBlank()) {
                return body;
            }
            return body + "\n\n## User Request\n\n" + args;
        };
    }

    // ── Frontmatter 解析 ──────────────────────────────────────────────

    private record CommandMeta(String description, String argumentHint, String[] aliases) {}
    private record ParsedFile(CommandMeta meta, String body) {}

    @SuppressWarnings("unchecked")
    private static ParsedFile splitFrontmatter(String content) {
        String trimmed = content.stripLeading();
        if (!trimmed.startsWith("---")) {
            return new ParsedFile(new CommandMeta(null, null, null), content);
        }

        // 按 "---" 分割：parts[0] 为空，parts[1] 为 YAML，parts[2] 为 body
        String[] parts = content.split("---", 3);
        if (parts.length < 3) {
            return new ParsedFile(new CommandMeta(null, null, null), content);
        }

        String yamlBlock = parts[1];
        String body = parts[2];

        try {
            Yaml yaml = new Yaml();
            Map<String, Object> map = yaml.load(yamlBlock);
            if (map == null) {
                return new ParsedFile(new CommandMeta(null, null, null), body);
            }

            String description = map.get("description") instanceof String s ? s : null;
            String argumentHint = map.get("argument-hint") instanceof String s ? s : null;

            String[] aliases = null;
            Object rawAliases = map.get("aliases");
            if (rawAliases instanceof List<?> list) {
                aliases = list.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .toArray(String[]::new);
            }

            return new ParsedFile(new CommandMeta(description, argumentHint, aliases), body);
        } catch (Exception e) {
            return new ParsedFile(new CommandMeta(null, null, null), content);
        }
    }

    /**
     * 返回 body 中第一个非空、非标题行，用作描述的后备。
     */
    private static String firstNonHeaderLine(String body) {
        for (String line : body.split("\n")) {
            String stripped = line.strip();
            if (!stripped.isEmpty() && !stripped.startsWith("#")) {
                return stripped;
            }
        }
        return null;
    }
}
