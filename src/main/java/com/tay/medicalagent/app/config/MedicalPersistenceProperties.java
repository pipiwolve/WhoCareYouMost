package com.tay.medicalagent.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

@Component
@ConfigurationProperties(prefix = "medical.persistence")
public class MedicalPersistenceProperties {

    private String store = PersistenceStore.MEMORY.value;

    private final Redis redis = new Redis();

    public String getStore() {
        return store;
    }

    public void setStore(String store) {
        this.store = store;
    }

    public PersistenceStore resolvedStore() {
        String candidate = store == null ? "" : store.trim().toLowerCase(Locale.ROOT);
        for (PersistenceStore persistenceStore : PersistenceStore.values()) {
            if (persistenceStore.value.equals(candidate)) {
                return persistenceStore;
            }
        }
        return PersistenceStore.MEMORY;
    }

    public boolean usesRedis() {
        return resolvedStore() == PersistenceStore.REDIS;
    }

    public Redis getRedis() {
        return redis;
    }

    public enum PersistenceStore {
        MEMORY("memory"),
        REDIS("redis");

        private final String value;

        PersistenceStore(String value) {
            this.value = value;
        }
    }

    public static class Redis {

        private String keyPrefix = "medical-agent:";

        private Duration sessionTtl = Duration.ofHours(12);

        private Duration conversationTtl = Duration.ofHours(72);

        private Duration profileTtl = Duration.ofDays(30);

        private Duration snapshotTtl = Duration.ofMinutes(30);

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public Duration getSessionTtl() {
            return sessionTtl;
        }

        public void setSessionTtl(Duration sessionTtl) {
            this.sessionTtl = sessionTtl;
        }

        public Duration getConversationTtl() {
            return conversationTtl;
        }

        public void setConversationTtl(Duration conversationTtl) {
            this.conversationTtl = conversationTtl;
        }

        public Duration getProfileTtl() {
            return profileTtl;
        }

        public void setProfileTtl(Duration profileTtl) {
            this.profileTtl = profileTtl;
        }

        public Duration getSnapshotTtl() {
            return snapshotTtl;
        }

        public void setSnapshotTtl(Duration snapshotTtl) {
            this.snapshotTtl = snapshotTtl;
        }
    }
}
