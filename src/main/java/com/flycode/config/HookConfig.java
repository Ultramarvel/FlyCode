package com.flycode.config;

import java.util.Map;

/**
 * YAML 配置中单条 hook 的映射类。
 * 字段与 config.yaml 中 hooks 列表的每一项一一对应。
 */
public class HookConfig {

    private String id;
    private String event;
    private String condition;
    private String type;
    private String command;
    private String message;
    private boolean reject;


    /** 是否只执行一次（同一个 session 内按 id 去重） */
    private boolean once;

    /** 异步执行：不阻塞主流程 */
    private boolean async;

    /**
     * action 失败时的行为：
     *   "fail"   — 传播错误（阻塞 hook 默认行为）
     *   "ignore" — 记录日志并继续
     *   "reject" — 将失败视为 reject（仅 pre_tool_use 有效）
     */
    private String onError;

    /** HTTP action 的 URL */
    private String url;

    /** HTTP action 的方法（GET/POST/PUT 等，默认 POST） */
    private String method;

    /** HTTP action 的请求头 */
    private Map<String, String> headers;

    /** HTTP action 的请求体 */
    private String body;

    /** 命令/HTTP 请求的超时时间（秒），0 或不设表示使用默认值 */
    private int timeout;

    // ---- getters & setters ----

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getEvent() { return event; }
    public void setEvent(String event) { this.event = event; }

    public String getCondition() { return condition; }
    public void setCondition(String condition) { this.condition = condition; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public boolean isReject() { return reject; }
    public void setReject(boolean reject) { this.reject = reject; }

    public boolean isOnce() { return once; }
    public void setOnce(boolean once) { this.once = once; }

    public boolean isAsync() { return async; }
    public void setAsync(boolean async) { this.async = async; }

    public String getOnError() { return onError; }
    public void setOnError(String onError) { this.onError = onError; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public int getTimeout() { return timeout; }
    public void setTimeout(int timeout) { this.timeout = timeout; }
}
