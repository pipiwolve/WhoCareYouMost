package com.tay.medicalagent.controller.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tay.medicalagent.app.MedicalApp;
import com.tay.medicalagent.session.ConsultationSession;
import com.tay.medicalagent.session.ConsultationSessionService;
import com.tay.medicalagent.web.support.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserProfileController.class)
@Import({ControllerTestConfig.class, GlobalExceptionHandler.class})
class UserProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConsultationSessionService consultationSessionService;

    @MockitoBean
    private MedicalApp medicalApp;

    @Test
    void initializeProfileShouldCreateUserAndSession() throws Exception {
        when(medicalApp.createThreadId()).thenReturn("thread-profile-1");

        MvcResult mvcResult = mockMvc.perform(post("/v1/users/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "张三",
                                  "age": 32,
                                  "gender": "MALE",
                                  "avatarId": "memoji_03"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("OK"))
                .andExpect(jsonPath("$.data.welcomeMessage").value("你好，张三。我是你的医疗向导，请问今天哪里不舒服？"))
                .andReturn();

        ArgumentCaptor<String> userIdCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> profileCaptor = ArgumentCaptor.forClass(Map.class);
        verify(medicalApp).saveUserProfileMemory(userIdCaptor.capture(), profileCaptor.capture());

        String userId = userIdCaptor.getValue();
        assertTrue(userId.startsWith("usr_"));
        assertEquals("张三", profileCaptor.getValue().get("name"));
        assertEquals(32, profileCaptor.getValue().get("age"));
        assertEquals("男", profileCaptor.getValue().get("gender"));
        assertEquals("memoji_03", profileCaptor.getValue().get("avatarId"));

        JsonNode responseBody = objectMapper.readTree(mvcResult.getResponse().getContentAsString());
        String sessionId = responseBody.path("data").path("sessionId").asText();
        ConsultationSession consultationSession = consultationSessionService.getRequiredSession(sessionId);
        assertEquals("thread-profile-1", consultationSession.threadId());
        assertEquals(userId, consultationSession.userId());
    }

    @Test
    void initializeProfileShouldReuseProvidedUserId() throws Exception {
        when(medicalApp.createThreadId()).thenReturn("thread-profile-2");

        mockMvc.perform(post("/v1/users/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "usr_existing",
                                  "name": "李四",
                                  "age": 28,
                                  "gender": "FEMALE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("OK"))
                .andExpect(jsonPath("$.data.userId").value("usr_existing"))
                .andExpect(jsonPath("$.data.sessionId").value(org.hamcrest.Matchers.startsWith("sess_")));

        verify(medicalApp).saveUserProfileMemory(eq("usr_existing"), eq(Map.of(
                "name", "李四",
                "age", 28,
                "gender", "女"
        )));
    }
}
