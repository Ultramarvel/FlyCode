package com.flycode.sandbox;

import java.util.ArrayList;
import java.util.List;

/**
 * Linux bubblewrap (bwrap) 沙箱实现。
 * bwrap 利用 Linux user namespace 创建轻量级隔离环境。
 */
public class BwrapSandbox implements Sandbox {

    @Override
    public boolean isAvailable() {
        // 通过 which 查找 bwrap 是否在 PATH 中
        try {
            Process p = new ProcessBuilder("which", "bwrap")
                    .redirectErrorStream(true)
                    .start();
            int code = p.waitFor();
            return code == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String wrap(String command, SandboxConfig config) {
        var args = new ArrayList<String>();

        // 隔离 user 和 pid namespace
        args.addAll(List.of("bwrap", "--unshare-user", "--unshare-pid"));

        // 根文件系统只读挂载
        args.addAll(List.of("--ro-bind", "/", "/"));

        // 按路径放行写入（可写绑定）
        for (String path : config.getAllowWrite()) {
            args.addAll(List.of("--bind", path, path));
        }

        // 强制只读（覆盖上面可写根路径下的子路径）
        for (String path : config.getDenyWrite()) {
            args.addAll(List.of("--ro-bind", path, path));
        }

        // 网络隔离
        if (!config.isNetworkEnabled()) {
            args.add("--unshare-net");
        }

        // 挂载 /proc，很多命令依赖它
        args.addAll(List.of("--proc", "/proc"));

        // 追加要执行的命令
        args.addAll(List.of("--", "bash", "-c", command));

        // 拼接成完整命令字符串，shell 特殊字符需要正确转义
        var sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(' ');
            String arg = args.get(i);
            // 包含空格或特殊字符的参数用单引号包裹
            if (arg.matches(".*[ \\t\\n\"'\\\\$`!].*")) {
                sb.append("'").append(arg.replace("'", "'\\''")).append("'");
            } else {
                sb.append(arg);
            }
        }
        return sb.toString();
    }
}
