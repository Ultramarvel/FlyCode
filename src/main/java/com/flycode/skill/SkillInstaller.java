package com.flycode.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

/**
 * 从 GitHub 下载并原子安装 skill 到本地目录。
 * <p>
 * 核心流程：解析 URL → 通过 GitHub Contents API 递归下载 → staging 临时目录 → rename 到最终位置。
 * 失败时 staging 目录被清理，不会留下残缺文件。
 * <p>
 * 安全限制：单文件最大 1 MiB，总大小最大 8 MiB，最多 64 个文件，最深 4 层目录。
 */
public final class SkillInstaller {

    // ── 安全限制常量 ──────────────────────────────────────────────────
    private static final int MAX_FILE_SIZE = 1 << 20;       // 1 MiB
    private static final long MAX_TOTAL_SIZE = 8L << 20;    // 8 MiB
    private static final int MAX_FILE_COUNT = 64;
    private static final int MAX_RECURSION_DEPTH = 4;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final HttpClient httpClient;
    private final String apiBase;

    public SkillInstaller() {
        this("https://api.github.com");
    }

    /** 支持测试时替换 API 地址。 */
    public SkillInstaller(String apiBase) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.apiBase = apiBase;
    }

    // ── URL 解析 ──────────────────────────────────────────────────────

    /**
     * 将用户提供的 URL 解析为 {@link SkillSource}。
     * <p>
     * 支持三种格式：
     * <ol>
     *   <li>{@code https://www.skills.sh/<owner>/<repo>/<skill-name>}</li>
     *   <li>{@code https://github.com/<owner>/<repo>/tree/<ref>/<subpath>}</li>
     *   <li>{@code https://raw.githubusercontent.com/<owner>/<repo>/<ref>/<subpath>/SKILL.md}</li>
     * </ol>
     */
    public static SkillSource parseSkillURL(String raw) {
        raw = raw.strip();
        URI uri = URI.create(raw);
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) {
            throw new IllegalArgumentException("only http(s) URLs are supported");
        }

        String host = uri.getHost();
        // 去掉首尾 / 后按 / 拆分
        String path = uri.getPath();
        if (path == null) path = "";
        String trimmed = path.replaceAll("^/+|/+$", "");
        String[] parts = trimmed.isEmpty() ? new String[0] : trimmed.split("/");

        return switch (host) {
            case "www.skills.sh", "skills.sh" -> {
                // /<owner>/<repo>/<skill-name>
                if (parts.length < 3) {
                    throw new IllegalArgumentException(
                            "skills.sh URL must be /<owner>/<repo>/<skill-name>");
                }
                String subpath = "skills/" + String.join("/", java.util.Arrays.copyOfRange(parts, 2, parts.length));
                yield new SkillSource(parts[0], parts[1], "main", subpath,
                        parts[parts.length - 1], raw);
            }
            case "github.com" -> {
                // /<owner>/<repo>/tree/<ref>/<...subpath>
                if (parts.length < 5 || !"tree".equals(parts[2])) {
                    throw new IllegalArgumentException(
                            "github.com URL must be /<owner>/<repo>/tree/<ref>/<subpath>");
                }
                String sub = String.join("/", java.util.Arrays.copyOfRange(parts, 4, parts.length));
                yield new SkillSource(parts[0], parts[1], parts[3], sub,
                        parts[parts.length - 1], raw);
            }
            case "raw.githubusercontent.com" -> {
                // /<owner>/<repo>/<ref>/<...subpath>/SKILL.md
                if (parts.length < 4) {
                    throw new IllegalArgumentException("raw.githubusercontent.com URL too short");
                }
                String[] subParts = java.util.Arrays.copyOfRange(parts, 3, parts.length);
                // 去掉尾部文件名（含 . 的视为文件）
                if (subParts.length > 0 && subParts[subParts.length - 1].contains(".")) {
                    subParts = java.util.Arrays.copyOf(subParts, subParts.length - 1);
                }
                if (subParts.length == 0) {
                    throw new IllegalArgumentException("raw URL missing skill subpath");
                }
                yield new SkillSource(parts[0], parts[1], parts[2],
                        String.join("/", subParts),
                        subParts[subParts.length - 1], raw);
            }
            default -> throw new IllegalArgumentException(
                    "unsupported host \"%s\" (try skills.sh or github.com)".formatted(host));
        };
    }

    // ── 安装主流程 ────────────────────────────────────────────────────

    /**
     * 将 src 描述的 skill 下载并安装到 {@code installRoot/<name>/}。
     * <p>
     * 写入过程是原子的：先 stage 到同级临时目录，成功后 rename。
     */
    public InstallReport install(SkillSource src, String installRoot) throws IOException {
        if (src == null) {
            throw new IllegalArgumentException("nil source");
        }
        validateSkillName(src.name());

        Path root = Path.of(installRoot);
        Files.createDirectories(root);

        // staging 目录与最终目录同级，保证 rename 在同一文件系统内
        Path staging = Files.createTempDirectory(root, ".install-" + src.name() + "-");
        try {
            // 计数器用可变 holder（record 不可变，这里用 long 数组绕开）
            int[] fileCount = {0};
            long[] totalBytes = {0L};

            walkAndDownload(src, src.subpath(), staging, fileCount, totalBytes, 0);

            if (!hasSkillManifest(staging)) {
                throw new IOException("downloaded tree missing SKILL.md or skill.yaml — not a skill?");
            }

            // 覆盖已有安装
            Path finalDir = root.resolve(src.name());
            if (Files.exists(finalDir)) {
                deleteRecursively(finalDir);
            }
            Files.move(staging, finalDir);

            return new InstallReport(src.name(), finalDir.toString(), fileCount[0], totalBytes[0]);

        } catch (Exception e) {
            // 失败时清理 staging
            deleteRecursively(staging);
            if (e instanceof IOException io) throw io;
            throw new IOException(e);
        }
    }

    /**
     * 返回用户全局 skill 安装目录 ~/.flycode/skills，不存在则创建。
     */
    public static String userSkillsRoot() throws IOException {
        Path root = Path.of(System.getProperty("user.home"), ".flycode", "skills");
        Files.createDirectories(root);
        return root.toString();
    }

    // ── GitHub Contents API 交互 ──────────────────────────────────────

    /**
     * GitHub Contents API 返回的单条条目（只保留需要的字段）。
     */
    private record ContentEntry(
            String name,
            String path,
            String type,        // "file" | "dir" | "symlink" | "submodule"
            String download_url,
            String content,
            String encoding,
            int size
    ) {}

    /**
     * 列出 GitHub 仓库指定路径下的条目。
     * 目录返回数组，单文件返回包装成单元素列表。
     */
    private List<ContentEntry> listContents(SkillSource src, String subpath) throws IOException {
        String endpoint = "%s/repos/%s/%s/contents/%s?ref=%s".formatted(
                apiBase, src.owner(), src.repo(), subpath,
                URLEncoder.encode(src.ref(), StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "flycode-install-skill")
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();

        HttpResponse<String> resp;
        try {
            resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted", e);
        }

        if (resp.statusCode() == 403) {
            String body = resp.body();
            if (body != null && body.length() > 512) body = body.substring(0, 512);
            throw new IOException("github API forbidden (rate-limited?): " + (body != null ? body.strip() : ""));
        }
        if (resp.statusCode() != 200) {
            throw new IOException("github API returned %d for %s".formatted(resp.statusCode(), endpoint));
        }

        String body = resp.body();
        if (body == null || body.isBlank()) {
            throw new IOException("github returned empty body");
        }

        // 目录返回 JSON 数组，单文件返回 JSON 对象
        String trimmedBody = body.strip();
        if (trimmedBody.startsWith("[")) {
            return MAPPER.readValue(body, new TypeReference<>() {});
        }
        ContentEntry single = MAPPER.readValue(body, ContentEntry.class);
        return List.of(single);
    }

    /**
     * 下载单个文件的内容。
     * 优先使用内联 base64（省一次请求），回退到 download_url。
     */
    private byte[] fetchBlob(ContentEntry entry) throws IOException {
        if (entry.size() > MAX_FILE_SIZE) {
            throw new IOException("file %s too large: %d bytes (max %d)".formatted(
                    entry.path(), entry.size(), MAX_FILE_SIZE));
        }

        // 内联 base64（小文件时 API 直接返回内容）
        if ("base64".equals(entry.encoding()) && entry.content() != null && !entry.content().isEmpty()) {
            String clean = entry.content().replace("\n", "");
            return Base64.getDecoder().decode(clean);
        }

        // 回退到 download_url
        if (entry.download_url() == null || entry.download_url().isEmpty()) {
            throw new IOException("no download_url for " + entry.path());
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(entry.download_url()))
                .header("User-Agent", "flycode-install-skill")
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();

        HttpResponse<byte[]> resp;
        try {
            resp = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted", e);
        }

        if (resp.statusCode() != 200) {
            throw new IOException("download %s: status %d".formatted(entry.download_url(), resp.statusCode()));
        }
        return resp.body();
    }

    // ── 递归下载 ──────────────────────────────────────────────────────

    /**
     * 递归遍历 GitHub 目录树，下载所有文件到 localDir，
     * 同时检查安全限制（文件数、总大小、深度）。
     */
    private void walkAndDownload(
            SkillSource src, String subpath, Path localDir,
            int[] fileCount, long[] totalBytes, int depth) throws IOException {

        if (depth > MAX_RECURSION_DEPTH) {
            throw new IOException("install tree too deep (>%d levels)".formatted(MAX_RECURSION_DEPTH));
        }

        List<ContentEntry> entries = listContents(src, subpath);
        for (ContentEntry entry : entries) {
            if (fileCount[0] >= MAX_FILE_COUNT) {
                throw new IOException("install file count limit (%d) reached".formatted(MAX_FILE_COUNT));
            }

            // 防止路径穿越
            if (entry.name().contains("..") || entry.name().contains("/") || entry.name().contains("\\")) {
                throw new IOException("suspicious entry name: \"%s\"".formatted(entry.name()));
            }

            Path target = localDir.resolve(entry.name());

            switch (entry.type()) {
                case "file" -> {
                    byte[] data = fetchBlob(entry);
                    if (totalBytes[0] + data.length > MAX_TOTAL_SIZE) {
                        throw new IOException("install total size limit (%d bytes) reached".formatted(MAX_TOTAL_SIZE));
                    }
                    Files.write(target, data);
                    fileCount[0]++;
                    totalBytes[0] += data.length;
                }
                case "dir" -> {
                    Files.createDirectories(target);
                    walkAndDownload(src, entry.path(), target, fileCount, totalBytes, depth + 1);
                }
                default -> {
                    // symlink / submodule 直接跳过
                }
            }
        }
    }

    // ── 校验辅助 ──────────────────────────────────────────────────────

    /**
     * 检查目录下是否有 SKILL.md 或 skill.yaml（合法 skill 的最低要求）。
     */
    private static boolean hasSkillManifest(Path dir) {
        return Files.isRegularFile(dir.resolve("SKILL.md"))
                || Files.isRegularFile(dir.resolve("skill.yaml"));
    }

    /**
     * 校验 skill 名称：只允许小写字母、数字、连字符、下划线，不能以 . 开头。
     */
    static void validateSkillName(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("empty skill name");
        }
        if (name.startsWith(".")) {
            throw new IllegalArgumentException("skill name cannot start with '.'");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_') {
                continue;
            }
            throw new IllegalArgumentException(
                    "skill name \"%s\" contains invalid char '%c' (use a-z 0-9 - _)".formatted(name, c));
        }
    }

    /**
     * 递归删除目录及其所有内容。
     */
    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
