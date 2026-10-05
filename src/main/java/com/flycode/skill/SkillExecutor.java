package com.flycode.skill;

import com.flycode.conversation.Message;
import com.flycode.skill.SkillCatalog.Skill;

import java.util.*;

/**
 * Executes skills in inline or fork mode.
 */
public final class SkillExecutor {

    private static final int FORK_RECENT_COUNT = 5;

    private SkillExecutor() {}

    /**
     * Activates the skill's SOP on the host agent and returns the rendered prompt body.
     */
    public static String executeInline(Skill skill, String args, SkillHost host) {
        String body = substituteArguments(skill.promptBody(), args);
        host.activateSkill(skill.meta().name(), body);
        host.recordSkillInvocation(skill.meta().name(), body);
        return body;
    }

    /**
     * Executes the skill in an isolated sub-agent and returns the
     * final assistant text.
     */
    public static String executeFork(Skill skill, String args, SkillForkHost host) {
        String body = substituteArguments(skill.promptBody(), args);
        host.recordSkillInvocation(skill.meta().name(), skill.promptBody());
        List<Message> seed = buildForkSeed(skill.meta().forkContext(), host.snapshotParentMessages());
        return host.runSubAgent(body, seed, skill.meta().model());
    }

    static String substituteArguments(String body, String args) {
        if (args == null || args.isBlank()) {
            return body;
        }
        if (body.contains("$ARGUMENTS")) {
            return body.replace("$ARGUMENTS", args);
        }
        return body + "\n\n## User Request\n\n" + args;
    }

    static List<Message> buildForkSeed(String mode, List<Message> parent) {
        if (parent == null || parent.isEmpty()) {
            return List.of();
        }
        return switch (mode != null ? mode : "none") {
            case "full" -> new ArrayList<>(parent);
            case "recent" -> {
                if (parent.size() <= FORK_RECENT_COUNT) {
                    yield new ArrayList<>(parent);
                }
                yield new ArrayList<>(parent.subList(parent.size() - FORK_RECENT_COUNT, parent.size()));
            }
            default -> List.of();
        };
    }

}
