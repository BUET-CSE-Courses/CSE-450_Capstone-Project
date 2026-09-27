# `mobile_Extract/` — what is actually here

> Produced 2026-08-28 by reading all 15 source/config files and by **executing
> `extractor.py` against the bundled `sample/`**. Claims are tagged:
>
> | Tag | Meaning |
> |---|---|
> | **VERIFIED** | Executed and observed during this read-through |
> | **CODE-READ** | Read in the source and traced, but never executed |
> | **UNVERIFIED** | Written, but nothing here proves it works |
>
> Nothing in this folder was modified. This file is the only addition.

---

## 0. What it is, in one paragraph

A **standalone Python FastAPI service** (title "Extraction Service", version
0.1.0, default public base URL `http://localhost:8001`) that takes a
photo/scan/PDF of a pre-printed worksheet page carrying four ArUco corner
markers, registers that image against a stored **layout** (a list of canonical
answer-box rectangles), and cuts one PNG crop per answer box out of the photo.
It stores the crops as BLOBs in SQLite and serves them back over HTTP.

It is **not Android code**, it is **not part of `Capstone_Android` or
`ASC_Capstone`**, and it shares no code, database, or auth with them. It looks
like a drop-in from a separate project (a stray macOS `.DS_Store` is present;
the folder is **not** a git repo and has no history — VERIFIED).

**It does no OCR, no handwriting recognition, and no grading.** The output is
cropped images plus geometry metadata. Where those crops go next is not decided
anywhere in this code.

---

## 1. Entry points

### 1.1 From outside this folder: nothing calls it

**VERIFIED** — `grep -rniI` across the whole `capstone app/` tree for
`mobile_extract`, `8001`, `answer_box`, `aruco`, `layout_id`, and `extraction`
returns **zero hits** outside this folder (the only matches are Android's
unrelated `data_extraction_rules.xml` and a comment about JSON extraction in
`GradingService.kt`). The Android app has no client for it, the Node server does
not proxy to it, and `CLAUDE.md` (audited 2026-08-21) does not mention it.

So: the only entry point is **HTTP**, and today there is **no caller**.

### 1.2 Process entry point

`main.py:15` builds the `FastAPI` app; `uvicorn main:app` is the implied launch
(no Dockerfile, no Procfile, no README, no start script anywhere — CODE-READ).
`main.py:32` `@app.on_event("startup")` calls `init_db()`, which `create_all`s
the four tables. CORS is wide open by default (`FRONTEND_ORIGINS=*`).

### 1.3 HTTP surface — 9 routes, complete inventory

| Method | Path | Body / params | Returns | Auth |
|---|---|---|---|---|
| GET | `/` | — | `{"status":"ok","service":"extraction"}` | none |
| GET | `/health` | — | `{"status":"ok"}` | none |
| POST | `/api/layouts` | `LayoutIn` JSON | `LayoutOut`, 201 | none |
| GET | `/api/layouts/{layout_id}` | — | `LayoutOut` | none |
| POST | `/api/submissions` | multipart: `layout_id`, `modality`, `page_index`, `image`, optional `submission_id` | `ExtractionResult` | none |
| POST | `/api/submissions/tablet` | `TabletSubmission` JSON, `?page_index=` | `ExtractionResult` | none |
| GET | `/api/submissions/{id}` | — | the raw stored `manifest` JSON (no response model) | none |
| GET | `/api/submissions/{id}/answers` | — | `GroupedSubmissionOut` | none |
| GET | `/api/submissions/{id}/crops/{answer_box_id}?part=N` | — | raw PNG bytes | none |

**There is no authentication, no authorisation, and no tenancy of any kind.**
Anyone who can reach the port can read any submission's crops by id. I can't
tell whether that's deliberate for a prototype or simply not done yet — but note
the contrast with `ASC_Capstone`, which is JWT-gated on every route.

`POST /api/submissions` is **incremental**: pass an existing `submission_id` and
it adds or replaces one page on that submission (`submissions.py:136-152`,
CODE-READ). It rejects a `submission_id` belonging to a different layout (400).

---

## 2. Data flow, step by step

### 2.1 Ingest: `POST /api/submissions` (`routers/submissions.py:117`)

1. Reject `modality` unless `photo` or `scanner` (tablet has its own route).
2. Load the layout or 404.
3. Read the upload. **Branch on file type by filename extension _or_ content
   type**: `.pdf`/`pdf` → `_pdf_to_images` (pdf2image at `SUBMISSION_PDF_DPI`,
   default 300); `.tif`/`.tiff` → `_tiff_to_images` (PIL frame `seek` loop, hard
   capped at 1000 frames); otherwise treat the bytes as a single page.
4. **Multi-page path** loops `i in range(min(n_pages, layout.page_count))`,
   calling `extract_page` per page. The `page_index` form field is **ignored**
   on this path — PDF/TIFF pages always land at indices 0..n-1.
   **Single-image path** uses the `page_index` form field.
5. `_store_page_data` (`:56`) deletes any prior `SubmissionImage` for that page
   and any prior `CropImage` for that page's boxes, then inserts the page image
   and one `CropImage` row per crop. Re-uploading a page is idempotent.
6. Merge the page result into `pages_by_index`, sort by index, build the
   `manifest`, upsert the `Submission` row, commit, return `ExtractionResult`.

### 2.2 The core: `extractor.extract_page()` (`extractor.py:248`)

Inputs: `layout` dict, `image_bytes`, `modality`, `page_index`, `ink_strokes`,
`submission_id`.

1. **`get_page_segments(layout, page_index)`** (`:235`) flattens the layout into
   `(box, part_index, bbox)` tuples for this page. If a box has `segments`, each
   segment is `[page_index, x, y, w, h]` and its **position in the list is the
   part index**; only segments whose page matches are returned. Otherwise it
   falls back to the box's own `page_index` + `bbox` (part 0). If nothing
   matches → early return with `crops: []` and an `error` string. (VERIFIED
   against `sample/layout.json`.)
2. **Decode** (`:21`) — PIL first, only to read a DPI from the file header
   (`None` when absent); then `cv2.imdecode`. Raises `ValueError` on undecodable
   input, which the router turns into a 400.
3. **Detect markers** (`:37`) — `cv2.aruco.ArucoDetector` over the whole image,
   dictionary from `ARUCO_DICT` (default `DICT_4X4_50`), reduced to
   `{marker_id: centre_point}` by averaging each marker's four corners.
   **Requires all four of ids 0,1,2,3.** Fewer → returns `crops: []`,
   `markers_detected: "2/4"`, and an `error` reading *"Only N/4 corner markers
   detected on page P. Flag for manual review."* — note this is still **HTTP
   200**, not an error status.
4. **Canonical marker positions** come from `marker_geometry.get_marker_positions`,
   computed purely from `page_w_px`/`page_h_px` and the `MARKER_MARGIN_PX` /
   `MARKER_SIZE_PX` settings — i.e. the service *assumes* where the generator put
   the markers. Order is TL, TR, BL, BR for ids 0,1,2,3.
5. **Transform** (`:44`), mapping **canonical → image**: `photo` →
   `cv2.findHomography(..., RANSAC, 5.0)`; `scanner` → `cv2.estimateAffine2D`
   lifted to 3×3; `tablet` → `None`/identity. (With exactly four
   correspondences RANSAC has no outlier headroom — it is effectively a plain
   4-point solve.)
6. **Per box**: transform the canonical bbox's four corners into image space
   (`_transform_bbox`, `:56`), optionally attempt local QR registration (§5),
   run the QR check, then crop.
7. **`_crop_region` (`:65`) takes the axis-aligned bounding rectangle of the
   warped quad and slices it out of the original image.** There is **no
   `cv2.warpPerspective`, no rectification, no deskew anywhere in this
   codebase.** For a genuinely skewed phone photo the crop is a slanted box's
   bounding rect: it keeps the perspective distortion and pulls in whatever
   surrounds the box. A degenerate rect logs a warning and yields a 10×10 black
   PNG rather than failing. (CODE-READ; the sample page is flat, so this path is
   untested in anger.)
8. Encode PNG, append the crop dict, return the page result.

### 2.3 Tablet path: `_extract_tablet` (`extractor.py:350`)

Rasterises `ink_strokes` onto a white canvas of the layout's canonical size with
`cv2.polylines` (black, fixed thickness 2, strokes of fewer than 2 points
skipped), then slices each box's canonical bbox straight out — no markers, no
transform, `qr_check` hard-coded `"absent"`. Stroke coordinates are assumed to
already be in canvas pixels; nothing validates or scales them.
`_store_page_data` is called with `b""` as the page image, so tablet submissions
have an empty `SubmissionImage` row. **UNVERIFIED** — never executed here.

### 2.4 Read-back: `GET /api/submissions/{id}/answers` (`submissions.py:262`)

Loads every `CropImage` for the submission, groups by `answer_box_id`, and walks
the layout's boxes **sorted by `order_index`**. Per box: `expected_parts =
len(segments) or 1`; `complete = {found parts} == set(range(expected_parts))`.
Each part gets a `crop_url` built from `PUBLIC_BASE_URL`. Boxes with no crops
still appear, with `parts: []` and `complete: false`.

---

## 3. The actual output shapes

### 3.1 `ExtractionResult` — returned by both POST routes (VERIFIED shape)

```json
{
  "submission_id": "sub-uuid",
  "layout_id": "lay-uuid",
  "modality": "photo",
  "pages": [
    {
      "page_index": 0,
      "markers_detected": "4/4",
      "transform_type": "homography",
      "crops": [
        {
          "answer_box_id": "ab_syzn1vsmmsrm6jat",
          "qr_check": "absent",
          "warped_bbox": [168, 600, 1128, 850],
          "part": 0,
          "registration": "global"
        }
      ],
      "image_resolution": "1242x1756",
      "image_dpi": null,
      "error": null
    }
  ]
}
```

- `markers_detected` is a **string**, `"4/4"` (or `"N/A"` for tablet / no boxes).
- `transform_type` ∈ `"homography" | "affine" | "identity" | "none"`.
- `qr_check` ∈ `"pass" | "fail" | "absent"`.
- `registration` ∈ `"local" | "global"`.
- **`warped_bbox` is `[x_min, y_min, x_max, y_max]` in _image_ pixels** — a
  different convention from the layout's input `bbox`, which is `[x, y, w, h]`
  in _canonical page_ pixels. Easy to get wrong; nothing in the code marks the
  switch.
- The crop **bytes are stripped** from the response by `_clean_page_result`
  (`submissions.py:101`); only the DB and the `/crops/` route carry them.

### 3.2 `GroupedSubmissionOut` — `GET /{id}/answers` (VERIFIED shape)

```json
{
  "submission_id": "sub-uuid",
  "layout_id": "lay-uuid",
  "modality": "photo",
  "answer_boxes": [
    {
      "answer_box_id": "ab_syzn1vsmmsrm6jat",
      "label": "",
      "points": 1,
      "order_index": 0,
      "expected_parts": 1,
      "parts": [
        {
          "part": 0,
          "page_index": 0,
          "qr_check": "absent",
          "warped_bbox": [168, 600, 1128, 850],
          "registration": "global",
          "crop_url": "http://localhost:8001/api/submissions/sub-uuid/crops/ab_syzn1vsmmsrm6jat?part=0"
        }
      ],
      "complete": true
    }
  ]
}
```

This is the shape a downstream grader would consume: **an ordered list of answer
boxes, each with a URL to a PNG.** No text, no transcription, no marks.

### 3.3 Layout input (`LayoutIn` / `AnswerBoxLayout`, `schemas.py:7`)

```jsonc
{
  "layout_id": "optional; server generates a uuid4 if omitted",
  "page_w_px": 1240, "page_h_px": 1754, "page_count": 1,
  "answer_boxes": [{
    "id": "ab_syzn1vsmmsrm6jat",
    "label": "", "points": 1, "order_index": 0,
    "page_index": 0,                       // used only when `segments` is absent
    "bbox": [168, 600, 960, 250],          // [x, y, w, h], canonical page px
    "segments": [[0, 168, 600, 960, 250]], // [[page_index, x, y, w, h], ...] — one per part
    "qr_segments": null                    // [[page_index, x, y, w, h] | null, ...] parallel to segments
  }]
}
```

`POST /api/layouts` uses `db.merge`, so **posting the same `layout_id` silently
overwrites the layout** — including for submissions already extracted against
the old geometry. No versioning, no guard.

### 3.4 Persistence (`models.py`) — SQLite, 4 tables

`layouts` (answer_boxes as a JSON column) · `submissions` (modality enum
tablet/photo/scanner, `image_dpi`, full `manifest` JSON) · `submission_images`
(full page bytes as `LargeBinary`) · `crop_images` (crop bytes plus `qr_check`,
`warped_bbox`, `registration`).

Every image lives **in the database as a BLOB** — no filesystem, no object
store. `crop_images` has no unique constraint on `(submission_id,
answer_box_id, part)`; duplicates are prevented only by the delete-first logic
in `_store_page_data`, and `get_crop_image` just takes `.first()`.

---

## 4. Dependencies

### 4.1 From the rest of this repo: **none**

Zero imports, zero shared config, zero shared DB. It does not know that
`ASC_Capstone` or `Capstone_Android` exist. (VERIFIED by grep.)

### 4.2 CV / ML libraries

| Library | Used for | Status |
|---|---|---|
| `opencv-contrib-python` | ArUco detect, homography/affine, perspective transform, PNG encode, QR detect | **VERIFIED present and working** (cv2 4.11.0, `cv2.aruco` available) |
| `numpy` | point maths | VERIFIED |
| `Pillow` | DPI sniff, TIFF frame splitting | VERIFIED |
| `pyzbar` | **fallback** QR decoder for when cv2's fails | **NOT INSTALLED** in this environment; the `ImportError` is swallowed (`extractor.py:98`), so the fallback silently never runs |
| `pdf2image` | PDF → PNG pages | importable, but needs the **poppler** binary on PATH — an **undeclared system dependency**. `_pdf_to_images` catches only `ImportError`, so a missing poppler raises `PDFInfoNotInstalledError` → unhandled 500 |

**There is no OCR library, no ML runtime, and no model.** No tesseract, no
TrOCR, no LiteRT, nothing. This service ends at the crop.

### 4.3 Web / data stack

`fastapi`, `uvicorn`, `sqlalchemy` (**not installed in this environment** — I
could not boot the app), `pydantic` v2, `pydantic-settings`, `python-multipart`.

---

## 5. Genuinely implemented vs. half-finished vs. dead

### Implemented and **VERIFIED by execution**

I ran `extract_page` directly against `sample/layout.json` +
`sample/sample_page.png` with modality `photo`. Result: **4/4 markers detected**,
`transform_type: "homography"`, two crops produced (7,474 and 8,326 PNG bytes),
warped bboxes `[168,600,1128,850]` and `[168,1283,1128,1573]`. Detected marker
centres landed within ~2 px of the canonical positions.

So the **core pipeline works**: ArUco detection → homography → bbox transform →
crop → PNG. That is the solid part of this folder.

### Implemented but **UNVERIFIED**

- The entire HTTP/persistence layer. `sqlalchemy` is not installed here, so
  nothing was booted. The code reads as complete and coherent; I simply have no
  evidence it runs.
- Scanner (affine) modality, tablet modality, PDF and TIFF ingestion,
  incremental multi-page submissions, multi-part answer boxes spanning pages.
  The data model supports all of them; the one sample exercises none of them.
- **Any real-world photo.** `sample_page.png` is 1242×1756 against a canonical
  1240×1754 — it is a *rendered page*, not a photograph. Perspective, blur,
  lighting, and partial marker occlusion have never been tested.

### Half-finished — stated plainly

- **QR verification is non-functional on the only sample present.** The sample
  page *does* carry a QR beside each answer box (visible in the PNG), but
  `qr_check` came back `"absent"` for both boxes (VERIFIED). Digging in: the QRs
  are rendered at roughly **43×43 px** for a dense payload, so the modules are
  sub-pixel and `cv2.QRCodeDetector` cannot decode them at 1×, 2×, 4×, or 8×
  upscale, nor with `detectAndDecodeMulti` over the whole page. The `pyzbar`
  fallback is not installed, so whether it would succeed is **unknown**. Either
  the page generator renders QRs too small, or `pyzbar` is a hard requirement
  that the code treats as optional.
- **Local QR registration (`_try_local_registration`, `extractor.py:147`) is
  gated off and cannot currently fire.** Three independent reasons: the
  `USE_LOCAL_QR_REGISTRATION` setting defaults to `false`; it needs
  `qr_segments`, which is `null` in the sample layout; and it needs a decodable
  QR, which the point above says we don't have. The function itself is the most
  carefully written code in the folder — it re-detects the QR near its predicted
  location, builds a per-box perspective transform from the QR's four corners,
  and rejects the result if the box's implied size deviates by more than 2× or
  its centre drifts more than 120 px from the page-level estimate. **All of it is
  dead in the current configuration.**
- **`registration` is misreported for tablet crops.** `_extract_tablet` omits
  the key; both `_store_page_data` and `_clean_page_result` default it to
  `"global"`, so tablet output claims a page-level registration that never
  happened.
- **`AnswerPartOut.page_index` is populated only for boxes that use `segments`**
  (`submissions.py:287-289`). A box using the older `page_index` + `bbox` form
  reports `page_index: null` in the grouped output, even though the layout knows
  it.
- **PDF/TIFF pages are stored with the wrong content type.** `_store_page_data`
  is handed the *upload's* content type (`submissions.py:183`), so PNG page
  bytes get filed as `application/pdf`.
- `_check_qr` searches an **80 px margin around the whole answer box** and takes
  the first QR it decodes. With boxes placed close together, a neighbouring
  box's QR can land in the search region and be reported as `"fail"` (a
  mismatch) rather than `"absent"`. Not triggered by the sample layout — its two
  boxes are ~430 px apart — but the failure mode is real.

### Dead code / unused fields (CODE-READ, confirmed by grep)

- `extract_page(..., submission_id=...)` and `_extract_tablet(..., submission_id)`
  — the parameter is defaulted to a fresh uuid4 and then **never read**.
- The `"order"` field parsed out of the pipe-form QR payload
  (`extractor.py:117`) is **never consumed anywhere**.
- `marker_geometry.CORNER_MARKER_IDS` is defined and never imported; `extractor`
  hard-codes `(0, 1, 2, 3)` and `range(4)` in three places instead.
- `AnswerBoxLayout.bbox` is redundant with `segments[0][1:]` whenever both are
  present (the sample has both, agreeing). `get_page_segments` prefers
  `segments`; nothing reconciles a disagreement.
- No tests, no fixtures beyond `sample/`, no CI, no Dockerfile, no README, no
  migration tooling (`create_all` only — **any schema change means deleting the
  .db file**).

---

## 6. Question ↔ answer-box pairing: **it does not exist**

Worth being blunt about, since it is likely the thing you want to build on.

**There is no question entity anywhere in this folder.** No question text, no
question id, no field that could hold one. The word "question" does not appear in
any `.py` file here.

What exists instead, on `AnswerBoxLayout`:

- `id` — an opaque string (`"ab_syzn1vsmmsrm6jat"` in the sample), generated
  elsewhere.
- `label` — free text, **`""` in the sample**, only echoed back in the grouped
  output.
- `points` — an int, defaulted to 1, **never used in any computation**; stored
  and echoed.
- `order_index` — the **only** ordering signal, used once, to sort the boxes in
  `GET /{id}/answers`.

So the pairing rule is: **positional, by `order_index`, decided by whoever built
the layout — not by this service.** There is no geometric heuristic (nothing like
"the text block immediately above this box is its question"), no proximity
matching, no reading-order inference, and no text extraction of any kind. The
sample page visibly has a prose block ending in `End-1` above box 0 and one
ending in `End-2` above box 1 — exactly the pairing a human would read off the
page — **and no code here reads it.**

The nearest thing to a linkage is the QR payload, parsed by `_parse_qr_payload`
(`extractor.py:104`) in two accepted forms:

- JSON: any object with keys `q`, `b`, and optionally `part`.
- Pipe-delimited: `q|b|part|order`.

`q` is compared against **`layout_id`** and `b` against **`answer_box_id`**
(`extractor.py:138`). So despite the suggestive name, **`q` is the layout/quiz
id, not a question id.** The QR encodes *"which box on which sheet is this"* for
registration and anti-mix-up purposes — it carries no question identity either.

If you need question↔box pairing, you are building it from scratch here, or
importing it from whatever produced `layout.json`.

---

## 7. Open questions I could not answer from the code

1. **What generates the worksheet pages?** Nothing here draws ArUco markers,
   renders QRs, or emits `layout.json`. `MARKER_SIZE_PX` / `MARKER_MARGIN_PX` are
   consumed *only* to predict where a generator put the markers — the two sides
   share these constants by convention, with no shared code and no validation.
   That generator is the real source of the question↔box relationship, and it is
   not in this folder.
2. **What does the QR payload actually contain?** I could not decode either
   sample QR (§5). Both accepted formats look speculative. Unknown whether the
   generator emits JSON or pipe form, and unknown whether `pyzbar` can read these
   QRs at their rendered size.
3. **Is `pyzbar` required or optional?** It is in `requirements.txt`, but its
   import failure is silently swallowed. Given cv2 fails on the sample, the whole
   QR feature may hinge on it.
4. **How does `layout_id` relate to the rest of the capstone?** It is a UUID
   *string*; `ASC_Capstone`'s assignments and questions are integer primary keys.
   There is no mapping table, no foreign key, no documented convention. Whether
   one layout is meant to be one assignment is a guess.
5. **What consumes the crops?** No handoff exists — nothing posts them anywhere,
   nothing calls a model. Whether the intent was to feed the Android on-device
   grader, a server-side model, or a human review UI is not decidable from the
   code.
6. **Is the lack of perspective rectification intentional?** Cropping the
   bounding rect of a warped quad is fine for flatbed scans and questionable for
   phone photos. The `photo` modality and the homography path imply real photos
   are expected, which makes the missing `warpPerspective` look like an omission
   — but I have no evidence either way.
7. **What coordinate space do tablet `ink_strokes` arrive in, and who produces
   them?** The code assumes canonical canvas pixels and hard-codes a 2 px stroke
   width. No client for this route exists.
8. **Was multi-part (an answer spanning pages) ever exercised?** The `segments` /
   `qr_segments` / `expected_parts` / `complete` machinery is fully written and
   entirely untested by anything present.
9. **Is unauthenticated access acceptable here?** Deployed as-is, submission ids
   are the only thing protecting student work.
10. **Why is `points` on the layout?** It suggests this service was once meant to
    know about marks. Nothing uses it.
