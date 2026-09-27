# current_plan_to_be_done.md: the plan

> Rewritten 2026-09-25. It replaces the 2026-09-24 plan, which had the phone as
> the teacher's marking device. That is gone: the phone is the **student's**
> app, and `Script-Checker-Web-End` is the only backend.
>
> Sources:
> - Read in full: `HANDOFF.md`, the previous version of this file, all of
>   `Capstone_Android` (both modules, tests, Gradle files, manifest), and the
>   `Script-Checker-Web-End` files listed in the session prompt.
> - Read only the Android parts: `CLAUDE_OLD.md`, `RUNBOOK.md`,
>   `INTEGRATION_AUDIT.md`.
> - Not read: `ASC_Capstone` and `v-2.1.1`.
>
> Every `file:line` below is in `Script-Checker-Web-End/backend/` unless it is
> prefixed otherwise. Anything not checked against code is marked
> **UNVERIFIED**.

---

## A. Architecture

### A.1 One picture

```
 TEACHER (browser)                        STUDENT (Android phone)
 Script-Checker-Web-End frontend          Capstone_Android :app + :extractor
   author paper -> finalize (geometry)      Microsoft sign-in (MSAL)
   bell (polls every 60 s)                  join course by code
   review / override / Grade / release      list assignments
            |                               GET pack (geometry + answer key)
            | HTTPS, Entra bearer token     print/solve on paper, photograph
            v                               POST pages -> hand in
 +--------------------------------------+   crop pages locally (:extractor)
 | Script-Checker-Web-End backend       |   grade each box (Qwen2-VL 2B,
 | FastAPI + Postgres, all under /api   |<--  LiteRT-LM, on device)
 |  existing: courses, student,         |   POST results (one call)
 |   submissions (server crops every    |   GET my grades (provisional)
 |   page), grading, notifications      |   POST re-evaluation request
 |  NEW (branch feature/on-device-      |
 |   grading): routers/on_device.py     |
 |   pack, start, results, my grades,   |
 |   re-eval, lease sweeper, fallback   |
 +------------------+-------------------+
                    | only flagged boxes, in the background
                    v
          self-hosted LLM (SELF_HOSTED_LLM_URL)
```

Two cropping paths share one geometry contract (§C.6):
- The **server** crops every uploaded page. Those crops are for the teacher's
  view and for fallback.
- The **phone** crops its own photos with `:extractor` and grades those.

The phone never downloads server crops. It could not anyway: the student crop
route refuses before release (`routers/submissions.py:961-970`).

### A.2 Folder map and git roots

State as found on 2026-09-25:

| Path | Role | Git |
|---|---|---|
| `capstone app/` (project folder) | Parent folder: plan, docs, model files | **Not a git repo yet.** No `.git`; `git rev-parse` fails. The user will put it on a new GitHub repo later. The root `.gitignore` already excludes `/Script-Checker-Web-End/` (and `*.litertlm`) for then. The prompt calls it "the capstone_cse450 git repo". No such name appears in any file here, and HANDOFF.md:121 says "not a git repo". See Status. |
| `Capstone_Android/` | Student app. `:app` (UI, networking, LiteRT-LM grading) and `:extractor` (ArUco registration and crops) | **Own nested `.git`.** Origin is `github.com/whoIsJihad/Capstone_Android`, branch `feature/on-device-grading`, one commit `8281d9f`. 29 modified files plus large untracked trees, uncommitted since August. |
| `Script-Checker-Web-End/` | The only backend and database (FastAPI + Postgres + React). The teammate's repo. | **Own `.git`.** Origin is `github.com/Undying2021Dreams/Script-Checker-Web-End`, branch `main` at `c69eea2`, equal to `origin/main` as of the last successful fetch. `git fetch` failed today ("Could not resolve host: github.com", no network), so it is not re-checked against GitHub. One untracked file, `addition_branch_phone.md`, which a previous session drafted; so it is not a pristine clone. `.env` files are not set up; only `backend/.env.example` and `frontend/.env.example` exist. Now in the parent `.gitignore`. |
| `ASC_Capstone/` | Legacy Node server | Own `.git`. Not used, not read, not modified, not deleted. |
| `v-2.1.1/` | Legacy teacher worksheet system | Not a git repo. Not used, not read, not modified, not deleted. |
| `*.litertlm` (root) | Qwen2-VL-2B (1,784,096,288 B), LLaVA-OV-0.5B (829,262,144 B), gemma3-1b (584,417,280 B) | Gitignored (`*.litertlm`) |
| `CLAUDE.md` | Short session rules | parent folder |
| `CLAUDE_OLD.md`, `HANDOFF.md`, `RUNBOOK.md`, `INTEGRATION_AUDIT.md` | Old ASC_Capstone architecture. **History only, never instructions.** | parent folder |

---

## B. Locked decisions

Copied from the 2026-09-25 prompt. They are not re-argued here. The places where
the code pushes back are listed in B.1.

1. `Script-Checker-Web-End` is the only backend and database. `ASC_Capstone`
   and `v-2.1.1` are legacy: not used, read, modified or deleted.
2. The Android app is for **students only**. Teachers use the web end in a
   browser.
3. Student flow:
   1. Sign in with Microsoft.
   2. Join a course by join code.
   3. List assignments.
   4. Download the assignment pack. It **includes the answer key**, always; this
      is an accepted demo risk.
   5. Solve on paper and photograph the pages in the app.
   6. Upload the pages, then hand in.
   7. Crop on the phone with `:extractor`.
   8. Grade each box on the phone (Qwen2-VL 2B via LiteRT-LM).
   9. Post all results in one call, flagging low-confidence boxes for fallback.
   10. The student sees the phone's marks at once; fallback boxes fill in when
       ready.
   11. The student can request re-evaluation.
4. **Fallback.** The server re-marks only the flagged boxes, in the background,
   with its self-hosted LLM. It needs both of these:
   - the app's `fallbackEnabled` flag (off for local testing);
   - the server's `SELF_HOSTED_LLM_URL`.

   If either is off, flagged boxes become "needs teacher review" instead. Build
   all of it now.
5. **Confidence** comes from a `CONFIDENCE` line in the model's reply. These
   always count as low confidence: a parse failure, `UNREADABLE`, an
   out-of-range score, a missing `CONFIDENCE`.
6. **Grading semantics follow `services/grading.py` exactly.**
   - Reply format is TRANSCRIPT / SCORE / FEEDBACK.
   - `looks_blank` runs before any model call; a blank box is a real 0.
   - Unreadable or a bad reply means needs review. Never invent marks.
   - Marks are per answer box. There is no whole-submission grade.
7. **Status.** `grading_status` is `"grading"` from the moment the phone starts
   until **every** eligible box is terminal (graded, blank or needs review),
   fallback boxes included. Only then is it `"graded"`. The server checks
   completeness itself.
8. **No frontend changes.** The teacher learns through the bell (polls every
   60 s):
   - the existing `script_submitted` notification on hand-in;
   - a new one when the phone's grading run completes.

   Marks appear when the teacher opens or refreshes the paper.
9. **Two cropping paths, one geometry contract.** The web end crops every page;
   the phone crops with `:extractor`. The phone never downloads server crops.
10. **Mark visibility.** The new "my grades" route shows **provisional** marks
    before release. This is a deliberate exception. Existing student routes stay
    release-only. Official marks are the ones the teacher releases.
11. **Stuck runs.** Start returns a run token and records the start time.
    - A run not completed within 15 minutes (a setting) expires: status becomes
      `"failed"` with a clear message.
    - The app resumes by starting again, which issues a new token.
    - A post carrying an expired token gets 409.
    - One additive, nullable migration.
12. A teacher pressing **Grade** on the website overwrites the phone's marks.
    Accepted and documented.
13. **Web end changes are additive only**, on branch `feature/on-device-grading`.
    - Every change is logged in `Script-Checker-Web-End/addition_branch_phone.md`
      in the same session that makes it.
    - Claude Code only edits files and runs read-only git (status, log, diff,
      branch --show-current). It never adds, commits, checks out, switches,
      creates or deletes branches, moves, removes, stashes, resets, merges,
      rebases or pushes, in any repo.
    - The user creates `feature/on-device-grading` in `Script-Checker-Web-End`,
      and commits and pushes both codebases. The teammate merges and deploys.
    - Before editing the web end, Claude checks that
      `git branch --show-current` prints `feature/on-device-grading`; if not,
      it stops and tells the user.
14. **Local first, then deployed.**
    - Locally, the user runs the web end and the phone reaches it with
      `adb reverse tcp:8000 tcp:8000`.
    - The demo uses the deployed Azure server over the internet.
    - The backend URL is configurable. Today it is hard-coded to
      `http://localhost:3000/api/` in `Capstone_Android/app/build.gradle.kts:25,31`.

### B.1 Where the code pushes back (smallest change proposed)

- **Decision 11 needs three nullable columns, still in one migration:**
  `on_device_run_token`, `on_device_started_at`, `on_device_posted_at` (§C.5).
  - With fewer columns, a teacher-started server run cannot be told apart from
    a phone run, and a stuck fallback cannot be told apart from an unposted run.
  - The draft `addition_branch_phone.md` says "a nullable column"; Phase 3
    corrects it.
- **Decision 6 "exactly" versus the `CONFIDENCE` line.**
  - `_FEEDBACK_RE` is DOTALL and runs to the end of the reply
    (`services/grading.py:321`). A `CONFIDENCE` line placed after FEEDBACK would
    end up inside the feedback. So the phone's format is
    TRANSCRIPT / SCORE / CONFIDENCE / FEEDBACK, and the parser removes the
    CONFIDENCE line before the ported `parse_grading_response` runs.
  - Scores stay floats in 0..max, as on the server (`grading.py:368-377`). They
    are not rounded to integers.
- **Decision 6 versus token budget.**
  - `grade_one` attaches question figures, then model-answer images, then crops
    (`grading.py:460`).
  - Qwen has 4096 tokens at 576 per image (`Capstone_Android/.../ModelSpec.kt:73-82`).
  - The phone therefore sends text plus the student's crops, and adds images
    only while they fit (§D.3). Semantics match; the attached inputs may not.
- **Decision 8 wording.** React Query's default `refetchOnWindowFocus` applies:
  `staleTime` is 30 s (`frontend/src/lib/queryClient.ts:6`) and nothing turns
  refetch-on-focus off (grep). So an open teacher page also refetches when the
  teacher returns to the tab. Harmless, but "does not update by itself" is not
  literally true.
- **Decision 9: "same geometry contract" holds for registration, not for the
  crop inset** (§C.6). The phone's `Inset.ANSWER_BOX` was derived from
  `v-2.1.1`'s unzoomed CSS, and `:extractor` cannot handle boxes that span
  pages. Both are fixed in Phase 5.
- **Decision 3 "photograph in the app".** Today the app only picks from the
  gallery (`ScanScreen.kt:64-72`); the CameraX preview is dead code.
  - Proposal: `ActivityResultContracts.TakePicture` (the system camera, full
    resolution, via a FileProvider), with gallery pick kept as a secondary path.
  - Delete CameraX **and the `CAMERA` manifest permission**. An app that
    declares `CAMERA` without holding it gets a `SecurityException` from
    `ACTION_IMAGE_CAPTURE`.

---

## C. The web end contract

All routes are under `/api` (`main.py:37-47`); the exceptions are `/health`
(`main.py:50`) and `/static`. Auth is a Microsoft Entra access token with scope
`api://<AZURE_CLIENT_ID>/access_as_user` (`security.py:31-36`). Users are
created on first sign-in and are students unless listed in `TEACHER_EMAILS`
(`security.py:39-66`).

### C.1 Verified facts the design rests on

| Fact | Evidence |
|---|---|
| Only `grade_submission` creates `AnswerGrade` rows or writes `llm_score`. The override routes only edit existing rows (404 "No mark for that answer box"). | `services/grading_runner.py:237-253`. `grep "AnswerGrade(\|llm_score ="` finds no other writer outside tests. `routers/grading.py:419-425`, `:571-577`. |
| `POST /api/submissions/{id}/grade` returns 409 while `queued`/`grading` and re-marks every non-protected box. `failed` is allowed. | `routers/grading.py:201-202`, `grading_runner.py:202-203` |
| `reset-marks` does not check grading status, only `released`. | `routers/grading.py:490-515` |
| Release requires `graded`. | `routers/grading.py:613-614` |
| `routers/student.py` never returns model answers or document content. `StudentAssignment` exposes `earned`/`max_score` only after `released_at`. | `routers/student.py:1-10`, `:62-68`; `schemas.py:157-182` |
| `GET /api/student/assignments?course_id=` and `GET /api/student/assignments/{question_id}/pdf` exist. Both need enrolment; the PDF omits groundTruthBox. | `routers/student.py:94-114`, `:117-144` |
| `AnswerBox` stores `page_index`, `bbox_x/y/w/h`, `segments_json`, `qr_bbox_json`, all written at finalize. | `models.py:239-253`, `routers/questions.py:846-858` |
| No student route exposes that geometry. `AnswerBoxOut` carries it only inside `QuestionOut`, which is served by teacher-only routes. | `routers/student.py`; `routers/questions.py:64-77`, `:154` |
| Finalize refuses boxes with no `points` (409). `points` can still be changed after finalize. | `routers/questions.py:918-931`, `:228-270` |
| `AnswerGrade` has no transcript and no confidence column; `raw_response` exists. | `models.py:313-361` (`raw_response` at `:337`) |
| `AnswerGrade` has a unique `(submission_id, answer_box_id)` index. `provider` is a free string. | `models.py:325-327`, `:336` |
| `grading_status` enum: `ungraded`/`queued`/`grading`/`graded`/`failed`. | `models.py:272-276` |
| The frontend polls `useSubmissionGrades` every 2 s and `useSubmissions` every 2.5 s, only while queued/grading. `staleTime` is 30 s. `useNotifications` polls every 60 s. | `frontend/src/lib/queries.ts:195-208`, `:275-288`, `:385-392`; `queryClient.ts:6` |
| The bell renders any `kind` generically (title, then navigate to link), so new kinds need no frontend change. | `frontend/src/components/NotificationBell.tsx:32,100` |
| Hand-in notifies the course teacher: kind `script_submitted`, link `/submissions/{id}`. | `routers/submissions.py:826-871` (notify at `:860-868`) |
| `notify()` does not commit; it joins the caller's transaction. | `services/notify.py:304-325` |
| Crop URLs are absolute, built from `PUBLIC_BASE_URL`. Model-answer image URLs are relative. So the app must always build URLs from its own base URL. | `routers/submissions.py:916`, `config.py:17`; `routers/grading.py:95` |
| Background work is in-process (`BackgroundTasks`). It is lost on restart. | `routers/grading.py:193-197` |
| Deploy scales to zero: min 0 / max 1 replica. | `deploy/azure.sh:190` |
| Migrations run on container start. | `Dockerfile:113` |
| The alembic head is `f8a2c31e76b4`. | `alembic/versions/f8a2c31e76b4_requests_notifications_profiles.py:10` |
| No startup or lifespan hook exists yet. | grep `lifespan\|on_event\|create_task` finds nothing |
| Rate limits: `LLM_LIMIT` 40/hour, `HEAVY_CPU_LIMIT` 30/min, keyed per token. | `ratelimit.py` |
| Aside, not ours to fix: `GET /api/crops/{sid}/{box}` lets the owning student read crops **before** release. This contradicts the gate in `routers/submissions.py:961-970`. The app must not use it. Tell the teammate. | `routers/images.py:93-114` |

### C.2 Existing routes the app uses (unchanged)

| Method + path | Request | Response used | Evidence |
|---|---|---|---|
| `GET /api/me` | none | `UserOut` (id, email, display_name, role…) | `routers/me.py:48` |
| `GET /api/courses` | none | `CourseOut[]` with `my_role`; the app shows `student` ones | `routers/courses.py:113-148` |
| `POST /api/courses/join` | JSON `{join_code}` (1-32 chars, uppercased server-side) | `CourseOut`. 404 bad code, 409 archived or you teach it. Rate-limited. | `routers/courses.py:222-249`, `schemas.py:122` |
| `GET /api/student/assignments?course_id=` | none | `StudentAssignment[]`: `question_id`, `title`, `total_marks`, `page_count`, `submission_id`, `submitted_pages`, `handed_in`, `submission_status`, `released`, `earned`/`max_score` (release-only) | `routers/student.py:94-114` |
| `GET /api/student/assignments/{question_id}/pdf` | none | the printable paper | `routers/student.py:117-144` |
| `POST /api/submissions` | multipart: `question_id` (req), `modality` (`photo`), `page_index` (optional; omitted means next page; explicit means replace that page and skip QR identification), `page_index_hint` (optional; QR wins), `image` (file), `submission_id` (optional; the server finds the open one) | `ExtractionResult` `{submission_id, question_id, modality, pages[]}`. 422 on a blurry photo (`assess_image`), wrong paper, or unreadable codes without an index. 409 once handed in or graded. | `routers/submissions.py:342-367`, `:382-392`, `:539-573`, `:608-623` |
| `GET /api/submissions/{id}` | none | the manifest (pages) | `routers/submissions.py:730-733` |
| `DELETE /api/submissions/{id}/pages/{page_index}` | none | the manifest. Before hand-in only. | `routers/submissions.py:736-786` |
| `POST /api/submissions/{id}/submit` (hand in) | none | `{submission_id, submitted_at}`. 409 if no pages. Notifies the teacher. | `routers/submissions.py:826-871` |
| `GET /api/submissions/{id}/grades` | none | `SubmissionGradesOut`, the **official** marks; student gets 403 before release | `routers/grading.py:318-348` |
| `GET /api/notifications` | `?limit` | `{items, unread}`; the student gets `marks_released` | `routers/me.py:150-178` |

App decision: send an explicit `page_index`, taken from the page the student
picks in the app. The printed footer "Page N of M" matches (doc_renderer). This
skips QR identification, which rarely survives a phone photo, and the phone uses
the same index to choose which segments to crop. The `wrong_paper` check still
runs (`routers/submissions.py:546-555`).

### C.3 New routes (new file `routers/on_device.py`, registered in `main.py`)

Names and shapes are proposals built from the real models. Phase 3 may refine
them; any change must be recorded here and in `addition_branch_phone.md`.

**1. `GET /api/student/assignments/{question_id}/pack`.** Enrolled student
only (reuse `student._assert_enrolled`); the question must be finalized.

```jsonc
{
  "pack_version": 1,
  "question_id": "…", "course_id": "…", "title": "…",
  "page_w_px": 1240, "page_h_px": 1754, "page_count": 2, "dpi": 150,
  "markers": {                       // SERVED, never computed on the phone
    "aruco_dict": "DICT_4X4_50",     // settings.ARUCO_DICT
    "marker_size_px": 60, "marker_margin_px": 40,
    "centres": {"0": [70,70], "1": [1170,70], "2": [70,1684], "3": [1170,1684]}
                                     // doc_renderer.get_marker_positions(page_w, page_h)
  },
  "boxes": [{
    "id": "ab_…", "label": "Q1(a)", "points": 5, "order_index": 0,
    "page_index": 0, "bbox": [x,y,w,h],
    "segments": [[page,x,y,w,h], …], // one crop per segment = "part"
    "question_text": "…",            // same derivation as build_grading_items
    "model_answer_text": "…",        // THE ANSWER KEY (decision 3)
    "model_answer_images": ["pack/images/model-answer/<GroundTruthImage.id>"],
    "question_images":     ["pack/images/question/<UploadedImage.id>"],
    "blocked_reason": null           // exactly the build_grading_items string, or null
  }]
}
```

- Text and blocked reasons are built with the same helpers, in the same order,
  as `build_grading_items` (`grading_runner.py:43-140`). The helpers are
  `pair_answer_boxes_with_ground_truth`, `question_text_by_ground_truth_box`,
  `question_nodes_by_ground_truth_box`, `extract_image_ids` and
  `extract_plain_text`. The only difference is that the pack returns image ids
  instead of bytes.
- A test asserts that the pack's `question_text`, `model_answer_text` and
  `blocked_reason` equal `build_grading_items`' output for the same paper.

**2. `GET /api/student/assignments/{question_id}/pack/images/{kind}/{image_id}`.**
`kind` is `model-answer` (a `GroundTruthImage`) or `question` (an
`UploadedImage`).
- Enrolled student only.
- The image must belong to this question: a GroundTruthBox's `question_id`, or
  `UploadedImage.question_id`.
- Returns the bytes with their content type.
- References are **relative**; the app prefixes its base URL.

**3. `POST /api/student/submissions/{submission_id}/on-device/start`.**
Owner only (`student_id == user.id`, else 404), and handed in (409 otherwise).

- Allowed when:
  - status is `ungraded` or `failed`; or
  - status is `grading` and this is the owner's own live, unposted run
    (`on_device_started_at` set and `on_device_posted_at` null). That is a
    resume.
- Refused with 409 when:
  - the submission is released;
  - status is `queued`, or `grading` with `on_device_started_at` null (a
    teacher-started server run);
  - a posted run's fallback is in progress;
  - status is `graded`. The student asks for re-evaluation instead.
- Effect: new token (uuid4); `on_device_started_at = now`,
  `on_device_posted_at = null`; `grading_status = "grading"`,
  `grading_error = null`.
- Response:
  `{run_token, started_at, lease_expires_at, eligible_box_ids: [...], protected_box_ids: [...]}`.
  Eligible means every box minus `protected_answer_box_ids`
  (`grading_runner.py:143-177`).
- Rate limit: `LLM_LIMIT`.

**4. `POST /api/student/submissions/{submission_id}/on-device/results`.**

```jsonc
{
  "run_token": "…",
  "use_fallback": true,                 // the app's fallbackEnabled flag
  "fallback_box_ids": ["ab_2"],         // low confidence, bad reply, or phone could not crop
  "results": [{
    "answer_box_id": "ab_1",
    "outcome": "scored" | "blank" | "needs_review",
    "score": 3.0,                       // required for scored; ignored for blank (saved as 0); null for needs_review
    "feedback": "…",
    "confidence": 0.82,                 // parsed CONFIDENCE, or null
    "raw_response": "TRANSCRIPT: …\nSCORE: 3\nCONFIDENCE: 0.82\nFEEDBACK: …",
    "review_reason": null
  }]
}
```

Checks, in order:
1. Owner (404).
2. Token equals the stored token (409 "run superseded or expired").
3. If already posted with this token, return the current my-grades payload with
   **200** and change nothing. This makes the call idempotent.
4. Status is `grading` and `on_device_started_at` is set (409 "run no longer
   active"; covers a teacher `reset-marks` mid-run).
5. Lease not expired (409; checked here too, not only by the sweeper).
6. Every eligible box is present exactly once, else **400 listing
   `missing_box_ids`**. Unknown or duplicate ids also give 400.
7. `scored` requires `0 <= score <= points`, else 400. The server never clamps.
8. `fallback_box_ids` must be a subset of the eligible boxes.

Save, one transaction, same columns `grade_submission` writes:
`max_score`, `llm_score`, `llm_feedback`, `raw_response`, `provider`,
`needs_manual_review`, `review_reason`.

| Box | Row written |
|---|---|
| Protected | skipped, untouched |
| Server's own `blocked_reason` (recomputed; the client is not trusted) | needs review with that reason, `provider="on_device"`, never fallback |
| Flagged, and fallback available (`use_fallback` and `SELF_HOSTED_LLM_URL` set) | **pending**: `llm_score=null`, `needs_manual_review=false`, `review_reason="Awaiting server re-mark"`, `provider="on_device"`; phone's reply kept in `raw_response` |
| Flagged, fallback unavailable | `needs_manual_review=true`, `review_reason="Low confidence on the phone; needs teacher review"` (or the phone's parse/unreadable reason), `llm_score=null` |
| Everything else | as posted, `provider="on_device"`; blank means `llm_score=0`, feedback "Nothing was written in this answer box." |

Then:
- Set `on_device_posted_at = now`.
- **If no pending boxes:** `grading_status="graded"`, `graded_at=now`,
  `on_device_started_at=null`, and notify the teacher.
- **Else:** stay `grading` and schedule a background task that:
  - rebuilds items with `build_grading_items` on the **server's** crops;
  - keeps only the pending boxes;
  - runs `grade_one(get_provider("self_hosted"), item)` under the same
    semaphore pattern;
  - writes rows with `provider="self_hosted"`;
  - then sets `graded`, clears `on_device_started_at`, and notifies.

  A missing server crop gives grade_one's own "No extracted answer image for
  this box", which is needs review.

The new notification: kind **`on_device_graded`**, title "<student> — phone
grading finished", body "<earned> of <max>, <n> need review", link
`/submissions/{id}`.

Response: the my-grades payload (route 5). 201 on first save, 200 on the
idempotent repeat.

This file becomes the **second writer** of `AnswerGrade` rows, beside
`grade_submission`, by design. It lives in a new `services/on_device.py` and
does not touch `grading_runner.py`.

**5. `GET /api/student/submissions/{submission_id}/grades`.** Owner only.
**Provisional before release** (decision 10).

```jsonc
{
  "submission_id": "…", "question_id": "…",
  "grading_status": "grading", "grading_error": null,
  "released": false, "provisional": true,          // provisional = !released
  "earned": 7.0, "max_score": 10,                  // submission_totals()
  "needs_review_count": 1, "pending_fallback_count": 1,
  "run": {"started_at": "…", "posted_at": "…", "lease_expires_at": "…"},
  "boxes": [{
    "answer_box_id": "ab_1", "label": "Q1(a)", "order_index": 0, "max_score": 5,
    "score": 3.0,                                  // AnswerGrade.score (override wins)
    "feedback": "…",                               // override_feedback or llm_feedback
    "provider": "on_device" | "self_hosted" | "<teacher's provider>",
    "needs_manual_review": false, "review_reason": null,
    "pending_fallback": false
  }]
}
```

It exposes nothing the pack did not already give the student. Pending is
defined as: status `grading`, `on_device_posted_at` set, the row's score null,
not needs review, and `review_reason == "Awaiting server re-mark"`.

**6. `POST /api/student/submissions/{submission_id}/re-evaluation`.**
- Owner only. Status must be `graded` (provisional or released); otherwise 409.
- Body `{message?: str (<=500)}`.
- Calls `notify(course.teacher_id, kind="re_evaluation_requested",
  title="<student> asks for re-evaluation", body=message or question title,
  link=f"/submissions/{id}")`.
- No new table.
- Rate limit, proposed: 5/hour per token.

**Sweeper.** A startup hook (FastAPI lifespan) added to `main.py` runs one sweep
at startup and then an asyncio loop every 60 s. The same check also runs lazily
at the top of routes 3-5, so correctness never depends on the loop; with scale
to zero, the loop only runs while a replica is up. A run is stuck when
`grading_status == "grading"`, `on_device_started_at` is set, and
`now - on_device_started_at > ON_DEVICE_LEASE_MINUTES` (new setting, default 15).

| Case | Action |
|---|---|
| Not posted | `grading_status="failed"`, `grading_error="The phone's grading run did not finish within 15 minutes. Start it again from the app, or press Grade."`, clear the token and `on_device_started_at`. The teacher's Grade button works again (failed is allowed). |
| Posted, fallback stuck (for example the server restarted mid-fallback) | Pending boxes become needs review, reason "Server re-mark did not finish". Then `graded`, clear `on_device_started_at`, notify `on_device_graded`. |

### C.4 Status transitions

```
ungraded --hand in--> ungraded(submitted_at set)
   |  POST on-device/start                     (teacher Grade: ungraded/failed/graded -> queued -> grading -> graded|failed)
   v
grading (token T, started_at, posted_at=null) --start again (resume)--> grading (token T')
   |                     \--lease expired (sweeper or lazy)--> failed --start again--> grading (T'')
   |  POST results with T                                     (teacher Grade allowed from failed)
   +--> no pending boxes ---------------------------> graded  (notify on_device_graded)
   +--> pending fallback: grading (posted_at set)
             --background self_hosted done-----------> graded  (notify)
             --lease expired------------------------> graded  (pending -> needs review, notify)
graded --teacher Grade--> queued --> grading --> graded   (overwrites unprotected phone marks; decision 12)
graded --teacher release--> graded + released_at (official marks; my-grades provisional=false)
```

### C.5 Migration (one, additive, nullable)

On `submissions`: `on_device_run_token VARCHAR NULL`,
`on_device_started_at TIMESTAMP NULL`, `on_device_posted_at TIMESTAMP NULL`.
Its down-revision is `f8a2c31e76b4`. It is applied by `alembic upgrade head` on
container start. No existing column is changed.

### C.6 Page geometry contract: server versus `:extractor`

**Registration still matches.**

| Item | Web end | `:extractor` | Match |
|---|---|---|---|
| Canonical page | `round(8.27*dpi) x round(11.69*dpi)` = 1240x1754 at 150 dpi (`services/doc_renderer.py:691-693`); `dpi` is per question | Takes the served size; no literals in main source | yes, if served |
| ArUco dictionary | `settings.ARUCO_DICT = "DICT_4X4_50"` (`config.py:65`, `doc_renderer.py:71-72`) | `Objdetect.DICT_4X4_50` hard-coded (`extractor/.../Registration.kt:43`) | yes. Phase 5 reads it from the pack and refuses anything else. |
| Marker size / margin | 60 / 40 (`config.py:66-67`) | Only in the test fixture (`SampleFixture.kt:28-29`) | yes (centres served) |
| Centre formula | `m + s//2` from each edge (`doc_renderer.py:109-120`) → (70,70), (1170,70), (70,1684), (1170,1684) | Uses served centres, matched by id (`Registration.kt:159-163`) | yes |
| Id order | `[0,1,2,3]` = TL, TR, BL, BR, row-major (`doc_renderer.py:73`); extractor uses ids 0..3 (`services/extractor.py:711-726`) | Validator requires ids exactly {0,1,2,3} | yes |
| Transform | photo: `findHomography(RANSAC, 5.0)`; scanner: `estimateAffine2D` (`services/extractor.py:145-154`) | Same (`Registration.kt:96-118`) | yes |

**The crop does not match.**

| Item | Web end | `:extractor` |
|---|---|---|
| Crop | Axis-aligned bounding rect of the warped **full bbox**, no inset (`services/extractor.py:166-174`). Photos are flattened first by `scan_photograph` (`routers/submissions.py:586-596`). | `warpPerspective` of an **inset** rectangle |
| Inset | none | `Inset.ANSWER_BOX = (10,23,10,10)` (`Layout.kt:84`). Derived from `v-2.1.1` CSS **without zoom**. The web end zooms box CSS by `dpi/96` (1.5625 at 150 dpi; `doc_renderer.py:647`, `:663-664`): border 2→~3 px, padding 8→12.5 px, 11 px label →~17 px font. **The inset is stale.** |

Decision for Phase 5: grade on the **full bbox** (`Inset.NONE`), as the server
does. Fallback and the teacher then look at the same region the phone graded.

**Multi-page boxes do not match.**
- The web end splits tall boxes into `segments_json` = `[[page,x,y,w,h], …]`, one
  crop per `part` (`doc_renderer.py:523-600`, `:741-745`;
  `services/extractor.py:415-430`), and grades all parts of a box in one call.
- `:extractor` has one page and one bbox per box and no segment concept
  (`Layout.kt:37-41`).
- The app blocks any layout that is not a single page
  (`ScanViewModel.kt:139-143`).
- `LayoutValidator`'s reading-order check (`LayoutValidator.kt:115-125`) is
  redundant once `order_index` is served.

Phase 5 fixes all three.

**UNVERIFIED risk.** Every crop, on both sides, contains the grey box label
(`#888`, below `looks_blank`'s paper-60 threshold). `looks_blank` trims only
4 % of each edge (`grading.py:108-113`), so a truly blank box may still read
as "written on". Measure this on real crops in Phase 2.

---

## D. Android changes by file

Paths are under `Capstone_Android/app/src/main/java/com/example/capstone/`
unless noted.

### D.1 Keep / rewrite / delete

| File | Action | Why / what |
|---|---|---|
| `CapstoneApplication.kt`, `ui/theme/*` | KEEP | |
| `data/local/ModelSpec.kt` | KEEP, small edit | `DEFAULT = QWEN2_VL_2B` already (`:117`). Set Qwen `expectedBytes = 1_784_096_288L` (`:73-82`, now null). |
| `data/local/LocalModelProvider.kt` | KEEP, small edits | Add `maxNumImages` to `EngineConfig` (Phase 1 decides the value). Share the sampler constants. `MODEL_DIR` stays dev-only. |
| `data/local/LocalGradingService.kt` | REWRITE | JSON prompt and `parseGrade` become a port of `GRADING_SYSTEM_PROMPT` + `build_user_message` + `parse_grading_response`, plus the CONFIDENCE line. Keep: a fresh Conversation per call, never-throws, and the retry once with a reminder. Add the `supportsVision` guard. Drop `gradeRaw`. |
| new `domain/grading/ReplyParser.kt` | NEW | Port of `parse_grading_response` (`grading.py:320-377`, regexes and `_NOTHING_WRITTEN` verbatim) plus `CONFIDENCE:` extraction. Missing, out-of-range or unparsable confidence becomes low. |
| new `domain/grading/BlankDetector.kt` | NEW | Port of `looks_blank`: 4 % edge trim, 90th-percentile paper tone, threshold paper-60, `BLANK_INK_FRACTION = 0.0002` (`grading.py:83-125`). |
| `domain/grading/GradingService.kt` | ADAPT | `GradeResult` becomes {outcome, score: Double?, transcript, feedback, confidence: Double?, raw, reviewReason}. |
| `domain/grading/WorksheetGrade.kt` | REWRITE | Delete `mergeBoxResults` and the min-confidence rule (`:132-149`); that rule caused the §3.8 "0". Keep the Scored/NeedsReview split, adding a Blank outcome. Totals are display only. |
| `domain/grading/WorksheetGrader.kt` | ADAPT | Grades per box with all of that box's part crops. Blank check before the model. Blocked boxes are never sent to the model. Flags come from the confidence threshold. |
| `domain/worksheet/QuestionResolver.kt` | ADAPT (shrink) | The join key becomes box id within one pack. Keep the all-or-nothing rule. |
| `domain/worksheet/WorksheetSession.kt` | ADAPT | Multi-page. Backed by files (see new `PagePhotoStore`) so a resume after process death works. |
| `domain/worksheet/MarkerCorners.kt` | KEEP | |
| `domain/model/Assignment.kt` | REWRITE | Course, Assignment (StudentAssignment), Pack, PackBox (segments). |
| `domain/model/Grade.kt`, `Submission.kt` | DELETE | Whole-submission concepts. Replaced by per-box `BoxMark`. |
| `data/remote/ApiService.kt` | REWRITE | Routes in §C.2 + §C.3. |
| `data/remote/AssignmentModels.kt`, `AuthModels.kt`, `GradeModels.kt` | REWRITE / DELETE | New DTOs named exactly as in §C. FastAPI errors are `{detail: str \| list}`. |
| `data/remote/LayoutModels.kt` | ADAPT | Becomes the pack DTO. Keep "served, never computed". |
| `data/repository/AssignmentRepository.kt` | REWRITE | Keep `toExtractorLayout`'s no-repair rule (`:245-275`), extended to segments. |
| `data/repository/AuthRepository.kt`, `data/local/TokenManager.kt` | REWRITE | MSAL Android single-account public client, `acquireTokenSilent` before each call. No backend token exchange: the Entra access token is the bearer. `TokenManager` is probably deleted (MSAL caches). |
| `di/AppContainer.kt` | ADAPT | `BASE_URL` comes from a Gradle property (default `http://localhost:8000/api/`; release is the Azure FQDN). `FALLBACK_ENABLED` build flag (debug false). Auth interceptor via MSAL. `HttpLoggingInterceptor(BODY)` must become HEADERS, with `Authorization` redacted, or it logs bearer tokens. |
| `MainActivity.kt` | ADAPT | Nav: SignIn → Courses (+ join) → Assignments → AssignmentDetail (pack) → Capture pages → Hand in → Grading → MyGrades. Auto-sign-in if MSAL has an account. |
| `ui/screens/Login*`, `Register*` | DELETE | Replaced by `SignInScreen`/`SignInViewModel`. |
| `ui/screens/Home*` | REWRITE | Courses and join code. The deprecated `TabRow` goes. |
| `ui/screens/AssignmentDetail*` | ADAPT | Shows the pack, pages and status. Remove the stray `PaddingValues(16.dp)`. |
| `ui/screens/Scan*` | REWRITE | `TakePicture` per page plus gallery fallback. Page picker (sends `page_index`). Upload, show 422 reasons, delete a page, hand in. Crop locally per page. Delete the dead `CameraPreview` (`:295-359`). |
| `ui/screens/Submit*` | DELETE | Orphaned. |
| `ui/screens/Result*` | REWRITE | Becomes MyGrades: per box, provisional or official, pending fallback, re-evaluation button. |
| `ui/screens/WorksheetGrading*` | ADAPT | Keep the state machine (retry never re-grades; cancel between boxes). Flow becomes start, grade, one results post. |
| `ui/screens/ModelTest*` | KEEP (temporary) | Phase 1's tool: raw Qwen capture, multi-image probe, new prompt. |
| `util/ImagePrep.kt` | KEEP | `toGradingPng` stays at a 1024 long edge; Phase 1 checks this against the 576-token image cost. |
| `AndroidManifest.xml` | ADAPT | Remove `CAMERA` and camera `uses-feature`. Add the MSAL `BrowserTabActivity` intent filter and a FileProvider. Restrict cleartext to `localhost` in `network_security_config.xml`. |
| `app/build.gradle.kts`, `gradle/libs.versions.toml` | ADAPT | Add MSAL. Remove CameraX. BASE_URL/FALLBACK_ENABLED fields. |
| `Capstone_Android/API_REQUIREMENTS.md`, `README.md` | DELETE / REWRITE | Old Node contract. |
| `:extractor` `Layout.kt` | ADAPT | `AnswerBoxRef` becomes a per-segment ref `(boxId, part, pageIndex, bbox, orderIndex)`. Crops carry `part`. |
| `:extractor` `Registration.kt` | ADAPT | Dictionary from the pack. Refuse anything but `DICT_4X4_50` with `InvalidLayout`. |
| `:extractor` `LayoutValidator.kt` | ADAPT | Drop the geometric reading-order check. Validate served `order_index` uniqueness and per-page segment overlap. |
| `:extractor` `PageExtractor.kt`, `ExtractionResult.kt`, `OpenCvNative.kt` | KEEP | Default inset becomes `Inset.NONE` for grading crops (§C.6). |

### D.2 Tests

- **Rewrite:** `GradeParsingTest` (TRANSCRIPT/SCORE/CONFIDENCE/FEEDBACK cases,
  including every `parse_grading_response` branch and real Qwen strings from
  Phase 1), `MergeBoxResultsTest`, and `AssignmentDtoParsingTest` (fixture
  becomes a real pack JSON).
- **New:** `BlankDetectorTest` (the server's thresholds) and a segments case in
  `PageExtractorTest` / `LayoutValidatorTest`.
- **Keep:** the other `:extractor` suites, `ImagePrepTest`,
  `ExifOrientationTest`, `MarkerCornersTest`.
- **Delete:** the `Example*` placeholders.

### D.3 On-device grading recipe (Phase 2)

For each box in `order_index` order:

1. Blocked → `needs_review` with the server's reason, never the model.
2. No crop (markers not found on its page) → `needs_review`, flagged for
   fallback. The server may have cropped it.
3. All parts blank (`BlankDetector`) → `blank`, score 0.
4. Prompt = the ported system prompt + `build_user_message`, adding
   `CONFIDENCE: <0.0-1.0>` between SCORE and FEEDBACK.
   - Images: the student's part crops, always. The model-answer image only if
     `model_answer_text` is blank. Question figures only while
     `576 * images + prompt + 300 reply` stays under 4096.
   - A box that cannot fit is `needs_review` and flagged.
5. Parse:
   - UNREADABLE, parse error or out of range → `needs_review` + flagged (after
     one retry with a reminder).
   - Otherwise scored, flagged when confidence is missing or below the
     threshold (a `BuildConfig` value, proposed 0.7).
6. Post once. If `fallbackEnabled` is false, still send `fallback_box_ids`, with
   `use_fallback=false`; the server turns them into needs review.

---

## E. Phases

Each phase ends with tests, a build, an update to §H, and a list of changed
files grouped by repo, each group with a suggested commit message. Claude runs
no git write commands; the user commits and pushes (decision 13).

1. **Qwen diagnosis** (Android only; ModelTest screen).
   - Push Qwen and check bytes (1,784,096,288).
   - Capture raw replies with the old JSON prompt and the new line prompt on
     real crops (log at `LocalGradingService.kt:95`).
   - Settle which §3.8 route fired: `parseGrade` null (`:141-174`), or
     `legible` missing/false forcing review (`:164`, `:172`).
   - Probe two images in one `Contents` and `maxNumImages`. If unsupported,
     decide to stitch parts into one image.
   - Measure latency per box.
   - Save the raw strings as test fixtures.
2. **Grading engine** (Android).
   - ReplyParser, BlankDetector, new prompt.
   - `LocalGradingService` rewrite, `WorksheetGrade` rewrite, Qwen
     `expectedBytes`.
   - Unit tests green.
3. **Web end branch.**
   - The user creates `feature/on-device-grading` in `Script-Checker-Web-End`.
     Claude confirms `git branch --show-current` prints it before editing, and
     stops if it does not.
   - Migration, `ON_DEVICE_LEASE_MINUTES`, `services/on_device.py`,
     `routers/on_device.py`, lifespan sweeper in `main.py`.
   - `tests/test_on_device.py`: pack parity with `build_grading_items`,
     enrolment, answer-key access, start rules, results validation (missing ids,
     range, token, idempotency, expiry), fallback on/off with a stubbed
     provider, sweeper, notifications, my-grades, re-evaluation.
   - `addition_branch_phone.md` updated in the same session.
   - `python -m pytest` needs the user's test Postgres; ask before running
     anything that needs services.
4. **Sign-in and networking** (Android).
   - MSAL, BASE_URL/FALLBACK_ENABLED, new ApiService and DTOs.
   - Courses, join and assignments screens.
   - Blocked on the Entra Android redirect (G.1).
   - Local interim: the user may create their own app registration with an
     Android platform, and point their local `AZURE_CLIENT_ID` at it.
5. **Scan, upload, crop** (Android + `:extractor`).
   - TakePicture, page picker, `POST /api/submissions`, delete page, hand in.
   - Segments in `:extractor`, `Inset.NONE`, validator changes.
   - `PagePhotoStore`.
6. **Grade, post, fallback, results** (Android).
   - Start, grade, results post, handle 409 (restart run), MyGrades polling
     while `grading`, re-evaluation.
7. **End-to-end local test.**
   - The user runs the web end.
   - `adb reverse tcp:8000 tcp:8000`.
   - A teacher authors and finalizes a 2-page paper with a box spanning pages.
   - The student joins, photographs, hands in and grades.
   - Check the bell, then refresh the teacher page: marks show with provider
     `on_device`.
   - Repeat with fallback on and a self-hosted URL.
   - Check lease expiry (short lease setting), teacher Grade overwrite, and
     release.
8. **Deployment handover.**
   - Release `BASE_URL` = Azure FQDN, release keystore signature hash.
   - Checklist in `addition_branch_phone.md` §8 for the teammate.
   - Demo run on the deployed server.

---

## F. Risks

- **Qwen multi-image is untested.** `EngineConfig` does not set `maxNumImages`
  (`LocalModelProvider.kt:210-216`). Multi-part boxes may need stitched crops.
- **Token budget.** 4096 context and 576 per image, so about 4 images plus the
  prompt at most. Long marking schemes shrink that further. The 576 figure is
  taken from `ModelSpec`; **UNVERIFIED** on device.
- **Self-reported confidence is weak.** Hence decision 5, where every doubtful
  reply is low confidence. Expect many flags until it is calibrated.
- **The answer key sits on the student's phone before they answer** (decision
  3). Demo only; unacceptable for real exams.
- **A modified app could post fake marks.** Mitigations:
  - marks are provisional until the teacher releases them;
  - `raw_response` is kept for audit;
  - the server re-checks ranges, completeness and blocked boxes.
- **The teacher page isn't live.** The bell polls every 60 s; refresh or tab
  focus does the rest.
- **Teacher Grade overwrites phone marks** on every unprotected box
  (decision 12).
- **Different crops.** The phone grades its own crops (original photo,
  `warpPerspective`); the teacher sees the server's (flattened, relit, bounding
  rect). Same bbox, different pixels.
- **Scale to zero and in-process work.** A fallback in flight dies with the
  replica. After the lease its boxes become needs review.
- **`looks_blank` versus the printed label** (§C.6), UNVERIFIED on both sides.
- **Stale docs.** `CLAUDE_OLD.md`, `HANDOFF.md`, `RUNBOOK.md` and
  `INTEGRATION_AUDIT.md` describe ASC_Capstone and contradict each other; for
  example, RUNBOOK says LLaVA is the default. Never follow them.
- **Uncommitted Android work.** `Capstone_Android` has a month of uncommitted
  work on its only branch. The user commits it before Phase 1 edits anything.

---

## G. External blockers (teammate)

1. **Entra app registration** `<DEPLOYED_CLIENT_ID>`
   (`deploy/azure.sh:46`) needs an **Android platform**: package
   `com.example.capstone` plus the debug keystore signature hash; add the
   release hash later.
2. **Review and merge** `feature/on-device-grading` after the user pushes it.
3. **Redeploy** with `./deploy/azure.sh deploy`. The migration runs on start
   (`Dockerfile:113`).
4. **Set `SELF_HOSTED_LLM_URL`** on the Container App (`deploy/azure.sh:54`,
   `:209-211`) to enable fallback. Optionally set `ON_DEVICE_LEASE_MINUTES`.
5. FYI: the pre-release student crop read at `routers/images.py:93-114` (§C.1).

---

## H. Status

Later sessions update this section: newest first, one block per session.

### 2026-09-27: Session 11, deployed web end handover (DOCS DONE; deployed test NOT RUN, blocked on the teammate)

- **User's report (not re-run here, no phone connected):** local testing passed. Gemma 4 E2B two-turn
  posted 5/5 and 10/15 as `on_device`; with "Force server re-mark" on, boxes came back `self_hosted`
  (closes "real fallback not run"). Next target: the teammate's **deployed** web end on Azure.
- **Web end audit (read-only):** `feature/on-device-grading`, `HEAD` = `origin/main` = `c69eea2`
  (`git ls-remote`; the branch is not on GitHub yet), so `git diff --stat origin/main...HEAD` is
  empty and every change is uncommitted: 5 new files (`routers/on_device.py`, `services/on_device.py`,
  migration `a9c4d2e81f37_on_device_runs.py`, `tests/test_on_device.py`, `addition_branch_phone.md`),
  5 modified (`config.py`, `main.py`, `models.py`, `schemas.py`, `tests/conftest.py`), all logged.
  Code dated 2026-09-25 (Session 3), unchanged since. Still in the tree and **not part of the branch**:
  `backend/.env.example` and `frontend/.env.example` deleted (restore before committing). One
  migration, single alembic head.
- **pytest** (user confirmed the test DB was up): **197 passed, 13 failed**, same 13 as Session 3, all
  poppler missing on Windows. `test_on_device.py` all passed.
- **`addition_branch_phone.md`** (docs only): §8 rewritten as the deploy checklist (merge or
  `workflow_dispatch` the branch, which also moves `:latest`; migrations on start, `Dockerfile:113`;
  `SELF_HOSTED_LLM_URL` without `/v1` for the Kaggle cell; `ON_DEVICE_LEASE_MINUTES`; Android redirect
  with hash `C3ASDt+nHPY0SKMEXBWNCK5zUic=`; personal accounts; "a few minutes to apply"; `TEACHER_EMAILS`;
  join code; values to send back). §1, §6, §10 and the changelog updated. Status stays "ready for review".
- **Found in the code (for the teammate):** `azure.sh` sets `TEACHER_EMAILS` only when it **creates**
  the app (default `<student-email>`, `:48`); later changes need `az containerapp update
  --set-env-vars`. `security.py` promotes to teacher on sign-in and **never demotes**, so a student
  account that was ever listed needs its `users.role` fixed in the database.
- **Android deployed flavor (read, not changed):** `applicationId = "com.example.capstone"`, no
  suffix on either flavor or build type (`app/build.gradle.kts:48`). local.properties keys:
  `webend.deployedUrl` **missing** (commented out); `webend.deployed.clientId` set, equals
  `<DEPLOYED_CLIENT_ID>`; `webend.signatureHash` set, equals the debug hash. Scope `api://<clientId>/access_as_user`
  and authority `common` are **hard-coded** (`MsalConfig.kt:23,49`); they match the deployed image's
  `VITE_AZURE_API_SCOPE` / `VITE_AZURE_TENANT_ID` (`image.yml` build-args). A custom Application ID URI
  or a single-tenant authority would need a code change (none made).
- **Next:** the user commits and pushes the web end branch. The teammate works through §8 and sends
  back the URL, client ID, API scope, tenant and join code. The user sets `webend.deployedUrl`, then
  `.\gradlew installDeployedDebug`, and runs the §8.9 smoke test.

### 2026-09-26: Session 10, real fallback setup (SERVER CONFIGURED; test run NOT DONE, stopped by the user for the day)

- **No code changed** in either repo. Both repos on `feature/on-device-grading` (web end @
  `c69eea2` + Session 3's uncommitted files; Android @ `8281d9f` + uncommitted work).
  `addition_branch_phone.md` untouched (no web end change).
- **Self-hosted LLM (the shared one the deployed web end uses):** Qwen/Qwen3-VL-8B-Instruct,
  uvicorn behind ngrok, `GET /health` → `{"status":"ok","model":"Qwen/Qwen3-VL-8B-Instruct","gpus":2}`.
  Serves **`/chat/completions` at the root**; `/v1/chat/completions` → 404. OpenAI-style replies
  (`choices`, `finish_reason`, `usage`); accepts `"model":"default"` and `data:` URL images.
  - First URL given (`<SELF_HOSTED_LLM_URL>`) was offline (`ERR_NGROK_3200`). The ngrok URL changes when
    the notebook restarts: update `.env` and restart the backend each time.
  - Current URL `<SELF_HOSTED_LLM_URL>`. While the notebook was
    misconfigured it took ~29–36 s for a 10-token reply and never finished 80 tokens in 240 s. After
    the user's fix: tiny call 0.7 s, 69 tokens 6.3 s (~11 tok/s), one 800×600 image + transcription
    4.4 s (494 prompt tokens). At that speed the backend's hard-coded **120 s** per call
    (`services/llm_provider.py:711`, `SelfHostedProvider.complete`) is enough; no change made.
    A slow or queued server (shared with the deployed app) would time the boxes out into review
    ("Grading request failed: …", message possibly empty for a timeout).
- **User set `backend/.env`** and restarted the backend:
  `SELF_HOSTED_LLM_URL=<SELF_HOSTED_LLM_URL>` (no `/v1`, no
  `/chat/completions`: the provider appends it), `ON_DEVICE_LEASE_MINUTES=15`.
- **Next (step 2, not run):** phone Home screen: "Server re-marking (debug)" on, "Force server
  re-mark (debug)" on, Gemma 4 E2B, trim off, plain off. Browser: Reset marks on `430c109c-…`; phone:
  "Grade again on this phone". Expected: paper stays `grading`, both boxes `provider=self_hosted`,
  then `graded`, bell "…was marked on the phone", app fills in the marks. Then step 3: per-box
  provider/score/reason/seconds + read-only SELECT on `answer_grades`; demo settings = re-marking on,
  force off. Before running, re-check `/health` (the URL may have changed overnight).
- **Unchecked risk:** the fallback marks from the **server's** crops (`crop_images`); if the web end
  has none for this submission (cf. Prompt 7 issue 4, QR 0 of 2), boxes get "No extracted answer
  image for this box" without a model call.

### 2026-09-26: Session 9c, DECISION: Gemma 4 E2B + two-turn is the default (CODE DONE; real graded run PASSED: 5/5 and 10/15)

- **User's decision:** real grading uses **Gemma 4 E2B with two-turn grading**, in the local and
  deployed builds. Qwen2-VL 2B is removed from the grading choices (it stays in `ModelSpec` for the
  Model Test screen). Plain one-turn grading is a debug option only. Also recorded in `CLAUDE.md`
  ("Grading decision").
- **Evidence it rests on:** benchmark `20260926-120233` (Session 9b): two-turn box 1 5/5, box 2
  10/15 (a=5 b=5 c=0, conf 95), blank 0; plain box 2 15/15 (wrong). Caveat told to the user: in
  that run turn 1 wrote `c) c) UNREADABLE`, so c=0 was turn 2's call, not the NOT ANSWERED rule. The
  parser/prompt fix made afterwards is **untested on device**; with it, that reply would go to
  review. The real graded run is the first test of the fix.
- **Built (Capstone_Android):**
  - `ModelSpec.DEFAULT = GEMMA4_E2B`, `GRADING_CHOICES = [GEMMA4_E2B]`.
  - `GradingDebugSettings`: two-turn is on unless a debug build chooses plain (new key
    `plain_grading_override`; the old opt-in `two_turn_override` is no longer read, so a stale
    "off" cannot turn plain on). Release: Gemma, two-turn, no trim, no forced re-mark.
  - `ConfiguredGradingService` (new): the service `GradingWorker` → `OnDeviceGradingRunner` uses;
    reads the model and config from the settings before every box. Replaces the anonymous
    wrapper in `AppContainer`. The Model Test screen and the benchmark build their own graders.
  - Home screen: model chip "Gemma 4 E2B" only; "Plain grading, one turn (debug)" switch, off.
- **Tests/build:** `testLocalDebugUnitTest` 326/326, `testDeployedDebugUnitTest` 326/326
  (`ConfiguredGradingServiceTest`: debug and release both grade with Gemma in two turns; plain read
  on the next box), `:extractor` 65/65, `assembleDeployedDebug` OK, `installLocalDebug` done.
- **First real graded run** (submission `430c109c-…`, Gemma 4 E2B, two-turn, both boxes): box 1
  **5/5** (21.8 s, two calls; turn 1's transcript began with the printed label "answer", harmless).
  Box 2 review "Model could not read the answer" (13.2 s, **one** call): turn 1 read a) and b)
  correctly but wrote `c) UNREADABLE` for the empty part despite the NOT ANSWERED instruction, and
  the old rule sent the box to review before turn 2 (hence "fast"). **Rule changed (user's
  instruction):** a part written as NOT ANSWERED or UNREADABLE, or left out of the transcript,
  counts 0, is shown to turn 2 as NOT ANSWERED, and the feedback says "Part c not answered (0
  marks)." ("… or not readable" for UNREADABLE); review only when no part was read. Cost: an
  illegible part is scored 0 and posted, with the note. Tests: 330/330 both flavours (device
  replies as fixtures, values as placeholders), `assembleDeployedDebug` OK, installed.
- **Regrade PASSED** (same submission, Reset marks + Grade again on this phone): box 1 **5/5**
  (22.5 s), box 2 **10/15** (21.1 s; turn 1 again `c) UNREADABLE`, turn 2 `PARTS: a=5 b=5 c=0`,
  conf 100, feedback ends "Part c not answered or not readable (0 marks)."). Web end: paper
  `graded`, 15/20, both rows `provider=on_device`, no review. First end-to-end pass of a partial
  multi-part answer on the phone (closes Prompt 7 open issue 1 / Prompt 9 issue 1 for this paper).
- **Still open:** real fallback (self-hosted LLM) not run; turn 1 reads the printed "answer" label
  into box 1's transcript (harmless); feedback accuracy; Prompt 7 issues 4–8.

### 2026-09-26: Session 9b, two-turn grading for Gemma 4 E2B (CODE DONE; box 2 10/15 on device, needs one confirming run; default NOT switched)

- **User's decision:** Gemma 4 E2B only; no Qwen, no new downloads for now; phone only. Web end
  **not changed** (branch still `feature/on-device-grading` @ `c69eea2`).
- **Built (Capstone_Android):**
  1. **Two-turn grading** (`GradingConfig.twoTurn`, debug switch "Two-turn grading", off by
     default; `TwoTurnGrading.kt`, `BoxGrader.gradeTwoTurn`). DELIBERATE DIFFERENCE from the web end.
     Parts come from the question text (`QuestionParts`: `a)`/`(a)` labels in sequence, marks from
     `Marks: N` / `N marks`; fewer than two labels = one part `answer`). Turn 1: crops + question text
     only (no model answer, scheme or question figures), transcript per part, NOT ANSWERED for no
     writing. Turn 2: new text-only conversation with question, model answer, marks per part and the
     transcript; reply `PARTS:` / `CONFIDENCE:` / `FEEDBACK:`. The phone sums the parts; a NOT
     ANSWERED part counts 0 whatever turn 2 says. Review (NEEDS_FALLBACK) for: unreadable/duplicate/
     unknown/missing PARTS, a part above its marks, total above the box, low/missing confidence.
     Also review (the assistant's choices, flagged to the user): a part missing from the transcript,
     an UNREADABLE part, every part NOT ANSWERED although the phone found ink, no typed model answer.
     Posted transcript = turn 1's; `raw_response` = `[turn 1: transcript]` + turn 1 +
     `[turn 2: marks]` + turn 2. The early stop accepts PARTS in place of SCORE.
  2. **"Force server re-mark"** (debug switch): every eligible box in `fallback_box_ids` and
     `use_fallback=true` (`OnDeviceGradingRunner.resultsBody(…, forceRemark)`); the phone still
     grades and posts its results. For testing the real fallback.
  3. `BoxGraderTest.kt:506,516`: the real answer key replaced with neutral values.
  4. Benchmark now Gemma 4 E2B × PLAIN / TWO_TURN (`GradingBenchmark.MODELS` / `VARIANTS`);
     `LocalGradingService.callCount` / `callsSince` keep both calls of a two-turn case.
- **Benchmark 2** (run `20260926-120233`, 6/6, no posting):

  | Variant | Box 1 (5) | Box 2 (15, a+b only) | Blank |
  |---|---|---|---|
  | plain | **5**, conf 100, 20.6 s | **15 (wrong)**, conf 95, 22.8 s: part c) invented from the model answer | 0, no call |
  | two-turn | **5**, conf 100, 16.2 s (10.4 + 5.7) | **10**, a=5 b=5 c=0, conf 95, 22.8 s (12.7 + 9.8) | 0, no call |

  - Two-turn turn 1 read a) and b) (one letter misread: F as E) and wrote **no** part c) value.
    Tokens: turn 1 prompt 444 (box 1) / 630 (box 2), turn 2 345 / 528; consistent with ~280 per image.
  - **But the 10/15 was partly luck.** Turn 1 wrote `a) a) …`, `b) b) …`, `c) c) UNREADABLE` (the
    student's own labels copied after the prompt's), and used UNREADABLE for an empty part. The
    parser kept `c) UNREADABLE` as text, so part c was neither NOT ANSWERED nor UNREADABLE, and turn
    2 gave c=0 itself. **Fixed after the run:** a repeated leading label is dropped, and both turn-1
    prompts now say NOT ANSWERED for no writing, UNREADABLE only for illegible writing. With the
    fix, that exact reply goes to **review** (part c UNREADABLE). The new prompt is **untested on
    device**: one more benchmark run decides.
- **Proposal (not applied; the user decides after the confirming run):** make Gemma 4 E2B two-turn
  the default and remove Qwen from `ModelSpec.GRADING_CHOICES` (keep the file on the phone).
- **Tests/build:** `testLocalDebugUnitTest` 322/322, `testDeployedDebugUnitTest` 322/322 (27 new),
  `:extractor` 65/65, `assembleDeployedDebug` OK, `installLocalDebug` done (with the fix).

### Open issues after Session 9b

1. **Two-turn box 2 needs one confirming device run** with the fixed parser and prompt (expected
   10/15; if turn 1 still writes UNREADABLE for part c, the box goes to review).
2. ~~Default model and mode not switched~~ **Switched in Session 9c** (Gemma 4 E2B + two-turn).
3. **Real fallback still not run.** "Force server re-mark" is ready for it. Session 10: server
   checked and `.env` set (URL + lease 15); only the device run and the report remain.
4. **Two-turn is marked by text only.** A question figure or a picture-only model answer is not
   seen in turn 2 (picture-only model answer: review). Transcription errors in turn 1 carry into the
   mark (F read as E on box 2; harmless there).
5. Still open: Gemma's confidence is not a safety net (95 on a wrong full mark in one turn); app
   logcat throttling on this phone; Prompt 7 issues 4–8 (QR, server `looks_blank`, feedback accuracy,
   deliberate differences, untested bell/override/re-evaluation/expiry).

### 2026-09-26: Session 9, Prompt 9 fallback + second model (Gemma 4 E2B), crop trim, benchmark (CODE DONE; default NOT switched, waiting for the user)

- **Repo state checked:** Capstone_Android `feature/on-device-grading` @ `8281d9f` + uncommitted work.
  Script-Checker-Web-End `feature/on-device-grading` @ `c69eea2` + Session 3's files; **not changed**
  this session (the user's rule for the second half: phone only).
- **Prompt 9, part 1 (fallback).** Setup given to the user: `SELF_HOSTED_LLM_URL` is the endpoint
  **without** `/chat/completions` (the provider appends it, `services/llm_provider.py:618-619`); the
  teammate's Kaggle notebook (removed in `39690b2`) serves `/chat/completions` at the root, so no
  `/v1`; vLLM / llama.cpp / LM Studio / Ollama need `/v1`, and the request's `"model": "default"` is
  hard-coded (vLLM needs `--served-model-name default`; Ollama likely rejects it, UNVERIFIED).
  `ON_DEVICE_LEASE_MINUTES` must be at least ~10 (15 = default) because the lease covers phone grading
  plus fallback; Session 7 used 2.
  - **"Fallback unavailable" path: PASSED** (URL empty, re-marking toggle on, submission
    `5a46d218-…`, Reset marks + Grade again). Box 1 5/5 (43.0 s). Box 2 NEEDS_FALLBACK "Could not read
    a valid mark" (38.3 s incl. repair), posted in `fallback_box_ids`; server row `on_device`, needs
    review, reason **"Fallback unavailable"**; paper `graded`, `fallback: "unavailable"` with its
    message on the phone.
  - **Real fallback (self-hosted LLM): NOT RUN.** The user moved on to the model question first.
- **Box 2 evidence (read-only).** The image the model gets is the phone's crop unchanged (934 × 781,
  under `ImagePrep.MAX_LONG_EDGE`), viewed: parts a) and b) clear in the top ~30 %. Qwen read part a
  correctly (`a) The student wrote: "a=<F1>/<M1>=<A1>ms^-2"`, repair `a) <A1>`). Cause: format, not
  pixels: the question text itself is `a) … (Marks: 5)b) … c) …`, and Qwen2-VL 2B answers per part.
  **App log lines were missing from logcat** for that run (a shell `log -t` shows fine: the phone
  likely throttles the app's logging, UNVERIFIED), so token counts are now saved by the app itself.
- **Model choice:** `litert-community/gemma-4-E2B-it-litert-lm`, `gemma-4-E2B-it.litertlm`,
  2,588,147,712 B, Apache-2.0, **not gated** (the Gemma 3n repos are gated: 401 without sign-in).
  Gemma 4 is supported since LiteRT-LM v0.10.1; the app's 0.16.1 loaded it (8.7 s) with **no
  upgrade**. Phone CPH2719 (MT6897), 7.3 GB RAM, ~2.5 GB available, 119 GB free. E4B (3.66 GB) not
  tried.
- **Built (Capstone_Android):**
  1. `ModelSpec.GEMMA4_E2B` (vision, 4096 tokens, 280 per image from `processor_config.json`,
     exact size checked; CPU text + GPU vision like Qwen). `ModelSpec.GRADING_CHOICES = [Qwen, Gemma]`;
     **Qwen stays DEFAULT**.
  2. `GradingDebugSettings` (debug builds; release ignores): grading model and crop trim, in the
     same `grading_settings` DataStore as `FallbackSetting` (now one shared instance). Home screen:
     model chips + "Trim crops to the writing" switch under the re-marking toggle. `AppContainer`'s
     `gradingService` applies both per box (`useSpec` is a no-op when unchanged). The Model Test
     screen's probes keep using that screen's own picker.
  3. **Crop trim rebuilt** (`CropTrim`, off by default): handwriting bbox + margin
     (`BlankDetector.writtenArea`), widened to at most 2 : 1, the printed label painted out with the
     median tone of a 16 px band around it (`ImagePrep.trimmedPng`). Only the model's input changes.
     Checked in `CropTrimTest` on the two real crops (copied to the ignored `app/build/trim-check/`,
     never to test resources) and viewed: box 2 934 × 781 → 789 × 395, parts a) and b) intact, no
     label; box 1 not cut (writing fills it), label painted. A faint flat patch stays where the label
     was.
  4. `LocalGradingService.lastCall` records the PNGs given to the engine and per-turn `TurnStats`
     (prompt/response tokens, TTFT, rates) from `getBenchmarkInfo()`.
  5. `GradingBenchmark` + "Run benchmark" on the Model Test screen (debug; never calls the web end):
     box 1, box 2 and the blank fixture (adb-pushed to `/data/local/tmp/llm/bench/`) × Qwen / Gemma ×
     PLAIN / PARTS_WORDING (change c) / TRIM; writes `no_backup/bench/<time>/results.json` + images.
- **Benchmark result** (run `20260926-112324`, 18/18 cases; seconds per box incl. repair):

  | Model | Variant | Box 1 (5) | Box 2 (15, a+b only) | Blank |
  |---|---|---|---|---|
  | Qwen2-VL 2B | plain | **5**, conf 100, 46.4 s | review: no SCORE (`a) The student wrote …`), 48.3 s | 0, no call |
  | Qwen2-VL 2B | parts wording | review: stops after TRANSCRIPT, 50.0 s | review: `TRANSCRIPT:` only, 46.7 s | 0 |
  | Qwen2-VL 2B | trim | **5**, conf 100, 49.0 s | review: per-part free text, 53.4 s | 0 |
  | Gemma 4 E2B | plain | **5**, conf 100, 29.4 s | **15** (wrong), conf 95, 26.6 s | 0 |
  | Gemma 4 E2B | parts wording | **5**, conf 100, 19.7 s | **15** (wrong), conf 100, 25.4 s | 0 |
  | Gemma 4 E2B | trim | **5**, conf 100, 18.2 s | **15** (wrong), conf 95, 27.3 s | 0 |

  - Gemma keeps the format every time and transcribes a) and b) correctly, but adds a part c)
    `a_n = <C1>` that is **not in the image** (the exact PNGs it got were checked: pixel-identical to
    the crop / the trim, a) and b) only). The value is the model answer's. It recites the model answer
    as the student's work and gives full marks at 95–100 confidence: a confident wrong mark that would
    be posted as GRADED, worse than Qwen's review.
  - Change (c) alone breaks Qwen's box 1 again (Session 7's regression, now isolated to (c)); the
    trim alone does not (5/5).
  - Tokens (engine counts): Qwen prompt 1,026 (box 1) / 1,167 (box 2) whether trimmed or not, so its
    encoder uses a fixed image size; TTFT 24–30 s. Gemma prompt 731 / 881 (864 trimmed), TTFT 7–9 s;
    consistent with ~280 image tokens (exact split UNVERIFIED). The repair turn's TTFT repeats the
    first turn's (stale `BenchmarkInfo` field).
- **Recommendation (not applied; the user decides):** keep **Qwen the default** for now and keep
  **trim off** and **(c) off**. Gemma is the better base (format kept, ~2× faster, box 1 right) but
  must not grade until it stops crediting model-answer parts; next experiment: transcribe first in a
  turn **without** the model answer, then mark against it in a second turn.
- **Tests/build:** `testLocalDebugUnitTest` 295/295, `testDeployedDebugUnitTest` 295/295 (15 new:
  `CropTrimTest`, `GradingBenchmarkTest`, `GradingDebugSettingsTest`), `:extractor` 65/65,
  `assembleLocalDebug` + `assembleDeployedDebug` OK, installed (local debug).

### Prompt 9 Status (Session 9)

| Item | Result |
|---|---|
| "Fallback unavailable" path | PASSED |
| Real fallback (self-hosted LLM) | NOT RUN |
| Gemma 4 E2B loads on LiteRT-LM 0.16.1 | PASSED (8.7 s) |
| Model + trim debug settings | BUILT, unit-tested; a real grading run with Gemma selected NOT RUN |
| Crop trim (rebuilt) | PASSED on the real crops (viewed); no accuracy change on device |
| Benchmark (18 cases, no posting) | RAN |
| Box 1 full answer | PASSED on both models (except Qwen + change c) |
| Box 2 partial answer | **FAILED** on both: Qwen no mark; Gemma 15/15, invented part c |
| Blank box | PASSED (0, no model call) |

### Open issues after Prompt 9

1. **Box 2 (partial multi-part answer) is still not graded correctly on the phone.** Qwen: no
   readable mark in every variant. Gemma 4 E2B: reads a) and b), then credits part c) from the model
   answer (15/15, conf 95–100). Next to try: a transcription turn without the model answer, then
   marking; or one call per part. Supersedes Prompt 7 issue 1.
2. **Change (c) (format reminder) breaks Qwen's box 1**, now isolated (Prompt 7 issue 2). The rebuilt
   trim alone does not break it. Both stay off.
3. **Real fallback not run.** Needs `SELF_HOSTED_LLM_URL` (setup above) and the lease back at 15.
4. **Confidence is not a safety net for Gemma:** it said 95–100 on a wrong full mark.
5. **Speed:** Qwen 46–53 s a box (TTFT 24–30 s), Gemma 18–29 s (TTFT 7–9 s) on this phone.
6. **App logcat lines dropped on this phone** (throttling, UNVERIFIED); the benchmark now saves its
   own counts. Real runs still rely on logcat.
7. Still open from Prompt 7: web end reads 0 of 2 QR codes (issue 4); the server's `looks_blank`
   likely counts the printed label (issue 5); feedback accuracy (issue 6); the deliberate
   phone/web-end differences (issue 7), now also the trim option and the model choice; bell,
   override/release, re-evaluation, expiry/resume not tested (issue 8).

### 2026-09-25: Session 8, Phase 8 handover (CODE DONE; deployed test NOT RUN, blocked on the teammate)

- **Repo state checked:** Capstone_Android `feature/on-device-grading` @ `8281d9f` + uncommitted work
  (remote `whoIsJihad/Capstone_Android`). Script-Checker-Web-End `feature/on-device-grading` @
  `c69eea2` (= `main`) + Session 3's uncommitted files; branch confirmed before editing.
- **Audit, Android:** no ASC_Capstone reference, no hard-coded server URL, client id or secret in
  `app/src/main` or `:extractor` (only `localhost:8000` for the `local` flavor and the
  `webend-url-not-set.invalid` placeholder); client ids, deployed URL and signature hash come from
  `local.properties`, which `Capstone_Android/.gitignore` ignores. OkHttp logs HEADERS with
  `Authorization` redacted, never BODY. Test fixtures hold placeholders, not answer keys.
  **Fixed:** (1) `logVerbatim` (`LocalModelProvider.kt`) printed every raw model reply in release
  builds too; a reply can recite the answer key, so release now logs the length only.
  (2) The "Model" button and the `model_test` route were in release builds; both are now
  `BuildConfig.DEBUG` only (the fallback toggle and the Phase 1 experiments already were).
- **Audit, web end:** `git diff main` = Session 3's files only, all logged, no secrets or URLs in the
  new files, `.env` ignored. **Not logged and not ours:** `backend/.env.example` and
  `frontend/.env.example` are deleted in the working tree; recorded in `addition_branch_phone.md`
  §5.1; the user restores them before committing.
- **Web end docs:** `addition_branch_phone.md` status **ready for review**; changelog rows for
  Session 8 and for the sessions with no web end change; "Tested" column per route; §8 rewritten as
  the full merge/deploy checklist; §10 test coverage.
- **Root `.gitignore`:** added `local.properties`, `.env`, `.env.*` (`!.env.example`),
  `/ASC_Capstone/`, `/v-2.1.1/`, so the capstone folder can become its own repo safely.
- **Deployed config:** the `deployed` flavor already had `FALLBACK_ENABLED = true`. Added
  `webend.deployed.clientId` (`<DEPLOYED_CLIENT_ID>`, from `deploy/azure.sh:46`) to `local.properties`;
  `webend.deployedUrl` is still blank (the FQDN is only known from `./deploy/azure.sh status`), so the
  deployed build refuses sign-in and names the missing key until it is filled.
- **Blocked on the teammate:** merge + redeploy, the Android redirect on the deployed registration
  (debug hash), `SELF_HOSTED_LLM_URL`, and the FQDN.
- **Tests/build:** `testLocalDebugUnitTest` 280/280, `testDeployedDebugUnitTest` 280/280,
  `:extractor` 65/65 (up to date, unchanged), `assembleDeployedDebug` and `assembleDeployedRelease`
  (unsigned; no release keystore exists) OK. Web end `pytest` not re-run (docs only; needs the test
  Postgres). Nothing installed; nothing run on the deployed server.

### Prompt 8 Status (Session 8)

| Item | Result |
|---|---|
| Sign-in | READY locally (PASSED S7); deployed BLOCKED (Android redirect, FQDN) |
| Assignments, pack | READY (PASSED S7 locally) |
| Upload + hand-in, phone cropping | READY (PASSED S7 locally) |
| On-device grading | READY for single-part boxes; multi-part boxes FAILED (S7 open issue 1); c/d-off build untested on device |
| Posting | READY (PASSED S7) |
| Teacher notifications | NOT TESTED on device (pytest only) |
| Fallback | BLOCKED (needs deploy + `SELF_HOSTED_LLM_URL`); pytest only |
| Run expiry and resume | NOT TESTED on device (pytest only) |
| Re-evaluation | NOT TESTED on device (pytest only) |
| Deployed config | BUILT; BLOCKED on `webend.deployedUrl` + teammate steps |

### 2026-09-25: Session 7, Phase 7 end-to-end test (ENDED by the user; multi-part grading FAILED, see "Open issues after Prompt 7")

- **Repo state checked:** Capstone_Android `feature/on-device-grading` @ `8281d9f` + uncommitted work
  (the user deleted Session 4/5's 16 old files at the start; the real tree then built and tested
  green). Script-Checker-Web-End `feature/on-device-grading` @ `c69eea2` + Session 3's uncommitted
  files (`backend/.env.example` and `frontend/.env.example` show as deleted; not this session's doing).
- **Phase 6 had no §H block.** Its code (start/grade/post runner, `GradingWorker`, `GradesPoller`,
  results screen) was already on disk and is what this test ran.
- **Setup (user's side):** web end on :8000 (alembic head `a9c4d2e81f37`), frontend :5173,
  `ON_DEVICE_LEASE_MINUTES=2`, no `SELF_HOSTED_LLM_URL`; phone `UWGAS4RC4TZTHM79`, `adb reverse`, Qwen
  1,784,096,288 bytes.
- **Run 1** (paper `testpaper3 (copy) (copy)`, `7aeeaefc-…`, 1 page, 2 boxes: 5 and 15 marks, model
  answers typed + image; submission `a4a74b5a-…`). The user changed the plan to 2 boxes (no partial
  answer). Box 1 answered correctly, box 2 blank. Result: 0/20, both needs review, ~6 min.

  | Box | Time | Outcome | Cause |
  |---|---|---|---|
  | 1 (5) | 273.8 s | needs review, "no CONFIDENCE line" | FEEDBACK looped on one 67-char clause (~15,000 chars) until the 4096 context was full; transcript only the first line; feedback wrong (said formula, answer factorised) |
  | 2 (15) | 91.7 s | needs review, "Could not read a valid mark" | not caught as blank: the printed `☐ answer` label = 219 px = 0.035 % > 0.02 %; the model then recited the model answer |

  Upload: web end read 0 of 2 QR codes. The app uploads the camera JPEG unchanged (no resize or
  re-encode; page ≈ 2,750 px wide in the photo); `pyzbar` loads in the backend `.venv`. Cause open;
  **the user deferred QR**.
- **Bugs fixed (Capstone_Android):**
  1. *Blank box with its printed label counted as written on.* `PrintedLabel` computes the label's
     region per crop from `doc_renderer.py` (2 px border + 8 px padding, 11 px bold,
     `☐ {label or "answer"}` / `… — part n of N`, zoom dpi/96); `BlankDetector.looksBlank(image,
     ignore)` leaves it out, everything else the server's rule (no region = identical, tested).
     `PaperPreparer.labelRegions` fills `AnswerToGrade.cropLabelRegions`. The real blank crop (halved)
     is now the fixture `grading/phone_blank_box_with_label.png`.
  2. *Runaway generation.* `LocalGradingService` streams (`sendMessageAsync`), caps the reply at
     `maxOutputToken = 300` (= `TokenBudget` reply reserve), passes `RepetitionPenaltyConfig(1.1,
     window 64)`, and `RunawayGuard` cancels (`cancelProcess`) when a 24–400-char piece repeats 3×; the
     reply is cut to one copy and marked. LiteRT-LM 0.16.1 supports all three (`nativeSendMessage`
     takes them); whether Qwen2-VL honours the cap and the penalty is **UNVERIFIED** on device, as is
     whether streamed messages are deltas (both handled).
  3. *Stale state after the teacher deletes a submission.* On the scan screen, phone crops of pages
     the server does not hold are dropped (`PageChecks.stalePhonePages`; snapshot taken before the
     server read), and saved runs for the paper other than the current submission are forgotten
     (`GradingRunStore.forgetOtherRuns`).
- **DELIBERATE DIFFERENCE from the web end (user's decision this session):** the phone's prompt asks
  for **TRANSCRIPT, SCORE, CONFIDENCE, FEEDBACK** (the server: TRANSCRIPT, SCORE, FEEDBACK, no
  CONFIDENCE). Generation stops at the end of the first FEEDBACK line once SCORE and CONFIDENCE came
  before it (`RunawayGuard.feedbackLineEnd`); a loop inside FEEDBACK then keeps the mark
  (`markCameBeforeFeedback`), a loop anywhere else goes to review. Supersedes Session 2's "CONFIDENCE
  after FEEDBACK" and B.1 bullet 2 as amended there. Cost: the phone and the server (teacher Grade,
  fallback) mark with slightly different prompts. `ServerParityTest` now checks "server prompt + one
  CONFIDENCE line after SCORE".
- **Web end (docs only, branch confirmed):** `addition_branch_phone.md` §9 records that the printed
  label likely counts as ink in the server's `looks_blank` on photos (server crop not measured,
  UNVERIFIED). **The user chose not to change `looks_blank`.**
- **Run 2** (same paper, submission `06447115-…`; box 1 correct, box 2 parts a and b only). Upload
  4/4 markers, 2 boxes cut; the phone cut 2 of 2; the stale-crop reset worked. **Both calls failed
  at the first decode step** after a full prefill (~60 s each; 1,620 and 1,761 prompt tokens, 0 reply
  tokens): `LiteRtLmJniException: Status Code: 3. Message: Logits dimensions must be [batch_size, 1,
  vocab_size]`. The only new per-call settings were `RepetitionPenaltyConfig(1.1, window 64)` and
  `maxOutputToken = 300` (no n-gram config was ever set); the log cannot tell them apart. **Fix:**
  the repetition penalty is removed (it is the one that works on the logits); the cap stays
  (`DecodeSettings.DEFAULT`); on that exact error the call is retried once in a fresh conversation
  with nothing extra (`DecodeSettings.PLAIN`, `retryAfter`). `RunawayGuard` unchanged. The cap is
  therefore still UNVERIFIED: if the retry fires on every call, the cap is the cause too.
- **Bug fixed: "Grade again" re-posted failed results.** `OnDeviceGradingRunner.reopen` kept every
  saved result, so after a teacher's **Reset marks** the phone would have re-sent the two "Grading
  request failed" results without calling the model. Now `GradingRunRecord.forGradingAgain`: after a
  reset (`ungraded`) every result goes; after a failure (e.g. expiry) only results whose model call
  failed (`StoredBoxResult.callFailed`: reason starts `Grading request failed` / `Grading on the phone
  failed`, no reply) go.
- **Run 3** (the user discarded `06447115-…` and re-uploaded; the stale-crop reset logged "dropping
  phone crops of pages [0]" and "forgot saved grading runs"). **Crash loop:** every call reached the
  end of its reply (the 300-token cap alone did *not* cause the logits error, so the repetition
  penalty was the cause) and then the process died:
  `NoSuchMethodError: No static method close$default(SendChannel, Throwable, int, Object)` in
  LiteRT-LM 0.16.1's `Conversation$sendMessageAsync$1$1.onDone` (`Conversation.kt:452`), i.e. its
  **Flow** variant is binary-incompatible with the app's kotlinx-coroutines. WorkManager restarted the
  run each time and box 1 started over (4 crashes, 21:06–21:15). **Fixes:** (1) `LocalGradingService`
  uses the **callback** `sendMessageAsync(contents, MessageCallback, …)` bridged with a
  `CompletableDeferred` + `select`; after the phone stops the model it waits at most 10 s for
  onDone/onError. (2) Crash-loop guard: `GradingRunRecord.gradingBoxId/gradingBoxStarts` are saved
  before each call and cleared with its result; a box whose grading has started
  `MAX_BOX_STARTS = 2` times without a result goes to review with `OnDeviceGradingRunner.CRASHED`
  (counts as `callFailed`, so "Grade again" retries it).
- **Bug fixed: a run stuck in retry backoff could not be restarted.** After the crashes the grading
  job sat in WorkManager's exponential backoff (jobscheduler: "Unsatisfied constraints:
  TIMING_DELAY"); opening the screen called `enqueueUniqueWork(KEEP)`, which left it waiting, and
  `cmd jobscheduler run -f` was refused ("being executed before schedule"). `GradingWorker.enqueue`
  now keeps only a RUNNING run and REPLACEs one that is waiting or finished (`policyFor`), called off
  the main thread.
- **Run 4** (new photo, submission `5a46d218-…`): box 1 **5/5** (65.5 s). Box 2 needs review,
  "Could not read a valid mark" (73.8 s): free text per part, "in the first/second/third image", part
  (c) (not answered) taken from the model-answer **picture**, cut at 300 tokens, no SCORE. The pack's
  model-answer images are just the typed model answer drawn as a picture (both checked). **Fix:** a
  model answer with text is sent as text only (`BoxGrader.withoutRenderedModelAnswer`; DELIBERATE
  DIFFERENCE from the web end; cost: a figure inside a model answer that also has text is not shown).
- **Run 5** (Reset marks + Grade again): box 1 **5/5** (43.2 s, one image instead of two). Box 2 needs
  review (35.7 s): `a) The student wrote: "a=<F1>/<M1>=<A1>ms^-2"` then the model ended its turn
  (20 tokens; no guard fired). Root cause: on a multi-part question the 2B model ignores the reply
  format (only in the system turn) and copies the question's a) b) c) layout. **Changes:** (a) early
  stop only once SCORE and CONFIDENCE have values; (b) SCORE accepts `N`, `N marks`, `N/D`, `N out of
  D` with D = the box's marks, else a parse error (`scoreIsAmbiguous`); (c) a format reminder with
  "add the parts up, write one total" at the end of the user message (`PHONE_FORMAT_REMINDER`);
  (d) the model gets each crop cut to its writing (`BlankDetector.writtenArea`, `ImagePrep.cropPng`);
  (e) one repair turn in the same conversation, `REPAIR_PROMPT`, when a reply has no readable SCORE and
  was not a runaway.
- **Run 6** (Reset marks + Grade again): **regression.** Box 1 needs review (47.3 s): reply
  `TRANSCRIPT: x^2-2x+1=0\nx=1`, stopped after 18 tokens; the repair reply was the same two lines. Its
  image was byte-identical to the 5/5 runs (not trimmed), and only the text changed (user message 147 →
  506 chars), so **(c) caused it**. Box 2 needs review, "CONFIDENCE 0 is below 60" (40.6 s): `TRANSCRIPT:
  [no handwriting] / SCORE: 0 / CONFIDENCE: 0 / FEEDBACK: … no student's handwriting`; its trimmed image
  (789 × 276, rebuilt and viewed) clearly holds parts a) and b), so (c) and (d) cannot be told apart.
  **Both (c) and (d) switched OFF** (`GradingConfig.formatReminder = false`, `trimCrops = false`; code
  kept; the token budget counts the server message again). (a), (b), (e) stay on. **Untested on device.**
- **Tests/build:** `:app` 280/280 (62 new this session), `:extractor` 65/65, `assembleLocalDebug` OK,
  installed. Not regraded (the user ended Prompt 7).

### Prompt 7 Status (Session 7)

| Item | Result |
|---|---|
| Sign-in (MSAL, local registration) | PASSED |
| Join course (code) | PASSED |
| Assignment pack download | PASSED |
| Upload + hand-in | PASSED |
| Phone cropping (2 of 2) | PASSED |
| Full-marks answer (box 1) | PASSED (5/5 in runs 4 and 5; run 6 regressed under (c), now off) |
| Loop fix (`RunawayGuard`, 300-token cap) | PASSED |
| Blank detection with the printed label | PASSED (unit-tested on the real photo; the phone did not call the model for it) |
| Posting results | PASSED |
| Reset marks + "Grade again on this phone" | PASSED |
| Partial / multi-part answer (box 2) | **FAILED** |
| Teacher bell notification | NOT TESTED |
| Override + release | NOT TESTED |
| Re-evaluation | NOT TESTED |
| Run expiry + resume | NOT TESTED |
| "Fallback unavailable" path | NOT TESTED |
| Real fallback (self-hosted LLM) | NOT TESTED |

### Open issues after Prompt 7

1. **Partial / multi-part answers are not graded on the phone (box 2).** Real replies, box 2's
   answer-key values as placeholders (all six are in
   `app/src/test/resources/grading/device_replies_session7.json`):
   - run 1 (blank, before the label fix): the model recited the model answer, no SCORE;
   - run 4: `a) The student wrote: "a = <A1>ms^-2" in the first image. … b) … second image … c) The
     student wrote: "a_n = <C1>ms^-2" in the third image …` cut at 300 tokens, no SCORE;
   - run 5: `a) The student wrote: "a=<F1>/<M1>=<A1>ms^-2"` (20 tokens, ended by the model);
   - run 6 (with c and d on): `TRANSCRIPT: [no handwriting] / SCORE: 0 / CONFIDENCE: 0 / FEEDBACK: …`.
   Diagnosis so far: Qwen2-VL 2B does not keep the reply format on a multi-part question; it follows
   the question's a) b) c) layout and writes free text or stops early. Not the crop (readable at 934 ×
   781, and the model read part a correctly in runs 4 and 5), not the cap or the guards. The repair
   turn (e) is untested on a box-2 reply. Options not yet tried: splitting a multi-part box into
   one call per part; a larger model.
2. **The c/d regression.** With the format reminder (c) and the crop trim (d) on, box 1 went from 5/5
   to "Could not read a valid mark" (same image, only the text changed), and box 2's model reported
   no handwriting in a trimmed image that clearly has it. Both are now OFF behind `GradingConfig`
   switches; the build with them off is **untested on device**.
3. **Grading speed**, seconds per box from the run store (Qwen2-VL 2B, this phone): run 1 273.8 / 91.7
   (loop to the full context; blank box sent to the model); run 2 ~60 each then failed (logits error);
   run 3 ~60–75 per attempt, crash loop; run 4 65.5 / 73.8 (two images per box); run 5 43.2 / 35.7
   (one image); run 6 47.3 (incl. 3.4 s repair) / 40.6. Engine load ~5 s once per process. Most of a
   box is prompt reading (~20–30 s at 50–57 tok/s for ~1,000–1,700 tokens); a further ~15–25 s per
   call is not in the prefill/decode figures (likely image encoding, UNVERIFIED).
4. **Web end reads 0 of 2 QR codes** on every phone upload. Not investigated, by the user's choice.
   Known: the app uploads the camera JPEG unchanged (page ≈ 2,750 px wide), and `pyzbar` loads in the
   backend `.venv`. The codes are only a check; they did not affect marks.
5. **The web end's `looks_blank` probably counts the printed label as ink** (219 px = 0.035 % > 0.02 %
   on the phone's crop of a blank box). Not changed, by the user's choice; recorded in
   `addition_branch_phone.md` §9. Server fallback and the teacher's Grade may send blank boxes to the
   model.
6. **Feedback accuracy.** Run 1 box 1 transcribed only the first line and said the student "applied
   the quadratic formula" when the working factorised; run 4 box 1's feedback said the student
   "transcribed the question and model answer". Marks were right, feedback is unreliable.
7. **Deliberate differences from the web end now in the phone** (keep in mind when comparing marks):
   reply order TRANSCRIPT, SCORE, CONFIDENCE, FEEDBACK with an early stop after the first FEEDBACK
   line; no picture of a model answer that has text; printed label left out of the blank check;
   stricter SCORE parsing; one repair turn.
8. Also still open from the prompt: steps 4–7 (bell, override/release, re-evaluation, expiry and
   resume, fallback) were never reached.

### 2026-09-25: Session 5, Phase 5 scan, upload, hand-in, phone crops (CODE DONE; real tree red until the user deletes 16 files)

- **Repo state checked:** Capstone_Android `feature/on-device-grading` @ `8281d9f`, all earlier work
  still uncommitted. Script-Checker-Web-End `feature/on-device-grading` @ `c69eea2` + Session 3's
  uncommitted files; **read only, not changed** this session (so no `addition_branch_phone.md` entry).
  Its `backend/.env` exists and was not read.
- **Decision 9 kept:** the phone grades crops it cuts itself; server crops are never downloaded. The
  upload reply (`ExtractionResult`) is only shown and judged.
- **Done, `:extractor`:**
  - Segments: `Segment(pageIndex, bbox)`; `AnswerBoxRef(id, orderIndex, segments)` (+ `single(...)`);
    `AnswerCrop.part`; `Layout.arucoDictionary`, `partsOnPage`, `pagesWithAnswers`. One crop per
    segment on the page, in (order_index, part) order = the web end's `get_page_segments`.
  - Served `order_index` replaces array order. `LayoutValidator`: reading-order check removed;
    refuses duplicate `order_index`, a box with no segments, overlap of any two segments on one page
    (named "ab_x part 2"), and any dictionary but `DICT_4X4_50`.
  - `PageExtractor` default inset is `Inset.NONE` (the server's region, §C.6); the stale
    `Inset.ANSWER_BOX` is deleted.
  - Every v-2.1.1 citation now cites `Script-Checker-Web-End/backend/services/extractor.py`,
    `doc_renderer.py`, `config.py` or `routers/on_device.py`.
  - `WebEndContractTest` (12): reads the web end's source (`-Pwebend.backend=` overrides
    `../Script-Checker-Web-End/backend`; skipped if absent) and checks dictionary, detector params,
    marker size/margin, ids and centre formula, page size formula, segment shape, `get_page_segments`,
    centre = corner mean, homography RANSAC 5.0 / affine, bbox corner order, no inset,
    `warped_bbox` truncation, `USE_LOCAL_QR_REGISTRATION = False`. Proven to fail on a scratch copy
    with margin 40→50 and RANSAC 5.0→3.0.
  - `WebEndGoldenTest` (5) over `extractor/src/test/resources/golden/*/golden.json`, written by the
    new `golden/make_golden.py`, which calls the web end's own `extract_page` on the same bytes. Checks:
    same (box, part) pieces per page; phone bounds vs server `warped_bbox` IoU ≥ 0.98 and ≤ 2 px per
    edge; tilted photo's rectified crop vs server's flat crop, mean grey difference over ink ≤ 45
    (measured ≈ 27–30 aligned, ≈ 68–71 when shifted 4 px). `golden/sample` (sample page, synthetic
    2-page layout with a spanning box, flat + tilted) generated this session: bounds match exactly.
    The web end also reproduces the old recorded sample numbers exactly.
- **Done, `:app`:**
  - `ApiService`: `POST submissions` (multipart `question_id`, `modality`=photo, `page_index`,
    `page_index_hint` (never sent), `submission_id`, `image`), `GET submissions/{id}`,
    `DELETE submissions/{id}/pages/{page_index}`, `POST submissions/{id}/submit`. DTOs
    `ExtractionResultDto`, `PageExtractionResultDto`, `CropInfoDto`, `HandInResponseDto`
    (`SubmissionModels.kt`). New `SubmissionRepository`.
  - **Explicit `page_index`, not the hint** (§C.2): the phone crops the page the student picked, so the
    server must file it under the same page. A wrong page is caught when the box QR checks are all
    "fail" and none "pass" (`PageChecks.serverVerdict`).
  - `PageChecks`: server page refused on `error`, markers ≠ 4/4, pieces ≠ the pack's for that page,
    or all-fail QR; hand-in needs every page that has answer boxes ready (pages without boxes need no
    photo).
  - Scan screen rewritten: one card per page; system camera (`TakePicture` + FileProvider,
    `cacheDir/captures`), gallery as second path, Remove page. Per photo: crop on the phone first
    (markers missing → retake, nothing uploaded), upload, judge the reply, then save the phone's crops.
    Shows the web end's result per page ("4/4 corner markers, 3 answer boxes cut, 2 of 3 codes read")
    and asks for a retake when it couldn't read the page. Hand in only when all pages are ready, after
    "Hand in your answers? After this you can't change your answers." Camera result survives process
    death (page parked in `SavedStateHandle`). Resume reads the server's copy via
    `student/assignments?course_id=` (pack `course_id`) and `GET submissions/{id}`.
  - `PagePhotoStore` (`noBackupFilesDir/scans/<qid>/page_<n>/`) replaces the in-memory
    `WorksheetSession`. Grading screen reads it; `QuestionResolver` resolves a box only with every part,
    in part order; `WorksheetGrader` sends all parts in one call.
  - `toExtractorLayout` follows `get_page_segments` (segments, else page_index + bbox); the
    single-page refusal (`singlePageProblem`) is gone.
  - Manifest: `CAMERA` permission and camera `uses-feature` removed (merged manifest checked); CameraX
    dependencies and catalog entries removed; dead `CameraPreview` deleted.
  - Tests: new `SubmissionApiTest` (8), `PageChecksTest` (8), `PagePhotoStoreTest` (6); updated
    `QuestionResolverTest` (+5 part cases), `AssignmentDtoParsingTest` (+3 segment cases),
    `MarkerCornersTest`.
- **Tests/build:** real tree `:extractor` 65/65. `:app` in the real tree does not compile, only because
  of the old files (TokenManager, Login/Register view models). In a scratch copy with the 16 files
  below removed, `.\gradlew test assembleDebug` passed: `:app` 182 per flavor (1 skipped:
  `webend.signatureHash` not in local.properties, as before), `:extractor` 65/65 (contract test not
  skipped), both debug APKs built.
- **Not done: file deletion.** The user deletes Session 4's 15 files plus
  `app/src/main/java/com/example/capstone/domain/worksheet/WorksheetSession.kt` (now unused).
- **Not done (open):** golden fixture from a real finalized paper (the user runs `make_golden.py
  paper`, steps in the session summary; poppler is missing on this machine, so pdf2image needs it on
  PATH, or PyMuPDF, or `--pages-dir`). Old page crops in `PagePhotoStore` are never cleaned up.
- **UNVERIFIED (device):** TakePicture on the user's phone, upload size/time over `adb reverse`,
  the all-fail QR heuristic on real photos, server 422 texts on real photos, process-death resume.
- **Next:** the user deletes the 16 files, builds the golden fixture, runs the on-phone steps. Then
  Phase 6 (start, grade, post results, MyGrades).

### 2026-09-25: Session 4, Phase 4 sign-in and networking (CODE DONE; real tree red until the user deletes 15 files)

- **Repo state checked:** Capstone_Android on `feature/on-device-grading` @ `8281d9f` (all earlier
  work still uncommitted). Script-Checker-Web-End on `feature/on-device-grading`, read only, **not
  changed** (the Entra registration is the user's own, so no teammate request was needed).
- **Entra:** the user's local web end uses **their own** app registration (client id starts
  `3790c7aa-39cc-4791-`, personal Microsoft account), not the teammate's `<DEPLOYED_CLIENT_ID>` (G.1 still
  applies to the deployed server). Debug keystore hash on this machine: `C3ASDt+nHPY0SKMEXBWNCK5zUic=`;
  redirect `msauth://com.example.capstone/C3ASDt%2BnHPY0SKMEXBWNCK5zUic%3D`.
- **Done (Capstone_Android only):**
  - Build: flavors `local` (BASE_URL `http://localhost:8000/api/`, default) and `deployed`
    (BASE_URL from `local.properties` `webend.deployedUrl`, normalised to `.../api/`). Also read from
    `local.properties`: `webend.clientId`, `webend.signatureHash` (BuildConfig + manifest
    placeholder). A missing key builds, but the sign-in screen names it. Gradle tasks are now
    `testLocalDebugUnitTest`, `installLocalDebug` (CLAUDE.md updated). MSAL 8.5.0 (+ the Duo SDK
    feed, restricted to `com.microsoft.device.display`, in `settings.gradle.kts`); MockWebServer
    4.12.0 and org.json for tests. DataStore dependency dropped (TokenManager was its only user).
  - `data/auth/`: `MsalConfig` (config JSON built at runtime from BuildConfig, written to
    `noBackupFilesDir`; single account; authority `common`, AzureADandPersonalMicrosoftAccount;
    scope `api://<clientId>/access_as_user`), `MsalAuthManager` (start, signIn / signInAgain,
    signOut, blocking `acquireTokenSilent`), `AuthInterceptor` (Bearer on every call; a 401 does one
    forced silent refresh and a retry; a second 401 or no token → `SignInRequiredException` and the
    app returns to sign-in).
  - Networking: `ApiService` rewritten to `me`, `courses`, `courses/join`,
    `student/assignments?course_id=`, `.../pack`, `.../pack/images/{kind}/{id}`. DTOs named as in
    `schemas.py` (`UserOut`, `CourseOut`, `JoinRequest`, `StudentAssignment`, `AssignmentPack`,
    `PackMarkers`, `PackBox`). FastAPI `detail` (string, list or object) turned into one line.
    Logging is HEADERS with `Authorization` redacted (was BODY, which would log the answer key).
  - Pack cache: `PackStore` in `noBackupFilesDir/packs/<question_id>/` (pack.json as received, plus
    every image). Refuses unknown `pack_version`, a pack for another question, and image refs other
    than `pack/images/(model-answer|question)/<id>`.
  - Screens: SignIn (shows name, email and role; teacher or admin gets "This app is for students;
    teachers use the website." and Sign out), Courses (join by code, `my_role == "student"` only),
    Assignments (status line per paper), AssignmentDetail (downloads and caches the pack; never shows
    the answer key). Scan and grading screens now take the question id (String) and read the cached
    pack through `AssignmentRepository.worksheetFor` (single page only, as before; Phase 5).
  - Cleartext is now allowed only for `localhost` / `127.0.0.1` (network_security_config).
  - Tests: `AuthInterceptorTest`, `MsalConfigTest`, `WebEndApiTest`, `AssignmentDtoParsingTest`
    rewritten for the pack (fixture `app/src/test/resources/webend/pack_v1.json`, synthetic, built
    from the schema; placeholder model answers).
- **Not done: file deletion** (the auto-mode classifier refused `rm` again). The user deletes these
  15 files. Until then `:app` does not compile:
  `ui/screens/LoginScreen.kt`, `LoginViewModel.kt`, `RegisterScreen.kt`, `RegisterViewModel.kt`,
  `SubmitScreen.kt`, `SubmitViewModel.kt`, `ResultScreen.kt`, `ResultViewModel.kt`,
  `data/local/TokenManager.kt`, `data/remote/GradeModels.kt`, `domain/model/Grade.kt`,
  `domain/model/Submission.kt`, `domain/grading/WorksheetGrade.kt`,
  `test/.../domain/grading/MergeBoxResultsTest.kt`, `test/.../data/local/GradeParsingTest.kt`
  (the last 7 are Session 2's list).
- **Tests/build:** in a scratch copy with those 15 files removed, `.\gradlew test assembleDebug`
  passed: `:app` 150/150 (per flavor), `:extractor` 42/42, both debug APKs built. The merged manifest
  has `msauth://com.example.capstone/C3ASDt+nHPY0SKMEXBWNCK5zUic=`. Apart from the 15 files, the
  scratch copy and the real tree are identical (`diff -rq`).
- **UNVERIFIED (needs the device and the user's web end):** interactive sign-in, the silent refresh
  against real Entra, `GET /api/me` accepting the MSAL token (audience and version with a
  personal-account registration), the MSAL redirect back into the app.
- **Follow-up in the same session: one client id per web end.** The user's registration
  (`3790c7aa-…`) serves only the LOCAL web end (teachers on the web and students on the phone both
  sign in with it; roles from `TEACHER_EMAILS`). The DEPLOYED server uses the teammate's registration
  (a different id). So `webend.clientId` is replaced by **`webend.local.clientId`** (flavor `local`,
  paired with `http://localhost:8000/api/`) and **`webend.deployed.clientId`** (flavor `deployed`,
  paired with `webend.deployedUrl`). `MSAL_CLIENT_ID` is now a per-flavor BuildConfig field, so each
  flavor gets its own MSAL config; `webend.signatureHash` stays shared, so the Android redirect is
  the same string in both registrations. Checked in the scratch build: local → `localhost:8000` +
  local id, deployed → deployed URL + deployed id. Tests 150/150 + 42/42, both APKs built.
- **Script-Checker-Web-End (docs only, branch confirmed `feature/on-device-grading`):**
  `addition_branch_phone.md` §8 now holds the exact request to the teammate (Android platform on the
  deployed registration: package, debug hash, client id back, release hash later) and a §3
  changelog row. No code changed there.
- **Test pass (same session, no device):**
  - Lint on both flavors: 0 errors; the 32 warnings are all older ones.
  - Every DTO checked field by field against the pydantic models (`UserOut`, `CourseOut`,
    `JoinRequest`, `StudentAssignment`, `AssignmentPack`, `PackMarkers`, `PackBox`): all match.
    The pack fixture validates with `AssignmentPack.model_validate_json`.
  - With the user's OK: the web end's `tests/test_on_device.py` 35/35 against `webend_test`, then a
    scratchpad-only script recorded the real responses of `/me`, `/courses`, `/courses/join` (404,
    422, 200), `/student/assignments`, `/pack` and both pack images for a student, a joining
    student, a teacher and an outsider. A scratch-only Kotlin test replayed them through the app's
    real Retrofit, interceptor and repositories: 4/4. It is not in the repo, because the recordings
    hold the fixture's model answers.
  - New `MsalConfigLoadTest` (Robolectric): MSAL's own loader accepts the generated config, and
    the redirect URI resolves to `BrowserTabActivity` in the merged manifest.
  - Scratch totals: `:app` 159/159 per flavor (155 + the 4 replay tests), `:extractor` 42/42, both
    APKs built.
  - **Fixed:** (1) finalize sets `bbox`/`page_index` to a box's **first segment** only and always
    writes `segments` (`routers/questions.py:848-854`), so a box running onto page 2 passed the
    single-page scan check and would have been graded on half its answer. `worksheetFor` now refuses
    split or later-page boxes by name (`singlePageProblem`), and the fixture is corrected to match
    finalize. (2) After process death the courses screen could call the web end before MSAL
    loaded and bounce a signed-in student to sign-in; the token path now loads MSAL itself, and
    Home reloads `/me` if it is missing.
  - Not run (the user declined): a read-only probe of the live :8000, and an emulator smoke test.
- **Next:** the user deletes the 15 files, adds the `local.properties` keys (`webend.local.clientId`,
  `webend.signatureHash`; `webend.deployed.clientId` and `webend.deployedUrl` once the teammate
  replies), adds the Android platform to their own registration, then runs the manual test. The
  teammate adds the same redirect to the deployed registration (G.1). Then Phase 5.

### 2026-09-25: Session 3, Phase 3 web end branch (CODE DONE, uncommitted)

- **Repo state checked:** `Script-Checker-Web-End` on `feature/on-device-grading` (created by the
  user from `main` @ `c69eea2`). No tracked changes before editing. `addition_branch_phone.md`
  was already in the repo root (untracked), not in the project root, so it was filled in rather
  than moved. Base `main` @ `c69eea2`, status "in progress".
- **Done (Script-Checker-Web-End/backend only):**
  - `routers/on_device.py`: the six routes of §C.3 (pack, pack images, start, results, my
    grades, re-evaluation), registered in `main.py` under `/api/student/...`.
  - `services/on_device.py`: run lifecycle, results save, background self-hosted fallback,
    `expire_stale_runs`, `sweep_forever`. `grading_runner.py` is untouched.
  - Migration `a9c4d2e81f37_on_device_runs` (down `f8a2c31e76b4`, single head) adds the three
    nullable columns of §C.5. `ON_DEVICE_LEASE_MINUTES = 15` in `config.py`.
  - `main.py` lifespan starts the sweeper (every 60 s). `tests/conftest.py` gets one line
    (`app.state.on_device_sweeper = False`).
  - `tests/test_on_device.py`: 35 tests.
  - `addition_branch_phone.md` has the full contract (§4), files, settings, migration and the
    changelog row.
- **Tests:** backend venv `backend/.venv` (already present; requirements installed this
  session). With the user's OK, Claude created the empty `webend_test` database on the running
  compose Postgres (`createdb` only). `tests/test_on_device.py` 35/35 passed. Full suite: 197
  passed, 13 failed. **All 13 are poppler (`pdfinfo`/`pdftoppm`) missing on this Windows
  machine**, in `test_print_size`, `test_question_access` (real-page renders) and
  `test_scanning`. They don't touch this code, and the Dockerfile installs `poppler-utils`.
  Offline `alembic upgrade --sql` shows exactly three `ADD COLUMN`s.
- **§C.3 text this session supersedes (the session prompt set these):**
  - Notification kinds are **`graded_on_device`** (not `on_device_graded`) and
    **`reevaluation_requested`** (not `re_evaluation_requested`).
  - Re-evaluation body is `{answer_box_ids?, message? (≤1000)}`, not ≤500. Rate limit 5/hour.
    Response `{submission_id, notified}`.
  - The flagged-box reason when fallback is unavailable is **"Fallback unavailable"**.
  - Sweeper: an expired run, **posted or not, becomes `failed`**, with the prompt's error text
    ("On-device grading didn't finish in time. Grade it on the website, or ask the student to
    reopen the app."). Pending boxes become needs review ("Server re-mark did not finish"). The
    §C.3 row "posted, fallback stuck → graded + notify" is replaced by this.
- **Refinements made while building (recorded in addition_branch_phone.md §4):**
  - The pack's text, points and blocked reason come from calling `build_grading_items` with a
    stand-in submission that has no crops. That is real reuse, not a copy.
  - Image refs are relative to the pack URL (`pack/images/<kind>/<id>`).
  - Results for protected boxes are ignored, not refused.
  - A 400 `detail` is an object: `{message, missing_box_ids | unknown_box_ids |
    duplicate_box_ids | answer_box_id}`.
  - The results response adds `fallback` (`none`/`scheduled`/`unavailable`/`completed`) and
    `fallback_message`.
  - My grades lists every box of the paper and adds `graded_count`.
  - On completion only `on_device_started_at` is cleared; the token and `on_device_posted_at`
    stay, so a repeat post is answered 200.
  - Start still returns 409 while a posted run's fallback is running (§C.3). The prompt's "own
    run still grading" is read as the unposted case.
  - The fallback task discards its work if the run was superseded, reset or expired meanwhile.
- **UNVERIFIED:** fallback against a real `SELF_HOSTED_LLM_URL` (tests use the fake provider);
  the sweeper loop in a running server (tests drive `expire_stale_runs` directly).
- **Next:** the user reviews, commits and pushes the branch (commands in the session summary).
  Phase 4 (Android sign-in and networking) builds its DTOs from `addition_branch_phone.md` §4.
  Earlier sessions' open items (7 files to delete in Capstone_Android; Phase 1 device logs) are
  still open.

### 2026-09-25: Session 2, Phase 2 grading engine (CODE DONE; build red until the user deletes 7 files)

- **Phase 1 findings did not exist.** Session 1 is still waiting for device
  logs, and no findings or fix are recorded anywhere. This session built what
  does not depend on them. Kept from Phase 1: all of `LocalGradingService`'s
  per-call logging, and experiments B and C on ModelTestScreen.
- **Source:** `Script-Checker-Web-End/backend/services/grading.py` and
  `tests/test_grading.py`, read only, at `c69eea2` on **`main`** (grading.py
  last changed in `04b436f`). `git branch --show-current` still prints `main`,
  not `feature/on-device-grading`. That did not matter here: the web end was
  not changed, and `git status` there still shows only `addition_branch_phone.md`.
- **Done (Capstone_Android only):**
  - `domain/grading/GradingPrompt.kt`: `GRADING_SYSTEM_PROMPT`,
    `AnswerToGrade` and `buildUserMessage`, ported word for word. The only
    addition is `CONFIDENCE: <a number from 0 to 100>` after the FEEDBACK line.
  - `domain/grading/ReplyParser.kt`: `parseGradingResponse`, a port of every
    branch (NOTHING WRITTEN, UNREADABLE, out of range treated as a parse
    error). Python `re` semantics are spelt out: `.`, `$`, `\s`, `\d`, `\W`,
    `strip()`, Unicode digits. `parseReply` cuts out every CONFIDENCE line,
    keeps the last one, and hands the rest to the ported parser unchanged, so
    FEEDBACK's DOTALL regex never swallows it.
  - `domain/grading/BlankDetector.kt`: `looksBlank`, the server's constants
    (0.0002, 4 % trim, 90th percentile, paper − 60), and PIL's `L24` luma.
    `ImagePrep.toGray` decodes on Android; an undecodable crop counts as
    written on, as on the server.
  - `domain/grading/TokenBudget.kt`: images × `imageTokens` + text + 300
    reply tokens. Drops question figures first, then model-answer images
    (last first); never crops. A text-less model answer keeps at least one
    image. Still over → NEEDS_FALLBACK with a reason.
  - `domain/grading/BoxGrader.kt`: per box, `grade_one`'s order. Blocked →
    NEEDS_REVIEW; no crop → NEEDS_FALLBACK; all parts blank → BLANK, score 0,
    no call. Also NEEDS_FALLBACK: text-only model, over budget, call throws,
    UNREADABLE, parse error, CONFIDENCE missing, not a number, out of 0..100,
    or below the threshold (`GradingConfig`, default 60). Result fields:
    answerBoxId, score, maxScore, transcript, feedback, confidence, status,
    reason, rawReply, modelId, durationMs.
  - `data/local/LocalGradingService.kt`: now only the LiteRT call
    (`GradingModel`). System turn, images then text, a fresh conversation per
    call. JSON prompt, `parseGrade` and `gradeRaw` are gone.
  - One call per box, **no retry**. The server makes one call, and a retry
    would need a second, different prompt; a bad reply goes to fallback.
  - Submission-level grade removed from the code: the `submitGrade` route and
    repository method, `getMyGrades`, `SubmissionDto.grades`, the Results
    button and route, and the grading screen's upload step. The grading
    screen shows per-box status; a box without a mark shows "–", never
    "null" and never a total.
  - ModelTestScreen: **Grade it** now runs one picked crop through the new
    engine, using the Question / Model answer / Max marks fields (moved out of
    the debug block). It shows the user message, every result field and the
    raw reply. Experiment A uses the engine. Experiment C uses the shared
    prompt constants.
  - Tests: new `ServerParityTest`, `ReplyParserTest`, `BlankDetectorTest` and
    `BoxGraderTest`. Fixtures in `app/src/test/resources/grading/` come from
    running the server's real functions (`make_fixtures.py`, beside them):
    its prompt, 5 user messages, 25 replies, and `looks_blank` on the server
    test's own 4 PNGs. The parity test compares against those.
- **Not done: file deletion (the auto-mode classifier refused `rm` on
  untracked files).** The user deletes these 7:
  `domain/grading/WorksheetGrade.kt` (holds `mergeBoxResults`),
  `test/.../domain/grading/MergeBoxResultsTest.kt`,
  `test/.../data/local/GradeParsingTest.kt`, `domain/model/Grade.kt`,
  `ui/screens/ResultScreen.kt`, `ui/screens/ResultViewModel.kt`,
  `data/remote/GradeModels.kt`. Until then `:app` does not compile
  (`ResultViewModel` calls the removed `getMyGrades`).
- **Tests/build:** in a scratch copy with those 7 files removed,
  `.\gradlew test assembleDebug` passed: `:app` 129/129, `:extractor` 42/42,
  debug APK built. Not yet run in the real tree (see above).
- **Plan text this session supersedes (the session prompt set these):**
  - B.1 bullet 2 and D.3 step 4: CONFIDENCE goes **after** FEEDBACK. It is not
    placed between SCORE and FEEDBACK; the parser removes it instead.
  - D.3 step 5 and C.3 route 4: CONFIDENCE is **0..100** with threshold
    **60**, not 0.0-1.0 with 0.7. The results post will carry 0..100 unless
    Phase 3 decides otherwise.
  - D.3 step 5 "after one retry": dropped (see above).
  - D.3 step 4 "model-answer image only if the text is blank": replaced by the
    drop order above.
- **UNVERIFIED (needs the device):** the system turn on Qwen2-VL (experiment
  C); more than one image per message (experiment B; `maxNumImages` unset);
  images-first order; 3 characters per token for the text estimate; 576
  tokens per image; the printed box label against `looks_blank` (§C.6).
- **Not in this prompt, still open for Phase 2:** Qwen `expectedBytes =
  1_784_096_288L` in `ModelSpec.kt`; real Qwen replies as test fixtures (from
  Phase 1 logs).
- **Next:** the user deletes the 7 files, then runs `.\gradlew test` and
  `.\gradlew assembleDebug` in the real tree. Then device runs of Grade it and
  experiments A to C, whose logs close Phase 1.

### 2026-09-25: Session 1, Phase 1 Qwen diagnosis (IN PROGRESS, waiting for device logs)

- **Done so far (Capstone_Android only):**
  - `LocalGradingService.runPrompt` logs per call: call number, PNG bytes and
    WxH, prompt chars, conversation open/close, elapsed ms, token counts, the
    raw reply verbatim (fenced `<<<…>>>`, chunked past logcat's 4 KB limit),
    and any `sendMessage` exception with its class and message.
  - `LocalModelProvider`: debug builds set `ExperimentalFlags.enableBenchmark`
    before engine init, so `Conversation.getBenchmarkInfo()` gives prompt
    (prefill) and response (decode) token counts; `getTokenCount()` gives the
    total. Logs `maxNumImages` (unset, so native default -1). New multi-image
    `runRawPrompt(prompt, images, systemInstruction, label)`.
  - ModelTestScreen, debug only: experiments A (3 boxes in a row through
    `grade()`), B (2 then 3 images in one message), C (web end
    `GRADING_SYSTEM_PROMPT` + `CONFIDENCE: <0-100>`).
  - Code read: each call opens a fresh `Conversation` and closes it (native
    `nativeDeleteConversation`, synchronous) under `inferenceMutex` before the
    next starts. No overlap on paper; the logs confirm or refute it.
- **Tests/build:** `testDebugUnitTest` 84/84 green; `assembleDebug` OK.
- **Next:** user runs the device steps; root cause, fix and "Phase 1
  findings" follow from the logs.

### 2026-09-25: Session 0b, git becomes read-only for Claude

- **Done:**
  - Checked that `CLAUDE_OLD.md` exists and the new short `CLAUDE.md` is in
    place (the rename had happened).
  - Replaced the session rules in `CLAUDE.md`: git is read-only for Claude;
    web end edits need `git branch --show-current` =
    `feature/on-device-grading` first; sessions end with a list of changed
    files and suggested commit messages instead of commits.
  - Updated decision 13, §A.2, §E (intro and Phase 3) and §F to match.
- **Git:** none run beyond reads. The project folder is still not a git repo;
  the user will put it on a new GitHub repo later, and the root `.gitignore`
  already excludes `/Script-Checker-Web-End/`.
- **Not touched:** Capstone_Android, Script-Checker-Web-End, ASC_Capstone,
  v-2.1.1.
- **Next:** unchanged. The user commits Capstone_Android's pending work and
  creates the web end branch; then Phase 1.

### 2026-09-25: Session 0, plan replacement (this file)

- **Done:**
  - Read everything listed in the prompt; §C.1 facts verified against code.
  - Rewrote this file, wrote the new `CLAUDE.md`, and renamed the old one to
    `CLAUDE_OLD.md`.
  - Added `/Script-Checker-Web-End/` to the root `.gitignore`.
- **Git:**
  - The project folder is **not a git repo**, so there was nothing to
    `git rm --cached`, and `git mv` / commit were impossible. (Superseded by
    Session 0b: Claude now runs no git write commands at all.)
  - The rename was done as a plain file move.
  - The user chose to leave all four files **uncommitted** this session.
- **Web end clone:**
  - `main` equals `origin/main` (`c69eea2`) as of the last successful fetch.
  - Today's fetch failed (no network).
  - Untracked `addition_branch_phone.md`, a draft from an earlier session.
  - No `.env` yet.
- **Not touched:** Capstone_Android, Script-Checker-Web-End, ASC_Capstone,
  v-2.1.1.
- **Next:** Phase 1. Before that, the user commits Capstone_Android's
  pending work.
