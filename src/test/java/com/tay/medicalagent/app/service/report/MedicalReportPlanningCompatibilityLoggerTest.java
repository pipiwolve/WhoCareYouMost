package com.tay.medicalagent.app.service.report;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MedicalReportPlanningCompatibilityLoggerTest {

    @Test
    void shouldLogResolvedPlanningModeAtStartup() {
        MedicalReportPlanningProperties properties = new MedicalReportPlanningProperties();
        properties.setMode("agentic");
        properties.getMcp().setServerName("mcp-server/amap-maps");

        MockEnvironment environment = new MockEnvironment();
        MedicalReportPlanningCompatibilityLogger compatibilityLogger =
                new MedicalReportPlanningCompatibilityLogger(environment, properties);

        Logger logger = (Logger) LoggerFactory.getLogger(MedicalReportPlanningCompatibilityLogger.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            compatibilityLogger.logDeprecatedFlagsIfNeeded();
        }
        finally {
            logger.detachAppender(appender);
        }

        assertTrue(appender.list.stream().anyMatch(event ->
                event.getFormattedMessage().contains("Medical report planning mode resolved.")
                        && event.getFormattedMessage().contains("configuredMode=agentic")
                        && event.getFormattedMessage().contains("resolvedMode=agentic")
        ));
    }
}
