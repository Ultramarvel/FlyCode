package com.flycode.remote;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 内嵌的 Web UI 前端 HTML 页面。
 * Web UI 入口 HTML，存储在 resources/com/flycode/remote/index.html 中，启动时加载。
 */
public final class WebContent {

    private WebContent() {}

    /** 完整的 HTML 页面内容，包含 CSS + JavaScript */
    public static final String INDEX_HTML;

    static {
        try (InputStream is = WebContent.class.getResourceAsStream("index.html")) {
            if (is == null) {
                throw new RuntimeException("index.html resource not found");
            }
            INDEX_HTML = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load index.html", e);
        }
    }
}
