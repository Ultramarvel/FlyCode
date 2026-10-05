package com.flycode.skill;

/**
 * 解析后的 skill 来源信息。
 * 不管用户提供哪种 URL 格式，最终都统一归一化为 GitHub Contents API 所需的字段。
 *
 * @param owner    GitHub 仓库 owner
 * @param repo     GitHub 仓库名
 * @param ref      分支或 tag，默认 "main"
 * @param subpath  仓库内 skill 目录的路径（无尾 /）
 * @param name     skill 名称（subpath 最后一段）
 * @param original 用户原始 URL，用于错误消息和报告
 */
public record SkillSource(
        String owner,
        String repo,
        String ref,
        String subpath,
        String name,
        String original
) {}
