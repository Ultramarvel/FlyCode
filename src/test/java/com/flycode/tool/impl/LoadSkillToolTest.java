package com.flycode.tool.impl;

import com.flycode.skill.SkillCatalog;
import com.flycode.skill.SkillCatalog.Skill;
import com.flycode.skill.SkillCatalog.SkillMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LoadSkillToolTest {

    private LoadSkillTool tool;
    private SkillCatalog catalog;

    @BeforeEach
    void setUp() {
        tool = new LoadSkillTool();
        catalog = new SkillCatalog();

        tool.setCatalog(catalog);
    }

    @Test
    void 正常激活skill返回完整SOP() {
        var meta = new SkillMeta("commit", "生成规范的 commit message", null, List.of(), "inline", null, null);
        var skill = new Skill(meta, "请按照以下步骤生成 commit message...", null, true);
        catalog.register(skill);

        var result = tool.execute(Map.of("name", "commit"));

        assertFalse(result.isError());
        assertTrue(result.output().contains("# Skill: commit"));
        assertTrue(result.output().contains("请按照以下步骤生成 commit message"));
    }

    /** fork 模式下 SOP 正文交给子 Agent，主对话只拿到最终结果。 */
    @Test
    void fork模式交给子Agent执行且正文不进主对话() {
        var meta = new SkillMeta("audit-deps", "审计依赖", null, List.of(), "fork", null, "none");
        catalog.register(new Skill(meta, "检查 pom.xml 里有风险的依赖版本", null, true));

        var host = new StubForkHost("发现 3 个风险依赖");
        tool.setForkHost(host);

        var result = tool.execute(Map.of("name", "audit-deps"));

        assertFalse(result.isError());
        assertEquals("发现 3 个风险依赖", result.output());
        assertFalse(result.output().contains("检查 pom.xml"), "SOP 正文不应出现在主对话里");
        assertTrue(host.receivedBody.contains("检查 pom.xml"), "子 Agent 应当收到 SOP 正文");
    }

    /** 宿主未接入子 Agent 运行时，fork 回退成 inline，工具仍然可用。 */
    @Test
    void 未接入ForkHost时fork回退为inline() {
        var meta = new SkillMeta("audit-deps", "审计依赖", null, List.of(), "fork", null, "none");
        catalog.register(new Skill(meta, "检查 pom.xml 里有风险的依赖版本", null, true));

        var result = tool.execute(Map.of("name", "audit-deps"));

        assertFalse(result.isError());
        assertTrue(result.output().contains("检查 pom.xml"));
    }

    /** inline 模式保持原样：不启子 Agent。 */
    @Test
    void inline模式不启动子Agent() {
        var meta = new SkillMeta("commit", "生成 commit message", null, List.of(), "inline", null, null);
        catalog.register(new Skill(meta, "按步骤生成 commit message", null, true));

        var host = new StubForkHost("不应被调用");
        tool.setForkHost(host);

        var result = tool.execute(Map.of("name", "commit"));

        assertTrue(result.output().contains("按步骤生成 commit message"));
        assertEquals("", host.receivedBody, "inline 模式不应触发子 Agent");
    }

    /** 只记录调用参数的 SkillForkHost 桩件。 */
    private static final class StubForkHost implements com.flycode.skill.SkillForkHost {
        private final String reply;
        private String receivedBody = "";

        StubForkHost(String reply) {
            this.reply = reply;
        }

        @Override
        public void activateSkill(String name, String body) { }

        @Override
        public List<com.flycode.conversation.Message> snapshotParentMessages() {
            return List.of();
        }

        @Override
        public String runSubAgent(String body,
                                  List<com.flycode.conversation.Message> seed,
                                  String model) {
            receivedBody = body;
            return reply;
        }
    }

    @Test
    void name为空返回错误() {
        var result = tool.execute(Map.of("name", ""));
        assertTrue(result.isError());
        assertTrue(result.output().contains("name is required"));
    }

    @Test
    void 不存在的skill返回错误() {
        var result = tool.execute(Map.of("name", "nonexistent"));
        assertTrue(result.isError());
        assertTrue(result.output().contains("unknown skill"));
    }

    @Test
    void body为空的skill返回错误() {
        var meta = new SkillMeta("empty-skill", "空 skill", null, List.of(), "inline", null, null);
        var skill = new Skill(meta, "", null, true);
        catalog.register(skill);

        var result = tool.execute(Map.of("name", "empty-skill"));
        assertTrue(result.isError());
        assertTrue(result.output().contains("empty body"));
    }

    @Test
    void catalog未设置返回错误() {
        var noWireTool = new LoadSkillTool();
        var result = noWireTool.execute(Map.of("name", "commit"));
        assertTrue(result.isError());
        assertTrue(result.output().contains("not initialized"));
    }

    /** SOP 只经工具结果回到对话，不额外注入第二份。 */
    @Test
    void 只通过工具结果返回SOP() {
        var noCallbackTool = new LoadSkillTool();
        noCallbackTool.setCatalog(catalog);

        var meta = new SkillMeta("review", "代码审查", null, List.of(), "inline", null, null);
        var skill = new Skill(meta, "审查步骤...", null, true);
        catalog.register(skill);

        var result = noCallbackTool.execute(Map.of("name", "review"));
        assertFalse(result.isError());
        assertTrue(result.output().contains("审查步骤"));
    }

    /**
     * SOP 正文在工具结果里只出现一次。工具结果本身就会进对话历史，
     * 若再往对话里注入一份，同一段 SOP 会占掉两份 token。
     */
    @Test
    void SOP正文不重复出现() {
        var meta = new SkillMeta("commit", "生成 commit", null, List.of(), "inline", null, null);
        var body = "第一步：读取暂存区的改动";
        catalog.register(new Skill(meta, body, null, true));

        var output = tool.execute(Map.of("name", "commit")).output();

        int first = output.indexOf(body);
        assertTrue(first >= 0, "工具结果应带回 SOP 正文");
        assertEquals(first, output.lastIndexOf(body), "SOP 正文不应出现第二次");
    }
}
