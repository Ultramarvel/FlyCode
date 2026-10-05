package com.flycode.sandbox;

import java.util.List;

/**
 * 控制沙箱的读写和网络权限。
 */
public class SandboxConfig {

    private final List<String> allowWrite;   // 允许写入的路径列表
    private final List<String> denyWrite;    // 始终只读的路径（优先级高于 allowWrite）
    private final boolean networkEnabled;    // 是否允许网络访问

    public SandboxConfig(List<String> allowWrite, List<String> denyWrite, boolean networkEnabled) {
        this.allowWrite = allowWrite != null ? List.copyOf(allowWrite) : List.of();
        this.denyWrite = denyWrite != null ? List.copyOf(denyWrite) : List.of();
        this.networkEnabled = networkEnabled;
    }

    public List<String> getAllowWrite() { return allowWrite; }
    public List<String> getDenyWrite() { return denyWrite; }
    public boolean isNetworkEnabled() { return networkEnabled; }
}
