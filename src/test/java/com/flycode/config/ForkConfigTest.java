package com.flycode.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * enable_fork 默认开着，而且配置里写 false 必须真的能关掉。
 * 用基本类型 boolean 存的话「没写」和「写了 false」是同一个默认值，后者就永远关不掉。
 */
class ForkConfigTest {

    @Test
    void 没写时默认开着() {
        assertTrue(new AppConfig().isForkEnabled());
    }

    @Test
    void 写了false就真的关掉() {
        var cfg = new AppConfig();
        cfg.setEnableFork(false);
        assertFalse(cfg.isForkEnabled());

        cfg.setEnableFork(true);
        assertTrue(cfg.isForkEnabled());
    }
}
