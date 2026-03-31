package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalHospitalPlanningSummary;
import com.tay.medicalagent.app.report.MedicalReportSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryMedicalReportSnapshotRepositoryTest {

    @Test
    void shouldExpireSnapshotAfterTtl() {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-31T00:00:00Z"));
        MedicalReportSnapshotProperties properties = properties();
        properties.setTtl(Duration.ofSeconds(2));
        properties.setCleanupInterval(Duration.ofSeconds(1));

        InMemoryMedicalReportSnapshotRepository repository = new InMemoryMedicalReportSnapshotRepository(properties, clock);
        repository.save(snapshot("sess_1"));

        assertTrue(repository.findBySessionId("sess_1").isPresent());

        clock.plus(Duration.ofSeconds(3));

        assertFalse(repository.findBySessionId("sess_1").isPresent());
    }

    @Test
    void shouldEvictOldestAccessedSnapshotWhenCapacityExceeded() {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-31T00:00:00Z"));
        MedicalReportSnapshotProperties properties = properties();
        properties.setMaxSessions(2);
        properties.setCleanupInterval(Duration.ofSeconds(1));

        InMemoryMedicalReportSnapshotRepository repository = new InMemoryMedicalReportSnapshotRepository(properties, clock);
        repository.save(snapshot("sess_1"));
        clock.plus(Duration.ofSeconds(1));
        repository.save(snapshot("sess_2"));
        clock.plus(Duration.ofSeconds(1));
        repository.findBySessionId("sess_1");
        clock.plus(Duration.ofSeconds(1));
        repository.save(snapshot("sess_3"));

        assertTrue(repository.findBySessionId("sess_1").isPresent());
        assertFalse(repository.findBySessionId("sess_2").isPresent());
        assertTrue(repository.findBySessionId("sess_3").isPresent());
        assertEquals(2, repository.size());
    }

    private MedicalReportSnapshotProperties properties() {
        MedicalReportSnapshotProperties properties = new MedicalReportSnapshotProperties();
        properties.setTtl(Duration.ofMinutes(30));
        properties.setCleanupInterval(Duration.ofMinutes(5));
        properties.setStaleLockTtl(Duration.ofSeconds(60));
        properties.setMaxSessions(500);
        return properties;
    }

    private MedicalReportSnapshot snapshot(String sessionId) {
        return new MedicalReportSnapshot(
                sessionId,
                "thread",
                "user",
                Instant.parse("2026-03-31T00:00:00Z"),
                "conversation",
                "profile",
                "location",
                new MedicalDiagnosisReport(
                        "报告",
                        true,
                        "CONFIRMED",
                        "中风险",
                        "胸痛",
                        "建议尽快线下评估",
                        "",
                        List.of("胸痛"),
                        List.of("尽快就医"),
                        List.of("胸痛持续加重"),
                        "建议尽快线下评估"
                ),
                MedicalHospitalPlanningSummary.empty()
        );
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void plus(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
