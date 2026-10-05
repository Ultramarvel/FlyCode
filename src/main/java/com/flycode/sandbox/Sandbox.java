package com.flycode.sandbox;

/**
 * OS 级沙箱的统一接口。
 * 不同平台（macOS seatbelt / Linux bubblewrap）各自实现。
 */
public interface Sandbox {

    /**
     * 将原始命令包装成沙箱内执行的命令字符串。
     *
     * @param command 原始 shell 命令
     * @param config  沙箱配置（允许写入路径、拒绝写入路径、网络控制）
     * @return 包装后的命令字符串
     */
    String wrap(String command, SandboxConfig config);

    /**
     * 检查当前平台的沙箱工具是否可用。
     */
    boolean isAvailable();
}
