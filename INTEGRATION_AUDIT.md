# `INTEGRATION_AUDIT.md` — `mobile_Extract/` vs `v-2.1.1/backend/`, and the readiness of `GET /api/questions/{id}`

> Produced 2026-08-30 by diffing every shared file byte for byte, and by
> **booting `v-2.1.1/backend`'s real `questions` router and serving
> `GET /api/questions/{id}` against a finalized record**. Companion to
> `mobile_Extract/NOTES.md` (2026-08-28) and `v-2.1.1/TEACHER_NOTES.md`
> (2026-08-30); same tag vocabulary.
>
> | Tag | Meaning |
> |---|---|
> | **VERIFIED** | Executed and observed during this audit |
> | **CODE-READ** | Traced in the source, never run |
> | **UNVERIFIED** | Written, but nothing here proves it works |
>
> **Neither codebase was modified.** This file is the only addition, and it sits
> at the parent-folder root, outside both trees. `sqlalchemy` was installed into
> a scratch directory (`pip --target`, not the global environment); the app was
> run with its CWD pointed at scratch, so `nlp_ocr.db`, `uploads/` and `pdfs/`
> were created there and `v-2.1.1/backend/` was left untouched.

---

## Part 0 — What was actually executed

`sqlalchemy` was absent (the reason `TEACHER_NOTES.md` could not boot); `playwright`
and `qrcode` are still absent, so **the PDF has still never been rendered by anyone.**

| # | Run | Result |
|---|---|---|
| **A** | `pip install --target <scratch>/libs sqlalchemy` → 2.0.52 | global env untouched |
| **B** | Booted `routers.questions` mounted on a `FastAPI` app exactly as `backend/main.py` mounts it; seeded one **finalized** `Question` + 2 `AnswerBox` rows through the real ORM models; served `GET /api/questions/{id}` | **HTTP 200**, full body in §2.1 |
| **C** | Imported his real `config.py` + `doc_renderer.py` constants | canvas `1240×1754`, `L=186 T=124 R=124 B=124`, `content_w=930`, `usable_h=1506`, marker centres `{0:(70,70), 1:(1170,70), 2:(70,1684), 3:(1170,1684)}` |
| **D** | Ordering probe: 3 boxes inserted in one order, `order_index` set to the opposite order, ids alphabetically opposite again | array follows **`order_index`** — not insertion order, not PK order |
| **E** | `PUT /{id}/blocks` on a draft with the boxes reordered | 200; `order_index` **reassigned from document order** |
| **F** | `PUT /{id}/blocks` with one box removed and one added | removed box **hard-deleted from the DB**, no tombstone |
| **G** | `PUT /{id}/blocks` and `POST /{id}/finalize` on a finalized question | **409** and **409** |
| **H** | `POST /{id}/clone` on a question that has answer boxes | **`IntegrityError: UNIQUE constraint failed: answer_boxes.id`** → unhandled → 500. Independently reproduces `TEACHER_NOTES.md` §3.4, this time over HTTP |
| **I** | `POST /api/questions` twice with the same `answer_boxes[].id` under two different questions | 201 then **500** — `answer_box.id` is a **global** PK, not scoped to `question_id` |
| **J** | Synthetic 1240×1754 page with real ArUco markers → perspective warp → his `_detect_aruco_markers` / `_compute_transform` / `_transform_bbox`, run once with correct canonical constants and again with wrong ones | 4/4 detected; error table in §2.3 |
| **K** | bbox interior arithmetic from the print CSS | table in §2.6 |

---

# Part 1 — The duplication

## 1.1 File-by-file

`mobile_Extract/` is flat; `v-2.1.1/backend/` nests the same modules one level down
(`services/`). Paths below are paired by role, not by path.

| `mobile_Extract/` | `v-2.1.1/backend/` | Verdict |
|---|---|---|
| `routers/__init__.py` | `routers/__init__.py` | **IDENTICAL** (both empty) |
| `extractor.py` (381 L) | `services/extractor.py` (229 L) | **FORKED** — but lines 21–73 / 22–74 are **byte-identical**: `_decode_image`, `_detect_aruco_markers`, `_compute_transform`, `_transform_bbox`, `_crop_region`. The whole registration core is one copied block (VERIFIED by `diff` on the extracted ranges). §1.2 |
| `marker_geometry.py` (19 L) | *(no file — same code inlined at* `services/doc_renderer.py:21-44`*)* | **FORKED BY LOCATION, IDENTICAL BY CONTENT** — `ARUCO_DICT_ID`, `ARUCO_DICT`, `CORNER_MARKER_IDS` and the whole of `get_marker_positions` are **byte-identical** (VERIFIED by `diff`) |
| `config.py` (31 L) | `config.py` (24 L) | **FORKED** — 3 settings shared and equal, 5 unique to mobile, 4 unique to teacher. §1.3 |
| `database.py` (23 L) | `database.py` (27 L) | **FORKED**, one difference: teacher adds `connect_args={"check_same_thread": False}` |
| `main.py` (44 L) | `main.py` (38 L) | **FORKED** — title, CORS policy, router set; mobile has an extra `GET /health` |
| `models.py` (71 L) | `models.py` (83 L) | **FORKED, structurally** — mobile has `Layout / Submission / SubmissionImage / CropImage` (images as BLOBs); teacher has `Question / AnswerBox / Submission` (images on disk). Only `Submission` is common, and it differs. §1.4 |
| `schemas.py` (91 L) | `schemas.py` (76 L) | **FORKED, structurally** — only `PageExtractionResult` and `TabletStroke` survive unchanged. §1.4 |
| `routers/submissions.py` (333 L) | `routers/submissions.py` (185 L) | **FORKED** — `_pdf_to_images` and `_tiff_to_images` are near-identical (one constant differs); everything else diverges. §1.5 |
| `requirements.txt` (11 L) | `requirements.txt` (13 L) | **FORKED** — teacher adds `qrcode[pil]>=7.4` and `playwright>=1.45`; nothing removed |
| `routers/layouts.py` (59 L) | — | **UNIQUE TO MOBILE** |
| `.env.example` | — | **UNIQUE TO MOBILE** |
| `sample/layout.json`, `sample/sample_page.png` | — | **UNIQUE TO MOBILE** — the only ArUco test fixture in the whole tree |
| `.gitignore`, `.DS_Store`, `__pycache__/` | `.DS_Store` | housekeeping |
| — | `routers/questions.py` (206 L) | **UNIQUE TO TEACHER** |
| — | `services/doc_renderer.py` (307 L) | **UNIQUE TO TEACHER** — the generator |
| — | `services/__init__.py` | **UNIQUE TO TEACHER** |
| — | `frontend/` (18 source files) | **UNIQUE TO TEACHER** |

Neither folder is a git repo (VERIFIED), so there is no history to say which was
copied from which.

## 1.2 `extractor.py` — exactly what forked

**Shared verbatim (VERIFIED byte-identical):** the five functions above, plus
`ARUCO_PARAMS = cv2.aruco.DetectorParameters()` and
`ARUCO_DETECTOR = cv2.aruco.ArucoDetector(ARUCO_DICT, ARUCO_PARAMS)`.

**Present in mobile, deleted on the teacher side:**

- `_encode_png` — mobile keeps crop bytes in memory for the DB; teacher writes files with `cv2.imwrite`
- `_detect_qr` — the two-stage cv2-then-`pyzbar` decoder, returning corner points
- `_parse_qr_payload` — accepts JSON **and** the pipe form `q|b|part|order`
- `_try_local_registration` (~90 lines) — per-box perspective transform off the QR corners, with a 0.5–2.0 size-plausibility gate and a 120 px centre-drift gate
- `get_page_segments` — the multi-part / `segments` flattener

**Present in teacher, absent from mobile:** writing the original page to
`uploads/{q}/{sub}/page{N}_original.jpg`, and `crop_path` on each crop dict.

**Same function, forked body — `_check_qr`:**

| | mobile | teacher |
|---|---|---|
| decoder | `_detect_qr()` (cv2 **+** pyzbar, extracted) | cv2 inline, pyzbar inline |
| payload parse | `_parse_qr_payload` — JSON **or** `q\|b\|part\|order` | `json.loads` only |
| `part` check | yes — mismatch → `"fail"` | no `part` concept |
| undecodable payload | `"fail"` | `"fail"` |
| search margin | **80 px** | **80 px** (equal) |

**Same signature, forked meaning — `extract_page`:** mobile takes `layout` and
iterates `get_page_segments(...)` → `(box, part_index, bbox)`; teacher takes
`question` and iterates
`[b for b in question["answer_boxes"] if b["page_index"] == page_index and b["bbox"]]`.
**The teacher side has no `part`, no `segments`, and no multi-page answer box at all.**

## 1.3 Every constant that appears in both — values side by side

**Registration constants — the ones that must agree for extraction to work:**

| Constant | `mobile_Extract` | `v-2.1.1/backend` | |
|---|---|---|---|
| `ARUCO_DICT` | `"DICT_4X4_50"` | `"DICT_4X4_50"` | **equal** |
| `MARKER_SIZE_PX` | `60` | `60` | **equal** |
| `MARKER_MARGIN_PX` | `40` | `40` | **equal** |
| `CORNER_MARKER_IDS` | `[0, 1, 2, 3]` | `[0, 1, 2, 3]` | **equal** |
| marker centre formula | `m + s//2` | `m + s//2` | **byte-identical function** |
| id → corner mapping | 0 TL, 1 TR, 2 BL, 3 BR | 0 TL, 1 TR, 2 BL, 3 BR | **equal** (row-major, **not** clockwise) |
| resulting centres @1240×1754 | (70,70) (1170,70) (70,1684) (1170,1684) | same | **equal** (VERIFIED, run C) |

All three of `ARUCO_DICT` / `MARKER_SIZE_PX` / `MARKER_MARGIN_PX` are
`pydantic-settings` fields with `env_file=".env"` on **both** sides — i.e. they
agree by default but are independently overridable per deployment, with nothing
checking. `mobile_Extract/.env.example` pins them explicitly; `v-2.1.1` has no
`.env.example`.

**Page geometry — where they have actually diverged:**

| | `mobile_Extract` | `v-2.1.1/backend` | |
|---|---|---|---|
| canvas W × H | **not computed** — read from the layout row (`page_w_px`, `page_h_px`) | **computed**: `round(8.27*dpi) × round(11.69*dpi)` = **1240 × 1754** | teacher is the source |
| `sample/layout.json` canvas | **1240 × 1754** | 1240 × 1754 | **equal** |
| DPI | `SUBMISSION_PDF_DPI = 300` (ingest only) | `DEFAULT_DPI = 150` (canvas only) | **different meanings, different values** |
| `LEFT_MARGIN` | not defined; implied **168** by the sample bbox `x` | **186** (`_MARKER_ZONE 100 + _QR_GUTTER 66 + 20`) | **DIVERGED — 18 px** |
| `RIGHT_MARGIN` | not defined; implied **112** (1240 − 168 − 960) | **124** (`_MARKER_ZONE + 24`) | **DIVERGED — 12 px** |
| `TOP` / `BOTTOM_MARGIN` | not defined | **124** each | teacher only |
| content width | implied **960** (sample `bbox[2]`) | **930** | **DIVERGED — 30 px** |
| `_QR_SIZE` | not defined; back-computed **28** | **46** | **DIVERGED** |
| `_QR_GUTTER` | not defined | **66** (`_QR_SIZE + 20`) | teacher only |
| `_MARKER_ZONE` | not defined | **100** (`40 + 60`) | teacher only |

> The 168/186 split is the load-bearing finding. `mobile_Extract/sample/layout.json`
> was produced by an **older build of this same generator**, when `_QR_SIZE` was 28
> and the margins added 12 rather than 24. The registration constants stayed frozen;
> **the page-layout constants drifted.** Nothing detected it. Read `bbox` from the
> API — never hardcode `x`.

**Extractor tuning constants — all equal, all still hardcoded on both sides:**

| Constant | mobile | teacher | |
|---|---|---|---|
| `findHomography` RANSAC threshold | `5.0` | `5.0` | equal |
| degenerate-crop fallback | `10×10` black PNG | `10×10` black PNG | equal |
| tablet stroke thickness | `2` | `2` | equal |
| tablet canvas fill | white `255` | white `255` | equal |
| `_check_qr` search margin | `80` px | `80` px | equal |
| TIFF frame cap | `1000` | `1000` | equal |
| PDF ingest DPI | `settings.SUBMISSION_PDF_DPI` = **300** | hardcoded **150** | **DIVERGED 2×** |
| `_try_local_registration` search margin / size gate / drift gate | `60` px / `0.5–2.0` / `120.0` px | — | mobile only |

**Constants in only one side:**

| mobile only | teacher only |
|---|---|
| `PUBLIC_BASE_URL = "http://localhost:8001"` | `UPLOAD_DIR = "./uploads"` |
| `FRONTEND_ORIGINS = "*"` (CORS wide open) | `PDF_DIR = "./pdfs"` |
| `DATABASE_URL = sqlite:///./mobile_extract.db` | `DATABASE_URL = sqlite:///./nlp_ocr.db` |
| `SUBMISSION_PDF_DPI = 300` | `DEFAULT_DPI = 150`, `DEFAULT_PHYSICAL_PAGE = "A4"` |
| `USE_LOCAL_QR_REGISTRATION = False` | CORS pinned to `localhost:5173` / `127.0.0.1:5173` |
| | frontend: `minHeight` default **90**, min **60**, max **800**, step **40**; `widthPercent` ∈ **{25, 50, 75, 100}** |
| | `A4` literals **8.27 × 11.69** in |

## 1.4 Schema / model divergence

`Layout` (mobile) and `Question` + `AnswerBox` (teacher) model the same thing
differently:

| | mobile `Layout` | teacher `Question` + `AnswerBox` |
|---|---|---|
| container id | `layout_id` (uuid4) | `question_id` (uuid4) |
| boxes stored as | one **JSON column** on the row | **separate table**, one row per box |
| box geometry | `bbox: [x,y,w,h]` **and** `segments: [[page,x,y,w,h], …]` | `bbox_x/y/w/h` + `page_index` columns |
| multi-part boxes | **yes** (`segments`, `qr_segments`, `expected_parts`, `complete`) | **no concept at all** |
| `order_index` | on the box, **serialized** in `LayoutOut` | on the box, **NOT serialized** in `AnswerBoxOut` |
| `page_count` | `nullable=False, default=1`, **serialized** | `nullable=True`, **never serialized** (§2.2) |
| `label`, `points` | present, dead | present, dead |
| document content | **none** | `content` (TipTap JSON) |
| lifecycle | none — `db.merge` silently overwrites a layout | `draft → finalized`, one-way, 409-guarded |

`Submission` is the one shared model and still differs: mobile keys on
`layout_id` and stores page/crop bytes in `submission_images` / `crop_images`;
teacher keys on `question_id`, writes files to disk, and adds three columns
(`original_image_path`, `image_width`, `image_height`) that **no code path ever
assigns** (CODE-READ, confirmed by grep).

Schema-level, only `PageExtractionResult` and `TabletStroke` survive the fork
unchanged. `CropInfo` differs: mobile has `part` + `registration`, teacher has
`crop_path`.

## 1.5 Route divergence

| Route | mobile | teacher |
|---|---|---|
| `GET /health` | yes | no |
| `POST /api/layouts`, `GET /api/layouts/{id}` | yes | no — replaced by the whole `/api/questions` surface |
| `GET /api/submissions/{id}/answers` | yes — **grouped, ordered, with `crop_url`, `expected_parts`, `complete`** | no |
| `GET …/crops/{box_id}?part=N` | yes — `part` param, bytes from DB | `part` **dropped**, `FileResponse` from disk |
| `POST /api/submissions` incremental (`submission_id` form field, re-upload one page) | yes | no — always a new submission |
| `POST /api/submissions` finalize gate | no | yes — 400 unless `state == "finalized"` |
| `/api/questions/*` (7 routes incl. `finalize`, `pdf`, `clone`) | no | yes |

Both are **entirely unauthenticated** (VERIFIED by reading every route decorator
on both sides) — in contrast to `ASC_Capstone`, which is JWT-gated everywhere.

## 1.6 Which copy is authoritative

**`v-2.1.1/backend/` is authoritative, without qualification, for anything
touching geometry.**

1. It is the **only generator**. `mobile_Extract` cannot produce a page, a
   marker, a QR or a `layout.json`; it can only consume one. `TEACHER_NOTES.md`
   §10 already identified `v-2.1.1` as the answer to `NOTES.md` §7.1's "what
   generates the worksheet pages?".
2. `mobile_Extract`'s only concrete layout — `sample/layout.json` — is **stale**
   against the current generator (x=168 vs 186, content 960 vs 930). Anything
   calibrated against that sample is calibrated against a page this generator no
   longer produces.
3. `mobile_Extract` has **no producer and no consumer**: zero references to
   `mobile_extract`, `layout_id` or port `8001` anywhere outside its own folder
   (VERIFIED by grep across the whole parent tree, excluding `node_modules/` and
   Android build artifacts). It is unreachable code at the repo level.

## 1.7 Can `mobile_Extract/` be deleted?

**Yes — after harvesting three things.** It is 100% unreferenced, its registration
core is a byte-identical copy of code that also lives in `v-2.1.1`, and its one
data fixture is stale. Nothing breaks.

Harvest first:

1. **`sample/sample_page.png` + `sample/layout.json`.** The **only** rendered
   page carrying real ArUco markers anywhere in the parent tree, and the only
   fixture any extraction test has ever run against (`NOTES.md` §5 ran the full
   pipeline on it). `v-2.1.1` cannot produce a replacement without `playwright`
   + Chromium. Copy them into a fixtures folder and note in the copy that its
   geometry predates `LEFT_MARGIN = 186`.
2. **`_try_local_registration`** (`extractor.py:147-236`). ~90 lines, the most
   carefully written code in either folder — per-box QR-anchored perspective
   transform with a size-plausibility gate and a centre-drift gate. It has no
   counterpart in `v-2.1.1` and is dead where it stands (three independent gates,
   `NOTES.md` §5). Keep it as reference if per-box re-registration is ever wanted.
3. **`GET /api/submissions/{id}/answers`** (`routers/submissions.py:262-330`) —
   the grouped read-back shape: ordered boxes → `crop_url` list, `expected_parts`,
   `complete`. It is the shape a downstream grader actually wants, and
   `v-2.1.1` has nothing like it.

Also worth writing down before deleting: `_parse_qr_payload`'s **pipe form**
`q|b|part|order`. `v-2.1.1` emits JSON only, and JSON is precisely what makes the
QR undecodable — a 72-byte payload forces a v5 symbol at 1.12 px/module
(`TEACHER_NOTES.md` §1.5). The pipe form with short codes is the shrink path if
the QRs are ever fixed. `mobile_Extract` is the only place it is written down.

Everything else — `Layout`, `segments` / multi-part boxes, DB-BLOB storage,
incremental page upload, `PUBLIC_BASE_URL` — is either superseded, never
exercised, or trivially re-derivable.

---

# Part 2 — API readiness for the extractor

## 2.1 The response (VERIFIED — served over HTTP, run B)

`GET /api/questions/e9cce9d0-34b1-4657-87ad-65898e6a71ab` → **200**. Two answer
boxes, one per page, `page_count = 2` in the DB row. Body reproduced verbatim
(TipTap `content` abridged; every other key is complete and in order):

```jsonc
{
  "question_id": "e9cce9d0-34b1-4657-87ad-65898e6a71ab",
  "state": "finalized",
  "physical_page": "A4",
  "dpi": 150,
  "content": {
    "type": "doc",
    "content": [
      { "type": "heading",   "attrs": {"level": 2}, "content": [{"type":"text","text":"Worksheet 3 - Kinematics"}] },
      { "type": "paragraph", "content": [{"type":"text","text":"1a. State Newton's second law."}] },
      { "type": "answerBox", "attrs": {"id":"ab_syzn1vsmmsrm6jat","label":"1a","points":1,
                                       "widthPercent":100,"minHeight":90} },
      { "type": "paragraph", "content": [ /* …prose + inline equation… */ ] },
      { "type": "answerBox", "attrs": {"id":"ab_uub03qhomsrm71en","label":"1b","points":2,
                                       "widthPercent":50,"minHeight":130} }
    ]
  },
  "answer_boxes": [
    { "id": "ab_syzn1vsmmsrm6jat", "label": "1a", "points": 1,
      "bbox": [186, 334, 930,  90], "page_index": 0 },
    { "id": "ab_uub03qhomsrm71en", "label": "1b", "points": 2,
      "bbox": [186, 124, 465, 130], "page_index": 1 }
  ],
  "page_w_px": 1240,
  "page_h_px": 1754,
  "page_count": null,
  "derived_from": null,
  "created_at":   "2026-08-30T09:15:00",
  "finalized_at": "2026-08-30T09:41:12"
}
```

**Exact key sets (VERIFIED by introspection, not by eye):**

```
top level      : answer_boxes, content, created_at, derived_from, dpi, finalized_at,
                 page_count, page_h_px, page_w_px, physical_page, question_id, state
answer_boxes[] : bbox, id, label, page_index, points
```

Provenance, stated plainly: `playwright` and `qrcode` are still not installed, so
`render_finalized_question` has **still never run** and the `bbox` values were
seeded, not measured. They are not invented — they are what
`render_finalized_question` composes,
`[page_idx, LEFT_MARGIN, round(TOP_MARGIN + top_off), round(content_w * pct / 100), round(h)]`,
evaluated against his real imported constants (run C) with two plausible measured
offsets. **The serialization path — the ORM read, `_box_to_out`, `_question_to_out`,
the Pydantic `QuestionOut`, the FastAPI response — is his real code and was really
executed.** So: field names, key sets, types, null-ness and ordering are
**VERIFIED**; the specific integers inside `bbox` are **CODE-READ**.

## 2.2 `page_count: null` — VERIFIED, not just code-read

The DB row held `2`. The response says `null`. `_question_to_out`
(`questions.py:29-42`) never passes `page_count`, and the Pydantic default fills
`None`. Confirmed by direct introspection: `"page_count" in body → True`,
`body["page_count"] → None`.

Infer page count as `max(page_index) + 1` over `answer_boxes`. That undercounts a
trailing page with no answer box — harmless for extraction (a page with no boxes
has nothing to extract) but wrong if you use it to tell a student how many photos
to take.

## 2.3 Q1 — Are the four ArUco marker centres in the response?

**No. There is no marker field of any kind.** Searched the response for any key
matching `marker` or `aruco`: **zero hits** (VERIFIED). No dictionary name, no
marker size, no margin, no centres, no id→corner mapping. A client must compute
them.

The computation is short. From `page_w_px` / `page_h_px` in the response, plus
three constants the client must **hardcode**:

```
m = MARKER_MARGIN_PX = 40      s = MARKER_SIZE_PX = 60      dict = DICT_4X4_50

id 0 (top-left)     = (m + s//2,               m + s//2)              = (70,   70)
id 1 (top-right)    = (page_w_px - m - s//2,   m + s//2)              = (1170, 70)
id 2 (BOTTOM-left)  = (m + s//2,               page_h_px - m - s//2)  = (70,   1684)
id 3 (bottom-right) = (page_w_px - m - s//2,   page_h_px - m - s//2)  = (1170, 1684)
```

Use integer floor `s//2`, not the geometrically correct centre — a 60 px marker
drawn at x=40 spans pixels 40–99, true centre 69.5, but his code reports 70.
Measured cost of being *correct* rather than bug-compatible: **0.66 px** (run J).

### Is that a blocker? Yes — in the sense that matters

**For building a working prototype this week: no.** The values are knowable, they
agree across both codebases (§1.3), and I verified the full detect → homography →
box-recovery round trip on a warped synthetic page: 4/4 markers, `homography`,
correct crop.

**For "extract reliably" — the standard set here: yes, this is a blocker,** for
one reason: *a mismatch is undetectable and does not fail loudly.*

- `MARKER_MARGIN_PX`, `MARKER_SIZE_PX` and `ARUCO_DICT` are `pydantic-settings`
  fields with `env_file=".env"`. Any deployment can change them. Nothing in the
  response, and nothing anywhere, tells a client that they did.
- If they change, detection still succeeds, `markers_detected` is still `4/4`,
  `transform_type` is still `"homography"`, and every crop is **silently wrong**.
  There is no residual check, no reprojection error, no assertion anywhere.
- **This has already happened once in this project.** The page-layout constants
  diverged between `mobile_Extract` and `v-2.1.1` (168 vs 186, §1.3) and nothing
  noticed. The registration constants are protected by nothing stronger.

Measured cost of a mismatch (VERIFIED, run J — real markers, real perspective
warp, his real transform code, box `[186, 334, 930, 90]`):

| Client's assumption | Recovered `warped_bbox` | Corner error vs truth |
|---|---|---|
| `m=40, s=60` (correct) | `[204, 330, 1084, 453]` | **0.0 px** |
| `m=50, s=60` | `[196, 323, 1093, 448]` | max **11.0**, mean 10.0 px |
| `m=40, s=80` | `[196, 323, 1093, 448]` | max **11.0**, mean 10.0 px |
| `m=30, s=60` | `[211, 336, 1076, 458]` | max **10.7**, mean 9.7 px |
| true pixel centre instead of `m + s//2` | — | max **0.66 px** |
| **ids 2 and 3 swapped** (clockwise instead of row-major) | — | max **990.6 px** |

A 10 px margin change costs ~10 px of systematic crop displacement — enough to
clip a descender off the top line of a 57 px writable strip (§2.6). **Assuming
clockwise marker order costs 990 px — a mirrored homography, and total garbage
that still reports `4/4` and `"homography"`.**

**The fix is one line on the teacher side.** `render_finalized_question` already
computes `marker_positions` and then throws them away after writing the PDF.
Emitting them into `QuestionOut` turns a silent convention into a checked
contract:

```jsonc
"markers": {
  "aruco_dict": "DICT_4X4_50",
  "marker_size_px": 60,
  "marker_margin_px": 40,
  "centres": { "0": [70, 70], "1": [1170, 70], "2": [70, 1684], "3": [1170, 1684] }
}
```

Until that exists, hardcode the constants **and** assert `page_w_px == 1240 &&
page_h_px == 1754` before trusting a crop — that catches a DPI or paper change,
though not a marker-constant change.

## 2.4 Q2 — Is there a stable ordering field for answer boxes?

**No explicit field. Order is array position, and array position is `order_index`.**

- `AnswerBoxOut` does **not** carry `order_index` — VERIFIED, the key set is
  exactly `{bbox, id, label, page_index, points}`. The column exists on the model
  (`models.py:58`); the schema omits it.
- The array **is** sorted by it: `Question.answer_boxes` declares
  `order_by="AnswerBox.order_index"`. **VERIFIED experimentally** (run D) —
  three boxes inserted in one order, given `order_index` in the opposite order,
  with ids alphabetically opposite again, came back in `order_index` order. So
  it is neither insertion order nor primary-key order.
- **Not geometry.** Nothing sorts by `bbox`. `page_index` is not sorted on either.

`order_index` is assigned as `enumerate(boxes_in)` over the document-order array
the editor sends (`_upsert_answer_boxes`, `questions.py:72`), so **array order ==
document order == reading order**, and `page_index` is monotonically
non-decreasing along it. Within one page, `bbox_y` increases along it too. That
gives you a free consistency check: **sorting by `(page_index, bbox[1])` must
reproduce the array order.** If it ever doesn't, something upstream is wrong —
assert on it.

Two caveats:

1. **`order_index` is positional, not stable.** It is recomputed from document
   order on **every** autosave. VERIFIED (run E): reordering boxes in a draft and
   saving rewrote all three. A box's ordinal is a fact about the current
   document, never an identity.
2. **But a finalized question is frozen**, so the array order you read is
   permanent for that question (§2.5). The instability only exists in `draft`,
   where `bbox` is `null` and there is nothing to extract anyway.

**Recommendation:** treat array position as the ordering, capture it at ingest,
and store it — do not re-derive it later. Ask for `order_index` in
`AnswerBoxOut`; it costs one word and removes the dependency on every layer
between the API and your database preserving JSON array order.

## 2.5 Q3 — Is `answer_box.id` stable enough to be a foreign key?

**Yes as a value, no as a bare key. Store `(question_id, answer_box_id)`, never
`answer_box_id` alone.**

What it is: `String`, **browser-generated**,
`'ab_' + Math.random().toString(36).slice(2,10) + Date.now().toString(36)`
(`AnswerBoxNode.jsx:5`) → e.g. `ab_syzn1vsmmsrm6jat`. ~19 chars, ~41 bits of
`Math.random` plus a millisecond timestamp. Not a UUID, not cryptographic, and
**the server never validates it or checks for collisions** — it is a
client-supplied primary key.

### Under what edits does it change?

| Action | Effect on the id | Evidence |
|---|---|---|
| Edit prose around the box | **unchanged** | CODE-READ `_upsert_answer_boxes` |
| Rename `label`, change `points`, width, height | **unchanged** — only those fields are overwritten | CODE-READ |
| Reorder boxes in the document | **unchanged**; `order_index` rewritten | **VERIFIED** (run E) |
| **Delete the box node** | **row hard-deleted, no tombstone** — id gone | **VERIFIED** (run F) |
| Re-insert a box after deleting one | **brand-new id** — the editor mints a fresh one | CODE-READ `AnswerBoxNode.jsx:5` + run F |
| `PUT /blocks` on a **finalized** question | **impossible — 409** | **VERIFIED** (run G) |
| `POST /finalize` on a finalized question | **impossible — 409** | **VERIFIED** (run G) |
| `POST /clone` a question with boxes | **500**, `IntegrityError` — clone never completes | **VERIFIED** (run H) |
| Delete the question | no route exists | CODE-READ — 7 routes, no DELETE |

**The consequence is unusually good for you.** `bbox`, `page_index` and the id
are written **exactly once**, at `finalize`, and every mutation path out of
`finalized` is closed — 409, 409, 500, and no DELETE route. So for a finalized
question, **`(question_id, answer_box_id) → bbox` is immutable for the lifetime
of the row.** That is a sound foreign key. Ironically the broken clone (run H) is
part of what guarantees it.

**Three real hazards:**

1. **The id is a global PK, not scoped to the question.** VERIFIED (run I): two
   different questions carrying the same `answer_boxes[].id` → the second
   `POST /api/questions` returns **500**. Today that means a bare
   `answer_box_id` happens to be globally unique — *but only because collisions
   crash rather than coexist.* If clone is ever fixed with a composite PK
   (`(question_id, id)`), bare ids stop being unique and any FK built on the id
   alone silently starts joining across questions. **Store the pair.**
2. **The id is opaque and carries no state.** It cannot tell you whether the
   question is finalized, what page it is on, or which version of the geometry it
   refers to. Store `bbox`, `page_index`, `page_w_px`, `page_h_px` and
   `finalized_at` alongside it at ingest — do not plan to re-fetch and trust that
   nothing moved.
3. **It is unvalidated client input.** No format check, no length check, no
   charset check. If you use it in a filesystem path — as **his own extractor
   does**, `uploads/{q}/{sub}/crops/{answer_box_id}.png` — sanitize it. Nothing
   upstream stops a crafted id containing `../`.

## 2.6 Q4 — How much of `bbox` is border and caption?

`bbox` is the **outer border-box** of `.answer-box-node`. From the print CSS
(`_PRINT_CSS_TEMPLATE`, `doc_renderer.py:205-215`):

```css
.answer-box-node { border: 2px dashed #999; border-radius: 6px; margin: 10px 0;
                   padding: 8px; box-sizing: border-box }
.ab-label        { font-size: 11px; font-weight: bold; color: #888 }
```

and the emitted HTML is
`<div class="answer-box-node" …><div class="ab-label">☐ {label}</div></div>` — so
the caption is a block element **inside** the padding box, at the top, occupying
its own line. `margin: 10px 0` is **outside** `bbox` and costs nothing.

Non-writing chrome, per edge:

| Edge | Border | Padding | Caption | **Total inset** |
|---|---|---|---|---|
| left | 2 | 8 | — | **10 px** |
| right | 2 | 8 | — | **10 px** |
| **top** | 2 | 8 | **~13** (11 px bold, `line-height: normal` ≈ 1.2) | **~23 px** |
| bottom | 2 | 8 | — | **10 px** |

### Concrete answer

**Apply inset `(left 10, top 23, right 10, bottom 10)` to leave only handwriting.**
For a safety margin against the ~0.5–1 px registration residual, print
fit-to-page scaling, and pen strokes that graze the border, use
**`(12, 26, 12, 12)`**.

Worked on the real first box, `bbox = [186, 334, 930, 90]` (VERIFIED, run K):

| | inset `(l,t,r,b)` | resulting canonical rect | % of `bbox` area |
|---|---|---|---|
| verbatim `bbox` | (0,0,0,0) | `[186, 334, 930, 90]` | 100.0% |
| border only | (2,2,2,2) | `[188, 336, 926, 86]` | 95.1% |
| border + padding | (10,10,10,10) | `[196, 344, 910, 70]` | 76.1% |
| **+ caption — recommended** | **(10,23,10,10)** | **`[196, 357, 910, 57]`** | **62.0%** |
| conservative | (12,26,12,12) | `[198, 360, 906, 52]` | 56.3% |

At `(10,23,10,10)`, how much survives depends almost entirely on box height:

| `bbox` w × h | writable w × h | % of `bbox` |
|---|---|---|
| 930 × 90 (the minimum) | 910 × **57** | 62% |
| 930 × 130 | 910 × 97 | 73% |
| 930 × 250 | 910 × 217 | 85% |
| 930 × 400 | 910 × 367 | 90% |
| 233 × 90 (25% width, minimum height) | 213 × **57** | 58% |

**Two rules that matter more than the numbers:**

1. **Apply the inset in canonical page space, to `bbox`, *before* the
   homography.** Insetting the warped quad or the axis-aligned crop afterwards
   applies a different amount on each edge, because the photo's scale varies
   across the page.
2. **`w` is `content_w × widthPercent / 100`, not measured** — the box is drawn
   at that width, so `w` is exact. `h` is the *measured rendered* height. A box
   at the 90 px minimum leaves **57 px** of writing room. Tell teachers to set
   130 px or more, or expect the top of the handwriting to sit against the
   caption.

**One caveat, honestly flagged.** `_measure` reads `getBoundingClientRect()` on a
wrapper `<div data-idx="i">` while the print pass emits the same block as a raw
sibling (`TEACHER_NOTES.md` §2.5). My reading is that adjacent-sibling margins
collapse to the same 10 px in both structures for the simple
heading/paragraph/box sequences the editor produces, so the drift should be zero
or near it — but **neither pass has ever run**, so that stays CODE-READ. Before
trusting `bbox_y` to the pixel: finalize one question, rasterize the PDF, and
measure where the dashed borders actually land. If there is a constant offset it
will be constant, and it folds into the inset.

## 2.7 Q5 — Is anything associating question prose with a box?

**No recorded association — but the TipTap tree is *not* unlinked. It is
positionally linked, and the link is machine-readable from the same response.**

Nothing in `answer_boxes[]` names its prompt. `label` is free text, defaults to
`""`, and is typed by hand. There is no `question_text`, no `prompt_id`, no
grouping node. That part stands.

But `content` is not opaque:

- Each answer box appears in `content.content[]` as a first-class
  `{"type": "answerBox", "attrs": {"id": …, "label": …, "points": …,
  "widthPercent": …, "minHeight": …}}` node, **carrying the same `id`** as the
  `answer_boxes[]` entry. VERIFIED in the live response above; `id` is a
  persisted TipTap attribute (`AnswerBoxNode.jsx:130-137`).
- `answer_boxes[]` is built by `editor.state.doc.descendants(...)` in document
  order (`QuestionEditor.jsx:67-76`), so the two lists are the **same sequence**.

So you can derive, in one call and with no extra endpoint:

> **the prompt for box `X` = the text of every block between the previous
> `answerBox` node and node `X`.**

Flatten `paragraph` / `heading` `content[]`: `text` nodes give `.text`,
`equation` nodes give `.attrs.latex` (wrap in `$…$`, or `$$…$$` when
`attrs.display` is true), `hardBreak` → newline.

That is a **heuristic, not a contract**. It breaks on: a shared preamble above
several boxes; a box whose prompt follows it; two boxes for one two-part
question; a heading that belongs to the section rather than the question. It is
good enough to prefill a grading UI and let a human confirm; it is **not** good
enough to feed an on-device grader unattended, because a wrong prompt yields a
confidently wrong mark with no signal that anything was misread.

Note also that `content` is the **pre-pagination** block list — it has no page
indices and no rendered heights. Cross-page prompts (prose on page 0, the box
pushed to page 1 by `_paginate`) are invisible in the tree; only `page_index` on
the box records that.

---

# Part 3 — What must change on the teacher side

Extraction here means: a student photographs a printed sheet, registers it off
the markers, and cuts out the right rectangles reliably. Grading is a separate
dependency and is called out where it dominates.

## Blocking

1. **Emit the marker contract in the response.** §2.3. `aruco_dict`,
   `marker_size_px`, `marker_margin_px`, and the four `centres`.
   `render_finalized_question` already computes all of it and discards it.
   Blocking because a mismatch produces **silently wrong crops with a `4/4` /
   `"homography"` success report** — 11 px of displacement for a 10 px constant
   change, 990 px for a marker-order mistake (both VERIFIED, run J) — and because
   the layout constants in this project have **already diverged once undetected**
   (§1.3). Cheapest item on the list, and the one that converts an unchecked
   convention into a checked one.

2. **Serialize `page_count`.** §2.2, VERIFIED. One field in `_question_to_out`;
   the value is already in the row. Blocking for any multi-page worksheet: a
   student app cannot tell the student how many photos to take, and
   `max(page_index)+1` undercounts a trailing page. It also un-breaks his own
   page selector (`ExtractPage.jsx:49`, `ExtractionView.jsx:57`).

3. **Model answer and rubric per answer box.** New columns on `answer_boxes`, a
   control in the `AnswerBoxNode` node view, plumbed through `_extractPayload` →
   `AnswerBoxIn` → `_upsert_answer_boxes` → `AnswerBoxOut`. Blocking for grading,
   not for extraction — but `GradingService.grade()` on the Android side already
   **requires** `modelAnswer` and `maxMarks` (`CLAUDE.md` §3.7), so without this
   there is nothing to grade against and the crops have nowhere to go. Largest
   item here by a wide margin: new schema, new UI, new API.

4. **A per-copy identifier on the printed sheet.** Every printed copy is
   byte-identical and carries no student name, no copy id, no page number
   (`TEACHER_NOTES.md` §6). Nothing on a returned photo identifies who wrote on
   it or which page it is. Blocking for any multi-student deployment; if the
   student app always knows its own identity and which page it is photographing,
   it can be deferred — but then a misfiled photo is unrecoverable and
   undetectable.

5. **A stable question ↔ prompt association, or an explicit decision to accept
   the positional heuristic.** §2.7. The cheap version is a process fix: require
   `label` to be filled in and treat it as authoritative. The correct version is
   a grouping node in the editor. Blocking in the sense that *somebody must
   decide*, because the failure mode of getting it wrong is a confident wrong
   mark against the wrong prompt, with no signal.

## Nice to have

6. **Expose `order_index` on `AnswerBoxOut`.** §2.4. One word. Array order works
   today and is `order_index` order (VERIFIED), but it depends on every layer
   between the API and your database preserving JSON array order.

7. **Publish the writable inset**, or shrink `bbox` to the content box. §2.6.
   Today every client must re-derive `(10, 23, 10, 10)` from CSS it cannot see.
   Either return an `inset` alongside `bbox`, or return the content rect directly
   and let the border and caption stay implicit.

8. **Raise the answer-box minimum height, or warn in the editor.** §2.6. A 90 px
   box leaves **57 px** of writing room, 62% of its area. The editor's own floor
   is 60 px, which the CSS `min-height: 90px` silently overrides — so the smallest
   achievable box is already the worst case, and nothing tells the teacher.

9. **Fix `clone`.** Run H, VERIFIED `IntegrityError` → 500. A finalized worksheet
   can never be revised and a deleted PDF can never be regenerated — and the 404
   message's own advice ("try re-finalizing (clone + finalize)") is the broken
   path. Not blocking for extraction; blocking for the teacher's own sanity. Note
   the fix interacts with §2.5: minting new box ids on clone keeps bare ids
   unique, a composite PK does not. **If you take the composite-PK route, tell
   the student side first.**

10. **Verify `bbox_y` empirically, once.** §2.6 caveat. Finalize one question,
    rasterize the PDF, measure where the dashed borders actually land, compare
    against the stored `bbox`. Nobody has ever run `_measure` or the print pass. A
    constant offset is easy to absorb; discovering one after a term of scanned
    worksheets is not.

11. **`page.pdf(format="A4")`.** `TEACHER_NOTES.md` §1.3 — the PDF is 1.56× A4, so
    it must be printed fit-to-page at ~64%. The homography absorbs the scale, so
    this is print ergonomics and marker legibility (60 px → 10.2 mm on paper), not
    coordinate math.

12. **Either fix the QR or delete it.** 1.12 px/module, undecodable before the
    page is even printed (`TEACHER_NOTES.md` §1.5), independently corroborated by
    `mobile_Extract` failing to decode its own sample at 1×/2×/4×/8×
    (`NOTES.md` §5). ArUco-only registration works — I verified it. Right now the
    QR consumes 66 px of left gutter, pushes `LEFT_MARGIN` from 120 to 186, and
    returns nothing. Note that **fixing it by enlarging `_QR_SIZE` moves
    `LEFT_MARGIN` and therefore invalidates the geometry of every
    already-finalized question** — do it before anything is finalized in anger, or
    not at all. If it is kept, the short-payload pipe form salvaged from
    `mobile_Extract` (§1.7) is the way to shrink the symbol, and a per-copy
    payload is the natural carrier for blocker #4.

13. **Rectify crops with `warpPerspective`** if the student app ever consumes
    *his* extractor's output rather than doing its own. `_crop_region` takes the
    axis-aligned bounding rect of a warped quad — at mild skew that is 1.32× the
    true box area with the perspective distortion left in (`TEACHER_NOTES.md`
    §9C). **If the student app extracts for itself, warp the canonical inset rect
    onto `[w-20, h-33]` and you get a rectified, consistently-sized, caption-free
    crop directly** — strictly better than anything either service currently
    produces.

14. **Authentication.** Both services are entirely unauthenticated (§1.5).
    Submission ids are the only thing protecting student work, against an
    `ASC_Capstone` that is JWT-gated on every route. Out of scope for extraction;
    not out of scope for deployment.
