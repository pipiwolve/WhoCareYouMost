package com.tay.medicalagent.interceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.tay.medicalagent.app.rag.config.MedicalRagProperties;
import com.tay.medicalagent.app.rag.model.KnowledgeSource;
import com.tay.medicalagent.app.rag.model.RagContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@Component
/**
 * 模型请求响应日志拦截器。
 * <p>
 * 主要用于观察 Agent 在调用 ChatModel 前后的上下文、消息数量和响应摘要。
 */
public class MyLogModelInterceptor extends ModelInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(MyLogModelInterceptor.class);
    private static final int MAX_PREVIEW_LENGTH = 500;

    private final MedicalRagProperties medicalRagProperties;

    public MyLogModelInterceptor(MedicalRagProperties medicalRagProperties) {
        this.medicalRagProperties = medicalRagProperties;
    }

    @Override
    public String getName() {
        return "MyLogModelInterceptor";
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        logRequest(request);

        ModelResponse response = handler.call(request);
        return logResponse(response);
    }

    private void logRequest(ModelRequest request) {
        Map<String, Object> context = request.getContext();
        String threadId = valueToString(context.get("thread_id"));
        String userId = valueToString(context.get("user_id"));
        String systemMessage = request.getSystemMessage() == null ? "" : request.getSystemMessage().getText();
        RagDebugInfo ragDebugInfo = extractRagDebugInfo(context);

        logger.info(
                "MODEL_REQUEST threadId={} userId={} messageCount={} tools={} ragApplied={} ragQuery={} ragSources={}",
                threadId,
                userId,
                request.getMessages() == null ? 0 : request.getMessages().size(),
                request.getTools(),
                ragDebugInfo.ragApplied(),
                preview(ragDebugInfo.ragQuery()),
                ragDebugInfo.ragSources()
        );

        if (logger.isDebugEnabled()) {
            logger.debug(
                    """
                    ==================== MODEL REQUEST ====================
                    threadId: {}
                    userId: {}
                    ragApplied: {}
                    ragQuery: {}
                    ragSources: {}
                    tools: {}
                    ---- system message ----
                    {}
                    ---- messages ----
                    {}
                    =======================================================
                    """,
                    threadId,
                    userId,
                    ragDebugInfo.ragApplied(),
                    ragDebugInfo.ragQuery(),
                    ragDebugInfo.ragSources(),
                    request.getTools(),
                    systemMessage,
                    formatMessages(request.getMessages())
            );
        }
    }

    private ModelResponse logResponse(ModelResponse response) {

        Object message = response.getMessage();

        if (message instanceof AssistantMessage assistantMessage) {
            logAssistantMessage(assistantMessage);
            return response;
        }

        if (message instanceof Flux<?> flux) {
            @SuppressWarnings("unchecked")
            Flux<ChatResponse> responseFlux = (Flux<ChatResponse>) flux;

            StringBuilder aggregatedText = new StringBuilder();
            Flux<ChatResponse> wrappedFlux = responseFlux
                    .doOnNext(chatResponse -> appendAssistantText(chatResponse, aggregatedText))
                    .doOnComplete(() -> logAggregatedResponse(aggregatedText.toString()));

            return new ModelResponse(wrappedFlux, response.getChatResponse());
        }

        logger.info("Model response intercepted. messageType={}", message == null ? "null" : message.getClass().getName());
        return response;
    }

    private void logAssistantMessage(AssistantMessage assistantMessage) {
        String text = assistantMessage.getText();
        logger.info("MODEL_RESPONSE text={}", preview(text));
        if (logger.isDebugEnabled()) {
            logger.debug(
                    """
                    ==================== MODEL RESPONSE ===================
                    {}
                    =======================================================
                    """,
                    text
            );
            if (!assistantMessage.getToolCalls().isEmpty()) {
                logger.debug("Model tool calls: {}", assistantMessage.getToolCalls());
            }
        }
    }

    private void appendAssistantText(ChatResponse chatResponse, StringBuilder aggregatedText) {
        if (chatResponse == null || chatResponse.getResult() == null || chatResponse.getResult().getOutput() == null) {
            return;
        }

        String text = chatResponse.getResult().getOutput().getText();
        if (text != null && !text.isBlank()) {
            aggregatedText.append(text);
        }
    }

    private void logAggregatedResponse(String text) {
        logger.info("MODEL_RESPONSE text={}", preview(text));
        if (logger.isDebugEnabled()) {
            logger.debug(
                    """
                    ==================== MODEL RESPONSE ===================
                    {}
                    =======================================================
                    """,
                    text
            );
        }
    }

    private String formatMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "<empty>";
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            builder.append(i + 1)
                    .append(". ")
                    .append(resolveRole(message))
                    .append(": ")
                    .append(message.getText());

            if (message instanceof AssistantMessage assistantMessage && !assistantMessage.getToolCalls().isEmpty()) {
                builder.append(" | toolCalls=").append(assistantMessage.getToolCalls());
            }
            if (message instanceof ToolResponseMessage toolResponseMessage) {
                builder.append(" | toolResponses=").append(toolResponseMessage.getResponses());
            }
            if (i < messages.size() - 1) {
                builder.append(System.lineSeparator());
            }
        }
        return builder.toString();
    }

    private String resolveRole(Message message) {
        if (message instanceof SystemMessage) {
            return "system";
        }
        if (message instanceof UserMessage) {
            return "user";
        }
        if (message instanceof AssistantMessage) {
            return "assistant";
        }
        if (message instanceof ToolResponseMessage) {
            return "tool";
        }
        return message == null ? "unknown" : message.getClass().getSimpleName();
    }

    private String preview(String text) {
        if (text == null || text.isBlank()) {
            return "<empty>";
        }
        if (text.length() <= MAX_PREVIEW_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_PREVIEW_LENGTH) + "...";
    }

    private String valueToString(Object value) {
        return value == null ? "unknown" : value.toString();
    }

    private RagDebugInfo extractRagDebugInfo(Map<String, Object> context) {
        Object ragAppliedValue = context.get(medicalRagProperties.getAppliedMetadataKey());
        boolean ragApplied = ragAppliedValue instanceof Boolean value && value;

        Object ragContextValue = context.get(medicalRagProperties.getContextMetadataKey());
        if (!(ragContextValue instanceof RagContext ragContext)) {
            return new RagDebugInfo(ragApplied, "", List.of());
        }

        List<String> ragSources = ragContext.sources().stream()
                .map(KnowledgeSource::sourceId)
                .toList();
        return new RagDebugInfo(ragContext.applied(), ragContext.query(), ragSources);
    }

    private record RagDebugInfo(boolean ragApplied, String ragQuery, List<String> ragSources) {
    }
}
