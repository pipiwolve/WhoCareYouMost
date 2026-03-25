package com.tay.medicalagent.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/health")
/**
 * 应用健康检查接口。
 */
public class HealthController {

    /**
     * 健康检查端点。
     *
     * @return 固定字符串 {@code OK}
     */
    @GetMapping
    public String health() {
        return "OK";
    }
}
