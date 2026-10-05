package com.flycode.skill;

import com.flycode.skill.SkillCatalog.Skill;

import java.util.*;

/**
 * 内置 skill 加载器。
 * 当前版本不包含任何内置 skill，所有 skill 通过用户目录或项目目录加载。
 */
public final class BuiltinSkills {

    private BuiltinSkills() {}

    /**
     * 返回空列表：skill 一律从用户目录或项目目录加载，不编译进程序。
     */
    public static List<Skill> load() {
        return List.of();
    }
}
