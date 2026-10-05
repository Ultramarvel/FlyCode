package com.flycode.sandbox;

/**
 * 根据当前操作系统返回对应的沙箱实现。
 * macOS 使用 seatbelt（sandbox-exec），Linux 使用 bubblewrap（bwrap），
 * 其他平台返回 null。
 */
public class SandboxFactory {

    private SandboxFactory() {}

    /**
     * 创建当前平台对应的沙箱实例。
     * 不支持的平台返回 null，调用方需判空。
     */
    public static Sandbox create() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("mac")) {
            return new SeatbeltSandbox();
        }
        if (os.contains("linux")) {
            return new BwrapSandbox();
        }
        return null;
    }
}
