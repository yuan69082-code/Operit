---
repo: https://github.com/yuan69082-code/Operit
---

# 语音录制反馈与持久化发送

现有入口无录制反馈、AI 忙时禁用、发送失败没有可恢复任务。录音和发送分离；录音直接写 WAV，松手建立持久化待发送项，转写与排队独立于输入框生命周期。失败保留录音及转写结果，可重试；历史落盘后才确认发送。

范围：聊天两种输入样式、录音器、音频分析分段、发送确认、语音任务 UI、本地声学分析。

手机本地 Python 路径需要核实运行环境，不能将推测的字时间标成实测。音量采用 dBFS、音高采用 Hz，未知音高不能标成零。

## 实现

- 录音覆盖层使用实际 RMS 历史采样绘制波形和录音秒数，不改变正在按住的手势目标。忙时短按仍停止回复，长按录音。
- 待发项独立于模型历史，AtomicFile 保存录音路径、原会话、稳定消息时间戳、归档位置、转写和本地分析。失败不删除 WAV。重启显示待重试；重试前按消息时间戳核对历史，避免历史已落盘但确认中断造成重复气泡。
- 通过原消息管线的落盘回调确认发送，语音队列不得读取或清除正在编辑的草稿。抢占失败有明确失败回调。
- WAV 以磁盘缓冲替代整段内存缓冲，120 秒为单次模型请求分段而非录制上限。通话连续模式原有自动分段规则不变，手动模式只由显式提交结束。
- LocalTerminalProvider 明确使用手机本地 proot，不跟随 SSH 设置。首次准备 Python venv、faster-whisper 1.2.1、librosa 0.11.0；Whisper small / CPU int8。依赖安装只在首次准备时尝试，之后不再重复运行 pip；声学分析转写使用 HF_HUB_OFFLINE=1 + local_files_only=True，small 缓存缺失时不自动下载，需预先缓存模型。soundfile 解码 16 kHz 单声道 WAV 为 NumPy 数组，避免 PyAV 的文件解码路径。Linux 根文件系统及 Python 必须可用，失败原样标红并保留任务。
- 顺序执行 Whisper word_timestamps、librosa 特征、按词时间加权对齐。0.1 秒 RMS 取能量平均后换算 dBFS；pYIN 无声帧不记零音高；超过 0.5 秒的词间隔标出。中文多字词不虚构逐字时间；间隔不保证是静音。完整 JSON 随 WAV 保存，AI 获得最多 400 个词的紧凑摘要并标注截断。

## 电脑运行

同一脚本位于 `app/src/main/assets/voice_analysis/analyze.py`。在电脑 Python venv 安装 `faster-whisper==1.2.1 librosa==0.11.0`、联网预缓存 `small` 模型后，使用 `HF_HUB_OFFLINE=1 python analyze.py all recording.wav`，输入为 16 kHz 单声道 WAV。模型预缓存示例：`python -c \"from faster_whisper import WhisperModel; WhisperModel('small', device='cpu', compute_type='int8')\"`。电脑自动上传/远程处理未接入，手机不依赖电脑。

## 验证

- Python 词时间/帧交集加权、无声基频、零时长与严格大于 0.5 秒边界：3 项通过。
- librosa 实际处理合成 200 Hz 波形及静音：得到每 0.1 秒一帧、约 -23 dBFS、200 Hz，静音无可靠基频。
- 新增 JVM 测试覆盖 75 秒 WAV、文件所有权和取消清理；更新手动分段测试，60 秒和 1 小时均不自动结束。
- 当前开发环境没有 Android SDK，完整编译和 JVM 测试交由 GitHub 构建；未声称手机端实测通过。

[DONE] 源码实现；待云端构建及手机验收。

## 分析失败后的显式选择

本地声学分析的异常单独记录，不再混同于原模型转写失败。原转写已保存时显示“直接发送”和“重新分析”；直接发送必须由用户点击，并将本地分析失败标记同时写入历史正文、AI 上下文与气泡显示。重试发送保留已选择的直接发送方式，重新分析则清除该选择并只重做本地分析。失败及选择字段持久化，旧任务缺少字段时按未选择读取。

新增显示回归用例覆盖原转写保留、失败标记可见及成功分析无错误标记；JVM 执行交由云端。源码差异检查通过。

## 本地 small 模型与离线规则（2026-10-25）

本地声学分析切换 `small`，不更改原有模型转写。手机端首次安装 Python 依赖仍可能联网；从第二次开始检测安装标记并跳过 pip。**每次转写**均强制 `HF_HUB_OFFLINE=1` 并将 `local_files_only=True` 传给 WhisperModel，不从 Hugging Face 下载。安装好依赖后，应在同一个手机本地 Linux 用户与缓存目录下预缓存 `small`；只有 `base` 缓存并不够。缺少 `small` 会触发已有的分析失败分支，可选择直接发送（标注失败）或准备缓存后重试。没有强制安装 av==13.1.0；使用 soundfile 解码 WAV 后传 NumPy 数组绕开 PyAV 文件解码，但 faster-whisper 自身导入仍依赖 av，不能保证绕过 av 的导入失败。
