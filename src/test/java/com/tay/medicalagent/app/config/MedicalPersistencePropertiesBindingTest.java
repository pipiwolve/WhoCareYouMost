package com.tay.medicalagent.app.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MedicalPersistencePropertiesBindingTest {

    @Test
    void shouldDefaultToMemoryStore() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of()));

        MedicalPersistenceProperties properties = binder
                .bind("medical.persistence", Bindable.of(MedicalPersistenceProperties.class))
                .orElseGet(MedicalPersistenceProperties::new);

        assertEquals(MedicalPersistenceProperties.PersistenceStore.MEMORY, properties.resolvedStore());
        assertFalse(properties.usesRedis());
        assertEquals("medical-agent:", properties.getRedis().getKeyPrefix());
        assertEquals(Duration.ofHours(12), properties.getRedis().getSessionTtl());
    }

    @Test
    void shouldBindRedisStoreAndTtls() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "medical.persistence.store", "redis",
                "medical.persistence.redis.key-prefix", "nightly:",
                "medical.persistence.redis.session-ttl", "6h",
                "medical.persistence.redis.conversation-ttl", "48h",
                "medical.persistence.redis.profile-ttl", "360h",
                "medical.persistence.redis.snapshot-ttl", "45m"
        )));

        MedicalPersistenceProperties properties = binder
                .bind("medical.persistence", Bindable.of(MedicalPersistenceProperties.class))
                .orElseThrow(() -> new IllegalStateException("medical.persistence binding failed"));

        assertTrue(properties.usesRedis());
        assertEquals(MedicalPersistenceProperties.PersistenceStore.REDIS, properties.resolvedStore());
        assertEquals("nightly:", properties.getRedis().getKeyPrefix());
        assertEquals(Duration.ofHours(6), properties.getRedis().getSessionTtl());
        assertEquals(Duration.ofHours(48), properties.getRedis().getConversationTtl());
        assertEquals(Duration.ofHours(360), properties.getRedis().getProfileTtl());
        assertEquals(Duration.ofMinutes(45), properties.getRedis().getSnapshotTtl());
    }
}
