package com.tay.medicalagent.app.config;

import com.tay.medicalagent.app.service.report.MedicalReportBuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class MedicalReportBuildConfiguration {

    @Bean(name = "medicalReportBuildExecutor")
    public Executor medicalReportBuildExecutor(MedicalReportBuildProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(properties.getExecutor().getThreadNamePrefix());
        executor.setCorePoolSize(Math.max(1, properties.getExecutor().getCorePoolSize()));
        executor.setMaxPoolSize(Math.max(executor.getCorePoolSize(), properties.getExecutor().getMaxPoolSize()));
        executor.setQueueCapacity(Math.max(1, properties.getExecutor().getQueueCapacity()));
        executor.initialize();
        return executor;
    }
}
