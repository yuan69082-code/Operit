import importlib.util
import pathlib
import unittest

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


if __name__ == "__main__":
    unittest.main()
