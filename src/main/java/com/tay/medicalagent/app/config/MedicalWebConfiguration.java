package com.tay.medicalagent.app.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.Executor;

@Configuration
@EnableConfigurationProperties(MedicalWebProperties.class)
/**
 * Web 层基础配置。
 * <p>
 * 提供前端联调所需的 CORS 规则与 SSE 异步执行器。
 */
public class MedicalWebConfiguration implements WebMvcConfigurer {

    private final MedicalWebProperties medicalWebProperties;

    public MedicalWebConfiguration(MedicalWebProperties medicalWebProperties) {
        this.medicalWebProperties = medicalWebProperties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/v1/**")
                .allowedOrigins(medicalWebProperties.getAllowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*");
    }

    @Bean(name = "medicalSseExecutor")
    public Executor medicalSseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("medical-sse-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.initialize();
        return executor;
    }
}
