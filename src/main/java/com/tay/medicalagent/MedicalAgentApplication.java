package com.tay.medicalagent;

import org.springframework.ai.vectorstore.elasticsearch.autoconfigure.ElasticsearchVectorStoreAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = ElasticsearchVectorStoreAutoConfiguration.class)
/**
 * Spring Boot 启动类。
 * <p>
 * 用于启动整个医疗问答应用，包括 Agent Runtime、RAG、Web 接口与示例配置。
 */
public class MedicalAgentApplication {

    /**
     * 应用启动入口。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(MedicalAgentApplication.class, args);
    }

}
