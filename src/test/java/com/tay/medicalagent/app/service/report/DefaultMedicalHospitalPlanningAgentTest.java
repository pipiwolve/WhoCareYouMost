package com.tay.medicalagent.app.service.report;

import com.tay.medicalagent.app.report.MedicalDiagnosisReport;
import com.tay.medicalagent.app.report.MedicalHospitalPlanningSummary;
import com.tay.medicalagent.app.report.MedicalPlanningIntent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultMedicalHospitalPlanningAgentTest {

    @Test
    void shouldBuildPromptWithLongitudeLatitudeOrderForMcpTools() {
        DefaultMedicalHospitalPlanningAgent agent = new DefaultMedicalHospitalPlanningAgent(
                null,
                null,
                null,
                null
        );

        String prompt = agent.buildPrompt(
                31.2304,
                121.4737,
                new MedicalDiagnosisReport(
                        "报告",
                        true,
                        "ok",
                        "HIGH",
                        "胸闷心慌",
                        "建议尽快就医",
                        "",
                        List.of("basis"),
                        List.of("next"),
                        List.of("escalation"),
                        "reply"
                ),
                new MedicalPlanningIntent(
                        true,
                        true,
                        "trigger",
                        "emergency",
                        "急诊",
                        "090101|090100|090102",
                        10000,
                        3,
                        true
                ),
                new BeanOutputConverter<>(MedicalHospitalPlanningSummary.class)
        );

        assertTrue(prompt.contains("用户坐标 location：121.4737,31.2304"));
        assertTrue(prompt.contains("例如 `121.4737,31.2304`"));
        assertTrue(prompt.contains("绝不能写成“纬度,经度”"));
        assertFalse(prompt.contains("用户坐标 location：31.2304,121.4737"));
    }
}
