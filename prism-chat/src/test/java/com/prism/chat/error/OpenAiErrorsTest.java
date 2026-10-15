package com.prism.chat.error;

import com.prism.common.exception.BizException;
import com.prism.common.result.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.core.codec.DecodingException;
import org.springframework.web.server.ServerWebInputException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAI 错误映射：BizException → 正确 HTTP 状态 + OpenAI type/code；
 * 解码失败 → 400；未知异常 → 500；SSE 中途错误负载格式。
 */
class OpenAiErrorsTest {

    @Test
    void quotaExceededMapsTo429WithChineseMessage() {
        OpenAiErrors.Spec spec = OpenAiErrors.spec(new BizException(ErrorCode.QUOTA_EXCEEDED, "本月配额已用尽"));
        assertThat(spec.status().value()).isEqualTo(429);
        assertThat(spec.type()).isEqualTo("rate_limit_error");
        assertThat(spec.code()).isEqualTo("quota_exceeded");
        assertThat(spec.message()).isEqualTo("本月配额已用尽");
    }

    @Test
    void invalidKeyMapsTo401() {
        OpenAiErrors.Spec spec = OpenAiErrors.spec(new BizException(ErrorCode.API_KEY_INVALID, "缺少应用身份头"));
        assertThat(spec.status().value()).isEqualTo(401);
        assertThat(spec.code()).isEqualTo("invalid_api_key");
    }

    @Test
    void channelUnavailableMapsTo502() {
        OpenAiErrors.Spec spec = OpenAiErrors.spec(new BizException(ErrorCode.CHANNEL_UNAVAILABLE, "无可用模型渠道"));
        assertThat(spec.status().value()).isEqualTo(502);
        assertThat(spec.type()).isEqualTo("api_error");
        assertThat(spec.code()).isEqualTo("channel_unavailable");
    }

    @Test
    void wrappedBizExceptionIsUnwrappedByCauseChain() {
        RuntimeException wrapped = new RuntimeException("flux wrapper",
                new IllegalStateException("inner",
                        new BizException(ErrorCode.QUOTA_EXCEEDED, "本月配额已用尽")));
        assertThat(OpenAiErrors.spec(wrapped).code()).isEqualTo("quota_exceeded");
    }

    @Test
    void malformedJsonMapsTo400InvalidRequest() {
        OpenAiErrors.Spec spec = OpenAiErrors.spec(new ServerWebInputException("bad body",
                null, new DecodingException("json error")));
        assertThat(spec.status().value()).isEqualTo(400);
        assertThat(spec.type()).isEqualTo("invalid_request_error");
    }

    @Test
    void unknownErrorMapsTo500() {
        OpenAiErrors.Spec spec = OpenAiErrors.spec(new RuntimeException("boom"));
        assertThat(spec.status().value()).isEqualTo(500);
        assertThat(spec.code()).isEqualTo("internal_error");
    }

    @Test
    void ssePayloadIsOpenAiErrorJson() {
        String payload = OpenAiErrors.sseErrorPayload(new BizException(ErrorCode.UPSTREAM_ERROR, "上游挂了"));
        assertThat(payload).contains("\"error\"").contains("upstream_error").contains("上游挂了");
    }
}
