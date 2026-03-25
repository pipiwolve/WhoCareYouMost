package com.tay.medicalagent.app.rag.hook;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.tay.medicalagent.app.rag.config.MedicalRagProperties;
import com.tay.medicalagent.app.rag.model.RagContext;
import com.tay.medicalagent.app.rag.retrieval.MedicalKnowledgeRetriever;
import com.tay.medicalagent.app.rag.retrieval.MedicalQueryBuilder;
import com.tay.medicalagent.app.rag.retrieval.RetrievalQueryEnhancer;
import com.tay.medicalagent.app.rag.retrieval.RagTriggerPolicy;
import com.tay.medicalagent.app.rag.store.MedicalRagContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Component
@HookPositions({HookPosition.BEFORE_AGENT})
/**
 * Agent 级 RAG 检索 Hook。
 * <p>
 * 在 Agent 开始执行时决定是否触发知识检索，并把检索结果写入本轮上下文，
 * 从而实现“先检索，再生成”的两步 RAG。
 */
public class MedicalRagAgentHook extends AgentHook {

    private static final Logger log = LoggerFactory.getLogger(MedicalRagAgentHook.class);

    private final MedicalKnowledgeRetriever medicalKnowledgeRetriever;
    private final MedicalQueryBuilder medicalQueryBuilder;
    private final RetrievalQueryEnhancer retrievalQueryEnhancer;
    private final RagTriggerPolicy ragTriggerPolicy;
    private final MedicalRagContextHolder medicalRagContextHolder;
    private final MedicalRagProperties medicalRagProperties;

    public MedicalRagAgentHook(
            MedicalKnowledgeRetriever medicalKnowledgeRetriever,
            MedicalQueryBuilder medicalQueryBuilder,
            RetrievalQueryEnhancer retrievalQueryEnhancer,
            RagTriggerPolicy ragTriggerPolicy,
            MedicalRagContextHolder medicalRagContextHolder,
            MedicalRagProperties medicalRagProperties
    ) {
        this.medicalKnowledgeRetriever = medicalKnowledgeRetriever;
        this.medicalQueryBuilder = medicalQueryBuilder;
        this.retrievalQueryEnhancer = retrievalQueryEnhancer;
        this.ragTriggerPolicy = ragTriggerPolicy;
        this.medicalRagContextHolder = medicalRagContextHolder;
        this.medicalRagProperties = medicalRagProperties;
    }

    @Override
    public String getName() {
        return "medical_rag_agent_hook";
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
        if (!medicalRagProperties.isEnabled()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        List<Message> messages = messagesFromState(state);
        String searchQuery = resolveSearchQuery(messages, config);
        String threadId = config.metadata("thread_id").map(Object::toString).orElse("");
        String userId = config.metadata("user_id").map(Object::toString).orElse("");

        if (log.isDebugEnabled()) {
            log.debug(
                    "RAG hook evaluating request. threadId={}, messageCount={}, searchQuery={}",
                    threadId,
                    messages.size(),
                    searchQuery
            );
        }

        if (!ragTriggerPolicy.shouldRetrieve(searchQuery)) {
            log.debug("RAG hook skipped retrieval. threadId={}, searchQuery={}", threadId, searchQuery);
            storeContext(config, threadId, RagContext.empty(searchQuery));
            return CompletableFuture.completedFuture(Map.of());
        }

        String enhancedQuery = retrievalQueryEnhancer.enhanceQuery(searchQuery, userId);
        if (log.isDebugEnabled() && !enhancedQuery.equals(searchQuery)) {
            log.debug("RAG hook enhanced query. threadId={}, originalQuery={}, enhancedQuery={}",
                    threadId, searchQuery, enhancedQuery);
        }

        RagContext ragContext = medicalKnowledgeRetriever.retrieve(enhancedQuery);
        if (log.isDebugEnabled()) {
            log.debug(
                    "RAG hook retrieved context. threadId={}, applied={}, sourceCount={}",
                    threadId,
                    ragContext.applied(),
                    ragContext.sources().size()
            );
        }
        storeContext(config, threadId, ragContext);
        return CompletableFuture.completedFuture(Map.of());
    }

    @SuppressWarnings("unchecked")
    private List<Message> messagesFromState(OverAllState state) {
        Optional<List> rawMessages = state.value("messages", List.class);
        if (rawMessages.isEmpty()) {
            return List.of();
        }
        return (List<Message>) rawMessages.get();
    }

    private String resolveSearchQuery(List<Message> messages, RunnableConfig config) {
        String searchQuery = medicalQueryBuilder.buildSearchQuery(messages);
        if (!searchQuery.isBlank()) {
            return searchQuery;
        }

        return config.metadata("latest_user_prompt")
                .map(Object::toString)
                .map(medicalQueryBuilder::normalizeQuery)
                .orElse("");
    }

    private void storeContext(RunnableConfig config, String threadId, RagContext ragContext) {
        config.metadata().ifPresent(metadata -> {
            metadata.put(medicalRagProperties.getContextMetadataKey(), ragContext);
            metadata.put(medicalRagProperties.getAppliedMetadataKey(), ragContext.applied());
        });
        medicalRagContextHolder.put(threadId, ragContext);
    }
}
