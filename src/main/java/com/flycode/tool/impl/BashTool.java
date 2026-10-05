package com.flycode.tool.impl;

import com.flycode.sandbox.Sandbox;
import com.flycode.sandbox.SandboxConfig;
import com.flycode.tool.Tool;
import com.flycode.tool.ToolCategory;
import com.flycode.tool.ToolResult;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class BashTool implements Tool {

    /**
     * 只读命令可以跟别的只读工具并发，会改东西的命令必须独占。
     *
     * <p>ls、cat、git status 这类跟 ReadFile 一样不动外部状态，没有互相干扰的余地；
     * rm、mv、npm install 一旦跟别人并发，执行顺序就不再是模型给出的那个顺序。
     * 判定复用那份安全命令白名单，重定向、管道、命令串联和命令替换都已经排除掉了。
     */
    @Override
    public boolean isConcurrencySafe(java.util.Map<String, Object> args) {
        Object command = args == null ? null : args.get("command");
        return command instanceof String s && com.flycode.tool.SafeCommands.isSafe(s);
    }

    private static final int MAX_TIMEOUT = 600;

    // 特殊命令 exit code 1 的语义提示（grep 没匹配到、diff 文件有差异等）
    private static final Map<String, String> EXIT_ONE_HINTS = Map.of(
            "grep", "no matches found",
            "egrep", "no matches found",
            "fgrep", "no matches found",
            "rg", "no matches found",
            "diff", "files differ",
            "find", "some directories were inaccessible",
            "test", "condition is false",
            "[", "condition is false"
    );

    // 工作目录
    private String workDir;

    // OS 级沙箱：包装命令在隔离环境中执行
    private Sandbox sandbox;
    private SandboxConfig sandboxConfig;

    public BashTool() {
        this.workDir = null;
    }

    public BashTool(String workDir) {
        this.workDir = workDir;
    }

    /** 设置 OS 级沙箱，命令执行前会通过沙箱包装 */
    public void setSandbox(Sandbox sandbox) { this.sandbox = sandbox; }
    public void setSandboxConfig(SandboxConfig config) { this.sandboxConfig = config; }

    private static final String DESCRIPTION = """
            Execute a shell command and return stdout and stderr.

            IMPORTANT: Avoid using this tool to run cat, head, tail, sed, awk, or echo commands. \
            Instead use the dedicated ReadFile, EditFile, or WriteFile tools which provide a better experience.

            Usage notes:
            - The working directory persists between commands, but shell state does not.
            - Always quote file paths containing spaces with double quotes.
            - Try to maintain your current working directory using absolute paths; avoid cd unless the user explicitly requests it.
            - Optional timeout in seconds (max 600). Default is 120s.
            - When issuing multiple independent commands, make separate parallel tool calls instead of chaining with &&.
            - Use && to chain sequential dependent commands. Use ; only when you don't care if earlier commands fail.
            - DO NOT use newlines to separate commands.

            Git Safety Protocol:
            - NEVER run destructive git commands (push --force, reset --hard, checkout ., clean -f, branch -D) unless the user explicitly requests it.
            - NEVER skip hooks (--no-verify) unless the user explicitly requests it.
            - Prefer creating a new commit rather than amending an existing one.
            - Before running destructive operations, consider safer alternatives.

            Avoid unnecessary sleep commands. Do not retry failing commands in a sleep loop — diagnose the root cause instead.
            When using find, search from "." or a specific path, not "/" — scanning the full filesystem is too expensive.""";

    @Override
    public String name() {
        return "Bash";
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.COMMAND;
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "command", Map.of("type", "string", "description", "Shell command to execute"),
                                "timeout", Map.of("type", "integer", "description", "Timeout in seconds (max 600)", "default", 120)
                        ),
                        "required", List.of("command")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String command = stringArg(args, "command", "");
        if (command.isEmpty()) {
            return ToolResult.error("Error: command is required");
        }

        int timeout = intArg(args, "timeout", 120);
        if (timeout > MAX_TIMEOUT) {
            timeout = MAX_TIMEOUT;
        }

        try {
            // 如果沙箱可用，将命令包装在沙箱中执行
            String actualCommand = command;
            if (sandbox != null && sandbox.isAvailable() && sandboxConfig != null) {
                actualCommand = sandbox.wrap(command, sandboxConfig);
            }

            ProcessBuilder pb = new ProcessBuilder("bash", "-c", actualCommand);
            // 合并 stdout 和 stderr 到同一个流，简化输出解析
            pb.redirectErrorStream(true);

            // 设置工作目录
            if (workDir != null && !workDir.isEmpty()) {
                pb.directory(new java.io.File(workDir));
            }

            Process process = pb.start();

            // 合并流后只需读取 getInputStream()
            String output;
            try (InputStream stream = process.getInputStream()) {
                output = new String(stream.readAllBytes());
            }

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.error("Error: command timed out after " + timeout + "s");
            }

            int exitCode = process.exitValue();

            var sb = new StringBuilder();
            if (!output.isEmpty()) {
                sb.append(output);
                if (!output.endsWith("\n")) {
                    sb.append('\n');
                }
            }

            // 非零 exit code 时附加退出码信息，但不设置 isError
            if (exitCode != 0) {
                sb.append("Exit code ").append(exitCode);
                // 对特殊命令附加语义提示
                String hint = getExitCodeHint(command, exitCode);
                if (hint != null) {
                    sb.append(" (").append(hint).append(")");
                }
                sb.append('\n');
            }

            // 正常执行完成，isError 始终为 false（仅超时和中断才为 true）
            return new ToolResult(sb.toString(), false);

        } catch (IOException e) {
            return ToolResult.error("Error executing command: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Error: command interrupted");
        }
    }

    /**
     * 对特殊命令的 exit code 1 返回语义提示。
     * 管道命令取最后一段（bash 默认返回最后一个命令的 exit code），
     * 对于 grep/diff/find/test 等命令，exit code 1 属于正常结果，附加提示帮助理解。
     */
    private String getExitCodeHint(String command, int exitCode) {
        if (exitCode != 1) {
            return null;
        }
        String baseCmd = extractBaseCommand(command);
        return EXIT_ONE_HINTS.get(baseCmd);
    }

    /**
     * 从完整命令字符串中提取基础命令名。
     * 处理管道（取最后一段）、路径前缀（取 basename）、env 前缀等。
     */
    private String extractBaseCommand(String command) {
        String cmd = command.strip();

        // 管道：取最后一段，因为 bash 的 exit code 由管道最后一个命令决定
        int pipeIdx = cmd.lastIndexOf('|');
        if (pipeIdx >= 0 && pipeIdx < cmd.length() - 1) {
            cmd = cmd.substring(pipeIdx + 1).strip();
        }

        // 跳过 env 变量赋值前缀（如 FOO=bar grep ...）
        while (cmd.contains("=") && !cmd.startsWith("=")) {
            int spaceIdx = cmd.indexOf(' ');
            int eqIdx = cmd.indexOf('=');
            if (eqIdx < spaceIdx || spaceIdx == -1) {
                // 这一段是环境变量赋值，跳过
                if (spaceIdx == -1) break;
                cmd = cmd.substring(spaceIdx + 1).strip();
            } else {
                break;
            }
        }

        // 取第一个 token（命令名本身）
        String[] parts = cmd.split("\\s+", 2);
        String token = parts[0];

        // 处理路径前缀，如 /usr/bin/grep → grep
        int slashIdx = token.lastIndexOf('/');
        if (slashIdx >= 0 && slashIdx < token.length() - 1) {
            token = token.substring(slashIdx + 1);
        }

        return token;
    }

    private static String stringArg(Map<String, Object> args, String key, String def) {
        var v = args.get(key);
        return v instanceof String s ? s : def;
    }

    private static int intArg(Map<String, Object> args, String key, int def) {
        var v = args.get(key);
        if (v instanceof Number n) return n.intValue();
        return def;
    }
}
