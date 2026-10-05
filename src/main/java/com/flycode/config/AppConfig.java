package com.flycode.config;

import java.util.List;

public class AppConfig {

    private List<ProviderConfig> providers;
    private String permissionMode;

    private List<McpServerConfig> mcpServers;
    private List<HookConfig> hooks;

    // 沙箱配置（嵌套对象，对应 YAML 中的 sandbox: 节点）
    private SandboxYamlConfig sandbox;

    private boolean enableCoordinatorMode;

    // enableFork 控制省略 subagent_type 时是否走 fork。用包装类型是因为它默认开着，
    // 基本类型 boolean 分不清「配置里没写」和「配置里写了 false」，后者就永远关不掉。
    private Boolean enableFork;

    public List<ProviderConfig> getProviders() { return providers; }

    public void setProviders(List<ProviderConfig> providers) { this.providers = providers; }

    public String getPermissionMode() { return permissionMode; }

    public void setPermissionMode(String permissionMode) { this.permissionMode = permissionMode; }

    public List<McpServerConfig> getMcpServers() { return mcpServers; }
    public void setMcpServers(List<McpServerConfig> mcpServers) { this.mcpServers = mcpServers; }

    public List<HookConfig> getHooks() { return hooks; }
    public void setHooks(List<HookConfig> hooks) { this.hooks = hooks; }

    public SandboxYamlConfig getSandbox() { return sandbox; }
    public void setSandbox(SandboxYamlConfig sandbox) { this.sandbox = sandbox; }

    public boolean isSandboxEnabled() { return sandbox != null && sandbox.isEnabled(); }
    public boolean isSandboxAutoAllow() { return sandbox == null || sandbox.isAutoAllow(); }
    public boolean isSandboxNetworkEnabled() { return sandbox != null && sandbox.isNetworkEnabled(); }

    public boolean isEnableCoordinatorMode() { return enableCoordinatorMode; }
    public void setEnableCoordinatorMode(boolean enableCoordinatorMode) { this.enableCoordinatorMode = enableCoordinatorMode; }

    public Boolean getEnableFork() { return enableFork; }
    public void setEnableFork(Boolean enableFork) { this.enableFork = enableFork; }

    /** fork 是否可用。配置里没写就是开着。 */
    public boolean isForkEnabled() { return enableFork == null || enableFork; }
}
