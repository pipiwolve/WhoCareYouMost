package com.tay.medicalagent.app.service.runtime;

import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.store.Store;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.tay.medicalagent.app.config.MedicalPersistenceProperties;
import com.tay.medicalagent.app.rag.config.MedicalRagProperties;
import com.tay.medicalagent.app.rag.hook.MedicalRagAgentHook;
import com.tay.medicalagent.app.rag.interceptor.MedicalRagContextInterceptor;
import com.tay.medicalagent.app.rag.retrieval.MedicalKnowledgeRetriever;
import com.tay.medicalagent.app.rag.retrieval.MedicalQueryBuilder;
import com.tay.medicalagent.app.rag.retrieval.RagTriggerPolicy;
import com.tay.medicalagent.app.rag.retrieval.RetrievalQueryEnhancer;
import com.tay.medicalagent.app.rag.store.MedicalRagContextHolder;
import com.tay.medicalagent.app.repository.store.StoreBackedUserProfileRepository;
import com.tay.medicalagent.app.service.model.MedicalAiModelProvider;
import com.tay.medicalagent.app.service.profile.DefaultUserProfileService;
import com.tay.medicalagent.app.service.profile.UserProfileFactExtractor;
import com.tay.medicalagent.app.service.profile.UserProfileService;
import com.tay.medicalagent.hook.UserProfileMemoryHook;
import com.tay.medicalagent.interceptor.MedicalSystemPromptInterceptor;
import com.tay.medicalagent.interceptor.MyLogModelInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class DefaultMedicalAgentRuntimeRedisIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private RedissonClient redissonClient;

    @AfterEach
    void tearDown() {
        if (redissonClient != null) {
            redissonClient.shutdown();
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void shouldResumeThreadContextAfterRuntimeReset() throws GraphRunnerException {
        RecordingChatModel chatModel = new RecordingChatModel();
        MedicalAiModelProvider medicalAiModelProvider = mock(MedicalAiModelProvider.class);
        when(medicalAiModelProvider.getChatModel()).thenReturn(chatModel);

        MedicalPersistenceProperties persistenceProperties = persistenceProperties();
        Store store = new MedicalRedisGraphStore(redisTemplate(), objectMapper, persistenceProperties);
        BaseCheckpointSaver checkpointSaver = new MedicalRedisCheckpointSaver(
                redisTemplate(),
                redissonClient(),
                persistenceProperties,
                RedisSaver.builder().redisson(redissonClient()).build(),
                persistenceProperties.getRedis().getConversationTtl()
        );

        UserProfileService userProfileService = new DefaultUserProfileService(
                new StoreBackedUserProfileRepository(store),
                new NoopUserProfileFactExtractor()
        );
        UserProfileMemoryHook userProfileMemoryHook = new UserProfileMemoryHook(userProfileService);

        MedicalRagProperties ragProperties = new MedicalRagProperties();
        ragProperties.setEnabled(false);
        MedicalRagContextHolder ragContextHolder = new MedicalRagContextHolder();
        MedicalRagAgentHook ragAgentHook = new MedicalRagAgentHook(
                mock(MedicalKnowledgeRetriever.class),
                mock(MedicalQueryBuilder.class),
                mock(RetrievalQueryEnhancer.class),
                mock(RagTriggerPolicy.class),
                ragContextHolder,
                ragProperties
        );

        DefaultMedicalAgentRuntime runtime = new DefaultMedicalAgentRuntime(
                medicalAiModelProvider,
                store,
                checkpointSaver,
                userProfileMemoryHook,
                ragAgentHook,
                new MedicalSystemPromptInterceptor(userProfileService),
                new MedicalRagContextInterceptor(ragProperties),
                new MyLogModelInterceptor(ragProperties)
        );

        runtime.doChatMessage("第一次症状描述", "thread-runtime-redis", "user-runtime");
        runtime.reset();
        runtime.doChatMessage("第二次补充说明", "thread-runtime-redis", "user-runtime");

        assertEquals(2, chatModel.capturedUserMessages().size());
        assertEquals(List.of("第一次症状描述"), chatModel.capturedUserMessages().get(0));
        assertTrue(chatModel.capturedUserMessages().get(1).contains("第一次症状描述"));
        assertTrue(chatModel.capturedUserMessages().get(1).contains("第二次补充说明"));
    }

    private StringRedisTemplate redisTemplate() {
        if (redisTemplate == null) {
            connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getFirstMappedPort());
            connectionFactory.afterPropertiesSet();
            redisTemplate = new StringRedisTemplate(connectionFactory);
            redisTemplate.afterPropertiesSet();
        }
        return redisTemplate;
    }

    private RedissonClient redissonClient() {
        if (redissonClient == null) {
            Config config = new Config();
            config.useSingleServer()
                    .setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getFirstMappedPort());
            redissonClient = Redisson.create(config);
        }
        return redissonClient;
    }

    private MedicalPersistenceProperties persistenceProperties() {
        MedicalPersistenceProperties properties = new MedicalPersistenceProperties();
        properties.setStore("redis");
        properties.getRedis().setKeyPrefix("medical-runtime-it:");
        properties.getRedis().setConversationTtl(Duration.ofMinutes(30));
        properties.getRedis().setProfileTtl(Duration.ofMinutes(30));
        return properties;
    }

    private static final class RecordingChatModel implements ChatModel {

        private final List<List<String>> capturedUserMessages = new ArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            List<String> userMessages = prompt.getInstructions().stream()
                    .filter(UserMessage.class::isInstance)
                    .map(Message::getText)
                    .toList();
            capturedUserMessages.add(userMessages);
            return new ChatResponse(List.of(
                    new Generation(new AssistantMessage("ack-" + capturedUserMessages.size()))
            ));
        }

        private List<List<String>> capturedUserMessages() {
            return capturedUserMessages;
        }
    }

    private static final class NoopUserProfileFactExtractor implements UserProfileFactExtractor {

        @Override
        public Map<String, Object> extractFacts(List<Message> messages, Map<String, Object> existingProfile) {
            return Map.of();
        }
    }
}
