package com.flycode.skill;

import com.flycode.conversation.Message;

import java.util.List;

/**
 * Extends {@link SkillHost} with the ability to run an isolated sub-agent.
 * Implemented by the TUI layer (which owns the LLM client + agent
 * constructor) and passed into {@link SkillExecutor#executeFork}.
 */
public interface SkillForkHost extends SkillHost {

    String runSubAgent(String body, List<Message> seed, String model);

    List<Message> snapshotParentMessages();
}

