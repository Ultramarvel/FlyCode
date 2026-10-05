package com.flycode.config;

import java.util.List;
import java.util.Map;

public class McpServerConfig {

    private String name;
    private String command;
    private List<String> args;
    private String url;
    private Map<String, String> headers;
    private Map<String, String> env;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCommand() { return command; }

    public void setCommand(String command) { this.command = command; }

    public List<String> getArgs() { return args; }
    public void setArgs(List<String> args) { this.args = args; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public Map<String, String> getHeaders() { return headers; }

    public void setHeaders(Map<String, String> headers) { this.headers = headers; }

    public Map<String, String> getEnv() { return env; }
    public void setEnv(Map<String, String> env) { this.env = env; }
}
