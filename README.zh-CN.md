# Vio Life

[English](README.md) | 中文

Vio Life 是基于 [Operit](https://github.com/AAswordman/Operit) 修改的个人 Android AI 助手。本仓库维护移动端版本，提供聊天、语音通话、助手与群聊、记忆与资料库，以及权限范围内的手机工具。

## 安装与更新

从本仓库的 [最新发布](https://github.com/yuan69082-code/Operit/releases/latest) 下载 `app-debug.apk`，也可以在已接入本更新源的应用内检查更新。

Vio Life 沿用此前个人版的安装包身份和固定签名，可以覆盖安装并保留原有数据。不要先卸载旧版。其他签名的上游版本不保证能够直接覆盖安装。

GitHub 仓库地址、Android 包名、已有文件目录和插件接口保留兼容标识，软件显示名称为 Vio Life。已有助手姓名、设定及聊天内容不会因为改名而被重写。

## 功能

- 独立助手、模型与工具配置，以及多助手群聊。
- 文本、语音、图片、文件与双方表情包收藏。
- 经 AI 审阅的上下文摘要、记忆与资料库、待办和主动联系。
- 可选的 XC 状态连接、通话静默检测与分段录音。
- 在用户授权范围内使用相机、相册、系统工具、MCP、工具包和工作区。

各功能的可用性取决于设备权限、用户配置以及所连接的模型和服务。

## 开发

- [构建说明](docs/doc-src/dev-core/BUILDING.md)
- [贡献说明](docs/doc-src/dev-core/CONTRIBUTING.md)
- [脚本开发](docs/SCRIPT_DEV_GUIDE.md)
- [ToolPkg 格式](docs/TOOLPKG_FORMAT_GUIDE.md)
- [本次品牌变更](docs/TODO/vio-life-branding/index.md)

构建需要外部依赖归档、初始化的 `terminal` 子模块，以及按照配置校验下载的本地语音模型。具体步骤见构建说明和本仓库发布工作流。

## 来源与许可

感谢 Operit Team 和 [上游贡献者](https://github.com/AAswordman/Operit/graphs/contributors) 的工作。本个人分支不代表上游官方发行；Operit 市场、OperitTerminal 和 Operit 2 等外部项目保留原名称与作者归属。[上游文档](https://operit.app/) 中的界面与本个人分支可能不同。

主项目沿用 [LICENSE](LICENSE) 中的 GNU LGPL v3。工具、示例、模板和第三方依赖以各自许可证及包元数据为准。原作者版权声明和许可证保留。
