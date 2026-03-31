# Medical Agent 前端实际调用接口说明 (Final V3)

本文档整理了目前前端**实际正在调用**的所有 API 接口及其数据结构。请后端严格按照以下结构返回数据，以确保前端状态机和 UI 组件（路线动画、报告预览等）正常渲染。

---

## 统一约定

前端所有非流式和非下载请求，均期望后端返回以下外壳结构：

```json
{
    "code": 200,          // 整数，200 为成功
    "message": "success", // 字符串提示信息
    "data": { ... }       // 具体业务数据对象（详见下文）
}
```

---

## 1. 建立用户档案 (Init Profile)

用户进入系统首先调用此接口建立会话，以获取后续所有请求所需的 `sessionId`。

**请求路径**: `POST /api/v1/users/profile`
**请求格式**: `application/json`

**请求参数 (Request Body)**:
```json
{
    "name": "张三",
    "age": 28,
    "gender": "FEMALE"  // 枚举: "MALE" | "FEMALE" | "OTHER"
}
```

**响应数据 (Response Data)**:
```json
{
    "userId": "user_12345",
    "sessionId": "session_67890",
    "welcomeMessage": "你好，张三。我是你的医疗向导，请问今天哪里不舒服？"
}
```

---

## 2. 医疗对话流式接口 (Chat Completions)

处理多轮对话的问诊逻辑。每次前端发消息均调用此接口。后端采用 **SSE (Server-Sent Events)** 格式返回。

**请求路径**: `POST /api/v1/chat/completions`
**请求头**: `Accept: text/event-stream`
**请求格式**: `application/json`

**请求参数 (Request Body)**:
```json
{
    "sessionId": "session_67890",
    "message": "我这两天有点发烧和头痛"
}
```

**响应数据 (SSE Event Stream)**:
后端需以纯文本流式返回，事件通过跨行 `\n\n` 分隔。事件类型及 `data` 格式如下：

1. **`event: meta`**: 返回 sessionId 等元信息
2. **`event: chunk`**: 逐字逐句返回 Markdown 文本片段
3. **`event: done`**: 🏁 最后一帧，极其重要！携带整轮对话推断的结构化状态与临时快照。前端极其依赖 `done` 事件推进状态机。

**非常关键：`done` 事件的 payload 数据结构（无需裹在 ApiResponse 外壳中，直接给此对象即可）**：

```json
{
    "sessionId": "session_67890",
    "reply": "我这两天有点发烧和头痛... 建议多喝水...", // 大模型完整回复文本
    "structuredReply": {                               // (可选) 聊天窗口内结构化风险提示
        "riskLevel": "中",
        "summary": "可能为上呼吸道感染",
        "basis": ["发烧", "头痛"],
        "nextSteps": ["量体温", "多喝水"],
        "escalationSignals": ["高温不退", "剧烈呕吐"],
        "followUpQuestions": ["体温多少度？", "有没有咳嗽？"],
        "disclaimer": "本建议仅供参考..."
    },
    "reportPreview": {                                 // (极其重要) 临时报告快照 UI，当此字段存在时，前端会有条件地展示路线卡片或触发定位拦截
        "title": "临时面诊记录",
        "riskLevel": "中",
        "summary": "疑似流行性感冒",
        // 🚨【非常关键：无经纬度时的强制触发条件】
        // 当用户在对话中请求找医院，但后端发现系统里没有该用户的经纬度时，
        // 必须原封不动返回以下两个字段（严格匹配字符串），前端才会立刻弹窗并后台拉起 GPS 定位重试逻辑：
        "routesAvailable": false,
        "routeStatusMessage": "未上传经纬度，无法进行就近医院规划",
        // 如果有了位置上报后的新对话轮次，后端也可在这里直接返回临时推荐医院。
        // 一旦此数组有数据，前端聊天区和侧边栏都会显示“路线规划卡片”。
        "hospitals": [] 
    },
    "reportAvailable": true,                           // 为 true 时，前端点亮侧边栏“生成报告”按钮
    "reportReason": "已获取足够的基础病症信息",
    "reportGenerated": false,                          
    "report": null,                                    // 聊天阶段保持为 null
    "ragApplied": true,
    "sources": [],
    "reportTriggerLevel": "recommended",               // (可选) "urgent"| "recommended"| "suggested"，影响按钮颜色
    "reportActionText": "生成感冒分析报告"               // (可选) 后端动态下发前端按钮的文案
}
```

---

## 3. 静默位置上报 (Update Location)

前端在对话进行到第一句话结束（进入 `symptom` 状态）后，会在后台无感弹出定位授权。获取经纬度后立即调用此接口发给后端，以便后端提前为生成带医院路径的报告做准备 (调用 MCP)。

**请求路径**: `POST /api/v1/reports/{sessionId}/location`
**请求格式**: `application/json`

**请求参数 (Request Body)**:
```json
{
    "latitude": 39.9042,
    "longitude": 116.4074,
    "consentGranted": true // 此处固定为 true，表示用户已授权浏览器定位
}
```

**响应数据 (Response Data)**:
```json
null // 或者 {}，只要 code=200 即可
```

---

## 4. 获取终版医疗报告与医院路线 (Get Final Report)

当用户点击“生成报告”按钮时前端拉取最终结果。

**请求路径**: `GET /api/v1/reports/{sessionId}`
**请求头**: `Accept: application/json`

**响应数据 (Response Data)**:
```json
{
    "ready": true,
    "reason": "报告生成完毕",
    "report": {
        "title": "面部痤疮及内分泌分析报告",
        "riskLevel": "低",
        "summary": "典型的青春期寻常痤疮...",
        "assessment": "根据多轮问诊，你具备典型的...",
        "basis": ["长期熬夜导致内分泌紊乱", "饮食上喜辛辣"],
        "recommendations": ["调整作息", "局部涂抹药膏"],
        "redFlags": ["若脸部出现大面积囊肿...", "若伴随剧烈红肿发热..."],
        "disclaimer": "AI 面诊结果不可替代专业执业医师诊断。",
        
        "routesAvailable": true, // false 时前端会展示兜底文案
        "routeStatusMessage": "周边无匹配的三甲专科医院，已为您放宽搜索范围", 
        
        "hospitals": [            // 核心的医院和路线数组
            {
                "name": "北京协和医院",
                "address": "东城区帅府园1号",
                "tier3a": true,   // 是否三甲，为 true 时前端 UI 会打上闪光小金标
                "distanceMeters": 1500,
                "routes": [
                    {
                        "mode": "DRIVE",   // 支持 "WALK" | "DRIVE" | "TRANSIT"
                        "distanceMeters": 1600,
                        "durationMinutes": 15,
                        "summary": "途径王府井大街，路况较好",
                        "steps": [
                            "从王府井大街出发",
                            "沿东单北大街行驶",
                            "到达北京协和医院"
                        ]
                    },
                    {
                        "mode": "WALK",
                        "distanceMeters": 1200,
                        "durationMinutes": 20,
                        "summary": "步行距离较短",
                        "steps": [
                            "步行200米前往地铁站",
                            "出站后步行800米到达医院"
                        ]
                    }
                ]
            }
        ]
    }
}
```

---

补充说明：
- `routes[].steps` 为新增字段，按出发到到达顺序返回路线分步文本。
- 步行/驾车通常返回逐步导航文案；公交/地铁通常返回“步行 -> 乘车 -> 步行/换乘”的高层步骤。

## 5. 下载 PDF 报告文件 (Download PDF)

用于触发原生浏览器的 PDF 下载。

**请求路径**: `GET /api/v1/reports/{sessionId}/pdf`
**请求头**: `Accept: application/pdf`

**响应行为**:
后端无需包裹 JSON，直接返回原生的二进制 PDF 流 (`Content-Type: application/pdf`) 即可。前端会自动使用 Blob 接管并触发浏览器下载行为。如果报错，后端返回常规带有 `code` 和 `message` 的 JSON 格式的错误体，前端会弹窗提示。
