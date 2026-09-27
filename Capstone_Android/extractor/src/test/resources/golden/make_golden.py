"""
Builds a golden fixture for WebEndGoldenTest: page images plus the crops the
WEB END's own extractor cuts from them, so the phone's :extractor can be held
to the same regions.

It imports Script-Checker-Web-End's backend code and calls its functions
directly. It starts no server, writes nothing to the database, and changes no
setting.

Two modes:

  sample   The repo's own sample page (extractor/src/test/resources/sample),
           with a synthetic two-page layout so a box that spans pages is
           covered. Needs only the backend's Python packages.

  paper    A real finalized paper from your local web end:
             - geometry read from the database the way the assignment pack
               builds it (routers/on_device.py, get_assignment_pack);
             - the PDF that GET /api/student/assignments/{id}/pdf serves
               (the question_pdfs row), unless --pdf gives a file;
             - pages rasterized at settings.DEFAULT_DPI, unless --pages-dir
               gives PNGs made some other way.
           Needs the web end's database running (DATABASE_URL, read from
           backend/.env when run from backend/).

Every page is also warped by a fixed perspective ("tilted", like a phone
photo held at an angle) and saved as a JPEG, and the web end extracts that
too. Both extractors are always given the same bytes.

The output holds NO answer key: no model answers, no question text. The page
images are the student paper, which the renderer prints without model
answers (routers/student.py, get_assignment_pdf).

Run with the backend's virtualenv, from Script-Checker-Web-End/backend:

  .\\.venv\\Scripts\\python.exe <this file> paper <question_id> --out <dir>
  .\\.venv\\Scripts\\python.exe <this file> sample --out <dir>
"""

from __future__ import annotations

import argparse
import glob
import io
import json
import os
import subprocess
import sys

import cv2
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))

# A fixed "phone at an angle" warp: each page corner moves by this fraction of
# the page size, onto a canvas this much larger, grey like a desk.
TILT_CORNERS = [(0.06, 0.04), (0.97, 0.00), (1.02, 1.05), (0.00, 0.98)]
TILT_CANVAS = 1.10
TILT_BACKGROUND = (96, 96, 96)


def _backend_on_path() -> None:
    backend = os.environ.get("WEBEND_BACKEND") or os.getcwd()
    if not os.path.isfile(os.path.join(backend, "services", "extractor.py")):
        sys.exit(
            "Run this from Script-Checker-Web-End/backend, or set WEBEND_BACKEND "
            "to that folder."
        )
    sys.path.insert(0, backend)


def _web_end_commit(backend: str) -> str | None:
    try:
        return subprocess.run(
            ["git", "-C", backend, "rev-parse", "--short", "HEAD"],
            capture_output=True, text=True, check=True,
        ).stdout.strip()
    except Exception:  # noqa: BLE001 - informational only
        return None


def _tilt(img: np.ndarray) -> np.ndarray:
    h, w = img.shape[:2]
    out_w, out_h = int(w * TILT_CANVAS), int(h * TILT_CANVAS)
    src = np.array([[0, 0], [w, 0], [w, h], [0, h]], dtype=np.float32)
    dst = np.array([[fx * w, fy * h] for fx, fy in TILT_CORNERS], dtype=np.float32)
    matrix = cv2.getPerspectiveTransform(src, dst)
    return cv2.warpPerspective(
        img, matrix, (out_w, out_h), flags=cv2.INTER_LINEAR,
        borderMode=cv2.BORDER_CONSTANT, borderValue=TILT_BACKGROUND,
    )


def _server_extract(extract_page, question_dict: dict, image_bytes: bytes, page_index: int) -> dict:
    result = extract_page(question=question_dict, image_bytes=image_bytes, modality="photo", page_index=page_index)
    crops = []
    for crop in result.get("crops", []):
        decoded = cv2.imdecode(np.frombuffer(crop["data"], dtype=np.uint8), cv2.IMREAD_COLOR)
        crops.append({
            "answer_box_id": crop["answer_box_id"],
            "part": crop.get("part", 0),
            "warped_bbox": crop["warped_bbox"],
            "size": [int(decoded.shape[1]), int(decoded.shape[0])],
        })
    return {
        "markers_detected": result.get("markers_detected"),
        "transform_type": result.get("transform_type"),
        "error": result.get("error"),
        "crops": crops,
    }


def _write_fixture(out_dir: str, layout: dict, question_dict: dict, pages: dict[int, np.ndarray],
                   mode: str, dpi: int, source: str) -> None:
    from services.extractor import extract_page

    os.makedirs(out_dir, exist_ok=True)
    images = []
    for page_index in sorted(pages):
        flat = pages[page_index]
        variants = [
            ("flat", f"page_{page_index}.png", ".png", flat, []),
            ("tilted", f"page_{page_index}_tilted.jpg", ".jpg", _tilt(flat), [cv2.IMWRITE_JPEG_QUALITY, 90]),
        ]
        for variant, name, ext, img, params in variants:
            ok, buf = cv2.imencode(ext, img, params)
            if not ok:
                sys.exit(f"could not encode {name}")
            data = buf.tobytes()
            with open(os.path.join(out_dir, name), "wb") as f:
                f.write(data)
            server = _server_extract(extract_page, question_dict, data, page_index)
            images.append({"file": name, "page_index": page_index, "variant": variant, "server": server})
            print(f"{name}: markers {server['markers_detected']}, {len(server['crops'])} crop(s)"
                  + (f", error: {server['error']}" if server["error"] else ""))

    backend = os.environ.get("WEBEND_BACKEND") or os.getcwd()
    golden = {
        "generated_by": "extractor/src/test/resources/golden/make_golden.py",
        "mode": mode,
        "source": source,
        "web_end_commit": _web_end_commit(backend),
        "raster_dpi": dpi,
        "layout": layout,
        "images": images,
    }
    with open(os.path.join(out_dir, "golden.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(golden, f, indent=2)
        f.write("\n")
    print(f"wrote {out_dir}")


def _markers(page_w: int, page_h: int) -> dict:
    from config import settings
    from services.doc_renderer import get_marker_positions

    return {
        "aruco_dict": settings.ARUCO_DICT,
        "marker_size_px": settings.MARKER_SIZE_PX,
        "marker_margin_px": settings.MARKER_MARGIN_PX,
        "centres": {str(k): list(v) for k, v in get_marker_positions(page_w, page_h).items()},
    }


def _question_dict_from_layout(layout: dict) -> dict:
    """The fields services/extractor.py reads (extract_page, get_page_segments)."""
    return {
        "question_id": layout["question_id"],
        "page_w_px": layout["page_w_px"],
        "page_h_px": layout["page_h_px"],
        "answer_boxes": [
            {"id": b["id"], "page_index": b["page_index"], "bbox": b["bbox"],
             "segments": b["segments"], "qr_segments": None}
            for b in layout["boxes"]
        ],
    }


# ── sample ──────────────────────────────────────────────────────────

def run_sample(out_dir: str) -> None:
    sample = os.path.join(HERE, "..", "sample")
    with open(os.path.join(sample, "layout.json"), encoding="utf-8") as f:
        old = json.load(f)
    page = cv2.imread(os.path.join(sample, "sample_page.png"), cv2.IMREAD_COLOR)
    first, second = old["answer_boxes"][0], old["answer_boxes"][1]
    # Synthetic: the one sample page stands in for both pages, and the second
    # box runs over onto page 1, as _paginate splits a tall box.
    boxes = [
        {"id": first["id"], "order_index": 0, "page_index": 0, "bbox": first["bbox"],
         "segments": [[0] + first["bbox"]]},
        {"id": second["id"], "order_index": 1, "page_index": 0, "bbox": second["bbox"],
         "segments": [[0] + second["bbox"], [1] + first["bbox"]]},
        {"id": "ab_sample_page_two", "order_index": 2, "page_index": 1, "bbox": second["bbox"],
         "segments": [[1] + second["bbox"]]},
    ]
    layout = {
        "question_id": old["layout_id"],
        "page_w_px": old["page_w_px"],
        "page_h_px": old["page_h_px"],
        "page_count": 2,
        "dpi": 150,
        "markers": _markers(old["page_w_px"], old["page_h_px"]),
        "boxes": boxes,
    }
    _write_fixture(out_dir, layout, _question_dict_from_layout(layout), {0: page, 1: page},
                   "sample", 150, "sample/sample_page.png with a synthetic two-page layout")


# ── paper ───────────────────────────────────────────────────────────

def _rasterize(pdf_bytes: bytes, dpi: int) -> list[np.ndarray]:
    try:
        from pdf2image import convert_from_bytes

        pil_pages = convert_from_bytes(pdf_bytes, dpi=dpi, fmt="png")
        return [cv2.cvtColor(np.array(p.convert("RGB")), cv2.COLOR_RGB2BGR) for p in pil_pages]
    except Exception as exc:  # noqa: BLE001 - poppler missing is the usual case on Windows
        poppler_error = exc
    try:
        import fitz  # PyMuPDF

        doc = fitz.open(stream=pdf_bytes, filetype="pdf")
        out = []
        for p in doc:
            pix = p.get_pixmap(dpi=dpi, alpha=False)
            arr = np.frombuffer(pix.samples, dtype=np.uint8).reshape(pix.height, pix.width, pix.n)
            out.append(cv2.cvtColor(arr, cv2.COLOR_RGB2BGR))
        return out
    except ImportError:
        pass
    sys.exit(
        f"Could not rasterize the PDF ({poppler_error}). Either put poppler's bin folder "
        "on PATH (pdf2image uses pdftoppm), or rasterize it yourself, e.g.\n"
        f"  pdftoppm -r {dpi} -png paper.pdf page\n"
        "and pass that folder with --pages-dir."
    )


def run_paper(question_id: str, out_dir: str, pdf_path: str | None, pages_dir: str | None) -> None:
    from config import settings
    from database import SessionLocal
    from models import Question, QuestionPdf
    from routers.questions import _question_to_dict

    db = SessionLocal()
    try:
        q = db.query(Question).filter(Question.id == question_id).first()
        if q is None or q.state != "finalized":
            sys.exit(f"No finalized question {question_id}.")
        if not (q.page_w_px and q.page_h_px):
            sys.exit("That question has no page size.")

        # The same rows and fields as get_assignment_pack.
        boxes = []
        for box in sorted(q.answer_boxes, key=lambda b: b.order_index):
            bbox = None
            if None not in (box.bbox_x, box.bbox_y, box.bbox_w, box.bbox_h):
                bbox = [box.bbox_x, box.bbox_y, box.bbox_w, box.bbox_h]
            boxes.append({"id": box.id, "order_index": box.order_index, "page_index": box.page_index,
                          "bbox": bbox, "segments": box.segments_json})
        layout = {
            "question_id": q.id,
            "page_w_px": q.page_w_px,
            "page_h_px": q.page_h_px,
            "page_count": q.page_count,
            "dpi": q.dpi,
            "markers": _markers(q.page_w_px, q.page_h_px),
            "boxes": boxes,
        }
        # What the upload route hands extract_page (routers/submissions.py).
        question_dict = _question_to_dict(q)

        dpi = settings.DEFAULT_DPI
        if q.dpi and q.dpi != dpi:
            print(f"note: the paper was laid out at {q.dpi} dpi, rasterizing at DEFAULT_DPI {dpi}")

        if pages_dir:
            files = sorted(glob.glob(os.path.join(pages_dir, "*.png")))
            if not files:
                sys.exit(f"No .png files in {pages_dir}.")
            rasters = [cv2.imread(f, cv2.IMREAD_COLOR) for f in files]
            source = f"pages from {pages_dir}"
        else:
            if pdf_path:
                with open(pdf_path, "rb") as f:
                    pdf_bytes = f.read()
                source = f"PDF {os.path.basename(pdf_path)}"
            else:
                row = db.query(QuestionPdf).filter(QuestionPdf.question_id == question_id).first()
                if row is None or not row.data:
                    sys.exit("No PDF has been generated for this question.")
                pdf_bytes = bytes(row.data)
                source = "question_pdfs row (what /api/student/assignments/{id}/pdf serves)"
            rasters = _rasterize(pdf_bytes, dpi)
    finally:
        db.close()

    if q.page_count and len(rasters) != q.page_count:
        print(f"warning: {len(rasters)} page image(s) for a {q.page_count}-page paper")
    for i, r in enumerate(rasters):
        print(f"page {i}: {r.shape[1]}x{r.shape[0]} px (canonical {q.page_w_px}x{q.page_h_px})")
    _write_fixture(out_dir, layout, question_dict, dict(enumerate(rasters)), "paper", dpi,
                   f"question {question_id}, {source}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="mode", required=True)
    s = sub.add_parser("sample")
    s.add_argument("--out", default=os.path.join(HERE, "sample"))
    p = sub.add_parser("paper")
    p.add_argument("question_id")
    p.add_argument("--out", required=True)
    p.add_argument("--pdf", help="use this PDF instead of the one in the database")
    p.add_argument("--pages-dir", help="use these PNGs (sorted by name) instead of rasterizing")
    args = parser.parse_args()

    _backend_on_path()
    if args.mode == "sample":
        run_sample(args.out)
    else:
        run_paper(args.question_id, args.out, args.pdf, args.pages_dir)


if __name__ == "__main__":
    main()
