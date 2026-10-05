package com.flycode.sandbox;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * macOS seatbelt（sandbox-exec）沙箱实现。
 * 通过动态生成 seatbelt profile 控制文件写入和网络访问权限。
 */
public class SeatbeltSandbox implements Sandbox {

    // 硬编码路径，防止 PATH 注入攻击
    private static final String SANDBOX_EXEC_PATH = "/usr/bin/sandbox-exec";

    @Override
    public boolean isAvailable() {
        return Files.exists(Path.of(SANDBOX_EXEC_PATH));
    }

    @Override
    public String wrap(String command, SandboxConfig config) {
        String profile = buildProfile(config);
        // 用 -p 参数传入 profile 内容，用 %q 风格转义命令
        return "%s -p '%s' bash -c %s".formatted(
                SANDBOX_EXEC_PATH, profile, shellQuote(command));
    }

    /**
     * 动态生成 seatbelt profile 字符串。
     * 策略：默认拒绝 → 放行执行/读取 → 按路径放行写入 → 按路径拒绝写入 → 网络控制。
     */
    static String buildProfile(SandboxConfig config) {
        var sb = new StringBuilder();

        sb.append("(version 1)\n");
        sb.append("(deny default)\n");

        // 允许进程执行和 fork
        sb.append("(allow process-exec)\n");
        sb.append("(allow process-fork)\n");
        // 允许读取系统信息
        sb.append("(allow sysctl-read)\n");
        // 全盘可读
        sb.append("(allow file-read* (subpath \"/\"))\n");

        // 按路径放行写入
        for (String path : config.getAllowWrite()) {
            sb.append("(allow file-write* (subpath \"%s\"))\n".formatted(path));
        }

        // 拒绝写入的路径放在 allow 之后，seatbelt 后出现的规则优先。
        // 单文件用 literal 精确匹配，目录用 subpath 前缀匹配。
        for (String path : config.getDenyWrite()) {
            var f = new java.io.File(path);
            String matcher = f.isDirectory() ? "subpath" : "literal";
            sb.append("(deny file-write* (%s \"%s\"))\n".formatted(matcher, path));
        }

        // 网络控制
        if (config.isNetworkEnabled()) {
            sb.append("(allow network*)\n");
        } else {
            sb.append("(deny network*)\n");
        }

        return sb.toString();
    }

    /**
     * 对命令字符串进行 shell 安全转义，防止二次解析问题。
     */
    private static String shellQuote(String s) {
        // 用双引号包裹，转义内部的 \, $, `, ", !
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("$", "\\$")
                       .replace("`", "\\`")
                       .replace("!", "\\!") + "\"";
    }
}
