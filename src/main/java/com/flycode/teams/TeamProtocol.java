package com.flycode.teams;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * 队友之间除了纯文本，还走几种结构化消息。
 *
 * <p>它们都带一个 requestId，应答回来时原样带回，Lead 才能把应答和自己发出的那条请求对上号：
 * 同时向三个队友发关闭请求时，三条应答不靠 ID 是分不清谁是谁的。
 */
public final class TeamProtocol {

    private TeamProtocol() {}

    /** 普通文本消息，直接拼进队友下一轮的 prompt。 */
    public static final String TEXT = "text";
    /** 由 Lead 发起，请队友收工。队友可以拒绝。 */
    public static final String SHUTDOWN_REQUEST = "shutdown_request";
    /** 队友对关闭请求的答复，approve 为 false 表示还没干完。 */
    public static final String SHUTDOWN_RESPONSE = "shutdown_response";
    /** 由队友发起，把计划交给 Lead 审批。 */
    public static final String PLAN_APPROVAL_REQUEST = "plan_approval_request";
    /** Lead 的审批结果，驳回时 text 里带修改意见。 */
    public static final String PLAN_APPROVAL_RESPONSE = "plan_approval_response";

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 生成请求标识。用随机串而不是自增序号，因为请求可能由不同进程里的队友发起，
     * 自增序号跨进程会撞。
     */
    public static String newRequestId() {
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder("req-");
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    private static String now() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }

    /** 关闭请求。text 里放原因，队友要拿它判断该不该同意。 */
    public static FileMailBox.MailMessage shutdownRequest(String from, String reason) {
        String why = (reason == null || reason.isEmpty()) ? "team is wrapping up" : reason;
        return new FileMailBox.MailMessage(from, TeammateRunner.SHUTDOWN_PREFIX + " " + why,
                now(), false, "", SHUTDOWN_REQUEST, newRequestId(), null);
    }

    /** 队友对关闭请求的答复。 */
    public static FileMailBox.MailMessage shutdownResponse(
            String from, String requestId, boolean approve, String reason) {
        return new FileMailBox.MailMessage(from, reason == null ? "" : reason,
                now(), false, "", SHUTDOWN_RESPONSE, requestId, approve);
    }

    /** 计划审批请求，text 是计划全文。 */
    public static FileMailBox.MailMessage planApprovalRequest(String from, String plan) {
        return new FileMailBox.MailMessage(from, plan == null ? "" : plan,
                now(), false, "", PLAN_APPROVAL_REQUEST, newRequestId(), null);
    }

    /** 审批结果，驳回时 feedback 说明哪里要改。 */
    public static FileMailBox.MailMessage planApprovalResponse(
            String from, String requestId, boolean approve, String feedback) {
        return new FileMailBox.MailMessage(from, feedback == null ? "" : feedback,
                now(), false, "", PLAN_APPROVAL_RESPONSE, requestId, approve);
    }

    /**
     * 判断消息是不是关闭请求。
     *
     * <p>除了看 type，还认 "[shutdown]" 文本前缀：窗格队友是独立进程，可能是旧版本启动的；
     * 而且用户手动往信箱里塞一行也该管用。
     */
    public static boolean isShutdownRequest(FileMailBox.MailMessage m) {
        if (SHUTDOWN_REQUEST.equals(m.type())) {
            return true;
        }
        return m.text() != null && m.text().strip().startsWith(TeammateRunner.SHUTDOWN_PREFIX);
    }

    /**
     * 应答是否为同意。字段缺省时按不同意处理，
     * 宁可让 Lead 多等一轮，也不能把没表态当成点头。
     */
    public static boolean approved(FileMailBox.MailMessage m) {
        return Boolean.TRUE.equals(m.approve());
    }
}
