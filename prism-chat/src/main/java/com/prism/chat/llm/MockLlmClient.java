package com.prism.chat.llm;

import com.prism.chat.dto.ChatMessage;
import com.prism.chat.entity.ModelChannel;
import com.prism.chat.util.TokenEstimator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 内置模拟上游：渠道 base_url 以 mock:// 开头时启用（种子渠道「内置模拟渠道」）。
 *
 * <p>生成 120~250 字中文结构化回答：固定句池 + 从问题中按 char-bigram 频次提取关键词，
 * 全部决策（目标长度 / 句序 / token 切分）由 prompt 的 MD5 派生，<b>无随机源</b>——
 * 同 prompt 必产生同输出，这是语义缓存能对 mock 渠道稳定命中的前提。
 * 按 4~8 字/token 流式下发，每 token 间隔 30ms 模拟真实节奏。</p>
 */
@Slf4j
@Component
public class MockLlmClient implements LlmClient {

    private static final String[] DEFAULT_KEYWORDS = {"整体方案", "落地路径", "验收口径"};

    @Override
    public boolean supports(String baseUrl) {
        return baseUrl != null && baseUrl.startsWith("mock://");
    }

    @Override
    public Flux<UpstreamChunk> chat(ModelChannel channel, List<ChatMessage> messages, Double temperature) {
        String prompt = lastUserContent(messages);
        String answer = generate(prompt);
        String promptAll = messages.stream()
                .map(ChatMessage::getContent)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
        UsageInfo usage = new UsageInfo(
                TokenEstimator.estimate(promptAll),
                TokenEstimator.estimate(answer),
                TokenEstimator.estimate(promptAll) + TokenEstimator.estimate(answer));
        log.debug("mock llm generate {} chars for channel {}", answer.length(), channel.getId());
        return Flux.fromIterable(tokenize(answer))
                .delayElements(Duration.ofMillis(30))
                .map(UpstreamChunk::content)
                .concatWith(Mono.just(UpstreamChunk.usageOnly(usage)));
    }

    // ---------- 生成（确定性） ----------

    String generate(String rawPrompt) {
        String prompt = rawPrompt == null ? "" : rawPrompt.strip();
        byte[] digest = md5(prompt);
        String[] kw = keywords(prompt);
        List<String> sentences = new ArrayList<>(List.of(
                "关于" + kw[0] + "这个问题，可以从三个层面来看。",
                "其一，" + kw[0] + "是问题的核心，建议先明确目标再拆解任务。",
                "其二，" + kw[1] + "相关的约束与依赖要提前梳理，避免中途返工。",
                "其三，围绕" + kw[2] + "的方案宜分阶段推进，小范围验证后再扩大。",
                "过程中应设置可量化的验收标准，方便持续跟踪效果。",
                "同时建议预留弹性余量，应对需求或环境变化。",
                "按里程碑定期复盘，动态调整节奏与资源配置。",
                "有条件的话引入自动化手段，降低重复操作成本。",
                "配套的文档沉淀与知识转移也不可忽视。",
                "风险方面要提前识别并准备应对预案。",
                "跨团队协作时明确接口人，减少沟通损耗。",
                "以上内容由内置模拟渠道生成，仅供联调测试使用。"));
        shuffle(sentences, digest);
        int target = 120 + (digest[0] & 0xFF) % 111; // 120~230，加一句封顶也不会超过 250
        StringBuilder sb = new StringBuilder();
        for (String s : sentences) {
            if (sb.length() >= target) {
                break;
            }
            if (sb.length() + s.length() > 250) {
                sb.append(s, 0, 250 - sb.length());
                break;
            }
            sb.append(s);
        }
        return sb.toString();
    }

    /** 关键词提取：含汉字的 char-bigram 按频次降序（同频字典序保证确定性）取前 3，不足补默认词 */
    static String[] keywords(String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return DEFAULT_KEYWORDS.clone();
        }
        Map<String, int[]> freq = new LinkedHashMap<>();
        int n = prompt.length();
        for (int i = 0; i + 1 < n; i++) {
            String gram = prompt.substring(i, i + 2);
            if (containsCjk(gram)) {
                freq.computeIfAbsent(gram, k -> new int[1])[0]++;
            }
        }
        List<String> top = freq.entrySet().stream()
                .sorted((a, b) -> b.getValue()[0] != a.getValue()[0]
                        ? Integer.compare(b.getValue()[0], a.getValue()[0])
                        : a.getKey().compareTo(b.getKey()))
                .map(Map.Entry::getKey)
                .limit(3)
                .toList();
        String[] out = new String[3];
        for (int i = 0; i < 3; i++) {
            out[i] = i < top.size() ? top.get(i) : DEFAULT_KEYWORDS[i];
        }
        return out;
    }

    /** 切 token：每片 4~8 字，片长由回答 MD5 派生（确定性） */
    static List<String> tokenize(String answer) {
        byte[] digest = md5(answer);
        List<String> out = new ArrayList<>();
        int i = 0;
        int k = 0;
        while (i < answer.length()) {
            int size = 4 + (digest[k++ % digest.length] & 0xFF) % 5;
            out.add(answer.substring(i, Math.min(answer.length(), i + size)));
            i += size;
        }
        return out;
    }

    private static void shuffle(List<String> list, byte[] digest) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = (digest[i % digest.length] & 0xFF) % (i + 1);
            Collections.swap(list, i, j);
        }
    }

    private static boolean containsCjk(String s) {
        return s.codePoints().anyMatch(TokenEstimator::isCjk);
    }

    private static String lastUserContent(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if (m != null && "user".equals(m.getRole())) {
                return m.getContent() == null ? "" : m.getContent();
            }
        }
        ChatMessage last = messages.get(messages.size() - 1);
        return last == null || last.getContent() == null ? "" : last.getContent();
    }

    static byte[] md5(String s) {
        try {
            return MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }
}
