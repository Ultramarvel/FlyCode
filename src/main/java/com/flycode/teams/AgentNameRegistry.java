package com.flycode.teams;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global singleton maintaining name → agent_id mappings.
 */
public final class AgentNameRegistry {

    private static final AgentNameRegistry INSTANCE = new AgentNameRegistry();

    private final Map<String, String> nameToId = new LinkedHashMap<>();

    private AgentNameRegistry() {}

    public static AgentNameRegistry getInstance() { return INSTANCE; }

    public synchronized void register(String name, String agentId) {
        nameToId.put(name, agentId);
    }

    public synchronized String resolve(String nameOrId) {
        if (nameToId.containsKey(nameOrId)) return nameToId.get(nameOrId);
        if (nameToId.containsValue(nameOrId)) return nameOrId;
        return null;
    }

    public synchronized void unregister(String name) {
        nameToId.remove(name);
    }

    /** 清空全部映射，主要用于测试隔离。 */
    public synchronized void clear() {
        nameToId.clear();
    }
}
