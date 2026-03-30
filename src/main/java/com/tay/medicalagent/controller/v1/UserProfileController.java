package com.tay.medicalagent.controller.v1;

import com.tay.medicalagent.app.MedicalApp;
import com.tay.medicalagent.session.ConsultationSession;
import com.tay.medicalagent.session.ConsultationSessionService;
import com.tay.medicalagent.web.dto.ProfileInitRequest;
import com.tay.medicalagent.web.dto.ProfileInitResponse;
import com.tay.medicalagent.web.support.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/users")
/**
 * 用户问诊初始化接口。
 */
public class UserProfileController {

    private final MedicalApp medicalApp;
    private final ConsultationSessionService consultationSessionService;

    public UserProfileController(MedicalApp medicalApp, ConsultationSessionService consultationSessionService) {
        this.medicalApp = medicalApp;
        this.consultationSessionService = consultationSessionService;
    }

    @PostMapping("/profile")
    public ApiResponse<ProfileInitResponse> initializeProfile(@Valid @RequestBody ProfileInitRequest request) {
        String userId = normalizeOrCreateUserId(request.userId());

        Map<String, Object> profileUpdates = new LinkedHashMap<>();
        profileUpdates.put("name", request.name().trim());
        profileUpdates.put("age", request.age());
        profileUpdates.put("gender", request.gender().toInternalValue());
        if (request.avatarId() != null && !request.avatarId().isBlank()) {
            profileUpdates.put("avatarId", request.avatarId().trim());
        }

        medicalApp.saveUserProfileMemory(userId, profileUpdates);
        ConsultationSession consultationSession =
                consultationSessionService.createSession(userId, medicalApp.createThreadId());

        String welcomeMessage = "你好，%s。我是你的医疗向导，请问今天哪里不舒服？".formatted(request.name().trim());
        return ApiResponse.success(new ProfileInitResponse(userId, consultationSession.sessionId(), welcomeMessage));
    }

    private String normalizeOrCreateUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return "usr_" + UUID.randomUUID().toString().replace("-", "");
        }
        return userId.trim();
    }
}
