"""Local voice analysis, shared by Operit Linux terminal and desktop Python.

Install: python -m pip install faster-whisper==1.2.1 librosa==0.11.0
Run: python analyze.py all recording.wav
No recording is uploaded. First transcription downloads the base model.
"""
import argparse
import json
import math
import os
from pathlib import Path


def save(path, value):
    target = Path(str(path) + ".tmp")
    target.write_text(json.dumps(value, ensure_ascii=False, allow_nan=False), encoding="utf-8")
    os.replace(target, path)


def transcribe(source):
    from faster_whisper import WhisperModel
    model = WhisperModel("base", device="cpu", compute_type="int8", cpu_threads=2)
    segments, info = model.transcribe(str(source), word_timestamps=True, beam_size=5,
                                     vad_filter=False, condition_on_previous_text=False)
    words = []
    for segment in segments:
        for word in segment.words or []:
            words.append({"word": word.word, "start": float(word.start), "end": float(word.end),
                          "probability": float(word.probability)})
    save(str(source) + ".words.json", {"words": words, "duration": float(info.duration)})


def features(source):
    import librosa
    import numpy as np
    import soundfile as sf
    # Bounded 30-second blocks, with overlap for pitch tracking at block boundaries.
    with sf.SoundFile(source) as audio:
        if audio.samplerate != 16000 or audio.channels != 1:
            raise ValueError("Expected 16 kHz mono WAV")
        sample_rate = audio.samplerate
        frames = []
        block = 30 * sample_rate
        for start in range(0, len(audio), block):
            left = max(0, start - 2048)
            audio.seek(left)
            samples = audio.read(min(block + 4096, len(audio) - left), dtype="float32")
            # pYIN marks unvoiced frames NaN; do not invent a pitch for them.
            f0, voiced, _ = librosa.pyin(samples, fmin=65, fmax=1000, sr=sample_rate,
                                        frame_length=1024, hop_length=1600, center=True)
            pitch_times = left / sample_rate + librosa.frames_to_time(
                np.arange(len(f0)), sr=sample_rate, hop_length=1600)
            for offset in range(start, min(start + block, len(audio)), 1600):
                local = offset - left
                window = samples[local:local + 1600]
                rms = float(librosa.feature.rms(y=window, frame_length=len(window),
                                               hop_length=len(window), center=False)[0, 0])
                center = offset / sample_rate + len(window) / (2 * sample_rate)
                index = int(np.argmin(np.abs(pitch_times - center)))
                pitch = float(f0[index]) if voiced[index] and np.isfinite(f0[index]) else None
                frames.append({"start": offset / sample_rate,
                               "end": (offset + len(window)) / sample_rate,
                               "rms": rms, "pitch_hz": pitch})
    save(str(source) + ".frames.json", frames)


def align_words(words, frames, duration):
    """Weight intersecting 0.1 s frames by overlap, never interpolate word boundaries."""
    result = []
    cursor = 0
    for index, word in enumerate(words):
        start, end = word["start"], word["end"]
        while cursor < len(frames) and frames[cursor]["end"] <= start:
            cursor += 1
        total = energy = pitch_sum = pitch_weight = 0.0
        for frame_index in range(cursor, len(frames)):
            frame = frames[frame_index]
            if frame["start"] >= end:
                break
            weight = max(0, min(end, frame["end"]) - max(start, frame["start"]))
            total += weight
            energy += frame["rms"] ** 2 * weight
            if frame["pitch_hz"] is not None:
                pitch_sum += frame["pitch_hz"] * weight
                pitch_weight += weight
        dbfs = max(-120.0, 10 * math.log10(max(energy / total, 1e-12))) if total else None
        next_start = words[index + 1]["start"] if index + 1 < len(words) else duration
        gap = max(0.0, next_start - end)
        result.append(dict(word, volume_dbfs=round(dbfs, 1) if dbfs is not None else None,
                           pitch_hz=round(pitch_sum / pitch_weight, 1) if pitch_weight else None,
                           pause_after_seconds=round(gap, 2) if gap > .5 else 0))
    return result


def align(source):
    transcript = json.loads(Path(str(source) + ".words.json").read_text(encoding="utf-8"))
    frames = json.loads(Path(str(source) + ".frames.json").read_text(encoding="utf-8"))
    aligned = align_words(transcript["words"], frames, transcript["duration"])
    lines = []
    for word in aligned:
        volume = "未知" if word["volume_dbfs"] is None else str(word["volume_dbfs"]) + " dBFS"
        pitch = "无可靠基频" if word["pitch_hz"] is None else str(word["pitch_hz"]) + " Hz"
        line = f'{word["word"].strip()}｜{word["start"]:.2f}–{word["end"]:.2f}秒｜音量{volume}｜音高{pitch}'
        if word["pause_after_seconds"]:
            line += f'｜后面间隔{word["pause_after_seconds"]}秒'
        lines.append(line)
    # Full measurements stay beside the WAV; AI gets a compact, bounded excerpt.
    context = "\n".join(lines[:400])
    if len(lines) > 400:
        context += f"\n其余{len(lines) - 400}个词的测量已保存到录音旁的 analysis.json。"
    save(str(source) + ".analysis.json", {"words": aligned, "context": context})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("stage", choices=["transcribe", "features", "align", "all"])
    parser.add_argument("audio", type=Path)
    args = parser.parse_args()
    for name, operation in [("transcribe", transcribe), ("features", features), ("align", align)]:
        if args.stage in (name, "all"):
            operation(args.audio)
    if args.stage in ("align", "all"):
        value = json.loads(Path(str(args.audio) + ".analysis.json").read_text(encoding="utf-8"))
        print(json.dumps({"context": value["context"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
