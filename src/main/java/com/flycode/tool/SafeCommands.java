package com.flycode.tool;

import java.util.Set;

/**
 * 只读的安全命令白名单。
 *
 * <p>两处在用：权限层判断要不要放行，调度层判断能不能并发。放在 tool 包是因为工具
 * 自己要用它算并发安全性，而 tool 包不能反过来依赖 permission 包。
 */
public final class SafeCommands {

    private static final Set<String> SAFE = Set.of(
            "ls", "dir", "pwd", "echo", "cat", "head", "tail", "wc",
            "find", "which", "whereis", "whoami", "hostname", "uname",
            "date", "cal", "uptime", "df", "du", "free", "env", "printenv",
            "file", "stat", "readlink", "realpath", "basename", "dirname",
            "sort", "uniq", "tr", "cut", "awk", "sed", "grep", "egrep", "fgrep",
            "diff", "comm", "tee", "xargs", "true", "false", "test",
            "git status", "git log", "git diff", "git show", "git branch",
            "git tag", "git remote", "git rev-parse", "git ls-files",
            "git blame", "git stash list", "go version", "go env",
            "node -v", "npm -v", "npx", "python --version", "pip list",
            "cargo --version", "rustc --version", "java -version", "java --version"
    );

    private SafeCommands() {}

    /**
     * 判断一条命令是不是只读的安全命令。
     *
     * <p>白名单前缀命中还不够，命令里出现重定向、管道、命令串联或命令替换就一律不算
     * 安全：cat 这种前缀不能成为拼接任意命令的入口。
     */
    public static boolean isSafe(String command) {
        if (command == null) return false;
        String trimmed = command.trim();
        if (trimmed.contains("|") || trimmed.contains(";") || trimmed.contains("&&")
                || trimmed.contains(">") || trimmed.contains("$(") || trimmed.contains("`")) {
            return false;
        }
        for (var safe : SAFE) {
            if (trimmed.equals(safe) || trimmed.startsWith(safe + " ")) {
                return true;
            }
        }
        return false;
    }
}
