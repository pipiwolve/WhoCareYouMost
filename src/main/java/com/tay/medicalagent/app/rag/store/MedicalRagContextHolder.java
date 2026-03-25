package com.tay.medicalagent.app.rag.store;

import com.tay.medicalagent.app.rag.model.RagContext;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
/**
 * 线程级 RAG 上下文缓存。
 * <p>
 * 用于在一次线程对话结束后把本轮检索结果暴露给聊天结果封装层读取。
 */
public class MedicalRagContextHolder {

    private final ConcurrentMap<String, RagContext> threadContexts = new ConcurrentHashMap<>();

    public void put(String threadId, RagContext ragContext) {
        if (threadId == null || threadId.isBlank() || ragContext == null) {
            return;
        }
        threadContexts.put(threadId, ragContext);
    }

    public Optional<RagContext> get(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(threadContexts.get(threadId));
    }

    public void remove(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return;
        }
        threadContexts.remove(threadId);
    }

    public void clear() {
        threadContexts.clear();
    }
}
