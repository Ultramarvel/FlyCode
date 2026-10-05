# FlyCode

FlyCode 是一款基于 Java 构建的智能编程 Agent，能够在终端中理解开发需求、分析项目代码，并自主完成文件编辑、命令执行、代码检索与任务规划。它支持多种大模型、MCP 工具、技能扩展、Git Worktree 和多智能体协作，旨在将复杂的软件开发任务转化为高效、可控且可扩展的自动化工作流。


![Java](https://img.shields.io/badge/Java-21-ff7f2a.svg)
![Gradle](https://img.shields.io/badge/Gradle-8.x-02303a.svg)
![MCP](https://img.shields.io/badge/MCP-1.1.3-5c6ac4.svg)
![Terminal](https://img.shields.io/badge/UI-Terminal-222222.svg)

## 功能概览

- 智能编程：理解自然语言需求，读取、搜索、编辑项目文件并执行开发命令
- 终端交互：提供流式输出、Markdown 渲染、斜杠命令和交互式权限确认
- 模型接入：支持 Anthropic、OpenAI 和 OpenAI 兼容协议，可配置多个模型服务商
- 工具扩展：支持 MCP 服务、工具动态检索、Hooks 和自定义 Skills
- 上下文管理：支持会话保存与恢复、自动压缩、长期记忆和代码回退
- 任务编排：支持计划模式、任务列表、子 Agent、多智能体团队和共享任务板
- 隔离开发：支持 Git Worktree，并可在 Linux 和 macOS 上启用系统级命令沙箱
- 远程控制：内置 HTTP 与 WebSocket 服务，可通过浏览器使用 Remote UI
- 自动化调用：支持非交互式 Prompt 和流式 JSON 输出，便于集成到脚本或流水线

## 技术栈

- 语言与构建：Java 21、Gradle Kotlin DSL、Shadow JAR
- 终端界面：JLine、Mordant、内部 Tea 风格状态模型
- 模型 SDK：Anthropic Java SDK、OpenAI Java SDK
- Agent 扩展：Model Context Protocol Java SDK
- 远程服务：Javalin、WebSocket
- 配置与数据：SnakeYAML、Jackson
- 测试：JUnit 5

## 仓库结构

```text
.
├── src/main/java/com/flycode/
│   ├── agent/                  # Agent 执行与流式事件
│   ├── command/                # 终端斜杠命令
│   ├── compact/                # 上下文压缩与恢复
│   ├── config/                 # YAML 配置加载
│   ├── llm/                    # 模型客户端和路由
│   ├── mcp/                    # MCP 服务管理
│   ├── memory/                 # 指令、记忆与召回
│   ├── permission/             # 工具权限控制
│   ├── remote/                 # HTTP / WebSocket 远程模式
│   ├── skill/                  # Skill 发现、加载与安装
│   ├── subagent/               # 子 Agent 调度
│   ├── teams/                  # 多智能体团队协作
│   ├── tool/                   # 内置工具及工具注册表
│   ├── tui/                    # 终端交互界面
│   ├── worktree/               # Git Worktree 隔离工作区
│   └── FlyCode.java            # 应用入口
├── src/main/resources/         # Remote UI 静态资源
├── src/test/                   # 单元测试
├── build.gradle.kts            # 构建与依赖配置
└── settings.gradle.kts         # Gradle 项目名称
```

运行后，FlyCode 会在项目的 `.flycode/` 目录保存项目级配置、会话、记忆、Skills 和文件历史。用户级配置及数据位于 `~/.flycode/`。

## 启动前准备

最低要求：

- JDK 21
- Git
- 可用的大模型 API

可选依赖：

- Node.js 与 `npx`：运行部分 MCP 服务时需要
- `tmux` 或 iTerm2：使用独立窗格运行团队成员时需要
- Bubblewrap：在 Linux 上启用系统级沙箱时需要

确认 Java 版本：

```bash
java -version
```

输出中的主版本应为 `21` 或更高。

## 配置模型

### 1. 创建配置目录

macOS / Linux：

```bash
mkdir -p .flycode
```

Windows PowerShell：

```powershell
New-Item -ItemType Directory -Force .flycode
```

### 2. 创建配置文件

新建 `.flycode/config.yaml`：

```yaml
providers:
  - name: primary
    protocol: anthropic
    base_url: https://api.anthropic.com
    model: your-model-name

permission_mode: default
enable_coordinator_mode: false
enable_fork: true
```

`protocol` 支持：

- `anthropic`
- `openai`
- `openai-compat`

### 3. 设置 API Key

建议通过环境变量提供密钥，避免把密钥提交到仓库。

macOS / Linux：

```bash
export ANTHROPIC_API_KEY=your-api-key
# 使用 OpenAI 或 OpenAI 兼容协议时：
export OPENAI_API_KEY=your-api-key
```

Windows PowerShell：

```powershell
$env:ANTHROPIC_API_KEY = "your-api-key"
# 使用 OpenAI 或 OpenAI 兼容协议时：
$env:OPENAI_API_KEY = "your-api-key"
```

FlyCode 会依次读取用户级 `~/.flycode/config.yaml`、项目级 `.flycode/config.yaml` 和项目级 `.flycode/config.local.yaml`，后读取的配置会覆盖前面的配置。也可以通过 `FLYCODE_CONFIG` 指定配置文件。

## 方式一：直接运行

克隆仓库：

```bash
git clone https://github.com/Ultramarvel/FlyCode.git
cd FlyCode
```

macOS / Linux：

```bash
./gradlew run
```

Windows：

```powershell
.\gradlew.bat run
```

启动后选择模型，然后直接输入开发任务。输入 `/help` 可以查看所有内置命令。

## 方式二：构建可执行 JAR

macOS / Linux：

```bash
./gradlew shadowJar
java -jar build/libs/flycode.jar
```

Windows：

```powershell
.\gradlew.bat shadowJar
java -jar build\libs\flycode.jar
```

## 非交互式调用

通过 `-p` 直接执行单次任务：

```bash
java -jar build/libs/flycode.jar -p "分析这个项目的入口和核心模块"
```

需要机器可读的流式事件时：

```bash
java -jar build/libs/flycode.jar -p "检查当前代码改动" --output-format stream-json
```

也可以把配置文件路径作为位置参数传入：

```bash
java -jar build/libs/flycode.jar /path/to/config.yaml
```

## Remote UI

启动默认的远程服务：

```bash
java -jar build/libs/flycode.jar --remote
```

浏览器访问：[http://localhost:18888](http://localhost:18888)

指定端口：

```bash
java -jar build/libs/flycode.jar --remote=:9000
```

Remote 模式通过 HTTP 提供页面，并通过 WebSocket 同步对话、工具调用、权限请求和 Agent 状态。

## 常用命令

| 命令 | 作用 |
| --- | --- |
| `/help` | 查看可用命令 |
| `/status` | 查看当前模型、会话和运行状态 |
| `/mcp` | 查看 MCP 服务连接状态 |
| `/plan` | 进入只读计划模式 |
| `/compact` | 压缩当前会话上下文 |
| `/session` | 管理当前会话 |
| `/resume` | 恢复历史会话 |
| `/rewind` | 恢复代码或对话检查点 |
| `/memory` | 管理自动记忆 |
| `/skills` | 查看或重新加载 Skills |
| `/review` | 审查当前代码改动 |
| `/sandbox` | 查看或调整命令沙箱 |
| `/clear` | 清空当前对话 |

## MCP 配置

在 `.flycode/config.yaml` 中添加 MCP 服务：

```yaml
mcp_servers:
  - name: context7
    command: npx
    args: ["-y", "@upstash/context7-mcp"]
```

也可以连接 Streamable HTTP 或 SSE 服务：

```yaml
mcp_servers:
  - name: remote-tools
    url: https://example.com/mcp
    transport: http
    headers:
      Authorization: "Bearer ${MCP_TOKEN}"
```

## Skills 与项目指令

Skills 可以放在以下目录：

- 用户级：`~/.flycode/skills/<skill-name>/SKILL.md`
- 项目级：`.flycode/skills/<skill-name>/SKILL.md`

FlyCode 会读取 `AGENTS.md`、`FLYCODE.md`、`.flycode/FLYCODE.md` 和 `FLYCODE.local.md` 作为项目指令。项目指令可以使用 `@path/to/file` 引入其他文件。

## 关键配置

主要配置文件是 `.flycode/config.yaml`。

重点配置项：

- `providers`：模型服务商、协议、地址、模型和上下文参数
- `permission_mode`：工具权限模式
- `mcp_servers`：MCP 服务列表
- `hooks`：生命周期 Hooks
- `sandbox`：系统级命令沙箱
- `enable_coordinator_mode`：是否让团队 Lead 只负责协调任务
- `enable_fork`：是否允许子 Agent 使用 Fork 模式

Provider 还可以设置：

- `thinking`：启用模型思考模式
- `context_window`：手动指定上下文窗口
- `max_output_tokens`：指定最大输出 Token 数

## 二次开发入口

第一次接手代码，建议按这个顺序阅读：

1. [FlyCode.java](src/main/java/com/flycode/FlyCode.java)
2. [FlyCodeModel.java](src/main/java/com/flycode/tui/FlyCodeModel.java)
3. [Agent.java](src/main/java/com/flycode/agent/Agent.java)
4. [ToolRegistry.java](src/main/java/com/flycode/tool/ToolRegistry.java)
5. [PromptBuilder.java](src/main/java/com/flycode/prompt/PromptBuilder.java)
6. [ConfigLoader.java](src/main/java/com/flycode/config/ConfigLoader.java)
7. [RemoteServer.java](src/main/java/com/flycode/remote/RemoteServer.java)

模块分工：

- `agent`：模型调用循环、流式事件和工具结果回传
- `tool`：文件、命令、检索、MCP 等工具能力
- `tui`：终端状态、输入、渲染和权限交互
- `memory` / `compact`：长期记忆与上下文控制
- `subagent` / `teams`：子任务并发与多智能体协作
- `worktree`：隔离开发目录的创建、切换和收尾
- `remote`：浏览器控制界面和 WebSocket 协议

## 测试

macOS / Linux：

```bash
./gradlew test
```

Windows：

```powershell
.\gradlew.bat test
```

构建完整可执行包：

```bash
./gradlew clean shadowJar
```

## 常见问题

### 1. 提示找不到配置文件

确认当前项目存在 `.flycode/config.yaml`，或者已经通过 `FLYCODE_CONFIG` 指定配置路径。

### 2. 模型请求返回未授权

检查当前协议对应的环境变量：Anthropic 协议使用 `ANTHROPIC_API_KEY`，OpenAI 和 OpenAI 兼容协议使用 `OPENAI_API_KEY`。

### 3. MCP 服务无法启动

先确认对应命令已经安装并可在终端直接运行。使用 `npx` 的 MCP 服务需要本机安装 Node.js。

### 4. 团队成员没有打开独立终端

独立窗格模式依赖 `tmux` 或 iTerm2。缺少这些工具时，可以使用进程内团队模式。

### 5. Remote UI 无法访问

默认端口是 `18888`。检查端口是否被占用，或使用 `--remote=:9000` 更换端口。
