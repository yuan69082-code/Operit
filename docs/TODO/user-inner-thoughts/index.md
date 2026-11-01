---
repo: https://github.com/yuan69082-code/Operit
branch: feat/user-inner-thoughts
---

# 用户心里话与正文双栏

现有 Android 输入框只有正文，用户希望显式输入未说出口的心里话，并在气泡里区分显示。

沿用当前消息 content 和草稿生命周期，不迁移数据库，不改变旧消息。新增用户心里话编辑区，心里话使用衬线斜体和较浅颜色，发送后可折叠，正文保持现有字体。

作用域为 Android Classic/Agent 两种输入样式、Bubble/Cursor 两种消息样式、共享消息编码。协议作为普通 user 内容发送给各供应商，说明“未说出口”和“实际说出口”的区别，不冒充 assistant reasoning，也不要求模型暴露内部推理。

实施及验证见 [01-implementation.md](01-implementation.md)。
