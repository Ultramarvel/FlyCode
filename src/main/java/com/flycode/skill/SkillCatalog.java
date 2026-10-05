package com.flycode.skill;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.stream.Stream;

/**
 * Manages skill discovery, loading, and context generation.
 * <p>
 * Phase-1 loading reads only frontmatter (fast startup); {@link #getFull}
 * triggers a phase-2 re-read of the body on each call (hot reload).
 * <p>
 * Three-tier loading via {@link #loadCatalog}: builtins → user global
 * ({@code ~/.flycode/skills/}) → project ({@code .flycode/skills/}),
 * with later tiers overriding earlier ones by name.
 */
public class SkillCatalog {

    // ── Data types ──────────────────────────────────────────────────────

    public record SkillMeta(
            String name,
            String description,
            String whenToUse,
            List<String> tags,
            String mode,
            String model,
            String forkContext
    ) {}

    public record Skill(SkillMeta meta, String promptBody, Path sourceDir, boolean bodyLoaded) {
        public Skill withBody(String newBody) {
            return new Skill(meta, newBody, sourceDir, true);
        }
    }

    // ── State ───────────────────────────────────────────────────────────

    private final Map<String, Skill> skills = new LinkedHashMap<>();
    private final Map<String, String> sources = new LinkedHashMap<>();
    private final Map<String, FileTime> dirModTimes = new LinkedHashMap<>();

    private String workDir;

    // ── Public API ──────────────────────────────────────────────────────

    public String getWorkDir() {
        return workDir;
    }

    public void register(Skill skill, String source) {
        skills.put(skill.meta().name(), skill);
        sources.put(skill.meta().name(), source);
    }

    public void register(Skill skill) {
        register(skill, "");
    }

    public Map<String, Skill> getSkills() {
        return Collections.unmodifiableMap(skills);
    }

    public Optional<Skill> get(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    /**
     * Returns the skill with its body loaded. For disk-backed skills the
     * body is re-read on every call (hot reload). On read failure the
     * previously-cached body is preserved.
     */
    public Optional<Skill> getFull(String name) {
        Skill skill = skills.get(name);
        if (skill == null) {
            return Optional.empty();
        }
        if (skill.sourceDir() == null) {
            return Optional.of(skill);
        }
        try {
            Skill reloaded = loadSkill(skill.sourceDir());
            if (reloaded != null) {
                skills.put(name, reloaded);
                return Optional.of(reloaded);
            }
        } catch (IOException ignored) {
            // Keep the previously-cached body
        }
        return Optional.of(skill);
    }

    public List<SkillMeta> list() {
        return skills.values().stream().map(Skill::meta).toList();
    }

    public String source(String name) {
        return sources.getOrDefault(name, "");
    }

    /**
     * 生成系统提示词里的 Skill 清单：只列名字和一句话描述，完整 SOP 等模型调
     * LoadSkill 时再取。目录为空时返回空串，调用方据此跳过这一段。
     */
    public String buildSection(String workDir) {
        var metas = list();
        if (metas.isEmpty()) return "";
        var skillsDir = Path.of(workDir, ".flycode", "skills");
        var sb = new StringBuilder();
        sb.append("## Available Skills\n\n");
        sb.append("Skills are installed at: ").append(skillsDir).append("\n");
        sb.append("When creating new skills, always place them under this directory as <skill-name>/SKILL.md.\n\n");
        sb.append("Only Skill names and one-line descriptions are listed below. To activate a Skill on demand call the LoadSkill tool with {name: \"<skill-name>\"}. After activation the Skill's full SOP gets pinned to the environment context, and any tools the Skill declares get registered. Users can also invoke a Skill directly with /<name>.\n\n");
        sb.append("If the user pastes a Skill URL (skills.sh, github.com tree URL, or raw SKILL.md URL) and asks to install / add / get it, call the InstallSkill tool with {url: \"<url>\"} — the new Skill becomes available immediately afterwards.\n\n");
        for (var meta : metas) {
            var desc = meta.description();
            if (desc.length() > 200) desc = desc.substring(0, 200) + "…";
            sb.append("- /").append(meta.name()).append(": ").append(desc).append("\n");
        }
        return sb.toString();
    }

    // ── Three-tier catalog loading ─────────────────────────────────────

    /**
     * Builds a catalog by merging three tiers, with later sources
     * overriding earlier ones by name (project wins over user wins over
     * builtin). Phase-1: only frontmatter is read; bodies stay empty
     * until {@link #getFull} is called.
     */
    public static SkillCatalog loadCatalog(String workDir) {
        SkillCatalog c = new SkillCatalog();
        c.workDir = workDir;

        // Tier 1: 从 resources/builtins/ 加载嵌入的内置 skill（优先级最低）
        for (var skill : BuiltinSkills.load()) {
            c.register(skill, "builtin");
        }

        // Tier 2: user global
        String home = System.getProperty("user.home");
        if (home != null) {
            c.loadTier(Path.of(home, ".flycode", "skills"), "user");
        }

        // Tier 3: project
        c.loadTier(Path.of(workDir, ".flycode", "skills"), "project");

        c.snapshotDirModTimes();
        return c;
    }

    public void reload(String workDir) {
        SkillCatalog fresh = loadCatalog(workDir);
        this.skills.clear();
        this.skills.putAll(fresh.skills);
        this.sources.clear();
        this.sources.putAll(fresh.sources);
        this.workDir = fresh.workDir;
        this.dirModTimes.clear();
        this.dirModTimes.putAll(fresh.dirModTimes);
    }

    /**
     * 检查 skill 目录的 modtime 是否自上次加载以来发生了变化。
     * modtime 变化表示有新增或删除的 skill（已有 skill 的文件编辑
     * 由 getFull 的按需重读处理）。
     */
    public boolean needsReload() {
        for (var entry : dirModTimes.entrySet()) {
            Path dir = Path.of(entry.getKey());
            FileTime recorded = entry.getValue();
            try {
                FileTime current = Files.getLastModifiedTime(dir);
                if (!current.equals(recorded)) {
                    return true;
                }
            } catch (IOException e) {
                if (recorded != null) {
                    return true;
                }
            }
        }
        for (String dirPath : skillDirPaths(workDir)) {
            if (!dirModTimes.containsKey(dirPath)) {
                try {
                    Files.getLastModifiedTime(Path.of(dirPath));
                    return true;
                } catch (IOException ignored) {
                }
            }
        }
        return false;
    }

    private void snapshotDirModTimes() {
        dirModTimes.clear();
        for (String dirPath : skillDirPaths(workDir)) {
            try {
                dirModTimes.put(dirPath, Files.getLastModifiedTime(Path.of(dirPath)));
            } catch (IOException e) {
                dirModTimes.put(dirPath, null);
            }
        }
    }

    private static List<String> skillDirPaths(String workDir) {
        List<String> dirs = new ArrayList<>();
        String home = System.getProperty("user.home");
        if (home != null) {
            dirs.add(Path.of(home, ".flycode", "skills").toString());
        }
        if (workDir != null) {
            dirs.add(Path.of(workDir, ".flycode", "skills").toString());
        }
        return dirs;
    }

    /**
     * Walk {@code dir}; each immediate subdirectory is treated as a skill.
     * Missing or inaccessible directories are silently ignored.
     */
    public void loadFromDirectory(Path dir) {
        loadTier(dir, dir.toString());
    }

    private void loadTier(Path dir, String source) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(Files::isDirectory).forEach(skillDir -> {
                try {
                    Skill skill = loadSkill(skillDir);
                    if (skill != null) {
                        register(skill, source);
                    }
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    // ── Loading internals ───────────────────────────────────────────────

    private static Skill loadSkill(Path dir) throws IOException {
        // Strategy 1: skill.yaml + prompt.md
        Path metaPath = dir.resolve("skill.yaml");
        if (Files.isRegularFile(metaPath)) {
            return loadFromYamlAndPrompt(dir, metaPath);
        }

        // Strategy 2: SKILL.md with optional YAML front-matter
        Path mdPath = dir.resolve("SKILL.md");
        if (Files.isRegularFile(mdPath)) {
            String content = Files.readString(mdPath);
            return parseSkillMD(dir, content);
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private static Skill loadFromYamlAndPrompt(Path dir, Path metaPath) throws IOException {
        String yamlText = Files.readString(metaPath);
        Yaml yaml = new Yaml();
        Map<String, Object> map = yaml.load(yamlText);
        if (map == null) {
            map = Map.of();
        }

        SkillMeta meta = metaFromMap(map, dir);

        String promptBody = "";
        Path promptPath = dir.resolve("prompt.md");
        if (Files.isRegularFile(promptPath)) {
            promptBody = Files.readString(promptPath);
        }

        return new Skill(meta, promptBody, dir, true);
    }

    @SuppressWarnings("unchecked")
    private static Skill parseSkillMD(Path dir, String content) {
        String body = content;
        Map<String, Object> frontMatter = Map.of();

        String trimmed = content.stripLeading();
        if (trimmed.startsWith("---")) {
            int firstSep = content.indexOf("---");
            int secondSep = content.indexOf("---", firstSep + 3);
            if (secondSep >= 0) {
                String yamlBlock = content.substring(firstSep + 3, secondSep);
                body = content.substring(secondSep + 3).strip();
                try {
                    Yaml yaml = new Yaml();
                    Map<String, Object> parsed = yaml.load(yamlBlock);
                    if (parsed != null) {
                        frontMatter = parsed;
                    }
                } catch (Exception ignored) {
                }
            }
        }

        SkillMeta meta = metaFromMap(frontMatter, dir);

        // Auto-generate description from first non-empty, non-heading line if absent
        String description = meta.description();
        if (description == null || description.isBlank()) {
            for (String line : body.split("\n")) {
                String stripped = line.strip();
                if (!stripped.isEmpty() && !stripped.startsWith("#")) {
                    description = stripped;
                    break;
                }
            }
            meta = new SkillMeta(meta.name(), description != null ? description : "",
                    meta.whenToUse(), meta.tags(),
                    meta.mode(), meta.model(), meta.forkContext());
        }

        return new Skill(meta, body, dir, true);
    }

    @SuppressWarnings("unchecked")
    private static SkillMeta metaFromMap(Map<String, Object> map, Path dir) {
        String name = stringVal(map, "name");
        if (name == null || name.isBlank()) {
            name = dir.getFileName().toString().toLowerCase().replace(' ', '-');
        }
        String description = stringVal(map, "description");
        String whenToUse = stringVal(map, "when_to_use");

        List<String> tags = List.of();
        Object rawTags = map.get("tags");
        if (rawTags instanceof List<?> list) {
            tags = list.stream().map(Object::toString).toList();
        }

        String mode = stringVal(map, "mode");
        if (mode == null || mode.isBlank()) {
            // Backward compat: context: "fork" treated same as mode: "fork"
            String ctx = stringVal(map, "context");
            if ("fork".equals(ctx)) {
                mode = "fork";
            } else {
                mode = "inline";
            }
        }

        String model = stringVal(map, "model");

        String forkContext = stringVal(map, "fork_context");
        if (forkContext == null || forkContext.isBlank()) {
            forkContext = "none";
        }

        return new SkillMeta(
                name,
                description != null ? description : "",
                whenToUse != null ? whenToUse : "",
                tags,
                mode,
                model != null ? model : "",
                forkContext
        );
    }

    private static String stringVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }
}
