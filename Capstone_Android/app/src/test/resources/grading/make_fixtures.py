"""
Renders the blank-detection images exactly as
Script-Checker-Web-End/backend/tests/test_grading.py::_image does, saves them
as PNG test fixtures for the Android port, and records what the server's own
looks_blank says about each. Also runs the server's parse_grading_response on
the reply cases the Kotlin tests use. Read-only against the web end.
"""
import io
import json
import os
import sys

BACKEND = sys.argv[1]
OUT = sys.argv[2]
sys.path.insert(0, BACKEND)

from services.grading import (  # noqa: E402
    AnswerToGrade, GRADING_SYSTEM_PROMPT, build_user_message, looks_blank, parse_grading_response,
)
from PIL import Image, ImageDraw, ImageFont  # noqa: E402


def _image(lines=(), bg="white", size=(900, 300)):
    font = ImageFont.load_default(size=40)
    im = Image.new("RGB", size, bg)
    d = ImageDraw.Draw(im)
    for i, line in enumerate(lines):
        d.text((30, 20 + i * 70), line, fill="black", font=font)
    buf = io.BytesIO()
    im.save(buf, format="PNG")
    return buf.getvalue()


cases = {
    "pure_white": _image(),
    "photographed_grey": _image(bg="#d8d8d8"),
    "faint_short_answer": _image(["x = 3"]),
    "full_working": _image(["2x + 4 = 10", "2x = 6", "x = 3"]),
}
os.makedirs(OUT, exist_ok=True)
blank = {}
for name, data in cases.items():
    with open(os.path.join(OUT, name + ".png"), "wb") as f:
        f.write(data)
    blank[name] = looks_blank(data)

replies = {
    "normal": ("SCORE: 3\nFEEDBACK: Good method, arithmetic slip.", 5),
    "decimal": ("SCORE: 2.5\nFEEDBACK: Half credit.", 5),
    "unreadable": ("SCORE: UNREADABLE\nFEEDBACK: Photo is blurred.", 5),
    "ignored_format": ("I think this deserves about 4 marks", 5),
    "banana": ("SCORE: banana\nFEEDBACK: hmm", 5),
    "empty": ("", 5),
    "above_max": ("SCORE: 9\nFEEDBACK: x", 5),
    "negative": ("SCORE: -2\nFEEDBACK: x", 5),
    "full": ("TRANSCRIPT: 2x = 6\nx = 3\nSCORE: 4\nFEEDBACK: Correct method.", 5),
    "nothing_written": ("TRANSCRIPT: NOTHING WRITTEN\nSCORE: 3\nFEEDBACK: Nice.", 5),
    "nothing_variant": ("TRANSCRIPT: (blank)\nSCORE: 2\nFEEDBACK: ok", 5),
    "empty_transcript": ("TRANSCRIPT:\nSCORE: 2\nFEEDBACK: ok", 5),
    "na": ("TRANSCRIPT: n/a\nSCORE: 1\nFEEDBACK: ok", 5),
    "dashes": ("TRANSCRIPT: ---\nSCORE: 1\nFEEDBACK: ok", 5),
    "nothing_in_sentence": ("TRANSCRIPT: nothing to add, x = 3\nSCORE: 1\nFEEDBACK: ok", 5),
    "feedback_then_score": ("FEEDBACK: fine SCORE: 2\nSCORE: 2", 5),
    "score_with_suffix": ("SCORE: 4/5 marks\nFEEDBACK: ok", 5),
    "lowercase": ("transcript: x=3\nscore: 5\nfeedback: ok", 5),
    "crlf": ("TRANSCRIPT: x = 3\r\nSCORE: 3\r\nFEEDBACK: Good.\r\n", 5),
    "missing_score": ("TRANSCRIPT: x = 3\nFEEDBACK: ok", 5),
    "missing_feedback": ("TRANSCRIPT: x = 3\nSCORE: 3", 5),
    "unreadable_lower": ("TRANSCRIPT: scribbles\nSCORE: unreadable, too faint\nFEEDBACK: retake", 5),
    "max_exact": ("SCORE: 5\nFEEDBACK: full", 5),
    "zero": ("SCORE: 0\nFEEDBACK: wrong", 5),
    "arabic_digit": ("SCORE: ٣\nFEEDBACK: ok", 5),
}
parsed = {k: parse_grading_response(t, m) for k, (t, m) in replies.items()}

IMG = (b"X", "image/png")
messages_in = {
    "crop_only": dict(label="", max_score=5, question_text="Solve 2x+4=10", ground_truth_text="x = 3", crops=[IMG]),
    "labelled": dict(label="Q1(a)", max_score=3, question_text="  Differentiate x^2 ", ground_truth_text="2x", crops=[IMG, IMG]),
    "gt_images": dict(label="", max_score=5, question_text="Q", ground_truth_text="", ground_truth_images=[IMG], crops=[IMG, IMG]),
    "all_three": dict(label="", max_score=5, question_text="Find angle x in the figure", ground_truth_text="40",
                      question_images=[IMG], ground_truth_images=[IMG], crops=[IMG]),
    "question_only_images": dict(label="b", max_score=10, question_text="", ground_truth_text="x", question_images=[IMG, IMG], crops=[IMG]),
}
messages = {k: {"in": {kk: (len(vv) if isinstance(vv, list) else vv) for kk, vv in v.items()},
                "out": build_user_message(AnswerToGrade(answer_box_id="a1", **v))}
            for k, v in messages_in.items()}

with open(os.path.join(OUT, "server_expectations.json"), "w", encoding="utf-8") as f:
    json.dump({"looks_blank": blank, "user_messages": messages, "replies": {k: {"text": replies[k][0], "max": replies[k][1], "parsed": parsed[k]} for k in replies}},
              f, indent=2, ensure_ascii=False)
with open(os.path.join(OUT, "server_prompt.txt"), "w", encoding="utf-8", newline="") as f:
    f.write(GRADING_SYSTEM_PROMPT)
print(json.dumps(blank))
for k, v in parsed.items():
    print(k, v)
