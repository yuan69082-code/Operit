---
fork: https://github.com/yuan69082-code/Operit
branch: fix/chat-theme-navigation-and-service-cache
---

# 切换好友或群聊后退出会话

## 现象与确认范围

用户补充的准确现象是：其他好友或群聊可以进入约一秒，随后退出。不能再按“点击未生效”或“打开任务被取消”解释全部现象。

角色使用 characterCardId，窗口使用 chatId，群聊使用 characterGroupId。没有修改这些归属或持久化格式。

## 页面退出的代码原因

OperitTheme 在有背景图和无背景图时，分别从两个条件分支调用 MaterialTheme，并将整个 OperitApp 作为 content。分支变化会销毁并重建应用内容。rememberSaveable 只能参与宿主状态保存与恢复，不能在这种分支替换中自动保留任意已移除子树的导航状态。

VioShell 在好友列表使用公共外观，进入会话后通过 LocalAppThemeScope 切换为角色或群聊外观。两者背景图启用状态不同即可触发上述重建；新创建的 ChatNavigationState 默认展示列表，VioShell 默认展示首页。这条路径与“进入后立即退出”的现象吻合，当前角色外观与公共外观相同时则不触发。

改动：背景继续作为独立图层，MaterialTheme 与应用 content 固定在同一个调用位置，仅更新颜色和字体参数。没有强行重开页面或增加延时。[DONE]

## 本次范围

用户已确认 503 自行恢复，并要求只处理其余问题，完成构建、合并与应用内更新发布。撤回此前为排查 503 准备的模型缓存与路由日志改动，本次不修改请求配置或缓存。

## 验证记录与待办

新增 Android 仪器回归测试：真实 OperitTheme 在公共外观与会话外观有背景、无背景两个方向来回切换，断言同一导航对象和 remember 标记不被重建。[DONE]

用户已明确授权构建、合并和发布。PR 先构建现有配对客户端产物；JVM 测试流程同时编译新增 Android 仪器测试。编译测试代码不代表已在设备上执行仪器测试。

应用内更新只接受 shared-v 发布中同时提供的 app-debug.apk 与 shared-release.json，因此沿用 Shared desktop preview 流程，在合并 main 后发布同一提交的配对版本，保留固定包名和签名。

构建与发布结果在完成后补充。手机端最终仍需确认背景开关不同的好友与群聊可以稳定停留。
