package com.tay.medicalagent.controller.v1;

import com.tay.medicalagent.session.ConsultationSessionRepository;
import com.tay.medicalagent.session.ConsultationSessionService;
import com.tay.medicalagent.session.InMemoryConsultationSessionRepository;
import com.tay.medicalagent.web.support.MedicalApiViewMapper;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
class ControllerTestConfig {

    @Bean
    ConsultationSessionRepository consultationSessionRepository() {
        return new InMemoryConsultationSessionRepository();
    }

    @Bean
    ConsultationSessionService consultationSessionService(ConsultationSessionRepository consultationSessionRepository) {
        return new ConsultationSessionService(consultationSessionRepository);
    }

    @Bean
    MedicalApiViewMapper medicalApiViewMapper() {
        return new MedicalApiViewMapper();
    }
}
