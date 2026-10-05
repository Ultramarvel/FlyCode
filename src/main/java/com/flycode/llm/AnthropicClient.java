package com.flycode.llm;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flycode.config.ProviderConfig;
import com.flycode.conversation.ConversationManager;
import com.flycode.conversation.Message;
import com.flycode.mcp.McpLoadingStrategy;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class AnthropicClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final com.anthropic.client.AnthropicClient sdkClient;
    private final String model;

    private final boolean thinking;
    private volatile String systemPrompt;
    private volatile int maxOutputTokens;

    public AnthropicClient(ProviderConfig cfg, String systemPrompt) {
        String apiKey = cfg.resolvedApiKey();
        if (apiKey.isEmpty()) {
            throw new LlmException.AuthenticationException(
                    "Anthropic API key not found. Set it in .flycode/config.yaml or via ANTHROPIC_API_KEY env var.");
        }
        this.sdkClient = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(cfg.getBaseUrl())
                .build();
        this.model = ModelResolver.resolve(cfg.getModel());
        this.thinking = cfg.isThinking();
        this.systemPrompt = systemPrompt;
        this.maxOutputTokens = cfg.resolvedMaxOutputTokens();

        // Layer 2 of context-window resolution: best-effort fetch from the
        // provider's models endpoint, cached back onto cfg so a later
        // cfg.resolvedContextWindow() can use it. Never blocks startup or
        // throws — any failure silently degrades to the built-in table.
        cfg.setFetchedContextWindow(fetchModelContextWindow());
    }

    /**
     * Fetch the model's context window from {@code GET {base_url}/v1/models/{model}}
     * (Anthropic protocol only), reading {@code ModelInfo.max_input_tokens}.
     *
     * <p>Best-effort: returns {@code 0} on any error (network, auth, unknown
     * model, missing field, timeout). Never throws — callers treat 0 as
     * "unavailable" and fall through to the next resolution layer.
     *
     * @return max input tokens (&gt; 0) on success, or {@code 0} on any failure
     */
    int fetchModelContextWindow() {
        try {
            var info = sdkClient.models().retrieve(
                    model,
                    com.anthropic.core.RequestOptions.builder()
                            .timeout(java.time.Duration.ofSeconds(5))
                            .build());
            return info.maxInputTokens()
                    .map(Long::intValue)
                    .filter(v -> v > 0)
                    .orElse(0);
        } catch (Exception | Error e) {
            // Swallow everything: this must never block or break startup.
            return 0;
        }
    }

    @Override
    public void setSystemPrompt(String prompt) {
        this.systemPrompt = prompt;
    }

    @Override
    public void setMaxOutputTokens(int tokens) {
        this.maxOutputTokens = tokens;
    }

    @Override
    public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools) {
        var queue = new LinkedBlockingQueue<StreamEvent>(64);

        Thread.startVirtualThread(() -> {
            try {
                doStream(conv, tools, queue);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                try { queue.put(new StreamEvent.Error(classifyError(e).getMessage())); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        });

        return queue;
    }

    private void doStream(ConversationManager conv, List<Map<String, Object>> tools,
                          BlockingQueue<StreamEvent> queue) throws Exception {

        // Anchor the prompt cache on the longest-stable prefix: system, then
        // tools, then the tail of the final user message. tool_result content
        // stays byte-stable past these breakpoints because the toolresult
        // budget finalizes each message at ingest and never rewrites history.
        var systemBlock = TextBlockParam.builder()
                .text(systemPrompt)
                .cacheControl(CacheControlEphemeral.builder().build())
                .build();
        // 发请求前补齐工具调用与结果的配对：中断、恢复会话、并发交错都可能留下
        // 悬空的 tool_use，缺配对会被 API 直接拒掉。
        var messageParams = buildMessages(com.flycode.conversation.ToolPairing.ensure(conv.getMessages()));
        markLastUserTailForCache(messageParams);
        var paramsBuilder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxOutputTokens)
                .system(MessageCreateParams.System.ofTextBlockParams(List.of(systemBlock)))
                .messages(messageParams);

        if (thinking) {
            if (ModelResolver.supportsAdaptiveThinking(model)) {
                paramsBuilder.thinking(ThinkingConfigAdaptive.builder().build());
            } else {
                paramsBuilder.thinking(ThinkingConfigEnabled.builder()
                        .budgetTokens(maxOutputTokens - 1)
                        .build());
            }
        }

        if (tools != null && !tools.isEmpty()) {
            // Tool schemas are stable across turns, so marking the last
            // tool effectively caches the entire tools block on the wire.
            //
            // 断点必须落在非延迟的工具上：带 defer_loading 的工具不允许同时带
            // cache_control，官方端点会直接拒掉整个请求。所以从尾部往前找第一个
            // 非延迟的工具，内建工具永远不延迟，总能找到落点。
            int cacheAt = -1;
            for (int i = tools.size() - 1; i >= 0; i--) {
                if (!Boolean.TRUE.equals(tools.get(i).get("defer_loading"))) {
                    cacheAt = i;
                    break;
                }
            }
            for (int i = 0; i < tools.size(); i++) {
                paramsBuilder.addTool(buildTool(tools.get(i), i == cacheAt));
            }
            // defer_loading 和 tool_reference 都在这个 beta 后面，只在真用到时才发，
            // 免得给不认识这个 header 的端点添麻烦
            if (needsToolSearchBeta(tools)) {
                paramsBuilder.putAdditionalHeader(
                        "anthropic-beta", McpLoadingStrategy.NATIVE_TOOL_SEARCH_BETA);
            }
        }

        String currentToolName = "";
        String currentToolId = "";
        var jsonAccum = new StringBuilder();
        var thinkingAccum = new StringBuilder();
        String thinkingSignature = "";
        boolean inThinking = false;
        int inputTokens = 0, outputTokens = 0;
        int cacheReadTokens = 0, cacheCreationTokens = 0;
        String stopReason = "end_turn";

        try (StreamResponse<RawMessageStreamEvent> streamResponse =
                     sdkClient.messages().createStreaming(paramsBuilder.build())) {

            var iterator = streamResponse.stream().iterator();
            while (iterator.hasNext()) {
                var event = iterator.next();
                if (event.isContentBlockStart()) {
                    var startEvent = event.asContentBlockStart();
                    var block = startEvent.contentBlock();
                    if (block.isThinking()) {
                        inThinking = true;
                        thinkingAccum.setLength(0);
                        thinkingSignature = "";
                    } else if (block.isToolUse()) {
                        var tu = block.asToolUse();
                        currentToolName = tu.name();
                        currentToolId = tu.id();
                        jsonAccum.setLength(0);
                        queue.put(new StreamEvent.ToolCallStart(currentToolId, currentToolName));
                    }
                } else if (event.isContentBlockDelta()) {
                    var delta = event.asContentBlockDelta().delta();
                    if (delta.isThinking()) {
                        String text = delta.asThinking().thinking();
                        thinkingAccum.append(text);
                        queue.put(new StreamEvent.ThinkingDelta(text));
                    } else if (delta.isSignature()) {
                        thinkingSignature = delta.asSignature().signature();
                    } else if (delta.isText()) {
                        queue.put(new StreamEvent.TextDelta(delta.asText().text()));
                    } else if (delta.isInputJson()) {
                        String partialJson = delta.asInputJson().partialJson();
                        jsonAccum.append(partialJson);
                        queue.put(new StreamEvent.ToolCallDelta(partialJson));
                    }
                } else if (event.isContentBlockStop()) {
                    if (inThinking) {
                        queue.put(new StreamEvent.ThinkingComplete(
                                thinkingAccum.toString(), thinkingSignature));
                        inThinking = false;
                    }
                    if (!currentToolName.isEmpty()) {
                        Map<String, Object> args;
                        try {
                            @SuppressWarnings("unchecked")
                            var parsed = MAPPER.readValue(jsonAccum.toString(), Map.class);
                            args = parsed;
                        } catch (Exception e) {
                            args = new HashMap<>();
                        }
                        queue.put(new StreamEvent.ToolCallComplete(
                                currentToolId, currentToolName, args));
                        currentToolName = "";
                        currentToolId = "";
                        jsonAccum.setLength(0);
                    }
                } else if (event.isMessageDelta()) {
                    var msgDelta = event.asMessageDelta();
                    var sr = msgDelta.delta().stopReason();
                    if (sr.isPresent()) {
                        stopReason = sr.get().asString();
                    }
                    var usage = msgDelta.usage();
                    outputTokens = (int) usage.outputTokens();

                    // Standard Anthropic only puts output_tokens in message_delta,
                    // but some compatible providers (e.g. MiniMax) also report
                    // input_tokens and cache fields here with the real values
                    // (message_start may carry 0). Override only when > 0 to
                    // avoid clobbering valid message_start data.
                    int deltaInput = deltaUsageLong(usage.inputTokens(), usage, "input_tokens");
                    int deltaCacheRead = deltaUsageLong(usage.cacheReadInputTokens(), usage, "cache_read_input_tokens");
                    int deltaCacheCreate = deltaUsageLong(usage.cacheCreationInputTokens(), usage, "cache_creation_input_tokens");
                    if (deltaInput > 0) {
                        inputTokens = deltaInput;
                    }
                    if (deltaCacheRead > 0) {
                        cacheReadTokens = deltaCacheRead;
                    }
                    if (deltaCacheCreate > 0) {
                        cacheCreationTokens = deltaCacheCreate;
                    }
                } else if (event.isMessageStart()) {
                    var msg = event.asMessageStart().message();
                    var usage = msg.usage();
                    inputTokens = (int) usage.inputTokens();
                    if (usage.cacheReadInputTokens().isPresent()) {
                        cacheReadTokens = usage.cacheReadInputTokens().get().intValue();
                    }
                    if (usage.cacheCreationInputTokens().isPresent()) {
                        cacheCreationTokens = usage.cacheCreationInputTokens().get().intValue();
                    }
                }
            }
        }

        queue.put(new StreamEvent.StreamEnd(
                stopReason != null ? stopReason : "end_turn", inputTokens, outputTokens,
                cacheReadTokens, cacheCreationTokens));
    }

    private List<MessageParam> buildMessages(List<Message> messages) {
        var result = new ArrayList<MessageParam>();
        for (var msg : messages) {
            boolean hasThinking = msg.getThinkingBlocks() != null && !msg.getThinkingBlocks().isEmpty();
            boolean hasToolUses = msg.getToolUses() != null && !msg.getToolUses().isEmpty();

            if ("assistant".equals(msg.getRole()) && (hasThinking || hasToolUses)) {
                var content = new ArrayList<ContentBlockParam>();
                if (hasThinking) {
                    for (var tb : msg.getThinkingBlocks()) {
                        content.add(ContentBlockParam.ofThinking(
                                ThinkingBlockParam.builder()
                                        .thinking(tb.thinking())
                                        .signature(tb.signature())
                                        .build()));
                    }
                }
                if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                    content.add(ContentBlockParam.ofText(
                            TextBlockParam.builder().text(msg.getContent()).build()));
                }
                if (hasToolUses) {
                    for (var tu : msg.getToolUses()) {
                        content.add(ContentBlockParam.ofToolUse(
                                ToolUseBlockParam.builder()
                                        .id(tu.toolUseId())
                                        .name(tu.toolName())
                                        .input(JsonValue.from(tu.arguments()))
                                        .build()));
                    }
                }
                if (content.isEmpty()) {
                    content.add(ContentBlockParam.ofText(
                            TextBlockParam.builder().text("").build()));
                }
                result.add(MessageParam.builder()
                        .role(MessageParam.Role.ASSISTANT)
                        .contentOfBlockParams(content)
                        .build());
            } else if (msg.getToolResults() != null && !msg.getToolResults().isEmpty()) {
                var content = new ArrayList<ContentBlockParam>();
                for (var tr : msg.getToolResults()) {
                    var trBuilder = ToolResultBlockParam.builder()
                            .toolUseId(tr.toolUseId())
                            .isError(tr.isError());
                    var blocks = toolResultContentBlocks(tr.contentBlocks());
                    if (blocks != null) {
                        trBuilder.contentOfBlocks(blocks);
                    } else {
                        trBuilder.content(tr.content());
                    }
                    content.add(ContentBlockParam.ofToolResult(trBuilder.build()));
                }
                result.add(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(content)
                        .build());
            } else {
                if (!result.isEmpty()) {
                    var prev = result.getLast();
                    if (prev.role().asString().equals(msg.getRole())) {
                        var merged = prev.toBuilder();
                        // SDK doesn't easily merge content, so we use addAssistant/addUser helpers
                        // For simplicity, just add a new message. The API handles consecutive same-role
                        // by requiring alternating roles, so we merge text.
                    }
                }
                var builder = MessageParam.builder()
                        .content(msg.getContent());
                if ("assistant".equals(msg.getRole())) {
                    builder.role(MessageParam.Role.ASSISTANT);
                } else {
                    builder.role(MessageParam.Role.USER);
                }
                result.add(builder.build());
            }
        }
        return mergeConsecutiveSameRole(result);
    }

    private List<MessageParam> mergeConsecutiveSameRole(List<MessageParam> messages) {
        if (messages.size() <= 1) return messages;
        var merged = new ArrayList<MessageParam>();
        merged.add(messages.getFirst());
        for (int i = 1; i < messages.size(); i++) {
            var prev = merged.getLast();
            var curr = messages.get(i);
            if (prev.role().equals(curr.role())) {
                // Both are simple text content — merge them
                var prevContent = prev.content();
                var currContent = curr.content();
                if (prevContent.isString() && currContent.isString()) {
                    merged.set(merged.size() - 1, MessageParam.builder()
                            .role(prev.role())
                            .content(prevContent.asString() + "\n\n" + currContent.asString())
                            .build());
                } else {
                    // One has block params — just append as-is, let API handle
                    merged.add(curr);
                }
            } else {
                merged.add(curr);
            }
        }
        return merged;
    }

    @SuppressWarnings("unchecked")
    /**
     * 这批工具里有没有带 defer_loading 的。
     *
     * <p>只在真用到时才发 beta header：不认识它的端点收到会直接拒请求，而
     * dispatch / eager 两条路压根不需要它。
     */
    public static boolean needsToolSearchBeta(List<Map<String, Object>> toolSchemas) {
        if (toolSchemas == null) return false;
        for (var schema : toolSchemas) {
            if (Boolean.TRUE.equals(schema.get("defer_loading"))) return true;
        }
        return false;
    }

    /**
     * 把工具结果里的结构化内容块转成 SDK 的块参数。
     *
     * <p>目前只处理 tool_reference：ToolSearch 在官方端点下靠它让服务端把 MCP 工具的
     * schema 展开进上下文。认不出来的类型整块丢掉——发一个服务端不认的块会让整个
     * 请求被拒，宁可退回纯文本。全部认不出时返回 null，调用方按 content 发文本。
     */
    private static List<ToolResultBlockParam.Content.Block> toolResultContentBlocks(
            List<Map<String, Object>> blocks) {
        if (blocks == null || blocks.isEmpty()) return null;
        var out = new ArrayList<ToolResultBlockParam.Content.Block>();
        for (var block : blocks) {
            if (!"tool_reference".equals(block.get("type"))) continue;
            if (!(block.get("tool_name") instanceof String toolName) || toolName.isEmpty()) continue;
            out.add(ToolResultBlockParam.Content.Block.ofToolReference(
                    ToolReferenceBlockParam.builder().toolName(toolName).build()));
        }
        return out.isEmpty() ? null : out;
    }

    private Tool buildTool(Map<String, Object> schema, boolean markCache) {
        var builder = Tool.builder()
                .name((String) schema.get("name"));
        if (schema.containsKey("description")) {
            builder.description((String) schema.get("description"));
        }
        var inputSchema = (Map<String, Object>) schema.getOrDefault("input_schema",
                Map.of("type", "object", "properties", Map.of()));
        builder.inputSchema(Tool.InputSchema.builder()
                .type(JsonValue.from(inputSchema.getOrDefault("type", "object")))
                .putAllAdditionalProperties(toJsonValueMap(inputSchema))
                .build());
        // 官方端点的原生延迟：工具留在 tools[] 里，服务端决定给不给模型看。
        // 数组的字节因此在整场会话里不变，缓存前缀不会断。
        if (Boolean.TRUE.equals(schema.get("defer_loading"))) {
            builder.deferLoading(true);
        }
        if (markCache) {
            builder.cacheControl(CacheControlEphemeral.builder().build());
        }
        return builder.build();
    }

    /**
     * Attach an ephemeral cache_control marker to the last content block of
     * the final user-role message in {@code messages}. Anthropic caches the
     * prefix up to (and including) this block; subsequent requests with a
     * byte-identical prefix hit the cache.
     *
     * <p>Mutates {@code messages} in place by swapping the trailing
     * MessageParam for a rebuilt one with cache_control attached — the
     * SDK's builder is immutable, so we can't edit in place at the field
     * level.
     */
    private void markLastUserTailForCache(List<MessageParam> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            var msg = messages.get(i);
            if (msg.role() != MessageParam.Role.USER) continue;
            // The user message's content is either a string or block list.
            // We need block form to attach cache_control, so up-convert if
            // it's a string.
            var content = msg.content();
            List<ContentBlockParam> blocks;
            if (content.string().isPresent()) {
                blocks = List.of(ContentBlockParam.ofText(
                        TextBlockParam.builder()
                                .text(content.string().get())
                                .cacheControl(CacheControlEphemeral.builder().build())
                                .build()));
            } else if (content.blockParams().isPresent()) {
                var orig = content.blockParams().get();
                if (orig.isEmpty()) return;
                blocks = new ArrayList<>(orig);
                var last = blocks.getLast();
                ContentBlockParam rebuilt;
                if (last.text().isPresent()) {
                    var t = last.text().get();
                    rebuilt = ContentBlockParam.ofText(t.toBuilder()
                            .cacheControl(CacheControlEphemeral.builder().build())
                            .build());
                } else if (last.toolResult().isPresent()) {
                    var tr = last.toolResult().get();
                    rebuilt = ContentBlockParam.ofToolResult(tr.toBuilder()
                            .cacheControl(CacheControlEphemeral.builder().build())
                            .build());
                } else {
                    return; // unsupported block type at tail — silently skip
                }
                blocks.set(blocks.size() - 1, rebuilt);
            } else {
                return;
            }
            messages.set(i, MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .contentOfBlockParams(blocks)
                    .build());
            return;
        }
    }

    private Map<String, JsonValue> toJsonValueMap(Map<String, Object> map) {
        var result = new LinkedHashMap<String, JsonValue>();
        for (var entry : map.entrySet()) {
            if ("type".equals(entry.getKey())) continue;
            result.put(entry.getKey(), JsonValue.from(entry.getValue()));
        }
        return result;
    }

    private LlmException classifyError(Exception e) {
        if (e instanceof LlmException le) return le;
        if (e instanceof com.anthropic.errors.UnauthorizedException ue) {
            return new LlmException.AuthenticationException("Invalid API key: " + ue.getMessage());
        }
        if (e instanceof com.anthropic.errors.RateLimitException) {
            return new LlmException.RateLimitException("Rate limited. Please wait.", "");
        }
        if (e instanceof com.anthropic.errors.BadRequestException bre) {
            String msg = bre.getMessage() != null ? bre.getMessage().toLowerCase() : "";
            if (msg.contains("prompt is too long") || msg.contains("too many tokens")) {
                return new LlmException.ContextTooLongException("Context too long: " + bre.getMessage());
            }
            return new LlmException("Bad request: " + bre.getMessage(), bre);
        }
        if (e instanceof com.anthropic.errors.AnthropicServiceException se) {
            if (se.statusCode() == 413) {
                return new LlmException.ContextTooLongException("Context too long: " + se.getMessage());
            }
            return new LlmException("API error (" + se.statusCode() + "): " + se.getMessage(), se);
        }
        if (e instanceof com.anthropic.errors.AnthropicIoException) {
            return new LlmException.NetworkException("Network error: " + e.getMessage(), e);
        }
        return new LlmException("Unexpected error: " + e.getMessage(), e);
    }

    /**
     * Extract a usage counter from a {@link MessageDeltaUsage}.
     *
     * <p>First tries the typed Optional accessor (works when the SDK version
     * maps the field). Falls back to {@code _additionalProperties()} for
     * Anthropic-compatible providers (e.g. MiniMax) that may include
     * non-standard fields the SDK doesn't map into the typed model.
     *
     * @param typed    the Optional from the typed accessor (e.g. {@code usage.inputTokens()})
     * @param usage    the delta usage object (for additionalProperties fallback)
     * @param jsonKey  the raw JSON key to look up in additionalProperties
     * @return the token count, or 0 if absent / unparseable
     */
    private static int deltaUsageLong(Optional<Long> typed,
                                      MessageDeltaUsage usage,
                                      String jsonKey) {
        if (typed.isPresent()) {
            return typed.get().intValue();
        }
        // Fallback: some providers put extra fields into additionalProperties
        var extra = usage._additionalProperties();
        if (extra != null && extra.containsKey(jsonKey)) {
            var val = extra.get(jsonKey);
            if (val != null) {
                Optional<Number> num = val.asNumber();
                if (num.isPresent()) {
                    return num.get().intValue();
                }
            }
        }
        return 0;
    }
}
