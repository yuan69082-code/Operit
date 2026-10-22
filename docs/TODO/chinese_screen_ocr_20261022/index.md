---
repository: https://github.com/yuan69082-code/Operit
branch: fix/chinese-screen-ocr
status: source-change-complete-device-validation-pending
---

# 中文屏幕 OCR 修正

## 原状与目标

默认 OCR 对同一画面分别运行拉丁与中文模型，再拼接两份不同的全文。中文模型已经支持拉丁字符，第二份转写会混入重复内容和错误文字。高质量模式只允许完整放大两倍，常见 1080×2400 屏幕因此完全跳过放大。

本次保持现有屏幕感知、圈选和附件调用接口，修正这两处实现。不增加模型请求、不上传截图、不使用语言模型改写识别结果，也不更换 OCR 引擎。中文模型本身的艺术字体识别误差仍需手机验证。

## 实现

- [x] Bitmap 与 URI 默认入口统一运行一次中文识别，输出一份中英文文本
- [x] 高质量模式按长边 4096 和 800 万像素预算，最多放大两倍；原图已超预算则保留原尺寸，不分配放大副本
- [x] 保留显式指定语言的入口及所有现有参数，自动感知与圈选无需改配置
- [x] 添加长屏、横屏、方图内存预算、超大原图及非法尺寸回归用例

## 验证与限制

完成静态调用路径审阅和差异空白检查。未执行编译或测试，未安装到手机；新增单元用例尚未运行。尺寸用例不验证 ML Kit 的文字准确率，不能作为错字已解决的证据。

手机验收应对同一屏幕分别核对中文艺术字体、普通字体、纯英文和中英数字混排；检查正文是否只出现一次，对照原图逐字记录错字与漏字，并确认横屏游戏文字识别和唤醒耗时。不得依据句意自动把“体”替换成“你”，否则会制造新的错误。

官方参考：[中文识别器同时支持中文与拉丁文字](https://developers.google.com/android/reference/com/google/mlkit/vision/text/chinese/ChineseTextRecognizerOptions)、[输入图片要求](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)。

[DONE] 源码修改；手机准确率与耗时验收待完成。
