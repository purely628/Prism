package com.prism.user.util;

import java.security.SecureRandom;

/**
 * 应用 API Key 生成器：sk-prism- + 32 位小写 hex（16 字节 SecureRandom）
 */
public final class AppKeyGenerator {

    public static final String PREFIX = "sk-prism-";
    public static final int HEX_LENGTH = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private AppKeyGenerator() {
    }

    public static String generate() {
        byte[] bytes = new byte[HEX_LENGTH / 2];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(PREFIX.length() + HEX_LENGTH);
        sb.append(PREFIX);
        for (byte b : bytes) {
            sb.append(HEX[(b >> 4) & 0x0F]);
            sb.append(HEX[b & 0x0F]);
        }
        return sb.toString();
    }
}
