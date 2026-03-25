package com.tay.medicalagent.app.config;

import com.alibaba.cloud.ai.graph.store.stores.MemoryStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
/**
 * Agent 运行所需的基础存储配置。
 * <p>
 * 当前用户长期画像默认使用内存态 {@link MemoryStore}，后续可替换为持久化实现。
 */
public class MedicalAgentStoreConfiguration {

    /**
     * 用户长期画像存储。
     *
     * @return 内存态用户画像存储
     */
    @Bean
    public MemoryStore userProfileMemoryStore() {
        return new MemoryStore();
    }
}
