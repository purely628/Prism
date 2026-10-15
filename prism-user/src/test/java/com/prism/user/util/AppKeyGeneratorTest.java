package com.prism.user.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * appKey 生成：格式（sk-prism- + 32 位小写 hex）与唯一性
 */
class AppKeyGeneratorTest {

    private static final Pattern FORMAT =
            Pattern.compile("^sk-prism-[0-9a-f]{32}$");

    @Test
    @DisplayName("生成的 appKey 符合 sk-prism-+32位hex 格式")
    void formatShouldMatchContract() {
        for (int i = 0; i < 100; i++) {
            String key = AppKeyGenerator.generate();
            assertTrue(FORMAT.matcher(key).matches(), "格式非法: " + key);
            assertEquals("sk-prism-", key.substring(0, AppKeyGenerator.PREFIX.length()));
            assertEquals(32, key.length() - AppKeyGenerator.PREFIX.length());
        }
    }

    @Test
    @DisplayName("连续生成 5000 个 appKey 互不重复")
    void keysShouldBeUnique() {
        int n = 5000;
        Set<String> seen = new HashSet<>(n * 2);
        for (int i = 0; i < n; i++) {
            String key = AppKeyGenerator.generate();
            assertTrue(seen.add(key), "出现重复 appKey: " + key);
        }
        assertEquals(n, seen.size());
    }

    @Test
    @DisplayName("两次生成结果不同（非固定值）")
    void generateShouldNotBeConstant() {
        assertNotEquals(AppKeyGenerator.generate(), AppKeyGenerator.generate());
    }
}
