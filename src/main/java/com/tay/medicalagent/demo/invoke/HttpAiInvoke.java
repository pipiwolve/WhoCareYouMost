package com.tay.medicalagent.demo.invoke;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.ContentType;
import cn.hutool.http.Header;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;

import java.util.List;
import java.util.Map;

public class HttpAiInvoke {

    private static final String GENERATION_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation";

    public static void main(String[] args) {
        String apiKey = TestApiKey.API_KEY;
        if (StrUtil.isBlank(apiKey)) {
            throw new IllegalStateException("请先设置环境变量 DASHSCOPE_API_KEY");
        }

        Map<String, Object> requestBody = Map.of(
                "model", "qwen-plus",
                "input", Map.of(
                        "messages", List.of(
                                Map.of(
                                        "role", "system",
                                        "content", "You are a helpful assistant."
                                ),
                                Map.of(
                                        "role", "user",
                                        "content", "你是谁？"
                                )
                        )
                ),
                "parameters", Map.of(
                        "result_format", "message"
                )
        );

        try (HttpResponse response = HttpRequest.post(GENERATION_URL)
                .header(Header.AUTHORIZATION, "Bearer " + apiKey)
                .header(Header.CONTENT_TYPE, ContentType.JSON.getValue())
                .body(JSONUtil.toJsonStr(requestBody))
                .execute()) {
            System.out.println(response.body());
        }
    }
}
