package com.flycode.teams;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentNameRegistryTest {

    @BeforeEach
    void reset() {
        AgentNameRegistry.getInstance().clear();
    }

    @Test
    void resolveByNameReturnsId() {
        var reg = AgentNameRegistry.getInstance();
        reg.register("reviewer", "agent-7");
        assertEquals("agent-7", reg.resolve("reviewer"));
    }

    @Test
    void resolveByIdReturnsIdItself() {
        var reg = AgentNameRegistry.getInstance();
        reg.register("reviewer", "agent-7");
        assertEquals("agent-7", reg.resolve("agent-7"));
    }

    @Test
    void resolveUnknownReturnsNull() {
        assertNull(AgentNameRegistry.getInstance().resolve("ghost"));
    }

    @Test
    void unregisterRemovesMapping() {
        var reg = AgentNameRegistry.getInstance();
        reg.register("reviewer", "agent-7");
        reg.unregister("reviewer");
        assertNull(reg.resolve("reviewer"));
    }
}
