import importlib.util
import pathlib
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest import mock

path = pathlib.Path(__file__).resolve().parents[2] / "app/src/main/assets/voice_analysis/analyze.py"
spec = importlib.util.spec_from_file_location("voice_analysis", path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class AlignmentTest(unittest.TestCase):
    def test_weighted_overlap_and_no_fabricated_character_times(self):
        words = [{"word": "沈知屿", "start": .05, "end": .15}]
        frames = [{"start": 0, "end": .1, "rms": .1, "pitch_hz": 100},
                  {"start": .1, "end": .2, "rms": .1, "pitch_hz": 200}]
        result = module.align_words(words, frames, 1.35)
        self.assertEqual(1, len(result))
        self.assertEqual(-20.0, result[0]["volume_dbfs"])
        self.assertEqual(150.0, result[0]["pitch_hz"])
        self.assertEqual(1.2, result[0]["pause_after_seconds"])

    def test_unvoiced_frames_are_not_zero_pitch(self):
        words = [{"word": "嗯", "start": 0, "end": .1}]
        result = module.align_words(words, [{"start": 0, "end": .1, "rms": 0, "pitch_hz": None}], .6)
        self.assertIsNone(result[0]["pitch_hz"])
        self.assertEqual(0, result[0]["pause_after_seconds"])

    def test_empty_and_zero_duration(self):
        self.assertEqual([], module.align_words([], [], 0))
        result = module.align_words([{"word": "?", "start": 0, "end": 0}], [], 0)
        self.assertIsNone(result[0]["volume_dbfs"])
        self.assertIsNone(result[0]["pitch_hz"])


class TranscriptionInputTest(unittest.TestCase):
    def test_transcription_receives_pcm_array_and_never_fetches_model(self):
        try:
            import numpy as np
            import soundfile as sf
        except ImportError:
            self.skipTest("numpy and soundfile required for PCM integration test")

        calls = {}

        class FakeModel:
            def __init__(self, name, **kwargs):
                calls["name"] = name
                calls["kwargs"] = kwargs

            def transcribe(self, audio, **kwargs):
                calls["audio"] = audio
                calls["options"] = kwargs
                return iter([SimpleNamespace(words=[SimpleNamespace(
                    word="测试", start=0.0, end=0.1, probability=0.9)])]), SimpleNamespace(duration=0.1)

        class FakeOpenCC:
            def __init__(self, config):
                self.config = config

            def convert(self, word):
                return word.replace("聽", "听").replace("什麼", "什么")

        with tempfile.TemporaryDirectory() as directory:
            audio_path = pathlib.Path(directory) / "sample.wav"
            sf.write(audio_path, np.zeros(1600, dtype=np.float32), 16000)
            with mock.patch.dict(sys.modules, {
                "faster_whisper": SimpleNamespace(WhisperModel=FakeModel),
                "opencc": SimpleNamespace(OpenCC=FakeOpenCC),
            }):
                module.transcribe(audio_path)
            self.assertEqual("small", calls["name"])
            self.assertTrue(calls["kwargs"]["local_files_only"])
            self.assertIsInstance(calls["audio"], np.ndarray)
            self.assertEqual(np.float32, calls["audio"].dtype)
            self.assertEqual(1600, len(calls["audio"]))
            self.assertTrue(calls["options"]["word_timestamps"])
            self.assertEqual("zh", calls["options"]["language"])
            self.assertIn("园园", calls["options"]["initial_prompt"])
            self.assertTrue(calls["options"]["vad_filter"])
            self.assertEqual(200, calls["options"]["vad_parameters"]["speech_pad_ms"])
            self.assertFalse(calls["options"]["condition_on_previous_text"])
            self.assertTrue(pathlib.Path(str(audio_path) + ".words.json").exists())

    def test_invalid_sample_rate_rejected_before_model_load(self):
        try:
            import numpy as np
            import soundfile as sf
        except ImportError:
            self.skipTest("numpy and soundfile required for WAV validation test")
        with tempfile.TemporaryDirectory() as directory:
            audio_path = pathlib.Path(directory) / "not16k.wav"
            sf.write(audio_path, np.zeros(800, dtype=np.float32), 8000)
            with self.assertRaisesRegex(ValueError, "16 kHz mono WAV"):
                module.transcribe(audio_path)


class ChineseTranscriptValidationTest(unittest.TestCase):
    def test_reject_emoji_only_and_punctuation_only(self):
        for text in ("😉", "……", "?!。", "  "):
            with self.subTest(text=text):
                self.assertFalse(module.is_valid_chinese_transcript([{"word": text}]))

    def test_accept_chinese_and_repeated_words(self):
        self.assertTrue(module.is_valid_chinese_transcript([{"word": "我"}, {"word": "听见了"}]))

    def test_convert_traditional_before_persisting(self):
        try:
            from opencc import OpenCC
        except ImportError:
            self.skipTest("OpenCC dependency required for actual conversion test")
        self.assertEqual("听什么", OpenCC("t2s").convert("聽什麼"))


if __name__ == "__main__":
    unittest.main()
