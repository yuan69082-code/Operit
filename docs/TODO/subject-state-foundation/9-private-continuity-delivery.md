# 私域连续性七项整合

本轮从已发布 +140（c67f626）继续，保留已合并 #53—#60、前六批、书房/后台结果互通、Gemini 请求与共享流异常隔离。所有实现归于 [PR #61](https://github.com/yuan69082-code/Operit/pull/61)，最终发布必须完成同提交双端检查。本文当前记录实现和验证边界，尚不代表已发布；最终结果追加在文末。

## 权威、材料与时序

唯一主体仍是 CharacterCard.id，不是模型、会话或订阅账号。InternalStateStore / CompanionStore / InternalStateHistory 承接原事务、版本、来源、增量归档；global 与 session 各自保持语义。正常聊天与后台从 CompanionContext / InnerStructureContext / CurrentRequestContext 读取，工具由 ToolExecutionManager 记录真实操作与回执。

本轮实际请求读取已有版本，既有工具续轮可读到刚保存的真实回执；完整主观候选仍在有效业务轮末采用，后续请求读取采用后的版本。没有为随记、页面、承接或反馈强制追加模型调用。

主体材料继续共用默认 2048、最大 16384 Token 的预算，完整模型窗口另核。本地可用分词器实际计量，远程估算保留计量类型，不能把字符数叫 Token。全文保存在原存储；分段、预览、预算未选入均不删除原文，不改变重要性或采用事实。

## 七项正常接线

### 1—2：活动随记与安静记录

EnhancedAIService 在首次回复和工具续轮用 ActivityNoteStream 采集显式 activity_note / waking_note，完整闭合段落及时写 ActivityNotes。保存点/回滚保持原文与撤回记录；未闭合段落不补造。普通聊天由 MessageProcessingDelegate 建立活动，后台由 CompanionRuntime 建立活动。工具回执从真实 ToolExecutionManager 保存，表达次数从 ChatHistoryManager 成功保存的最终消息计算；流式显示不算保存，已保存不等于已读。

活动按助手、稳定触发、活动 ID、来源窗口和时间归档。安静、处理中、取消、中断、失败、没有随记、明确不公开分别呈现；没有工具、没有表达或没有下一次安排也成立。安排只读取保存成功的计划。ActivityNotes.materials / 已有内部状态查询工具可为同助手后续请求取用随记和原始内容，不作为新的现实经历重复作用。

旧 companion_note 仍私密；新协议明确 activity_note / waking_note 可由园园在私域查看。ActivityNotesPanel、PrivateStateView 和 WebPrivateStateBridge 对原私密梦、梦理解、companion_note 以及工具回执保持同一可见范围，打开技术详情也不会放出旧正文。原始归档未删除。

入口为原唤醒记录/这段时间，以及私域里的梦醒随记。分页读取活动和片段，不把历史装进热快照。

### 3：XC 退出与日记整合

移除 XC 专用设置、仪表盘、绑定、自动请求、状态注入、同步和后台入口。旧配置在升级及恢复后保持停用；原历史仍可作为既有数据保留。通用 MCP、OB、本地记忆、内部状态和念头池没有整组删除，也不修改用户外部服务。

独立日记导航改到书房日记，继续使用 AssistantRoom.diaries、RoomDiary 中 DiaryEditor 与 companion_diary 工具，旧稿、作者、编辑和备份路径不复制、不清空。

### 4：摘要只整理过去

FunctionalPrompts、SummaryFidelityPrompts、SummaryReviewer、SegmentedSummary、ReviewedSummaryBatches 的中英文、草稿、审阅、滚动合并、自定义栏目与后续注入统一区分事实、讨论、建议、愿望、决定、已执行、取消和未知。原文有计划时保留来源，不删除所有“下一步”文字；未核实的旧摘要安排不升级为命令或权威状态。

可选窗口补充放在已有审阅之前，避免审阅完成后追加未经检查的计划。摘要不能直接创建安排、执行工具或改人格；后续主体仍可根据材料作决定。复用既有审阅次数，没有无限重写循环。规则及模拟测试不证明真实模型永不编造。

### 5：订阅渠道与真实限制

- ChatGPT/Codex：沿用 CodexAuthManager、OAuthClient、UsageClient、CodexProvider 及原设置。补取消、并发刷新、账号切换旧请求失效、退出和凭据备份边界，不建第二套 Codex 登录。
- Kimi Code：套餐凭据、取消/退出/重新连接接入现有 Provider 工厂及普通配置选择；复用兼容协议的正文、工具与续轮。服务未提供的精确余额标未知；权益限定编程用途，不冒称通用聊天会员。
- Grok：设备授权、取消/超时、刷新、退出、重登和账号失效隔离接入原 Provider。账号资格、可用范围和额外收费仍以服务授权结果为准；没有真实账号，未宣称免费或无限。
- Claude：官方第三方会员 OAuth 使用限制，未将按量 API 包装为订阅。界面给出具体限制与官方入口。
- Gemini：官方 Code Assist/CLI 第三方直接 OAuth 范围限制，未制造不可用登录按钮；普通 API 配置仍正常保留。
- GLM：官方 Coding Plan 对指定工具的范围限制，未将 Operit 通用 API Key 当套餐凭据。界面说明当前未获支持的缺口。

SubscriptionAccounts / SubscriptionProvider 复用模型配置、AIServiceFactory、现有流和工具结果配对，账号变化不改助手身份。合并刷新，区分 401、网络、限流；不悄悄改付费 API、账号或模型。额度读取失败不宣布聊天凭据失效。凭据设备加密、日志脱敏且不进入普通备份；退出不删角色状态。

仅参考 Kelivo 的协议，未复制 AGPL 客户端源码。参考快照不能证明账号可用。官方核对依据：
[Claude](https://code.claude.com/docs/en/legal-and-compliance)、
[Gemini CLI](https://geminicli.com/docs/resources/tos-privacy/)、
[GLM](https://docs.bigmodel.cn/cn/coding-plan/tool/others)、
[Kimi Code](https://www.kimi.com/help/kimi-code/membership-guide)、
[Grok](https://docs.x.ai/build/enterprise)。

正常 Provider 工厂、模拟 HTTP 首次正文/工具调用/配对结果续轮和退出旧请求隔离有测试；这不等于真实订阅实测。Claude、Gemini、GLM 的订阅开放是具体外部限制，六家不能统一标为“已可用”。

### 6：私域承接前六批

原生入口：私域 → 当前助手 → 内部状态。PrivateInnerStatePanel 统一当前、人格与自我、偏好、关系、牵挂与事件、相互影响、打算与行动、休息。旧入口复用同页，不新增底部导航，不创建状态副本。梦醒随记是私域独立区块，并在休息分区可查看。

Windows / WebChat 使用原手机连接的私域入口，经 WebPrivateStateBridge 读取手机同一权威数据；不在独立桌面聊天里再造主体数据库。群聊和其他助手不能借该接口读本助手私域。

内容包括初始参考、已采用人格/自我/价值/立场/局限、长期/短期/情境/配合偏好、有方向的关系、开放驱力/需要/欲望及感受类别、类别内不同事情、重点排序、念头、联合与成员、决定、执行、反馈、能力版本、休息与来源。详情按 ID 取完整真实回执和当前成员，不拿列表概要当全文，不用旧联合覆盖成员新状态。

原数值状态是同一状态体系的数值实现，保留真实演化规则、用户修改来源、紧凑趋势和变化点来源；不拿它替代完整人格/关系。已采用人格优先于初始人设，运行契约保留。旧关系编辑、旧桥接和其他助手通用写入口不能无声覆盖该主体。

休息/时间规则、求生欲特例和私人梦历史沿用第六批；新增 waking_note 可只有醒后印象或感觉，不要求先造完整梦。真实休息/醒后活动采集，页面打开不请求模型。随记、梦情节、外部事实、后来理解和实际分享保持不同来源；后续采用不把梦中行为当现实证据。没有周期造梦、闲置兜底思考或新常驻服务。

### 7：已有内容承接与全文保护

InnerStructure 保持 schema 1 的内容契约，以 storage=archive_v1、family_index=1 标记内在记录/候选的归档布局，读取进度存入既有历史数据库行和增量版本，热快照只保留元数据。旧内联布局的原值、全文、来源与版本按原事务日志迁移，不把旧数值变化自动造为心事。念头也复用既有归档，旧文件保留为恢复来源。

私域“已有内容承接 · 候选全文与修订”提供文件、粘贴、直接填写；正常模型候选也能使用 established_import。人格、立场、偏好、关系等均有实际原文与来源、已有确定内容声明两条路径。声明记录实际提交时刻、主体、类别、候选 ID、可选形成时间，不冒称找到旧聊天。没有旧原文不拒绝合法声明。

用户首次主动开启 48 小时，助手/模型/窗口不重新计时。状态元数据和设备不备份计时回执保留原开始、截止与时钟高水位；旧备份不能把已开始窗口重置。截止限制新增/改写该来源；及时提交且未改动的候选仍可后续采用。正常新变化不限于承接期，不为赶截止自动采用。

InternalStateStore 的 submitEstablishedContent / withdrawCandidate、EstablishedContent / InnerReadProgress 统一查看全文、编辑、撤回和采用：
- 所有未采用候选可修订或撤回；修订产生新版本/哈希，旧已读资格失效。
- 采用必须绑定正常后续请求实际取得的该版完整内容；页面查看、同轮提出、预览或缺少片段不授予资格。
- 完整原文、条件、例外、对象和矛盾不被标题或模型短句替换。模型建议是另一候选。
- 已采用版本只能从它提出新修改候选，保留原版本和前后关系，不能退成草稿绕过。
- 去掉每轮 8、候选 32、当前 128 的业务上限，没有换成另一固定总条数；页大小仅是处理方式。
- 长文按准确哈希和连续片段实际发送，响应后登记读取进度；候选与已采用内容都公平轮转，不让后排永远不可读。
- 导入按项保存并用稳定内容标识续接；重复导入不重复创建，已采用内容的进一步改变仍走候选版本流程。

## 引擎与 Operit 的对应

引擎核对 0567ba4 及三份用户指定增补文档，均只读；没有把独立候选或规划冒称原库已合入代码。

1. open_needs / open_affect：广义、细分、对象需要和持续感受，对应 InnerStructure 与数值状态；开放类别不压成封闭分数。求生欲不随时间变化、可无理由归零但不停止执行，按用户明确规则适配。
2. dynamic_mind / subject_growth：已采用人格、自我、价值与发展依据，对应既有候选/采用、准确版本与来源。新增已有确定内容声明来源由本轮用户要求引入，不伪造历史体验。
3. person_relations：对象身份与方向认识，对应 relation 内在记录及已有对象索引，旧称谓不是双方共同事实。
4. affect_union：成员、含义、真实来源与版本，对应 InnerInteractions 原子采用及成员独立发展；不照搬固定心理系数或时间投影，不循环放大。
5. intention_execution / software_organ：决定、尝试、真实回执、主观理解，对应 SubjectActions、SoftwareCapabilityArtifacts 和原执行器。本地已有可构建/调整/运行能力的边界保留，普通工具不一概宣称成长或任意程序构建。
6. 时间、休息、梦：对应 SubjectRestDreams、原时间锚点/恢复和正常私域输入；梦内容与现实证据不同。自由醒后随记、公开范围和无需完整梦前置为本轮适配。
7. 记忆/资料仍用本地、OB/MCP、书房和既有来源查询；本轮没有移植 Python 运行时、另建记忆库或外接第二权威。

前六批实际正常入口、持久化与后续读取没有被新页面替换，详细契约仍见 1—8 号文档。本轮的新版本、条数与来源规则以上文为准，旧文档中的 8/32/128、只接受实际旧原文及旧严格输出顺序属于历史契约。

## 11 月 10 日崩溃收尾

七项实现接线后读取 operit_log_20261110_053119.txt。确认的 last-crash：+136，2026-11-10 05:29:40，InternalStateReportStream.finish:108 / EnhancedAIService:1293，LATE_REPORT。日志没有失败轮原始输出，不能断定具体哪个标记或模型造成；重启后的模型名和旧进程退出原因不据此归类。

保留 +140 共享流隔离。在正常最终子回复内，允许完整独立状态报告位于正文/控制输出之前或之后；中间工具回合不提前采用。报告不得嵌进随记/消息/工具参数/回执/推理或代码示例。JSON、重复/截断/大小、主体、来源、版本与采用校验继续有效。错误锁存在本轮，保留同一分片已确认公开前缀，拒绝报告并保留旧状态；记录 rejected / state_adopted=false，而非假成功，不重做消息或工具。

EnhancedReportIsolationTest 使用实际 EnhancedAIService.sendMessage、共享流、MessageProcessingDelegate 消费边界、工具执行和聊天保存；只替换模型为受控输出，以及无关视觉提示准备。覆盖正文/控制输出在前、首次坏报告、工具续轮坏报告、后台中断完整随记保留和下一条输入仍可处理。模拟范围不冒称手机崩溃现场实测。

## 验证证据与仍未验证

- CI 的失败曾真实阻止交付，未删断言、跳失败或停用功能。146 的活动测试确认为测试管理器跨测试持有旧 Context，已隔离两个管理器；偏好测试补齐本来必需的 context/exceptions。后续以最终运行结果为准。
- 新测试覆盖正常候选超过 8/32/128、48 小时、备份恢复、全文修改与真实读取资格、活动、来源可见范围、Provider 工具续轮、摘要与报告失败边界。
- 浏览器实际生产页面 + 模拟 HTTP 交互 51 次、无 JavaScript 错误，无模型请求。截图与详细检查见 screenshots/ui-check.json。这是 Windows/WebChat 与手机宽度布局验证，不是 Android Compose 真机截图。
- [桌面概览](screenshots/private-desktop-overview.png)、[完整候选](screenshots/private-desktop-fulltext.png)、[承接入口](screenshots/private-mobile-import.png)、[梦醒随记](screenshots/private-mobile-waking.png)、[读取失败](screenshots/private-mobile-read-error.png) 均使用标明的模拟内容，不能证明真实主体行为。
- 真机、真实模型理解、实际 MCP、真实订阅账号、Android 原生触控截图、系统长期后台及这台手机的崩溃现场尚未验证。书房整书扫描随文件增长的原有限制保留。
- Claude/Gemini/GLM 订阅范围是外部限制；相应部分没有标为可用。Android/Windows 最终检查、签名、发布和 +136/+140 更新识别证据将在完成后追加。

## 后续维护边界

复用当前唯一状态入口、增量归档、正常实际读取证明和工具回执。不要恢复 XC 同步，不把旧私密随记批量公开，不把导入声明改成伪造证据，不通过增加调用取得同轮采用资格。新增来源或页面继续使用同一预算与权限；人格成长不因承接截止而停止。没有新第七批内部状态工程，也不包含引擎第八九批专项或独立引擎可视化。
