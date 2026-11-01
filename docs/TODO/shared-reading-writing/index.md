---
repo: https://github.com/yuan69082-code/Operit
branch: feat/shared-reading-writing
---
# 共读与写作

在现有书房新增共读和写作，两者均分 Sex / 日常。共读导入 TXT、EPUB 或公开网页；写作按章节保存，你与助手可共同创作。选中文字后留下原文下方的批注，选择字体颜色，附照片或手绘；读后感独立于原文。

复用书架、阅读页、CompanionStore、助手身份校验和单线程唤醒调度。章节版本不可变，批注绑定章节版本和字符区间。用户完成阅读、发布章节或留言产生持久事件；助手批注产生未读标记和通知。长篇按章按页交给模型，不塞满每轮上下文。

详见 [01-implementation.md](01-implementation.md)。
