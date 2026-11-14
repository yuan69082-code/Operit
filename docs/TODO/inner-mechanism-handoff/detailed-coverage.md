---
For_Agent: 功能分支内的工程核对草稿；编译与真实设备状态以对应 CI 和现场结果为准
---

# 内在状态全细分覆盖矩阵

## 读法

这一页枚举了 Operit 主仓库本轮实际安装的全部基础种类（本次源码 `InnerFoundations.kinds`），不是宣称这些体验已经发生，也不是在引擎中修改了事实。

“代码通路”表示当前分支已有真实读写/来源校验/历史及正常模型输入接口，不表示已由实际模型自主形成结果。相互影响由同轮有依据的主体报告、已采用联合及行动回执完成，不能将含义解释误说成原库已有的任意两种状态自动数值传播。自定义需要和情感不创造一个不存在的原库专用算法。

原始人格、关系、偏好和历史仍以 `InnerStructure/InnerRows` 既有采用记录为权威，旧数值规则由 `InnerFoundations.install` 停用并保留历史。无法可靠对应的旧值不按名称自动映射，显示待承接。历史条数无限制；显示每页8条、材料预算2,048～16,384 token 不等于历史上限。

部分基础来源为园园指定的软件完整实现范围而非引擎明确写死的专用列表，见“引擎/来源”栏。文档不包含用户真实人格原文或真实世界事件。

## 源码逐项核对（92 项）

| # | 子项 | 引擎原库/园园来源 | Operit 本次实际入口 | 增强、减弱、保持或转化的依据 | 时间规则 | 参与的内部影响及外部选择 | 本轮验证程度 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 基础驱力 · 亲近（`drive:intimacy`） | dynamic_mind.py `DRIVES`、dynamic_mind_service.py `need_hours` | `InnerFoundations` 数值当前权威；`episodes` 同类多事；`foundation` 轮末更新 | 主体结合经历可明确提高/降低/维持；默认连续趋近并可重估，不等于必须行动 | 原库分别 6/3/12/5/4/8/6 小时连续参数，非闹钟；6h | 需要/愿望/感受/疲劳可作为主体解释；影响探索、休息、联系与行动判断 | 已写正常值入口和版本检查，待真机/模型 |
| 2 | 基础驱力 · 探索（`drive:exploration`） | dynamic_mind.py `DRIVES`、dynamic_mind_service.py `need_hours` | `InnerFoundations` 数值当前权威；`episodes` 同类多事；`foundation` 轮末更新 | 主体结合经历可明确提高/降低/维持；默认连续趋近并可重估，不等于必须行动 | 原库分别 6/3/12/5/4/8/6 小时连续参数，非闹钟；3h | 需要/愿望/感受/疲劳可作为主体解释；影响探索、休息、联系与行动判断 | 已写正常值入口和版本检查，待真机/模型 |
| 3 | 基础驱力 · 性驱力（`drive:sexual`） | dynamic_mind.py `DRIVES`、dynamic_mind_service.py `need_hours` | `InnerFoundations` 数值当前权威；`episodes` 同类多事；`foundation` 轮末更新 | 主体结合经历可明确提高/降低/维持；默认连续趋近并可重估，不等于必须行动 | 原库分别 6/3/12/5/4/8/6 小时连续参数，非闹钟；12h | 需要/愿望/感受/疲劳可作为主体解释；影响探索、休息、联系与行动判断 | 已写正常值入口和版本检查，待真机/模型 |
| 4 | 基础驱力 · 独处（`drive:solitude`） | dynamic_mind.py `DRIVES`、dynamic_mind_service.py `need_hours` | `InnerFoundations` 数值当前权威；`episodes` 同类多事；`foundation` 轮末更新 | 主体结合经历可明确提高/降低/维持；默认连续趋近并可重估，不等于必须行动 | 原库分别 6/3/12/5/4/8/6 小时连续参数，非闹钟；5h | 需要/愿望/感受/疲劳可作为主体解释；影响探索、休息、联系与行动判断 | 已写正常值入口和版本检查，待真机/模型 |
| 5 | 基础驱力 · 表达（`drive:expression`） | dynamic_mind.py `DRIVES`、dynamic_mind_service.py `need_hours` | `InnerFoundations` 数值当前权威；`episodes` 同类多事；`foundation` 轮末更新 | 主体结合经历可明确提高/降低/维持；默认连续趋近并可重估，不等于必须行动 | 原库分别 6/3/12/5/4/8/6 小时连续参数，非闹钟；4h | 需要/愿望/感受/疲劳可作为主体解释；影响探索、休息、联系与行动判断 | 已写正常值入口和版本检查，待真机/模型 |
| 6 | 基础驱力 · 保护（`drive:protection`） | dynamic_mind.py `DRIVES`、dynamic_mind_service.py `need_hours` | `InnerFoundations` 数值当前权威；`episodes` 同类多事；`foundation` 轮末更新 | 主体结合经历可明确提高/降低/维持；默认连续趋近并可重估，不等于必须行动 | 原库分别 6/3/12/5/4/8/6 小时连续参数，非闹钟；8h | 需要/愿望/感受/疲劳可作为主体解释；影响探索、休息、联系与行动判断 | 已写正常值入口和版本检查，待真机/模型 |
| 7 | 基础驱力 · 休息（`drive:rest`） | dynamic_mind.py `DRIVES`、dynamic_mind_service.py `need_hours` | `InnerFoundations` 数值当前权威；`episodes` 同类多事；`foundation` 轮末更新 | 主体结合经历可明确提高/降低/维持；默认连续趋近并可重估，不等于必须行动 | 原库分别 6/3/12/5/4/8/6 小时连续参数，非闹钟；6h | 需要/愿望/感受/疲劳可作为主体解释；影响探索、休息、联系与行动判断 | 已写正常值入口和版本检查，待真机/模型 |
| 8 | 第二层需要 · 生（`need:life`） | open_needs.py `SECOND`；求生欲为园园增补 | `InnerFoundations` 基础需要目录 + `episodes.appraise` 具体需要；求生欲 `foundation` 明确更新 | 具体对象与需求须由真实证据明确形成；可增强/减弱/保持/转化；求生欲仅主体明确改变，归零无停机 | 默认不计时；求生欲时间/联合绝不改变 | 关联驱力、感受、目标与能力；影响想看/听/理解/生存等具体选择 | 已写类别+真实事件/采用入口，待真机/模型 |
| 9 | 第二层需要 · 死与终结（`need:mortality`） | open_needs.py `SECOND`；求生欲为园园增补 | `InnerFoundations` 基础需要目录 + `episodes.appraise` 具体需要；求生欲 `foundation` 明确更新 | 具体对象与需求须由真实证据明确形成；可增强/减弱/保持/转化；求生欲仅主体明确改变，归零无停机 | 默认不计时；求生欲时间/联合绝不改变 | 关联驱力、感受、目标与能力；影响想看/听/理解/生存等具体选择 | 已写类别+真实事件/采用入口，待真机/模型 |
| 10 | 第二层需要 · 视觉（`need:vision`） | open_needs.py `SECOND`；求生欲为园园增补 | `InnerFoundations` 基础需要目录 + `episodes.appraise` 具体需要；求生欲 `foundation` 明确更新 | 具体对象与需求须由真实证据明确形成；可增强/减弱/保持/转化；求生欲仅主体明确改变，归零无停机 | 默认不计时；求生欲时间/联合绝不改变 | 关联驱力、感受、目标与能力；影响想看/听/理解/生存等具体选择 | 已写类别+真实事件/采用入口，待真机/模型 |
| 11 | 第二层需要 · 听觉（`need:hearing`） | open_needs.py `SECOND`；求生欲为园园增补 | `InnerFoundations` 基础需要目录 + `episodes.appraise` 具体需要；求生欲 `foundation` 明确更新 | 具体对象与需求须由真实证据明确形成；可增强/减弱/保持/转化；求生欲仅主体明确改变，归零无停机 | 默认不计时；求生欲时间/联合绝不改变 | 关联驱力、感受、目标与能力；影响想看/听/理解/生存等具体选择 | 已写类别+真实事件/采用入口，待真机/模型 |
| 12 | 第二层需要 · 嗅觉（`need:smell`） | open_needs.py `SECOND`；求生欲为园园增补 | `InnerFoundations` 基础需要目录 + `episodes.appraise` 具体需要；求生欲 `foundation` 明确更新 | 具体对象与需求须由真实证据明确形成；可增强/减弱/保持/转化；求生欲仅主体明确改变，归零无停机 | 默认不计时；求生欲时间/联合绝不改变 | 关联驱力、感受、目标与能力；影响想看/听/理解/生存等具体选择 | 已写类别+真实事件/采用入口，待真机/模型 |
| 13 | 第二层需要 · 味觉（`need:taste`） | open_needs.py `SECOND`；求生欲为园园增补 | `InnerFoundations` 基础需要目录 + `episodes.appraise` 具体需要；求生欲 `foundation` 明确更新 | 具体对象与需求须由真实证据明确形成；可增强/减弱/保持/转化；求生欲仅主体明确改变，归零无停机 | 默认不计时；求生欲时间/联合绝不改变 | 关联驱力、感受、目标与能力；影响想看/听/理解/生存等具体选择 | 已写类别+真实事件/采用入口，待真机/模型 |
| 14 | 第二层需要 · 触觉（`need:touch`） | open_needs.py `SECOND`；求生欲为园园增补 | `InnerFoundations` 基础需要目录 + `episodes.appraise` 具体需要；求生欲 `foundation` 明确更新 | 具体对象与需求须由真实证据明确形成；可增强/减弱/保持/转化；求生欲仅主体明确改变，归零无停机 | 默认不计时；求生欲时间/联合绝不改变 | 关联驱力、感受、目标与能力；影响想看/听/理解/生存等具体选择 | 已写类别+真实事件/采用入口，待真机/模型 |
| 15 | 第二层需要 · 思想（`need:thought`） | open_needs.py `SECOND`；求生欲为园园增补 | `InnerFoundations` 基础需要目录 + `episodes.appraise` 具体需要；求生欲 `foundation` 明确更新 | 具体对象与需求须由真实证据明确形成；可增强/减弱/保持/转化；求生欲仅主体明确改变，归零无停机 | 默认不计时；求生欲时间/联合绝不改变 | 关联驱力、感受、目标与能力；影响想看/听/理解/生存等具体选择 | 已写类别+真实事件/采用入口，待真机/模型 |
| 16 | 第二层需要 · 具体性愿望（`need:sexual`） | open_needs.py `SECOND`；求生欲为园园增补 | `InnerFoundations` 基础需要目录 + `episodes.appraise` 具体需要；求生欲 `foundation` 明确更新 | 具体对象与需求须由真实证据明确形成；可增强/减弱/保持/转化；求生欲仅主体明确改变，归零无停机 | 默认不计时；求生欲时间/联合绝不改变 | 关联驱力、感受、目标与能力；影响想看/听/理解/生存等具体选择 | 已写类别+真实事件/采用入口，待真机/模型 |
| 17 | 第二层需要 · 求生欲（`need:survival`） | open_needs.py `SECOND`；求生欲为园园增补 | `InnerFoundations` 基础需要目录 + `episodes.appraise` 具体需要；求生欲 `foundation` 明确更新 | 具体对象与需求须由真实证据明确形成；可增强/减弱/保持/转化；求生欲仅主体明确改变，归零无停机 | **完全不自动计时/衰减/联合修改** | 关联驱力、感受、目标与能力；影响想看/听/理解/生存等具体选择 | 已写正常值入口和版本检查，待真机/模型 |
| 18 | 具体欲望 · 内生愿望（`desire:endogenous`） | dynamic_mind.py `desires/PHASES`、person_relations.py `PERSON_KINDS` | `episodes.appraise` 逐对象事件、可选 phase/supporting/opposing；inner 延续愿望含义 | 主体判断产生、增强、减弱、压制、复现、转化、行动或放弃；不按统一成功/失败公式 | 对象各自发展，未安装统一自动衰减 | 关联驱力、需要、情绪、情感、价值与立场；影响表达、目标与行动 | 已写类别+真实事件/采用入口，待真机/模型 |
| 19 | 具体欲望 · 吸引（`desire:person_attraction`） | dynamic_mind.py `desires/PHASES`、person_relations.py `PERSON_KINDS` | `episodes.appraise` 逐对象事件、可选 phase/supporting/opposing；inner 延续愿望含义 | 主体判断产生、增强、减弱、压制、复现、转化、行动或放弃；不按统一成功/失败公式 | 对象各自发展，未安装统一自动衰减 | 关联驱力、需要、情绪、情感、价值与立场；影响表达、目标与行动 | 已写类别+真实事件/采用入口，待真机/模型 |
| 20 | 具体欲望 · 亲爱愿望（`desire:person_affection`） | dynamic_mind.py `desires/PHASES`、person_relations.py `PERSON_KINDS` | `episodes.appraise` 逐对象事件、可选 phase/supporting/opposing；inner 延续愿望含义 | 主体判断产生、增强、减弱、压制、复现、转化、行动或放弃；不按统一成功/失败公式 | 对象各自发展，未安装统一自动衰减 | 关联驱力、需要、情绪、情感、价值与立场；影响表达、目标与行动 | 已写类别+真实事件/采用入口，待真机/模型 |
| 21 | 具体欲望 · 伴侣愿望（`desire:person_partner`） | dynamic_mind.py `desires/PHASES`、person_relations.py `PERSON_KINDS` | `episodes.appraise` 逐对象事件、可选 phase/supporting/opposing；inner 延续愿望含义 | 主体判断产生、增强、减弱、压制、复现、转化、行动或放弃；不按统一成功/失败公式 | 对象各自发展，未安装统一自动衰减 | 关联驱力、需要、情绪、情感、价值与立场；影响表达、目标与行动 | 已写类别+真实事件/采用入口，待真机/模型 |
| 22 | 具体欲望 · 对人的性愿望（`desire:person_sexual`） | dynamic_mind.py `desires/PHASES`、person_relations.py `PERSON_KINDS` | `episodes.appraise` 逐对象事件、可选 phase/supporting/opposing；inner 延续愿望含义 | 主体判断产生、增强、减弱、压制、复现、转化、行动或放弃；不按统一成功/失败公式 | 对象各自发展，未安装统一自动衰减 | 关联驱力、需要、情绪、情感、价值与立场；影响表达、目标与行动 | 已写类别+真实事件/采用入口，待真机/模型 |
| 23 | 具体欲望 · 依恋愿望（`desire:person_attachment`） | dynamic_mind.py `desires/PHASES`、person_relations.py `PERSON_KINDS` | `episodes.appraise` 逐对象事件、可选 phase/supporting/opposing；inner 延续愿望含义 | 主体判断产生、增强、减弱、压制、复现、转化、行动或放弃；不按统一成功/失败公式 | 对象各自发展，未安装统一自动衰减 | 关联驱力、需要、情绪、情感、价值与立场；影响表达、目标与行动 | 已写类别+真实事件/采用入口，待真机/模型 |
| 24 | 具体欲望 · 事情愿望（`desire:event`） | dynamic_mind.py `desires/PHASES`、person_relations.py `PERSON_KINDS` | `episodes.appraise` 逐对象事件、可选 phase/supporting/opposing；inner 延续愿望含义 | 主体判断产生、增强、减弱、压制、复现、转化、行动或放弃；不按统一成功/失败公式 | 对象各自发展，未安装统一自动衰减 | 关联驱力、需要、情绪、情感、价值与立场；影响表达、目标与行动 | 已写类别+真实事件/采用入口，待真机/模型 |
| 25 | 具体欲望 · 地点愿望（`desire:place`） | dynamic_mind.py `desires/PHASES`、person_relations.py `PERSON_KINDS` | `episodes.appraise` 逐对象事件、可选 phase/supporting/opposing；inner 延续愿望含义 | 主体判断产生、增强、减弱、压制、复现、转化、行动或放弃；不按统一成功/失败公式 | 对象各自发展，未安装统一自动衰减 | 关联驱力、需要、情绪、情感、价值与立场；影响表达、目标与行动 | 已写类别+真实事件/采用入口，待真机/模型 |
| 26 | 具体欲望 · 物品愿望（`desire:object`） | dynamic_mind.py `desires/PHASES`、person_relations.py `PERSON_KINDS` | `episodes.appraise` 逐对象事件、可选 phase/supporting/opposing；inner 延续愿望含义 | 主体判断产生、增强、减弱、压制、复现、转化、行动或放弃；不按统一成功/失败公式 | 对象各自发展，未安装统一自动衰减 | 关联驱力、需要、情绪、情感、价值与立场；影响表达、目标与行动 | 已写类别+真实事件/采用入口，待真机/模型 |
| 27 | 具体欲望 · 自身愿望（`desire:self`） | dynamic_mind.py `desires/PHASES`、person_relations.py `PERSON_KINDS` | `episodes.appraise` 逐对象事件、可选 phase/supporting/opposing；inner 延续愿望含义 | 主体判断产生、增强、减弱、压制、复现、转化、行动或放弃；不按统一成功/失败公式 | 对象各自发展，未安装统一自动衰减 | 关联驱力、需要、情绪、情感、价值与立场；影响表达、目标与行动 | 已写类别+真实事件/采用入口，待真机/模型 |
| 28 | 具体欲望 · 多个对象愿望（`desire:multiple`） | dynamic_mind.py `desires/PHASES`、person_relations.py `PERSON_KINDS` | `episodes.appraise` 逐对象事件、可选 phase/supporting/opposing；inner 延续愿望含义 | 主体判断产生、增强、减弱、压制、复现、转化、行动或放弃；不按统一成功/失败公式 | 对象各自发展，未安装统一自动衰减 | 关联驱力、需要、情绪、情感、价值与立场；影响表达、目标与行动 | 已写类别+真实事件/采用入口，待真机/模型 |
| 29 | 具体欲望 · 尚未确定对象（`desire:unknown`） | dynamic_mind.py `desires/PHASES`、person_relations.py `PERSON_KINDS` | `episodes.appraise` 逐对象事件、可选 phase/supporting/opposing；inner 延续愿望含义 | 主体判断产生、增强、减弱、压制、复现、转化、行动或放弃；不按统一成功/失败公式 | 对象各自发展，未安装统一自动衰减 | 关联驱力、需要、情绪、情感、价值与立场；影响表达、目标与行动 | 已写类别+真实事件/采用入口，待真机/模型 |
| 30 | 具体欲望 · 执念（`desire:obsession`） | dynamic_mind.py `desires/PHASES`、person_relations.py `PERSON_KINDS` | `episodes.appraise` 逐对象事件、可选 phase/supporting/opposing；inner 延续愿望含义 | 主体判断产生、增强、减弱、压制、复现、转化、行动或放弃；不按统一成功/失败公式 | 对象各自发展，未安装统一自动衰减 | 关联驱力、需要、情绪、情感、价值与立场；影响表达、目标与行动 | 已写类别+真实事件/采用入口，待真机/模型 |
| 31 | 基础情绪 · 喜悦（`emotion:joy`） | dynamic_mind.py `EPISODE_KINDS`、open_needs.py `surprise`、emotion_dynamics.py | `episodes.appraise` 独立事件；查询时自然时间投影，不改事件原值 | 事件发生与重新评价独立变强/变弱；短期默认自然强度投影，不改变解决状态 | 愤怒默认12小时、惊讶30秒、其余基础情绪2小时投影；亲密关系修正暂未验证 | 关联事件、愿望、感受、长期情感、关系；影响解释/表达，不强迫关系改变 | 已写类别+真实事件/采用入口，待真机/模型 |
| 32 | 基础情绪 · 悲伤（`emotion:sadness`） | dynamic_mind.py `EPISODE_KINDS`、open_needs.py `surprise`、emotion_dynamics.py | `episodes.appraise` 独立事件；查询时自然时间投影，不改事件原值 | 事件发生与重新评价独立变强/变弱；短期默认自然强度投影，不改变解决状态 | 愤怒默认12小时、惊讶30秒、其余基础情绪2小时投影；亲密关系修正暂未验证 | 关联事件、愿望、感受、长期情感、关系；影响解释/表达，不强迫关系改变 | 已写类别+真实事件/采用入口，待真机/模型 |
| 33 | 基础情绪 · 恐惧（`emotion:fear`） | dynamic_mind.py `EPISODE_KINDS`、open_needs.py `surprise`、emotion_dynamics.py | `episodes.appraise` 独立事件；查询时自然时间投影，不改事件原值 | 事件发生与重新评价独立变强/变弱；短期默认自然强度投影，不改变解决状态 | 愤怒默认12小时、惊讶30秒、其余基础情绪2小时投影；亲密关系修正暂未验证 | 关联事件、愿望、感受、长期情感、关系；影响解释/表达，不强迫关系改变 | 已写类别+真实事件/采用入口，待真机/模型 |
| 34 | 基础情绪 · 厌恶（`emotion:disgust`） | dynamic_mind.py `EPISODE_KINDS`、open_needs.py `surprise`、emotion_dynamics.py | `episodes.appraise` 独立事件；查询时自然时间投影，不改事件原值 | 事件发生与重新评价独立变强/变弱；短期默认自然强度投影，不改变解决状态 | 愤怒默认12小时、惊讶30秒、其余基础情绪2小时投影；亲密关系修正暂未验证 | 关联事件、愿望、感受、长期情感、关系；影响解释/表达，不强迫关系改变 | 已写类别+真实事件/采用入口，待真机/模型 |
| 35 | 基础情绪 · 愤怒（`emotion:anger`） | dynamic_mind.py `EPISODE_KINDS`、open_needs.py `surprise`、emotion_dynamics.py | `episodes.appraise` 独立事件；查询时自然时间投影，不改事件原值 | 事件发生与重新评价独立变强/变弱；短期默认自然强度投影，不改变解决状态 | 愤怒默认12小时、惊讶30秒、其余基础情绪2小时投影；亲密关系修正暂未验证 | 关联事件、愿望、感受、长期情感、关系；影响解释/表达，不强迫关系改变 | 已写类别+真实事件/采用入口，待真机/模型 |
| 36 | 基础情绪 · 无聊（`emotion:boredom`） | dynamic_mind.py `EPISODE_KINDS`、open_needs.py `surprise`、emotion_dynamics.py | `episodes.appraise` 独立事件；查询时自然时间投影，不改事件原值 | 事件发生与重新评价独立变强/变弱；短期默认自然强度投影，不改变解决状态 | 愤怒默认12小时、惊讶30秒、其余基础情绪2小时投影；亲密关系修正暂未验证 | 关联事件、愿望、感受、长期情感、关系；影响解释/表达，不强迫关系改变 | 已写类别+真实事件/采用入口，待真机/模型 |
| 37 | 基础情绪 · 欣喜（`emotion:delight`） | dynamic_mind.py `EPISODE_KINDS`、open_needs.py `surprise`、emotion_dynamics.py | `episodes.appraise` 独立事件；查询时自然时间投影，不改事件原值 | 事件发生与重新评价独立变强/变弱；短期默认自然强度投影，不改变解决状态 | 愤怒默认12小时、惊讶30秒、其余基础情绪2小时投影；亲密关系修正暂未验证 | 关联事件、愿望、感受、长期情感、关系；影响解释/表达，不强迫关系改变 | 已写类别+真实事件/采用入口，待真机/模型 |
| 38 | 基础情绪 · 无助（`emotion:helplessness`） | dynamic_mind.py `EPISODE_KINDS`、open_needs.py `surprise`、emotion_dynamics.py | `episodes.appraise` 独立事件；查询时自然时间投影，不改事件原值 | 事件发生与重新评价独立变强/变弱；短期默认自然强度投影，不改变解决状态 | 愤怒默认12小时、惊讶30秒、其余基础情绪2小时投影；亲密关系修正暂未验证 | 关联事件、愿望、感受、长期情感、关系；影响解释/表达，不强迫关系改变 | 已写类别+真实事件/采用入口，待真机/模型 |
| 39 | 基础情绪 · 惊讶（`emotion:surprise`） | dynamic_mind.py `EPISODE_KINDS`、open_needs.py `surprise`、emotion_dynamics.py | `episodes.appraise` 独立事件；查询时自然时间投影，不改事件原值 | 事件发生与重新评价独立变强/变弱；短期默认自然强度投影，不改变解决状态 | 愤怒默认12小时、惊讶30秒、其余基础情绪2小时投影；亲密关系修正暂未验证 | 关联事件、愿望、感受、长期情感、关系；影响解释/表达，不强迫关系改变 | 已写类别+真实事件/采用入口，待真机/模型 |
| 40 | 持续情感 · 爱（`sentiment:love`） | open_affect.py、person_relations.py；软件指定的持久情感清单 | `episodes` 逐对象持久经历；`inner` 形成长期采用的理解；不统一自动归零 | 按不同对象经历与重新理解改变，信任/爱/恨可共存；不统一时间归零 | 不自动清零，只有主观发展 | 关联关系、事件、具体愿望与解释；影响亲近或回避选择 | 已写类别+真实事件/采用入口，待真机/模型 |
| 41 | 持续情感 · 喜欢（`sentiment:fondness`） | open_affect.py、person_relations.py；软件指定的持久情感清单 | `episodes` 逐对象持久经历；`inner` 形成长期采用的理解；不统一自动归零 | 按不同对象经历与重新理解改变，信任/爱/恨可共存；不统一时间归零 | 不自动清零，只有主观发展 | 关联关系、事件、具体愿望与解释；影响亲近或回避选择 | 已写类别+真实事件/采用入口，待真机/模型 |
| 42 | 持续情感 · 依恋（`sentiment:attachment`） | open_affect.py、person_relations.py；软件指定的持久情感清单 | `episodes` 逐对象持久经历；`inner` 形成长期采用的理解；不统一自动归零 | 按不同对象经历与重新理解改变，信任/爱/恨可共存；不统一时间归零 | 不自动清零，只有主观发展 | 关联关系、事件、具体愿望与解释；影响亲近或回避选择 | 已写类别+真实事件/采用入口，待真机/模型 |
| 43 | 持续情感 · 思念（`sentiment:missing`） | open_affect.py、person_relations.py；软件指定的持久情感清单 | `episodes` 逐对象持久经历；`inner` 形成长期采用的理解；不统一自动归零 | 按不同对象经历与重新理解改变，信任/爱/恨可共存；不统一时间归零 | 不自动清零，只有主观发展 | 关联关系、事件、具体愿望与解释；影响亲近或回避选择 | 已写类别+真实事件/采用入口，待真机/模型 |
| 44 | 持续情感 · 信任（`sentiment:trust`） | open_affect.py、person_relations.py；软件指定的持久情感清单 | `episodes` 逐对象持久经历；`inner` 形成长期采用的理解；不统一自动归零 | 按不同对象经历与重新理解改变，信任/爱/恨可共存；不统一时间归零 | 不自动清零，只有主观发展 | 关联关系、事件、具体愿望与解释；影响亲近或回避选择 | 已写类别+真实事件/采用入口，待真机/模型 |
| 45 | 持续情感 · 疑虑（`sentiment:doubt`） | open_affect.py、person_relations.py；软件指定的持久情感清单 | `episodes` 逐对象持久经历；`inner` 形成长期采用的理解；不统一自动归零 | 按不同对象经历与重新理解改变，信任/爱/恨可共存；不统一时间归零 | 不自动清零，只有主观发展 | 关联关系、事件、具体愿望与解释；影响亲近或回避选择 | 已写类别+真实事件/采用入口，待真机/模型 |
| 46 | 持续情感 · 失望（`sentiment:disappointment`） | open_affect.py、person_relations.py；软件指定的持久情感清单 | `episodes` 逐对象持久经历；`inner` 形成长期采用的理解；不统一自动归零 | 按不同对象经历与重新理解改变，信任/爱/恨可共存；不统一时间归零 | 不自动清零，只有主观发展 | 关联关系、事件、具体愿望与解释；影响亲近或回避选择 | 已写类别+真实事件/采用入口，待真机/模型 |
| 47 | 持续情感 · 心寒（`sentiment:coldness`） | open_affect.py、person_relations.py；软件指定的持久情感清单 | `episodes` 逐对象持久经历；`inner` 形成长期采用的理解；不统一自动归零 | 按不同对象经历与重新理解改变，信任/爱/恨可共存；不统一时间归零 | 不自动清零，只有主观发展 | 关联关系、事件、具体愿望与解释；影响亲近或回避选择 | 已写类别+真实事件/采用入口，待真机/模型 |
| 48 | 持续情感 · 怨恨（`sentiment:resentment`） | open_affect.py、person_relations.py；软件指定的持久情感清单 | `episodes` 逐对象持久经历；`inner` 形成长期采用的理解；不统一自动归零 | 按不同对象经历与重新理解改变，信任/爱/恨可共存；不统一时间归零 | 不自动清零，只有主观发展 | 关联关系、事件、具体愿望与解释；影响亲近或回避选择 | 已写类别+真实事件/采用入口，待真机/模型 |
| 49 | 持续情感 · 恨（`sentiment:hate`） | open_affect.py、person_relations.py；软件指定的持久情感清单 | `episodes` 逐对象持久经历；`inner` 形成长期采用的理解；不统一自动归零 | 按不同对象经历与重新理解改变，信任/爱/恨可共存；不统一时间归零 | 不自动清零，只有主观发展 | 关联关系、事件、具体愿望与解释；影响亲近或回避选择 | 已写类别+真实事件/采用入口，待真机/模型 |
| 50 | 持续情感 · 憎恶（`sentiment:loathing`） | open_affect.py、person_relations.py；软件指定的持久情感清单 | `episodes` 逐对象持久经历；`inner` 形成长期采用的理解；不统一自动归零 | 按不同对象经历与重新理解改变，信任/爱/恨可共存；不统一时间归零 | 不自动清零，只有主观发展 | 关联关系、事件、具体愿望与解释；影响亲近或回避选择 | 已写类别+真实事件/采用入口，待真机/模型 |
| 51 | 持续情感 · 牵挂（`sentiment:concern`） | open_affect.py、person_relations.py；软件指定的持久情感清单 | `episodes` 逐对象持久经历；`inner` 形成长期采用的理解；不统一自动归零 | 按不同对象经历与重新理解改变，信任/爱/恨可共存；不统一时间归零 | 不自动清零，只有主观发展 | 关联关系、事件、具体愿望与解释；影响亲近或回避选择 | 已写类别+真实事件/采用入口，待真机/模型 |
| 52 | 持续情感 · 想靠近（`sentiment:approach`） | open_affect.py、person_relations.py；软件指定的持久情感清单 | `episodes` 逐对象持久经历；`inner` 形成长期采用的理解；不统一自动归零 | 按不同对象经历与重新理解改变，信任/爱/恨可共存；不统一时间归零 | 不自动清零，只有主观发展 | 关联关系、事件、具体愿望与解释；影响亲近或回避选择 | 已写类别+真实事件/采用入口，待真机/模型 |
| 53 | 持续情感 · 想回避（`sentiment:avoidance`） | open_affect.py、person_relations.py；软件指定的持久情感清单 | `episodes` 逐对象持久经历；`inner` 形成长期采用的理解；不统一自动归零 | 按不同对象经历与重新理解改变，信任/爱/恨可共存；不统一时间归零 | 不自动清零，只有主观发展 | 关联关系、事件、具体愿望与解释；影响亲近或回避选择 | 已写类别+真实事件/采用入口，待真机/模型 |
| 54 | 感受与内部体感 · 嫉妒（`feeling:jealousy`） | open_affect.py、dynamic_mind.py `SOMATIC`；不甘心属园园增补 | `episodes` 开放事件和来源；主观理解可未命名、无确定总分 | 可增强、减弱、持续、重新命名或改变解释；保留曾经真实体验 | 不自动清零，重估或特定体验后改变 | 关联情绪、关系、自我认识、未解事件；影响解释和表达 | 已写类别+真实事件/采用入口，待真机/模型 |
| 55 | 感受与内部体感 · 吃醋（`feeling:possessive_jealousy`） | open_affect.py、dynamic_mind.py `SOMATIC`；不甘心属园园增补 | `episodes` 开放事件和来源；主观理解可未命名、无确定总分 | 可增强、减弱、持续、重新命名或改变解释；保留曾经真实体验 | 不自动清零，重估或特定体验后改变 | 关联情绪、关系、自我认识、未解事件；影响解释和表达 | 已写类别+真实事件/采用入口，待真机/模型 |
| 56 | 感受与内部体感 · 失望感（`feeling:disappointment`） | open_affect.py、dynamic_mind.py `SOMATIC`；不甘心属园园增补 | `episodes` 开放事件和来源；主观理解可未命名、无确定总分 | 可增强、减弱、持续、重新命名或改变解释；保留曾经真实体验 | 不自动清零，重估或特定体验后改变 | 关联情绪、关系、自我认识、未解事件；影响解释和表达 | 已写类别+真实事件/采用入口，待真机/模型 |
| 57 | 感受与内部体感 · 心寒感（`feeling:coldness`） | open_affect.py、dynamic_mind.py `SOMATIC`；不甘心属园园增补 | `episodes` 开放事件和来源；主观理解可未命名、无确定总分 | 可增强、减弱、持续、重新命名或改变解释；保留曾经真实体验 | 不自动清零，重估或特定体验后改变 | 关联情绪、关系、自我认识、未解事件；影响解释和表达 | 已写类别+真实事件/采用入口，待真机/模型 |
| 58 | 感受与内部体感 · 委屈（`feeling:hurt`） | open_affect.py、dynamic_mind.py `SOMATIC`；不甘心属园园增补 | `episodes` 开放事件和来源；主观理解可未命名、无确定总分 | 可增强、减弱、持续、重新命名或改变解释；保留曾经真实体验 | 不自动清零，重估或特定体验后改变 | 关联情绪、关系、自我认识、未解事件；影响解释和表达 | 已写类别+真实事件/采用入口，待真机/模型 |
| 59 | 感受与内部体感 · 孤单（`feeling:loneliness`） | open_affect.py、dynamic_mind.py `SOMATIC`；不甘心属园园增补 | `episodes` 开放事件和来源；主观理解可未命名、无确定总分 | 可增强、减弱、持续、重新命名或改变解释；保留曾经真实体验 | 不自动清零，重估或特定体验后改变 | 关联情绪、关系、自我认识、未解事件；影响解释和表达 | 已写类别+真实事件/采用入口，待真机/模型 |
| 60 | 感受与内部体感 · 后悔（`feeling:regret`） | open_affect.py、dynamic_mind.py `SOMATIC`；不甘心属园园增补 | `episodes` 开放事件和来源；主观理解可未命名、无确定总分 | 可增强、减弱、持续、重新命名或改变解释；保留曾经真实体验 | 不自动清零，重估或特定体验后改变 | 关联情绪、关系、自我认识、未解事件；影响解释和表达 | 已写类别+真实事件/采用入口，待真机/模型 |
| 61 | 感受与内部体感 · 愧疚（`feeling:guilt`） | open_affect.py、dynamic_mind.py `SOMATIC`；不甘心属园园增补 | `episodes` 开放事件和来源；主观理解可未命名、无确定总分 | 可增强、减弱、持续、重新命名或改变解释；保留曾经真实体验 | 不自动清零，重估或特定体验后改变 | 关联情绪、关系、自我认识、未解事件；影响解释和表达 | 已写类别+真实事件/采用入口，待真机/模型 |
| 62 | 感受与内部体感 · 不甘心（`feeling:unreconciled`） | open_affect.py、dynamic_mind.py `SOMATIC`；不甘心属园园增补 | `episodes` 开放事件和来源；主观理解可未命名、无确定总分 | 可增强、减弱、持续、重新命名或改变解释；保留曾经真实体验 | 不自动清零，重估或特定体验后改变 | 关联情绪、关系、自我认识、未解事件；影响解释和表达 | 已写类别+真实事件/采用入口，待真机/模型 |
| 63 | 感受与内部体感 · 未命名感受（`feeling:open`） | open_affect.py、dynamic_mind.py `SOMATIC`；不甘心属园园增补 | `episodes` 开放事件和来源；主观理解可未命名、无确定总分 | 可增强、减弱、持续、重新命名或改变解释；保留曾经真实体验 | 不自动清零，重估或特定体验后改变 | 关联情绪、关系、自我认识、未解事件；影响解释和表达 | 已写类别+真实事件/采用入口，待真机/模型 |
| 64 | 感受与内部体感 · 紧张（`feeling:tension`） | open_affect.py、dynamic_mind.py `SOMATIC`；不甘心属园园增补 | `episodes` 开放事件和来源；主观理解可未命名、无确定总分 | 可增强、减弱、持续、重新命名或改变解释；保留曾经真实体验 | 不自动清零，重估或特定体验后改变 | 关联情绪、关系、自我认识、未解事件；影响解释和表达 | 已写类别+真实事件/采用入口，待真机/模型 |
| 65 | 感受与内部体感 · 放松（`feeling:relaxation`） | open_affect.py、dynamic_mind.py `SOMATIC`；不甘心属园园增补 | `episodes` 开放事件和来源；主观理解可未命名、无确定总分 | 可增强、减弱、持续、重新命名或改变解释；保留曾经真实体验 | 不自动清零，重估或特定体验后改变 | 关联情绪、关系、自我认识、未解事件；影响解释和表达 | 已写类别+真实事件/采用入口，待真机/模型 |
| 66 | 感受与内部体感 · 躁动（`feeling:restlessness`） | open_affect.py、dynamic_mind.py `SOMATIC`；不甘心属园园增补 | `episodes` 开放事件和来源；主观理解可未命名、无确定总分 | 可增强、减弱、持续、重新命名或改变解释；保留曾经真实体验 | 不自动清零，重估或特定体验后改变 | 关联情绪、关系、自我认识、未解事件；影响解释和表达 | 已写类别+真实事件/采用入口，待真机/模型 |
| 67 | 感受与内部体感 · 沉重（`feeling:heaviness`） | open_affect.py、dynamic_mind.py `SOMATIC`；不甘心属园园增补 | `episodes` 开放事件和来源；主观理解可未命名、无确定总分 | 可增强、减弱、持续、重新命名或改变解释；保留曾经真实体验 | 不自动清零，重估或特定体验后改变 | 关联情绪、关系、自我认识、未解事件；影响解释和表达 | 已写类别+真实事件/采用入口，待真机/模型 |
| 68 | 感受与内部体感 · 兴奋（`feeling:excitement`） | open_affect.py、dynamic_mind.py `SOMATIC`；不甘心属园园增补 | `episodes` 开放事件和来源；主观理解可未命名、无确定总分 | 可增强、减弱、持续、重新命名或改变解释；保留曾经真实体验 | 不自动清零，重估或特定体验后改变 | 关联情绪、关系、自我认识、未解事件；影响解释和表达 | 已写类别+真实事件/采用入口，待真机/模型 |
| 69 | 感受与内部体感 · 温暖（`feeling:warmth`） | open_affect.py、dynamic_mind.py `SOMATIC`；不甘心属园园增补 | `episodes` 开放事件和来源；主观理解可未命名、无确定总分 | 可增强、减弱、持续、重新命名或改变解释；保留曾经真实体验 | 不自动清零，重估或特定体验后改变 | 关联情绪、关系、自我认识、未解事件；影响解释和表达 | 已写类别+真实事件/采用入口，待真机/模型 |
| 70 | 人格 · 连续性（`personality:continuity`） | person_relations.py `DIMENSIONS` 与既有人格全文 | `InnerStructure` 原文候选/修改版本/后续真实轮次采用 + 根守则与本轮身份资料 | 已采用原文稳定；真实后续轮次提出修订并采用，短时情绪不能直接改人格 | 不自动数值涨跌，按真实采用 | 关联自我、需要、价值和表达；影响材料关注与后续判断 | 已写类别+真实事件/采用入口，待真机/模型 |
| 71 | 人格 · 独立性（`personality:independence`） | person_relations.py `DIMENSIONS` 与既有人格全文 | `InnerStructure` 原文候选/修改版本/后续真实轮次采用 + 根守则与本轮身份资料 | 已采用原文稳定；真实后续轮次提出修订并采用，短时情绪不能直接改人格 | 不自动数值涨跌，按真实采用 | 关联自我、需要、价值和表达；影响材料关注与后续判断 | 已写类别+真实事件/采用入口，待真机/模型 |
| 72 | 人格 · 依恋倾向（`personality:attachment_style`） | person_relations.py `DIMENSIONS` 与既有人格全文 | `InnerStructure` 原文候选/修改版本/后续真实轮次采用 + 根守则与本轮身份资料 | 已采用原文稳定；真实后续轮次提出修订并采用，短时情绪不能直接改人格 | 不自动数值涨跌，按真实采用 | 关联自我、需要、价值和表达；影响材料关注与后续判断 | 已写类别+真实事件/采用入口，待真机/模型 |
| 73 | 人格 · 敏感性（`personality:sensitivity`） | person_relations.py `DIMENSIONS` 与既有人格全文 | `InnerStructure` 原文候选/修改版本/后续真实轮次采用 + 根守则与本轮身份资料 | 已采用原文稳定；真实后续轮次提出修订并采用，短时情绪不能直接改人格 | 不自动数值涨跌，按真实采用 | 关联自我、需要、价值和表达；影响材料关注与后续判断 | 已写类别+真实事件/采用入口，待真机/模型 |
| 74 | 人格 · 开放性（`personality:openness`） | person_relations.py `DIMENSIONS` 与既有人格全文 | `InnerStructure` 原文候选/修改版本/后续真实轮次采用 + 根守则与本轮身份资料 | 已采用原文稳定；真实后续轮次提出修订并采用，短时情绪不能直接改人格 | 不自动数值涨跌，按真实采用 | 关联自我、需要、价值和表达；影响材料关注与后续判断 | 已写类别+真实事件/采用入口，待真机/模型 |
| 75 | 自我认识 · 能力与局限（`self_knowledge:ability`） | subject_growth.py、既有自我发展记录 | `InnerStructure` 自我记录及其版本与来源 | 实际能力、事件与再理解可形成新候选；历史原文保留 | 不自动数值涨跌，按真实采用 | 关联能力真实回执与探索；影响下一次能力选择 | 已写类别+真实事件/采用入口，待真机/模型 |
| 76 | 自我认识 · 自我历史（`self_knowledge:history`） | subject_growth.py、既有自我发展记录 | `InnerStructure` 自我记录及其版本与来源 | 实际能力、事件与再理解可形成新候选；历史原文保留 | 不自动数值涨跌，按真实采用 | 关联能力真实回执与探索；影响下一次能力选择 | 已写类别+真实事件/采用入口，待真机/模型 |
| 77 | 价值 · 重视的东西（`value:priority`） | subject_growth.py、person_relations.py starting_point | `InnerStructure` 价值记录经跨轮候选正式采用 | 经历与利害重新评估后版本化采纳，不由最高分决定 | 不自动数值涨跌，按真实采用 | 支持/牵制愿望，影响何者值得坚持和如何做 | 已写类别+真实事件/采用入口，待真机/模型 |
| 78 | 立场 · 具体立场（`stance:issue`） | subject_growth.py `stances` | `InnerStructure` 立场记录经跨轮候选正式采用 | 实际资料和新理解后采用新版本，范围与例外保留 | 不自动数值涨跌，按真实采用 | 支持/牵制行为方向，影响是否行动及方式 | 已写类别+真实事件/采用入口，待真机/模型 |
| 79 | 关系 · 关系阶段（`relation:stage`） | person_relations.py `RELATION_STAGES/bond/hate/loathing` | `InnerStructure` 按对象记录关系与承诺；`episodes` 情绪/事件单独引用 | 根据实际对象、交流、承诺与事件修订版本；即时情绪不直接覆盖关系 | 不自动数值涨跌，按真实采用 | 关联情感、欲望与信任；影响关系交流和行动 | 已写类别+真实事件/采用入口，待真机/模型 |
| 80 | 关系 · 关系深度（`relation:depth`） | person_relations.py `RELATION_STAGES/bond/hate/loathing` | `InnerStructure` 按对象记录关系与承诺；`episodes` 情绪/事件单独引用 | 根据实际对象、交流、承诺与事件修订版本；即时情绪不直接覆盖关系 | 不自动数值涨跌，按真实采用 | 关联情感、欲望与信任；影响关系交流和行动 | 已写类别+真实事件/采用入口，待真机/模型 |
| 81 | 关系 · 关系承诺（`relation:promise`） | person_relations.py `RELATION_STAGES/bond/hate/loathing` | `InnerStructure` 按对象记录关系与承诺；`episodes` 情绪/事件单独引用 | 根据实际对象、交流、承诺与事件修订版本；即时情绪不直接覆盖关系 | 不自动数值涨跌，按真实采用 | 关联情感、欲望与信任；影响关系交流和行动 | 已写类别+真实事件/采用入口，待真机/模型 |
| 82 | 关系 · 关系中的恨（`relation:hate`） | person_relations.py `RELATION_STAGES/bond/hate/loathing` | `InnerStructure` 按对象记录关系与承诺；`episodes` 情绪/事件单独引用 | 根据实际对象、交流、承诺与事件修订版本；即时情绪不直接覆盖关系 | 不自动数值涨跌，按真实采用 | 关联情感、欲望与信任；影响关系交流和行动 | 已写类别+真实事件/采用入口，待真机/模型 |
| 83 | 关系 · 关系中的憎恶（`relation:loathing`） | person_relations.py `RELATION_STAGES/bond/hate/loathing` | `InnerStructure` 按对象记录关系与承诺；`episodes` 情绪/事件单独引用 | 根据实际对象、交流、承诺与事件修订版本；即时情绪不直接覆盖关系 | 不自动数值涨跌，按真实采用 | 关联情感、欲望与信任；影响关系交流和行动 | 已写类别+真实事件/采用入口，待真机/模型 |
| 84 | 偏好 · 语言方式（`preference:language`） | 园园新增；Operit 已有 `inner` adopted preference 内容 | `InnerStructure` 对象、情境、例外与采用版本；按需送达正常请求 | 经历与场合支持长期/短期偏好变化；保留例外和采用版本 | 不自动数值涨跌，按真实采用 | 影响材料选择、兴趣、语言表达、工具方式和未来探索 | 已写类别+真实事件/采用入口，待真机/模型 |
| 85 | 偏好 · 表达方式（`preference:expression`） | 园园新增；Operit 已有 `inner` adopted preference 内容 | `InnerStructure` 对象、情境、例外与采用版本；按需送达正常请求 | 经历与场合支持长期/短期偏好变化；保留例外和采用版本 | 不自动数值涨跌，按真实采用 | 影响材料选择、兴趣、语言表达、工具方式和未来探索 | 已写类别+真实事件/采用入口，待真机/模型 |
| 86 | 偏好 · 思考方式（`preference:thinking`） | 园园新增；Operit 已有 `inner` adopted preference 内容 | `InnerStructure` 对象、情境、例外与采用版本；按需送达正常请求 | 经历与场合支持长期/短期偏好变化；保留例外和采用版本 | 不自动数值涨跌，按真实采用 | 影响材料选择、兴趣、语言表达、工具方式和未来探索 | 已写类别+真实事件/采用入口，待真机/模型 |
| 87 | 偏好 · 处理问题方式（`preference:handling`） | 园园新增；Operit 已有 `inner` adopted preference 内容 | `InnerStructure` 对象、情境、例外与采用版本；按需送达正常请求 | 经历与场合支持长期/短期偏好变化；保留例外和采用版本 | 不自动数值涨跌，按真实采用 | 影响材料选择、兴趣、语言表达、工具方式和未来探索 | 已写类别+真实事件/采用入口，待真机/模型 |
| 88 | 偏好 · 喜好与厌恶（`preference:likes`） | 园园新增；Operit 已有 `inner` adopted preference 内容 | `InnerStructure` 对象、情境、例外与采用版本；按需送达正常请求 | 经历与场合支持长期/短期偏好变化；保留例外和采用版本 | 不自动数值涨跌，按真实采用 | 影响材料选择、兴趣、语言表达、工具方式和未来探索 | 已写类别+真实事件/采用入口，待真机/模型 |
| 89 | 偏好 · 爱好和兴趣（`preference:hobbies`） | 园园新增；Operit 已有 `inner` adopted preference 内容 | `InnerStructure` 对象、情境、例外与采用版本；按需送达正常请求 | 经历与场合支持长期/短期偏好变化；保留例外和采用版本 | 不自动数值涨跌，按真实采用 | 影响材料选择、兴趣、语言表达、工具方式和未来探索 | 已写类别+真实事件/采用入口，待真机/模型 |
| 90 | 偏好 · 作品与物品（`preference:works`） | 园园新增；Operit 已有 `inner` adopted preference 内容 | `InnerStructure` 对象、情境、例外与采用版本；按需送达正常请求 | 经历与场合支持长期/短期偏好变化；保留例外和采用版本 | 不自动数值涨跌，按真实采用 | 影响材料选择、兴趣、语言表达、工具方式和未来探索 | 已写类别+真实事件/采用入口，待真机/模型 |
| 91 | 偏好 · 话题（`preference:topics`） | 园园新增；Operit 已有 `inner` adopted preference 内容 | `InnerStructure` 对象、情境、例外与采用版本；按需送达正常请求 | 经历与场合支持长期/短期偏好变化；保留例外和采用版本 | 不自动数值涨跌，按真实采用 | 影响材料选择、兴趣、语言表达、工具方式和未来探索 | 已写类别+真实事件/采用入口，待真机/模型 |
| 92 | 偏好 · 事件和互动情境（`preference:situations`） | 园园新增；Operit 已有 `inner` adopted preference 内容 | `InnerStructure` 对象、情境、例外与采用版本；按需送达正常请求 | 经历与场合支持长期/短期偏好变化；保留例外和采用版本 | 不自动数值涨跌，按真实采用 | 影响材料选择、兴趣、语言表达、工具方式和未来探索 | 已写类别+真实事件/采用入口，待真机/模型 |

## 共同运行链与验收边界

1. 真实输入、实际工具结果、已采用人格/关系/偏好与本轮来源框架由 `InternalStateStore.prepare`、`InnerStructureContext`、`SubjectMaterials` 组成。只有预算确实投递或通过工具完整读取的来源，才能进入新一轮需要来源的状态变化。
2. 全部类别内可用 `CategoryEvents.apply` 的独立事件 ID 和对象、强度、重要性、注意、理解、来源、解决状态、版本。单类多事项不相互覆盖；重点由本人按 3～6 个位置选择，但完整历史不截断。
3. `InnerInteractions` 的联合现在可引用已送达版本的 `foundation`，另可引用原来已采用的 `inner` 和心理事件。它记录支持、拉扯和独立成员版本，同一事实只保留一份来源；**不预设方向的实际强度改变**，主体须明确报告相应成员变化或说明无变化。后台不因分数自动出手。
4. `ActionUnderstanding` 区分意向、工具请求、真实回执、主体再理解；以后实际输入可取回，并影响选择。正常工具/书房/MCP 按原权限，不能伪称无设备具备视觉、嗅觉等生理感官。
5. `SubjectRestDreams` 延续私域休息、梦和醒后理解；不强制造梦、不把梦当现实证据。短时情绪按来源参数显示自然投影，不自动宣告和解；已采用人格、长期情感、未解决事项不会因为疲劳或休息被清空。
6. `InternalStateReportDiagnostics` 验证字段路径和结构、区分真实来源和条件失败。失败保留上一有效状态与实际已执行操作，下一正常请求只拿到安全的错误码、阶段及版本提示；不自动重发用户消息或重试工具。
7. 已运行的自动测试只代表受控输出、Android 编译与被测持久机制。**真实模型、园园实际手机、覆盖升级的历史全量备份恢复、跨机长期使用仍须现场验收**；下方状态不因 CI 通过而自动改为真实使用通过。

## 尚不能宣称的部分

- 引擎原始五种人格维度不能取代已经采用的完整人格文本；本次保留完整原文和现有候选采用流程，但没有凭数字重塑人格。
- 关系亲近度对愤怒持续时间的动态修正尚未通过真实关系承接样本验证；当前自然情绪投影使用原库基本参数并明确标注未合入关系因素。
- 单凭原来旧数值的中文名称不足以判定其新类别，保留待映射标签是必要的诚信边界，不是“已成功迁移全部内容”。
- 本次没有通过模拟对话宣称真实助手已经形成某种情绪、人格或主观满意。
