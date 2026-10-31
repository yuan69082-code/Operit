# Vertex AI 模型提供商

在模型配置中选择 `Vertex AI (API Key)`，填写 Vertex AI Express API Key 和模型 ID。默认地址为 `https://aiplatform.googleapis.com`，默认模型 ID 为 `gemini-2.5-flash`，可改为账户可用的 Gemini 模型。

普通请求使用 `/v1/publishers/google/models/{model}:generateContent`，流式请求使用 `:streamGenerateContent?alt=sse`。密钥放在 `x-goog-api-key` 请求头。连接测试发送一条短消息到同一推理接口。

Express API 不提供模型列表接口，因此请手动填写模型 ID。点击获取模型列表会显示说明，不会向 Gemini Developer API 发送请求。

此提供商接入 Express API Key 模式，不处理标准 Vertex AI 的项目、地区、服务账号 JSON 或 OAuth Token 刷新。原有 Google 和 Gemini 通用配置保持不变；新增配置使用独立的 `VERTEX_AI` 标识。思考配置、工具调用、多模态输入和 Google Search 使用现有 Gemini 请求及响应处理。

实现依据：[Google Cloud Express REST API](https://docs.cloud.google.com/vertex-ai/generative-ai/docs/reference/express-mode/api-reference)。

新增 `VertexAiEndpointTest` 覆盖请求路径、流式 SSE、完整模型名与错误输入。遵循仓库 AGENTS.md，本次未执行编译、构建或测试命令，也未使用真实 API Key 实测。
