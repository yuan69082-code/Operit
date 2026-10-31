# Vertex AI 模型提供商

在模型配置中选择 `Vertex AI (API Key)`，填写 Vertex AI Express API Key 和模型 ID。默认地址为 `https://aiplatform.googleapis.com`，默认模型 ID 为 `gemini-2.5-flash`，可改为账户可用的 Gemini 模型。

普通请求使用 `/v1/publishers/google/models/{model}:generateContent`，流式请求使用 `:streamGenerateContent?alt=sse`。密钥放在 `x-goog-api-key` 请求头。连接测试发送一条短消息到同一推理接口。

Express API 不提供模型列表接口，因此请手动填写模型 ID。点击获取模型列表会显示说明，不会向 Gemini Developer API 发送请求。

此提供商使用 API Key 认证，支持 Express 地址和显式指定项目、地区的 publisher 地址，不处理服务账号 JSON 或 OAuth Token 刷新。原有 Google 和 Gemini 通用配置保持不变；新增配置使用独立的 `VERTEX_AI` 标识。思考配置、工具调用、多模态输入和 Google Search 使用现有 Gemini 请求及响应处理。

## 显式指定模型地区

需要固定到全球端点时，可将 API 端点填写为 `https://aiplatform.googleapis.com/v1/projects/PROJECT_ID/locations/global`，将 `PROJECT_ID` 替换为实际项目 ID。也接受结尾带 `/publishers/google` 或 `/publishers/google/models` 的地址，以及 `v1beta1` 版本。程序追加模型和请求方法，保留用户指定的项目、地区和版本；不会自动更换地区或改写已有配置。

此模式仍通过 `x-goog-api-key` 使用绑定服务账号的授权密钥，项目和模型访问权限需由 Google 验证。普通未绑定服务账号的密钥不能视为等价凭据。参见 [Google 授权密钥说明](https://docs.cloud.google.com/docs/authentication/api-keys)。

诊断边界：Cloud Shell 成功而手机返回 `us-central1` 的 404，并不能单凭错误文本证明代理修改了请求。本修复解决客户端拒绝显式全球地址的限制；不代表已经在用户手机上验证成功。路径测试覆盖全球、区域、两种 API 版本、流式和非流式请求，以及不完整路径和查询参数拒绝。

实现依据：[Google Cloud Express REST API](https://docs.cloud.google.com/vertex-ai/generative-ai/docs/reference/express-mode/api-reference)。

新增 `VertexAiEndpointTest` 覆盖请求路径、流式 SSE、完整模型名与错误输入。遵循仓库 AGENTS.md，本次未执行编译、构建或测试命令，也未使用真实 API Key 实测。

## 思考配置切换修复

同一提供商内更换模型或端点时，若思考控制发生变化且旧选项不属于新控制，保存时初始化新控制。Vertex 内置 thinkingLevel 控制初始化为 LOW；同一控制下有效的用户选择保持不变。请求执行仍严格校验，不吞掉非法参数。

配置版本 5 只迁移使用原始内置规则的 Vertex 配置：残留数字预算迁移到 LOW，反向残留等级迁移到 1024。自定义规则和有效档位不变。新增 VertexThinkingSelectionTest 覆盖跨模型保存、请求构造和旧配置迁移。
