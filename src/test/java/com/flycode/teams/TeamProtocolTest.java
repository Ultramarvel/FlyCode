package com.flycode.teams;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/** 结构化消息协议：关闭协商与计划审批。 */
class TeamProtocolTest {

    @Test
    void 关闭请求能被识别() {
        var req = TeamProtocol.shutdownRequest(TeammateRunner.LEAD_NAME, "收工");
        assertEquals(TeamProtocol.SHUTDOWN_REQUEST, req.type());
        assertNotNull(req.requestId(), "关闭请求必须带 requestId，否则应答对不上");
        assertTrue(TeamProtocol.isShutdownRequest(req));

        // 纯文本前缀同样要认，窗格队友可能是旧版本进程
        var legacy = new FileMailBox.MailMessage(TeammateRunner.LEAD_NAME, "[shutdown] stop");
        assertTrue(TeamProtocol.isShutdownRequest(legacy));

        var normal = new FileMailBox.MailMessage(TeammateRunner.LEAD_NAME, "继续改 auth 模块");
        assertFalse(TeamProtocol.isShutdownRequest(normal), "普通消息不该被误判");
    }

    @Test
    void 应答带回请求标识与表态() {
        var req = TeamProtocol.shutdownRequest(TeammateRunner.LEAD_NAME, "收工");

        var yes = TeamProtocol.shutdownResponse("alice", req.requestId(), true, "done");
        assertTrue(TeamProtocol.approved(yes));
        assertEquals(req.requestId(), yes.requestId());

        var no = TeamProtocol.shutdownResponse("alice", req.requestId(), false, "还在跑测试");
        assertFalse(TeamProtocol.approved(no));

        // 没表态时按不同意处理，不能当成点头
        var silent = new FileMailBox.MailMessage("alice", "");
        assertFalse(TeamProtocol.approved(silent));
    }

    @Test
    void 计划审批一来一回() {
        var req = TeamProtocol.planApprovalRequest("alice", "1. 先读 auth 包\n2. 抽出接口");
        assertEquals(TeamProtocol.PLAN_APPROVAL_REQUEST, req.type());
        assertTrue(req.text().contains("抽出接口"), "计划全文应放在 text 里");

        var rej = TeamProtocol.planApprovalResponse(
                TeammateRunner.LEAD_NAME, req.requestId(), false, "别动 handler 层");
        assertFalse(TeamProtocol.approved(rej));
        assertEquals("别动 handler 层", rej.text(), "驳回意见应放在 text 里");
        assertEquals(req.requestId(), rej.requestId());
    }

    @Test
    void 字段能穿过一次序列化() throws Exception {
        var mapper = new ObjectMapper();
        var req = TeamProtocol.shutdownRequest(TeammateRunner.LEAD_NAME, "收工");
        var resp = TeamProtocol.shutdownResponse("alice", req.requestId(), false, "还没跑完");

        var json = mapper.writeValueAsString(resp);
        var got = mapper.readValue(json, FileMailBox.MailMessage.class);

        assertEquals(TeamProtocol.SHUTDOWN_RESPONSE, got.type());
        assertEquals(req.requestId(), got.requestId());
        assertEquals(Boolean.FALSE, got.approve(), "approve=false 必须原样穿过序列化");
    }

    @Test
    void 请求标识不重复() {
        var seen = new HashSet<String>();
        for (int i = 0; i < 200; i++) {
            assertTrue(seen.add(TeamProtocol.newRequestId()), "请求 ID 撞了");
        }
    }
}
