package com.tay.medicalagent.support;

public final class LiveTestSupport {

    private LiveTestSupport() {
    }

    public static String requireDashScopeApiKey() {
        return requireValue("DASHSCOPE_API_KEY", "AI_DASHSCOPE_API_KEY");
    }

    public static String requireAmapApiKey() {
        return requireValue("amap.mcp.api-key", "AMAP_MAPS_API_KEY");
    }

    public static String requireValue(String... keys) {
        for (String key : keys) {
            String systemProperty = System.getProperty(key);
            if (systemProperty != null && !systemProperty.isBlank()) {
                return systemProperty.trim();
            }
            String environmentValue = System.getenv(key);
            if (environmentValue != null && !environmentValue.isBlank()) {
                return environmentValue.trim();
            }
        }
        throw new IllegalStateException("缺少 live 测试必需配置: " + String.join(" / ", keys));
    }
}
