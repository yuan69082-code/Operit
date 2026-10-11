# Vio Life

English | [中文](README.zh-CN.md)

Vio Life is a personal Android AI assistant fork of [Operit](https://github.com/AAswordman/Operit). This repository maintains the mobile edition with chat, voice calls, independent assistants, group conversations, memory, a library, and authorized device tools.

## Install and update

Download `app-debug.apk` from [this repository's latest release](https://github.com/yuan69082-code/Operit/releases/latest), or check for updates inside a personal build already connected to this update source.

Vio Life keeps the previous personal build's application identity and persistent signing certificate, allowing an in-place update that preserves data. Do not uninstall first. Builds signed by other distributors, including upstream, may not support an in-place update.

The repository URL, Android package name, existing storage paths, and plugin identifiers remain compatible. Renaming does not rewrite existing assistant names, settings, or conversations.

## Features

- Independent assistants, model and tool configurations, and multi-assistant group chat.
- Text, voice, images, files, and user/assistant sticker collections.
- AI-reviewed context summaries, memory, a library, to-dos, and proactive contact.
- Optional XC state integration, silence detection, and segmented call recording.
- Camera, gallery, system tools, MCP, packages, and workspaces within granted permissions.

Availability depends on device permissions, configuration, and connected models and services.

## Development

- [Build guide](docs/doc-src/dev-core/BUILDING.md)
- [Contribution guide](docs/doc-src/dev-core/CONTRIBUTING.md)
- [Script development](docs/SCRIPT_DEV_GUIDE.md)
- [ToolPkg format](docs/TOOLPKG_FORMAT_GUIDE.md)
- [Branding change record](docs/TODO/vio-life-branding/index.md)

Building requires external dependency archives, an initialized `terminal` submodule, and the verified local speech model configured by the project. See the build guide and this repository's release workflow.

## Attribution and license

Thanks to the Operit Team and [upstream contributors](https://github.com/AAswordman/Operit/graphs/contributors). This personal fork is not an official upstream distribution. External projects, including the Operit marketplace, OperitTerminal, and Operit 2, retain their names and attribution. [Upstream documentation](https://operit.app/) may differ from this fork.

The main project retains GNU LGPL v3 as stated in [LICENSE](LICENSE). Tools, examples, templates, and third-party dependencies follow their respective licenses and metadata. Original copyright notices and licenses are retained.
