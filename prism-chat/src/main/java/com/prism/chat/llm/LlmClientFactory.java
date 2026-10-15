package com.prism.chat.llm;

import com.prism.chat.entity.ModelChannel;
import com.prism.common.exception.BizException;
import com.prism.common.result.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;

/** 按渠道 base_url 选择对应 LlmClient 实现 */
@Component
public class LlmClientFactory {

    private final List<LlmClient> clients;

    public LlmClientFactory(List<LlmClient> clients) {
        this.clients = clients;
    }

    public LlmClient forChannel(ModelChannel channel) {
        String baseUrl = channel.getBaseUrl() == null ? "" : channel.getBaseUrl();
        return clients.stream()
                .filter(c -> c.supports(baseUrl))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.UPSTREAM_ERROR,
                        "无可用 LlmClient 实现处理渠道 base_url：" + baseUrl));
    }
}
