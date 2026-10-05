package com.flycode.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 {@link SkillInstaller} 中不依赖网络的逻辑：URL 解析和名称校验。
 */
class SkillInstallerTest {

    // ── skills.sh URL 格式 ────────────────────────────────────────────

    @Test
    void parseSkillsShURL() {
        var src = SkillInstaller.parseSkillURL(
                "https://www.skills.sh/anthropics/skills/frontend-design");
        assertEquals("anthropics", src.owner());
        assertEquals("skills", src.repo());
        assertEquals("main", src.ref());
        assertEquals("skills/frontend-design", src.subpath());
        assertEquals("frontend-design", src.name());
    }

    @Test
    void parseSkillsShNestedPath() {
        var src = SkillInstaller.parseSkillURL(
                "https://skills.sh/owner/repo/category/my-skill");
        assertEquals("owner", src.owner());
        assertEquals("repo", src.repo());
        assertEquals("skills/category/my-skill", src.subpath());
        assertEquals("my-skill", src.name());
    }

    @Test
    void parseSkillsShTooShort() {
        assertThrows(IllegalArgumentException.class,
                () -> SkillInstaller.parseSkillURL("https://skills.sh/owner/repo"));
    }

    // ── github.com URL 格式 ──────────────────────────────────────────

    @Test
    void parseGitHubTreeURL() {
        var src = SkillInstaller.parseSkillURL(
                "https://github.com/anthropics/skills/tree/main/skills/pdf");
        assertEquals("anthropics", src.owner());
        assertEquals("skills", src.repo());
        assertEquals("main", src.ref());
        assertEquals("skills/pdf", src.subpath());
        assertEquals("pdf", src.name());
    }

    @Test
    void parseGitHubTreeDifferentRef() {
        var src = SkillInstaller.parseSkillURL(
                "https://github.com/user/repo/tree/v2.1/path/to/skill");
        assertEquals("user", src.owner());
        assertEquals("repo", src.repo());
        assertEquals("v2.1", src.ref());
        assertEquals("path/to/skill", src.subpath());
        assertEquals("skill", src.name());
    }

    @Test
    void parseGitHubMissingTree() {
        assertThrows(IllegalArgumentException.class,
                () -> SkillInstaller.parseSkillURL(
                        "https://github.com/owner/repo/blob/main/file.md"));
    }

    // ── raw.githubusercontent.com URL 格式 ──────────────────────────

    @Test
    void parseRawGitHubURL() {
        var src = SkillInstaller.parseSkillURL(
                "https://raw.githubusercontent.com/anthropics/skills/main/skills/pdf/SKILL.md");
        assertEquals("anthropics", src.owner());
        assertEquals("skills", src.repo());
        assertEquals("main", src.ref());
        assertEquals("skills/pdf", src.subpath());
        assertEquals("pdf", src.name());
    }

    @Test
    void parseRawGitHubTooShort() {
        assertThrows(IllegalArgumentException.class,
                () -> SkillInstaller.parseSkillURL(
                        "https://raw.githubusercontent.com/a/b/main"));
    }

    // ── 异常 URL ─────────────────────────────────────────────────────

    @Test
    void parseUnsupportedHost() {
        assertThrows(IllegalArgumentException.class,
                () -> SkillInstaller.parseSkillURL("https://gitlab.com/a/b/tree/main/x"));
    }

    @Test
    void parseNonHttpScheme() {
        assertThrows(IllegalArgumentException.class,
                () -> SkillInstaller.parseSkillURL("ftp://skills.sh/a/b/c"));
    }

    // ── 名称校验 ─────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"my-skill", "skill_name", "a123", "test"})
    void validSkillNames(String name) {
        assertDoesNotThrow(() -> SkillInstaller.validateSkillName(name));
    }

    @Test
    void emptyNameRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SkillInstaller.validateSkillName(""));
    }

    @Test
    void dotPrefixRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SkillInstaller.validateSkillName(".hidden"));
    }

    @Test
    void uppercaseRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SkillInstaller.validateSkillName("MySkill"));
    }

    @Test
    void spacesRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SkillInstaller.validateSkillName("my skill"));
    }

    @Test
    void urlPreservesOriginal() {
        String original = "https://github.com/a/b/tree/main/x/y";
        var src = SkillInstaller.parseSkillURL(original);
        assertEquals(original, src.original());
    }

    @Test
    void urlTrimsWhitespace() {
        var src = SkillInstaller.parseSkillURL(
                "  https://www.skills.sh/a/b/c  ");
        assertEquals("a", src.owner());
        assertEquals("c", src.name());
    }
}
