# 素材与验证

## 界面素材

`app/src/main/res/drawable-nodpi/reading_shelf.png` 是通过内置 image_gen 生成的木质书格装饰，不是文章内容。书脊、标题、计数与交互由原生 Compose 绘制。

生成提示：

Use case: photorealistic-natural. Asset type: background texture for a real Android bookshelf UI, not a mockup. Create ONE front-facing empty oak bookshelf cubby, landscape 3:2. Entire frame is the inside of a wooden shelf, with warm cream plaster at back, pale golden oak vertical sides, warm sunlight and soft leaf shadows from left. One horizontal wood shelf board runs across full bottom at exactly 88 percent of image height, its front lip occupies bottom 12 percent. The top 88 percent is empty usable space where app will overlay vertical books and a small category title. A narrow cascading ivy at extreme left 8 percent only; tiny fern at far right edge 8 percent only. Central 84 percent absolutely EMPTY, no books, no text, no objects, no frame around image, no phone, no labels. Fine realistic oak grain, tactile wood, soft afternoon light, cream and muted forest green palette, straight horizontal perspective. This should look like the warm elegant physical bookshelf in a private reading room. No stars, no decorative writing, no watermark.

中文手写字体使用 [Long Cang](https://github.com/google/fonts/tree/main/ofl/longcang)，原字体未修改，随应用打包。SIL OFL 1.1 许可随包放在 `assets/licenses/LongCang-OFL.txt`。字体路径为 `res/font/reading_handwriting.ttf`。阅读页面不额外调用模型。

## 数据兼容

旧日记、正文、摘要、作者和批注字段继续使用。新获取的网页额外保存 `page_blocks`，按来源顺序排列文字与最多 12 张图片；未迁移、重写旧内容。纯文本正文仍供现有工具读取。

新随记和用户批注分别记录 `ai_note_at`、`user_note_at`；旧记录无法确定助手随记日期时不展示推测日期。用户查看书页、获取正文均不标记助手已读。

## 检查记录

Android Tests #115 在 cb7815b 上完成编译及全部 JVM 检查：1599 项，0 失败，6 项原有跳过。ReadingPage 5 项、ReadingRoom 9 项、AssistantDiary 3 项全部通过。新增检查覆盖图文顺序、正文长度、非网页图片排除、重复图片、普通旧网页解析以及批注和随记时间隔离。

首轮测试发现 Jsoup absUrl 在重建 URL 时会丢掉凭据。图片现在直接从原始 src 使用 OkHttp 解析并校验，防止校验归一化结果丢失信息。最后的编译与测试报告已验证修正。[DONE]

手机安装后的验收入口：助手房间 → 阅读角 → 书架；日记、待读、已读每层横向滑动，点书脊进入书页。来源和自主探索仍在书架上方。需另行确认手机上的最终视觉效果。
