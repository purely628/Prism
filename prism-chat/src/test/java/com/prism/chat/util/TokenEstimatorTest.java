package com.prism.chat.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TokenEstimator：ceil(中文×1 + 非中文÷4) 的近似估算边界。
 */
class TokenEstimatorTest {

    @Test
    void emptyOrNullIsZero() {
        assertThat(TokenEstimator.estimate(null)).isZero();
        assertThat(TokenEstimator.estimate("")).isZero();
        assertThat(TokenEstimator.estimate("   ")).isEqualTo(1); // 3 个非中文字符 → ceil(0.75)
    }

    @Test
    void chineseCharCountsOneEach() {
        assertThat(TokenEstimator.estimate("你好")).isEqualTo(2);
        assertThat(TokenEstimator.estimate("知识库检索")).isEqualTo(5);
    }

    @Test
    void nonChineseDividedByFourAndCeiled() {
        assertThat(TokenEstimator.estimate("abcd")).isEqualTo(1);   // 4/4=1 整除
        assertThat(TokenEstimator.estimate("abc")).isEqualTo(1);    // ceil(0.75)=1
        assertThat(TokenEstimator.estimate("abcde")).isEqualTo(2);  // ceil(1.25)=2
    }

    @Test
    void mixedTextCeilsSum() {
        // 中文 2 + 非中文 3 → 2 + 0.75 = 2.75 → ceil = 3
        assertThat(TokenEstimator.estimate("你好abc")).isEqualTo(3);
    }
}
