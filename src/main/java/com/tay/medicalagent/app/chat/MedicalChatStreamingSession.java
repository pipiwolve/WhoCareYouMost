package com.tay.medicalagent.app.chat;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 医疗聊天流式会话结果。
 *
 * @param deltas     模型实时文本片段
 * @param finalResult 流结束后聚合出的完整聊天结果
 */
public record MedicalChatStreamingSession(
        Flux<String> deltas,
        Mono<MedicalChatResult> finalResult
) {
}
