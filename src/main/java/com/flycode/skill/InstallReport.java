package com.flycode.skill;

/**
 * 安装完成后的汇报信息，由 {@link SkillInstaller#install} 返回，
 * 工具层用它生成给模型的成功消息。
 *
 * @param skillName  安装的 skill 名称
 * @param targetDir  最终安装目录的绝对路径
 * @param fileCount  下载的文件总数
 * @param totalBytes 下载的字节总数
 */
public record InstallReport(
        String skillName,
        String targetDir,
        int fileCount,
        long totalBytes
) {}
