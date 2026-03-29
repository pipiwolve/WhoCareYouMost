package com.tay.medicalagent.app.rag.interceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.tay.medicalagent.app.rag.config.MedicalRagProperties;
import com.tay.medicalagent.app.rag.model.RagContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.stereotype.Component;

@Component
/**
 * RAG 上下文注入拦截器。
 * <p>
 * 在模型真正调用前把检索命中的知识片段拼接进系统提示词，
 * 让生成阶段显式感知知识库依据。
 */
public class MedicalRagContextInterceptor extends ModelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(MedicalRagContextInterceptor.class);

    private final MedicalRagProperties medicalRagProperties;

    public MedicalRagContextInterceptor(MedicalRagProperties medicalRagProperties) {
        this.medicalRagProperties = medicalRagProperties;
    }

    @Override
    public String getName() {
        return "medical_rag_context_interceptor";
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        if (!medicalRagProperties.isEnabled()) {
            return handler.call(request);
        }

        Object ragContextValue = request.getContext().get(medicalRagProperties.getContextMetadataKey());
        if (!(ragContextValue instanceof RagContext ragContext) || !ragContext.applied()) {
            if (log.isDebugEnabled()) {
                log.debug("Skip RAG context injection because no applied RAG context is present.");
            }
            return handler.call(request);
        }

        if (log.isDebugEnabled()) {
            log.debug(
                    "Injecting RAG context into model request. query={}, sourceCount={}, contextChars={}",
                    ragContext.query(),
                    ragContext.sources().size(),
                    ragContext.contextText() == null ? 0 : ragContext.contextText().length()
            );
        }

        String ragSystemPrompt = """
                [MEDICAL_RAG_CONTEXT]
                以下内容来自医疗知识库检索结果。
                回答时请优先依据这些资料。
                如果知识库未提供足够依据，请明确说明“当前知识库未提供足够依据”，不要编造指南、证据或来源。

                检索问题：
                %s

                检索上下文：
                %s
                """.formatted(ragContext.query(), ragContext.contextText());

        SystemMessage enhancedSystemMessage;
        if (request.getSystemMessage() == null) {
            enhancedSystemMessage = new SystemMessage(ragSystemPrompt);
        }
        else {
            enhancedSystemMessage = new SystemMessage(request.getSystemMessage().getText() + "\n\n" + ragSystemPrompt);
        }

        ModelRequest enhancedRequest = ModelRequest.builder(request)
                .systemMessage(enhancedSystemMessage)
                .build();
        return handler.call(enhancedRequest);
    }
}
