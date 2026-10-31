---
fork: https://github.com/yuan69082-code/Operit
---

# 自动交付

现状：PR 构建完仍需人工合并，main 再次打包才发布。

方案：同一次 Shared desktop preview 运行验证同一候选提交，执行 Windows 检查、Android 单测和签名打包；通过后自动合并本仓库非草稿 PR，并发布该次运行的成对产物。发布前核对实际合并树与已构建树一致。主线变化时自动调度新候选验证。

作用域：shared-desktop、android-build、android-tests 工作流，自动交付脚本及隔离的模拟 API 测试。保留现有 shared-v 标签、版本计数、签名和更新清单格式。

## 使用与边界

本仓库由仓库所有者创建、目标为 main 的非草稿 PR 默认自动交付。草稿及外部 PR 只验证，不自动合并或发布。需要暂缓的工作应保留为草稿。

Android 单测、Android 签名打包和 Windows 检查构建都读取 prepare 输出的同一候选 SHA。发布 job 依赖全部通过，并检查安装包、清单和实际合并树。GITHUB_TOKEN 完成合并后不依赖新的 push 事件，直接发布本次运行产物。

主线变化则调度 shared-desktop 的 workflow_dispatch，带 pr_number 重新生成候选；合并期间发生主线竞争则构建真实 main。无法合并或测试失败时停止，不绕过 GitHub 分支保护。

版本继续使用 Shared desktop preview 的 run_number；晚完成的旧版本不会覆盖新版本。上传先创建草稿 Release，全部上传后才公开。发布中断可重跑失败 job，沿用同一安装包和版本号。

后续修改只需提交并创建非草稿 PR；确认自动流水线启动后即可结束交互，无需等待后再手动合并。失败时查看 Actions，不宣称已发布。

## 验证

10 项隔离模拟 API 测试通过，覆盖正常合并、主线前进、合并竞争、PR head 变化、草稿/外部仓库、错误清单、合并失败、重跑、重复发布及版本顺序。三个修改后的 workflow 通过 YAML 解析。首次 GitHub 实际运行仍需确认 token 权限及完整发布链路。

[DONE] 实现和本地验证完成；由本次 PR 的自动流程完成首次线上验证及交付。
