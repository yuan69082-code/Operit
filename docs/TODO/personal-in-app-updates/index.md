---
fork: https://github.com/yuan69082-code/Operit
---

# 个人版应用内更新

个人 Debug 包 com.ai.assistance.operit.debug 从 yuan69082-code/Operit 的已发布 Releases 获取 app-debug.apk，跳过草稿、预发布和没有该 APK 的版本。它不接收上游正式 APK 或 Nightly 补丁。其他包的更新行为保持原样，复用现有应用内下载及 Android 安装确认界面。

Android Build 在个人 Debug 构建时传入 OPERIT_PERSONAL_BUILD_NUMBER。版本名为 1.12.2+构建序号，版本码为 5100000+构建序号，支持从第 12 次构建覆盖安装。若基础版本更改，需同步调整 Gradle 与发布校验中的版本常量。

流水线显式绑定 OPERIT_DEBUG_KEYSTORE_FILE，并在上传和发布前用 apksigner 检查最终 APK 的固定个人证书。构建成功后，使用 job 级 contents: write 权限将 APK 发布到同版本标签的 Release。gh release create 携带 APK，上传完成后发布。签名密钥 Secret 不应删除或替换。

第 12 版尚未接入个人更新源，需要手动覆盖安装一次含本次改动的版本；后续在关于页面检查更新，选择应用内下载并确认安装。第 2 版签名不同，不能直接覆盖，需完整备份后迁移。更新不等同于无确认静默安装。

静态核对了版本比较支持 +序号、APK 资源名称、更新入口和发布顺序。未运行本地编译或测试；端到端结果以 CI 和设备安装为准。

[DONE]
