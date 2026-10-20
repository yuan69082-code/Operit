# 引擎窗口边界

EngineWindow 是平台连接与投递账本，不是主体运行时。它不持有 SubjectState，不调用模型、生成最终表达或控制引擎进程。

vendor 下两个文件来自 yuan69082-code/vio-live-frontend 提交 1eaeef29c6e09156fa0350392f0acb95f9a17c96：

- backend/src/core/canonical-json.js
- backend/src/integrations/continuity-engine/http-continuity-integration-transport.js

迁入时仅调整 CommonJS 导入、导出与本项目错误类型。旧 HTTP 传输提供原协议的投递、结果回传和查询原语，但当前生产 PendingEngineAdapter 只开放显式健康检查，不调用写入原语。

## 为什么等待协议

截至核对引擎 c2c6beb，旧 HTTP 初始化仍校验固定 first-round 测试身份；不能用任意正式主体替换测试字段。健康检查不验证令牌或主体。正式接入必须由引擎侧给出可验证绑定和事件协议后，再替换 PendingEngineAdapter。用户配置无法绕过这个限制。

operit-window-event/v1 只用于 Android 与本仓库共享服务之间。禁止直接把它 POST 到引擎。事件 ID 和规范化哈希标识不可变事实；关联 ID 隔离解绑重绑，不能将旧事件转给另一个主体。

## 后续适配器

adapterFactory 是模块装配接口，不是 HTTP 可配置的执行入口。适配器提供 inspect、canDeliver、deliver、query。只有协议和主体绑定验证通过后，canDeliver 才能返回 true。deliver/query 必须校验真实引擎回执后转换为 eventId、eventHash、subjectId、status 及 finalExpression。completed 的 finalExpression 可以是 null，表示没有对外表达；不能编造“我在等待”。

unknown 与 waiting 仅查询，不重新 deliver。关闭与撤权会阻止新投递及查询，不声称撤回已经被远端接收的操作。模型和工具能力另需明确的逐项授权与版本适配，本批不执行旧 capability 请求。
