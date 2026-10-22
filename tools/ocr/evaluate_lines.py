"""Evaluate private line samples locally; images and expected text stay outside the repo.

Run in a venv with numpy, opencv-python and onnxruntime installed. Cases JSON is a
list of {"box": [left, top, right, bottom], "expected": "..."} objects. Bounds
include the desired line border, matching the Android recognizer input.
"""

import argparse
import json
import math
import time

import cv2
import numpy as np
import onnxruntime as ort


def distance(expected, actual):
    previous = list(range(len(actual) + 1))
    for row, left in enumerate(expected, 1):
        current = [row]
        for column, right in enumerate(actual, 1):
            current.append(min(current[-1] + 1, previous[column] + 1,
                               previous[column - 1] + (left != right)))
        previous = current
    return previous[-1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True)
    parser.add_argument("--image", required=True)
    parser.add_argument("--cases", required=True)
    args = parser.parse_args()
    with open(args.cases, encoding="utf-8") as stream:
        cases = json.load(stream)
    image = cv2.imread(args.image)
    if image is None:
        raise ValueError("Cannot read sample image")
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(args.model, sess_options=options, providers=["CPUExecutionProvider"])
    labels = [""] + session.get_modelmeta().custom_metadata_map["character"].rstrip("\r\n").split("\n") + [" "]
    crops = []
    for case in cases:
        left, top, right, bottom = case["box"]
        if not (0 <= left < right <= image.shape[1] and 0 <= top < bottom <= image.shape[0]):
            raise ValueError("Case bounds must be inside the image")
        crops.append(image[top:bottom, left:right])
    indices = list(range(len(crops)))
    texts = [""] * len(crops)
    started = time.perf_counter()
    for start in range(len(indices)):
        batch = indices[start:start + 1]
        width = max(320, min(2048, max(int(48 * crops[i].shape[1] / crops[i].shape[0]) for i in batch)))
        tensor = np.zeros((len(batch), 3, 48, width), np.float32)
        for slot, index in enumerate(batch):
            crop = crops[index]
            resized_width = min(math.ceil(48 * crop.shape[1] / crop.shape[0]), width)
            pixels = cv2.resize(crop, (resized_width, 48)).astype(np.float32)
            tensor[slot, :, :, :resized_width] = (pixels.transpose(2, 0, 1) / np.float32(255) - np.float32(.5)) / np.float32(.5)
        scores = session.run(None, {session.get_inputs()[0].name: tensor})[0]
        if scores.shape[2] != len(labels):
            raise ValueError("Model output does not match its dictionary")
        for slot, index in enumerate(batch):
            previous, result = -1, []
            for token in scores[slot].argmax(axis=1):
                if token != 0 and token != previous:
                    result.append(labels[token])
                previous = token
            texts[index] = "".join(result)
    errors = sum(distance(case["expected"], text) for case, text in zip(cases, texts))
    characters = sum(len(case["expected"]) for case in cases)
    # Aggregate results only: don't write private text or image paths into CI logs.
    print(json.dumps({"cases": len(cases), "exact_matches": sum(c["expected"] == t for c, t in zip(cases, texts)),
                      "character_edits": errors, "reference_characters": characters,
                      "elapsed_seconds": round(time.perf_counter() - started, 3)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
