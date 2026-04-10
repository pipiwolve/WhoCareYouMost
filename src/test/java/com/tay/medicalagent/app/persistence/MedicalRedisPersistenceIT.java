package com.tay.medicalagent.app.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.tay.medicalagent.app.config.MedicalPersistenceProperties;
import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalHospitalPlanningSummary;
import com.tay.medicalagent.app.report.MedicalReportSnapshot;
import com.tay.medicalagent.app.repository.store.StoreBackedUserProfileRepository;
import com.tay.medicalagent.app.repository.redis.RedisThreadConversationRepository;
import com.tay.medicalagent.app.service.report.RedisMedicalReportSnapshotRepository;
import com.tay.medicalagent.app.service.runtime.MedicalRedisGraphStore;
import com.tay.medicalagent.session.ConsultationSession;
import com.tay.medicalagent.session.RedisConsultationSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class MedicalRedisPersistenceIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void shouldRoundTripConsultationSessions() {
        RedisConsultationSessionRepository repository = new RedisConsultationSessionRepository(
                redisTemplate(),
                objectMapper,
                persistenceProperties()
        );
        ConsultationSession session = new ConsultationSession(
                "sess_" + UUID.randomUUID(),
                "thread_redis",
                "user_redis",
                Instant.parse("2026-04-01T00:00:00Z"),
                Instant.parse("2026-04-01T00:00:10Z"),
                31.23,
                121.47,
                Instant.parse("2026-04-01T00:00:20Z")
        );

        repository.save(session);

        ConsultationSession loaded = repository.findBySessionId(session.sessionId()).orElseThrow();
        assertEquals(session.sessionId(), loaded.sessionId());
        assertEquals(session.threadId(), loaded.threadId());
        assertEquals(session.latitude(), loaded.latitude());
    }

    @Test
    void shouldRoundTripThreadConversationAndClear() {
        RedisThreadConversationRepository repository = new RedisThreadConversationRepository(
                redisTemplate(),
                objectMapper,
                persistenceProperties()
        );

        repository.append("thread_a", new UserMessage("我胸闷"));
        repository.append("thread_a", AssistantMessage.builder().content("建议尽快线下评估。").build());

        List<Message> messages = repository.findByThreadId("thread_a");
        assertEquals(2, messages.size());
        assertInstanceOf(UserMessage.class, messages.get(0));
        assertInstanceOf(AssistantMessage.class, messages.get(1));
        assertEquals("建议尽快线下评估。", messages.get(1).getText());

        repository.clear();

        assertTrue(repository.findByThreadId("thread_a").isEmpty());
    }

    @Test
    void shouldRoundTripUserProfileAndSnapshot() {
        MedicalPersistenceProperties properties = persistenceProperties();
        StoreBackedUserProfileRepository userProfileRepository = new StoreBackedUserProfileRepository(
                new MedicalRedisGraphStore(redisTemplate(), objectMapper, properties)
        );
        RedisMedicalReportSnapshotRepository snapshotRepository = new RedisMedicalReportSnapshotRepository(
                redisTemplate(),
                objectMapper,
                properties
        );

        userProfileRepository.save("user_profile", Map.of("name", "张三", "age", 28, "allergies", List.of("青霉素")));
        Map<String, Object> profile = userProfileRepository.findByUserId("user_profile").orElseThrow();
        assertEquals("张三", profile.get("name"));

        MedicalReportSnapshot snapshot = new MedicalReportSnapshot(
                "sess_snapshot",
                "thread_snapshot",
                "user_profile",
                Instant.parse("2026-04-01T00:00:00Z"),
                "conversation-fp",
                "profile-fp",
                "location-fp",
                new MedicalDiagnosisReport(
                        "张三的医疗诊断报告",
                        true,
                        "CONFIRMED",
                        "中风险",
                        "胸闷",
                        "建议尽快线下评估",
                        "",
                        List.of("胸闷"),
                        List.of("尽快就医"),
                        List.of("胸痛加重"),
                        "建议尽快线下评估"
                ),
                MedicalHospitalPlanningSummary.empty()
        );

        snapshotRepository.save(snapshot);
        MedicalReportSnapshot loaded = snapshotRepository.findBySessionId("sess_snapshot").orElseThrow();
        assertEquals("张三的医疗诊断报告", loaded.report().reportTitle());

        snapshotRepository.deleteBySessionId("sess_snapshot");
        assertFalse(snapshotRepository.findBySessionId("sess_snapshot").isPresent());
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

    private MedicalPersistenceProperties persistenceProperties() {
        MedicalPersistenceProperties properties = new MedicalPersistenceProperties();
        properties.setStore("redis");
        properties.getRedis().setKeyPrefix("medical-it:" + UUID.randomUUID() + ":");
        properties.getRedis().setSessionTtl(Duration.ofMinutes(30));
        properties.getRedis().setConversationTtl(Duration.ofMinutes(30));
        properties.getRedis().setProfileTtl(Duration.ofMinutes(30));
        properties.getRedis().setSnapshotTtl(Duration.ofMinutes(30));
        return properties;
    }
}
