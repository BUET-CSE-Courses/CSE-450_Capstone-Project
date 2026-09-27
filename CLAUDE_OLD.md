# Capstone: On-Device Worksheet Grading — state of the code

> Factual record of both repos, produced by reading every source file and by
> executing the builds, the test suites, the typechecker and the migration check.
> First written **2026-08-21**; re-audited **2026-08-30** for the `:extractor`
> module and the teacher-worksheet layout/import path (sections 0, 1, 3.2-3.5,
> 3.12-3.14, 4, 6, 7, 8 rewritten against the code as it stands that day; every
> claim in them re-verified by reading or executing, never carried over).
> It describes what the code is, not what it should become. If something here is
> not true of the code, the file is wrong and should be re-audited.
> Claude Code must not rewrite this file spontaneously; only on explicit request.

Every claim is tagged with one of three states:

| State | Meaning |
|---|---|
| **VERIFIED** | Compiled, passed a test, or was executed and observed during this audit |
| **UNVERIFIED** | Code is written and compiles, but this behaviour has never been executed |
| **NOT PRESENT** | Referenced by another part of the system, absent from the codebase |

---

# 0. Repo layout

One parent folder, two independent git repos plus two read-only reference
folders:

```
capstone app/                  not itself a git repo
├── Capstone_Android/          git root — Kotlin + Jetpack Compose app
│                              branch feature/on-device-grading, uncommitted changes
│   ├── app/                   :app          — the student application
│   └── extractor/             :extractor    — ArUco registration + crop library (§7)
├── ASC_Capstone/              git root
│   ├── server/                Node + Express + Prisma + PostgreSQL
│   │                          branch feature/local-grade-endpoint, uncommitted changes
│   ├── api_doc.md
│   ├── readme.md
│   └── specs/
├── v-2.1.1/                   teacher worksheet system (Python/FastAPI). NOT a
│                              git repo here, not built or run by this project.
│                              Read-only source of the printed-page geometry.
├── INTEGRATION_AUDIT.md       41,415 bytes. The audit of v-2.1.1 that the
│                              extractor and the import route are built against;
│                              cited by section number throughout both.
├── LLaVA-OneVision-0.5B.litertlm      829,262,144 bytes
├── Qwen2-VL-2B.litertlm             1,784,096,288 bytes
├── gemma3-1b-it-int4.litertlm         584,417,280 bytes
└── .gitignore                         contains only `*.litertlm`
```

`ModelSpec.expectedBytes` matches for LLaVA and Gemma exactly (VERIFIED —
`ls -l`). `Qwen2-VL-2B` is present on disk at 1,784,096,288 bytes and its spec
carries `expectedBytes = null` deliberately (§3.8).

Android git state: 25 modified files, and untracked `extractor/`,
`data/local/{LocalGradingService,LocalModelProvider,ModelSpec}.kt`,
`data/remote/LayoutModels.kt`, `domain/grading/`, `domain/worksheet/`, `util/`,
`ui/screens/ModelTest{Screen,ViewModel}.kt`,
`ui/screens/WorksheetGrading{Screen,ViewModel}.kt`, and the test directories.
Single commit on the branch: `8281d9f Initial commit`.

Server git state (repo root is `ASC_Capstone/`, not `ASC_Capstone/server/`):
10 modified files — `.gitignore`, `server/prisma/schema.prisma`,
`server/src/config/env.ts`, `server/src/lib/http-error.ts`,
`server/src/middleware/error-handler.ts`,
`server/src/routes/{assignment,submission}.routes.ts`,
`server/src/schemas/{assignment,submission}.schemas.ts`,
`server/src/services/grading.service.ts` — plus untracked
`server/prisma/migrations/20260830120000_teacher_worksheet_join/` and
`server/src/services/teacher-worksheet.service.ts`.
HEAD is `3bcf9cf before first eval`.
---

# 1. Status summary

Re-verified 2026-08-30 by reading every source file changed since the previous
audit and by executing the builds, the test suites, the typechecker and the
migration check. Nothing below is carried over unexamined.

## VERIFIED

**Android — both modules compile and all 126 unit tests pass.**

- `./gradlew --rerun-tasks :app:compileDebugKotlin :extractor:compileDebugKotlin`
  → BUILD SUCCESSFUL, 15 tasks executed. Two deprecation warnings, no errors:
  `HomeScreen.kt:45` (`TabRow`) and `ScanScreen.kt:300` (`LocalLifecycleOwner`).
- `./gradlew cleanTestDebugUnitTest testDebugUnitTest` → BUILD SUCCESSFUL.
  **`:app` 84 tests, `:extractor` 42 tests — 126 total, 0 failures, 0 errors**
  (fresh run, XML regenerated this audit; see §3.12 for the per-suite split).
  The line `Failed to create image decoder with message 'incomplete input'` in
  the `:extractor` output is `PageExtractorTest`'s undecodable-input case
  logging from native OpenCV; the test asserting it passes.
- Because compilation succeeds, every LiteRT-LM signature the app calls exists
  in `litertlm-android:0.16.1` (§5), and every `org.opencv.*` signature the
  extractor calls exists in both the Android AAR and the openpnp JVM build.
- **The extractor's registration core is executed against a real ArUco page.**
  `GoldenSampleTest` decodes `sample_page.png`, detects all four markers and
  reproduces the warped bboxes `[168,600,1128,850]` and `[168,1283,1128,1573]`
  recorded from the Python `extract_page`, within 3 px per component.
  `MarkerContractTest` measures the cost of a wrong contract on those same real
  markers: a 10 px marker-margin error displaces every crop ~11 px, and
  swapping marker ids 2 and 3 displaces them ~990 px — both while still
  detecting 4/4.

**Server — typechecks and the schema is migrated.**

- `npx tsc --noEmit` → exit 0, no errors.
- PostgreSQL reachable at `127.0.0.1:5433` (Docker container `capstone-db`,
  image `postgres:16`, published `0.0.0.0:5433->5432/tcp`). The container was
  found **stopped** and was started during this audit to run the check below.
  It is still not defined by any file in either repo.
- `npx prisma migrate status` → `3 migrations found`, `Database schema is up to
  date!` — the new `20260830120000_teacher_worksheet_join` is applied.

**Carried from the 2026-08-21 audit, not re-executed this pass:** the 14-route
curl sweep of the HTTP + persistence layer. Those routes are unchanged except
where noted in §2, and the two routes added since (`POST /assignments/import`,
and the layout/marking fields on student `GET /assignments/:id`) have **never
been exercised over HTTP** — see UNVERIFIED.

## UNVERIFIED

- **`POST /api/assignments/import` has never been called.** It is the only way
  an assignment can acquire a layout, an `external_question_id` or an
  `external_answer_box_id`, so every row those columns exist for is
  hypothetical. It requires a reachable teacher worksheet system at
  `TEACHER_API_BASE_URL`; nothing in either repo starts one.
- **The student `GET /assignments/:id` layout payload has never been observed
  over HTTP.** Its shape is pinned only by `AssignmentDtoParsingTest`, which
  parses a hand-written JSON fixture — not a server response.
- **The whole extractor path against a real photograph.** Every extractor test
  runs against `sample_page.png`, which is a *rendered* page at 1242×1756
  against a canonical 1240×1754. Perspective, blur, lighting, glare, partial
  marker occlusion and a genuine phone camera have never been tested.
  `warpPerspective` rectification is therefore verified as arithmetic and
  unverified as an answer to a skewed photo.
- **`OpenCvNative.load()` has never run.** It is quarantined off every test
  classpath by construction (§7.2), so whether the OpenCV AAR's arm64 native
  library loads on a device is unknown.
- **Everything requiring the physical device.** Engine construction and
  `initialize()`, LiteRT-LM inference, the GPU vision backend, handwriting
  transcription quality, latency, and whether the app uid can read
  `/data/local/tmp/llm/`. There is no run log, benchmark, or recorded output in
  either repo.
- **The Android app against the server.** Nothing records the app having
  completed a login, an assignment fetch, a submission upload, or a grade post.
- **`AssignmentRepository.submitAnswers` / `submitGrade`.** Both are written and
  both compile; neither has ever sent a request.
- `ExampleInstrumentedTest` (1 test) — requires a device or emulator, never run.
- `LocalGradingService.grade()` end to end. Its parsing half is covered by
  tests; the inference half has never executed.
- Every screen and ViewModel — no UI or instrumentation tests exist.

## NOT PRESENT

- **Any route that can set `model_answer` on an imported question.** The import
  route creates every Question with `model_answer = ""` and its own docblock
  says "the teacher fills those in here afterwards" — there is no PUT, PATCH or
  POST anywhere in the 15 routes that can do it (VERIFIED by enumerating every
  `Router.<verb>` call). The consequence is traced in §6: **no imported
  assignment can currently reach the model at all.**
- **Any enforcement of `LayoutDto.layoutVersion`.** The field is parsed and its
  own KDoc says a client that does not know the version should refuse the
  layout; nothing reads it.
- **Any reader of the served ArUco dictionary.** `MarkerContractDto.arucoDict`,
  `markerSizePx`, `markerMarginPx` and `source` are all parsed and none is read.
  `Registration` hardcodes `Objdetect.DICT_4X4_50` (§7.5).
- **Any reader of `LayoutAnswerBoxDto.orderIndex`, `.label` or `.points`.**
  Order index is re-derived from array position by the extractor.
- **`GET grades/me` client method.** `ApiService` has 8 methods against the
  server's 15 routes. `AssignmentRepository.getMyGrades()` still calls
  `getMySubmissions()` and reads `SubmissionDto.grades` instead.
- **A `grade` field on the server's Grade row** — `GradeDto` still declares
  `grade: String`; the server sends `obtained_marks: Int` (§4).
- **`GradingService.gradeRaw` callers.** Declared on the interface, implemented,
  never invoked (the debug probe uses `runRawPrompt`).
- **`SubmitScreen` and `SubmitViewModel` callers.** The `submit/{assignmentId}`
  route was replaced by `grade/{assignmentId}`; both files still compile and
  neither is reachable (§3.13).
- **`ScanScreen.CameraPreview` callers.** A private composable, never called.
  The CameraX dependencies and the `CAMERA` manifest permission are still
  declared for it (§3.13).
- **Any code producing `graded_by = "frontier_api"`.** The Prisma enum and the
  Zod schema accept it; nothing writes it.
- **Server tests.** `package.json` `"test": "echo \"Error: no test specified\" &&
  exit 1"`. No test files, no test framework in `devDependencies`.
- **`docker-compose.yml`** — absent from both repos (VERIFIED by `find`).
- **Any guard preventing an image being sent to a text-only model** (§6.3 of the
  previous audit; still true — `LocalGradingService.runPrompt` attaches the
  image regardless of `spec.supportsVision`).
- **Multi-page worksheet support in the app.** `ScanViewModel` blocks any layout
  whose boxes are not all on page 0.
---

# 2. Server — `ASC_Capstone/server`

## 2.1 Stack (from `package.json`)

Dependencies: `@prisma/client` ^6.10.1, `bcrypt` ^6.0.0, `cors` ^2.8.6,
`dotenv` ^17.4.2, `express` ^5.2.1, `express-rate-limit` ^8.5.2, `helmet`
^8.3.0, `jsonwebtoken` ^9.0.3, `multer` ^2.2.0, `zod` ^4.4.3.

Dev: `prisma` ^6.10.1, `ts-node-dev` ^2.0.0, `typescript` ^5.6.3 (resolved
5.9.3 at runtime), plus `@types/*`.

Scripts: `dev` = `ts-node-dev --respawn --transpile-only src/index.ts`,
`build` = `tsc`, `start` = `node dist/index.js`, `test` = a failing stub.

`tsconfig.json`: target ES2020, commonjs, `strict: true`, `rootDir: src`,
`outDir: dist`.

## 2.2 Environment (`src/config/env.ts`)

Parsed with Zod at import time; `process.exit(1)` on failure. `.env` exists and
is gitignored. Seven variables are set in `.env`; the two `TEACHER_API_*`
entries added for the import route (§8.2) are **not** set and fall back to their
defaults.

| Var | Schema |
|---|---|
| `NODE_ENV` | enum development/test/production, default `development` |
| `PORT` | coerced positive int, default 3000 |
| `DATABASE_URL` | string min 1, **required** |
| `JWT_SECRET` | string min 16, **required** |
| `JWT_EXPIRES_IN` | string, default `"1d"` |
| `CONFIDENCE_THRESHOLD` | coerced number 0..1, default 0.8 |
| `TEACHER_API_BASE_URL` | url, default `http://localhost:8000` — read only by the import route |
| `TEACHER_API_TIMEOUT_MS` | coerced positive int, default 10000 — read only by the import route |
| `STUDENT_SELF_REGISTER` | optional string, transformed to `value !== "false"` |

`CONFIDENCE_THRESHOLD` is read in exactly one place: `grading.service.ts`, which
nothing imports (§2.8).

## 2.3 Wiring (`src/app.ts`)

```
helmet() → cors() → express.json({ limit: "2mb" })
         → static /uploads from <cwd>/uploads
         → /api              healthRouter
         → /api/auth         authRouter
         → /api/assignments  assignmentRouter
         → /api/submissions  submissionRouter
         → /api/grades       gradeRouter
         → notFoundHandler → errorHandler
```

`src/index.ts` awaits `prisma.$connect()` before `app.listen`, and exits 1 if
the connection throws.

**Auth** (`middleware/auth.ts`): `authenticate` requires
`Authorization: Bearer <jwt>`, verifies it, and sets
`req.user = { id: Number(payload.sub), role: payload.role }`. Failure → 401.
`authorize(...roles)` → 401 when `req.user` is absent, 403 on a role mismatch.
`assignmentRouter`, `submissionRouter` and `gradeRouter` each call
`.use(authenticate)` globally.

**Validation** (`middleware/validate.ts`): `validate(schema)` calls
`schema.parse({ body, params, query })` and lets the `ZodError` propagate.

**Errors** (`middleware/error-handler.ts`): `HttpError` → its own status;
`ZodError` → 400 `{ error: "Validation failed", issues }`; anything with
`code === "P2002"` → 409; otherwise `console.error` and 500.

**JWT** (`utils/jwt.ts`): payload is `{ sub: String(userId), role }`, signed with
`env.JWT_SECRET` and `expiresIn: env.JWT_EXPIRES_IN`.

## 2.4 Route inventory

**15 routes.** The 14 below were exercised by curl in the 2026-08-21 audit and
are unchanged since. `POST /api/assignments/import`, added in this branch, is
listed with them and has **never been called** (§1, §8.2). There is no PUT and
no PATCH on assignments or questions — enumerated this audit, and the reason
§6.2 exists.

| Method | Path | Auth | Role | Zod schema | Notes |
|---|---|---|---|---|---|
| GET | `/api/health` | none | — | none | `{ status: "ok" }` |
| POST | `/api/auth/register` | none | — | `registerSchema` | 403 for `student` when `STUDENT_SELF_REGISTER=false`; bcrypt cost 10; 201 |
| POST | `/api/auth/login` | none | — | `loginSchema` | rate limited 10 / 15 min / IP; identical "Invalid credentials" for unknown email and wrong password |
| POST | `/api/assignments` | JWT | teacher | `createAssignmentSchema` | nested `questions: { create }`; 201 |
| GET | `/api/assignments` | JWT | both | `studentAssignmentQuerySchema` | branches on role |
| GET | `/api/assignments/:id` | JWT | both | `assignmentIdParamSchema` | branches on role |
| POST | `/api/submissions` | JWT | student | `createSubmissionSchema` | `multipart/form-data`, `upload.any()` |
| GET | `/api/submissions/me` | JWT | student | none | newest first, derived `status` |
| GET | `/api/submissions/me/:id` | JWT | student | none (manual `Number.isInteger` → 400) | ownership in the where-clause |
| GET | `/api/submissions/assignment/:assignmentId` | JWT | teacher | none | 404 unknown, 403 not owner |
| POST | `/api/submissions/:id/grade` | JWT | student | `createSubmissionGradeSchema` | §2.5 |
| GET | `/api/grades/me` | JWT | student | none | includes `assignment` and `submission` |
| GET | `/api/grades/assignment/:assignmentId` | JWT | teacher | none | 404 unknown, 403 not owner |
| PATCH | `/api/grades/:id/override` | JWT | teacher | `overrideGradeSchema` | sets `graded_by=teacher_override`, `confidence=null` |
| POST | `/api/assignments/import` | JWT | teacher | `importAssignmentSchema` | **NEVER CALLED.** Imports one finalized teacher question; 201 create / 200 refresh / 409 changed box ids / 400 / 404 / 502. See §8.2 |

The four routes with no Zod schema pass `Number(req.params.…)` straight to
Prisma without an integer check.

### Role branching on the assignment routes

`GET /api/assignments` — student branch selects per question **only**
`{ id, question_text }`, plus `submissions: { where: { student_id } }` reduced to
`submission_status` = `"completed"` when at least one submission exists,
otherwise `"pending"`; filtered in JS by `?status=pending|completed`. Teacher
branch returns `where: { teacher_id }` with the full `questions` relation.

`GET /api/assignments/:id` — student branch selects
`{ id, title, description, total_marks, created_at, updated_at, questions: { id,
question_text, marks, model_answer, rubric } }`. **`model_answer` and `rubric`
are exposed to the student here on purpose** (comment at
`assignment.routes.ts:174-178`: the Android client grades on-device and needs
something to grade against). Teacher branch includes `questions` and a teacher
summary, and enforces ownership with a 403.

### `POST /api/submissions`

Reads `assignment_id`, an `answers` JSON string (parsed in-handler when it
arrives as a string), and one file per question named `image_<question_id>`.
`multer.diskStorage` writes to `<cwd>/uploads/answers` with a
`${Date.now()}-${random}${ext}` filename; the `fileFilter` rejects any mimetype
not starting with `image/` (400).

Validation order: assignment exists (404) → every `question_id` belongs to it
(400) → no duplicate `question_id` (400) → every image fieldname parses to a
positive int (400) → no two images for one question (400) → every answer has an
image (400).

One `prisma.$transaction` creates the Submission with nested Answers, each
carrying `answer_image_path = /uploads/answers/<filename>`. **No grade row is
created.** Responds 201 with the submission including `answers[]`.

Line 117 calls `validate(createSubmissionSchema)(req, res, () => undefined)` and
discards the result; the `createSubmissionSchema.shape.body.parse` two lines
later is what actually validates.

## 2.5 `POST /api/submissions/:id/grade`

`submission.routes.ts:412-549`. Student-only. Records what the phone reports;
the server grades nothing here.

Request body (`schemas/submission.schemas.ts:30-41`):

```jsonc
{
  "obtained_marks": 7,           // int >= 0, required
  "feedback": "…",               // string, required (not optional)
  "confidence": 0.82,            // number 0..1, required
  "graded_by": "local_model",    // "local_model" | "frontier_api", default "local_model"
  "answers": [                   // optional, defaults to []
    { "question_id": 41, "transcription": "…" }   // transcription optional, default ""
  ]
}
```

`graded_by` does not accept `teacher_override`; that value is only ever written
by `PATCH /api/grades/:id/override`.

Validation order: submission exists (404) → `submission.student_id === req.user.id`
(403, checked after existence so the two are distinguishable) →
`obtained_marks <= assignment.total_marks` (400) → every `question_id` belongs to
this assignment (400) → no duplicate `question_id` (400) → every `question_id`
already has an Answer row on this submission (400).

Persistence, one `prisma.$transaction`: each listed Answer's `answer_text` is
updated to its `transcription`, then `grade.findFirst({ where: { submission_id } })`
decides between `update` and `create`. `Grade` has **no unique constraint on
`submission_id`**, so upsert is unavailable and the find-then-update is what keeps
repeat calls from leaving two grade rows. Response: the Grade row, **201 when
created, 200 when updated** (both observed).

## 2.6 Prisma schema (`prisma/schema.prisma`)

```prisma
enum Role          { teacher, student }
enum QuestionType  { mcq, short_answer, essay }
enum GradingSource { local_model, frontier_api, teacher_override }

model User {            // @@map("users")
  id Int @id @default(autoincrement())
  email String @unique @db.VarChar(255)
  password_hash String @db.Text
  role Role
  created_at DateTime @default(now())
  updated_at DateTime @updatedAt
  created_assignments Assignment[]
  submissions Submission[]
  grades Grade[]
}

model Assignment {      // @@map("assignments")
  id Int @id @default(autoincrement())
  teacher_id Int
  title String @db.VarChar(255)
  description String? @db.Text
  total_marks Int @db.SmallInt
  created_at DateTime @default(now())
  updated_at DateTime @updatedAt
  teacher User @relation(fields: [teacher_id], references: [id], onDelete: Cascade)
  questions Question[]
  grades Grade[]
  submissions Submission[]
}

model Question {        // @@map("questions")
  id Int @id @default(autoincrement())
  assignment_id Int
  question_text String @db.Text
  marks Int @db.SmallInt
  question_type QuestionType
  model_answer String @db.Text     // NOT NULL
  rubric String? @db.Text          // nullable
  created_at DateTime @default(now())
  updated_at DateTime @updatedAt
  assignment Assignment @relation(..., onDelete: Cascade)
  answers Answer[]
}

model Submission {      // @@map("submissions")
  id Int @id @default(autoincrement())
  assignment_id Int
  student_id Int
  submitted_at DateTime @default(now())
  updated_at DateTime @updatedAt
  assignment Assignment @relation(..., onDelete: Cascade)
  student User @relation(..., onDelete: Cascade)
  answers Answer[]
  grades Grade[]
}

model Answer {          // @@map("answers")
  id Int @id @default(autoincrement())
  submission_id Int
  question_id Int
  answer_text String @db.Text      // NOT NULL, "" allowed
  answer_image_path String? @db.Text
  created_at DateTime @default(now())
  updated_at DateTime @updatedAt
  submission Submission @relation(..., onDelete: Cascade)
  question Question @relation(..., onDelete: Cascade)
}

model Grade {           // @@map("grades")
  id Int @id @default(autoincrement())
  assignment_id Int
  submission_id Int                // NO unique constraint
  student_id Int
  obtained_marks Int @db.SmallInt
  feedback String? @db.Text
  confidence Float?                // nullable: teacher override has none
  graded_by GradingSource
  graded_at DateTime @default(now())
  updated_at DateTime @updatedAt
  assignment Assignment @relation(..., onDelete: Cascade)
  submission Submission @relation(..., onDelete: Cascade)
  student User @relation(..., onDelete: Cascade)
}
```

Constraints that shape the rest of the system:

- **`Grade` is per-submission, not per-question.** There is no `question_id` on
  it, and only one `obtained_marks`, one `feedback` and one `confidence` per
  submission. Per-question detail can only live in `Answer.answer_text`.
- **`Grade.submission_id` is not unique**, which is why the grade route does
  find-then-update rather than upsert. Nothing in the database prevents a second
  grade row for one submission; only that handler does.
- **`Grade.confidence` is nullable** and `PATCH …/override` is the only writer of
  `null`.
- **`Question.model_answer` is NOT NULL, `rubric` is nullable.**
- Every relation is `onDelete: Cascade`; deleting a User removes their
  assignments, submissions, answers and grades (confirmed during audit cleanup).
- The only unique index in the database is `users_email_key`.

## 2.7 Migrations

1. `20260719193603_idk/migration.sql` — 117 lines: three enums, six tables, the
   `users_email_key` unique index, nine foreign keys, all `ON DELETE CASCADE`.
2. `20260818000000_add_answer_image_path/migration.sql` —
   `ALTER TABLE "answers" ADD COLUMN "answer_image_path" TEXT;`
3. `20260830120000_teacher_worksheet_join/migration.sql` — adds
   `assignments.external_question_id` and `questions.external_answer_box_id`,
   creates the `layouts` table with its FK and its
   `layouts_assignment_id_key` unique index, and creates the two composite
   unique indexes `assignments(teacher_id, external_question_id)` and
   `questions(assignment_id, external_answer_box_id)`. See §8.3.

`migration_lock.toml` → `provider = "postgresql"`.
**Applied: VERIFIED (2026-08-30)** — `prisma migrate status` reports
`3 migrations found` and `Database schema is up to date!`. The `capstone-db`
container was found stopped and started for the check.

## 2.8 Dead and deliberately retained code

- **`src/services/grading.service.ts`** — 72 lines, exports `gradeSubmission()`,
  a bag-of-words token-overlap scorer that reads `env.CONFIDENCE_THRESHOLD` and
  picks `local_model` vs `frontier_api` from it. **Unreferenced** —
  `grep -rn gradeSubmission src --include=*.ts` returns only its own definition
  (re-verified 2026-08-30). A file header added in this branch states why it is
  kept: a planned server-side grading fallback and a lexical-overlap baseline
  for evaluation. It is the only reader of `CONFIDENCE_THRESHOLD`, which is why
  that variable is still in `env.ts` despite no route using it.
- `src/services/` is no longer a dead directory: `teacher-worksheet.service.ts`
  (365 lines, untracked) sits beside it and **is** imported, by the import route
  only (§8.2).
- **`submission.routes.ts:117`** — the discarded `validate(...)` call described
  in §2.4.
- `src/types/express.d.ts` declares `Request.files?: Multer.File[]`; the
  submission handler casts `req.files` to `Express.Multer.File[]` anyway.

---

# 3. Android — `Capstone_Android`

## 3.1 Build configuration

Two modules: `:app` and `:extractor` (§7.2), joined by
`settings.gradle.kts: include(":extractor")` and
`app/build.gradle.kts: implementation(project(":extractor"))`. The root
`build.gradle.kts` adds `alias(libs.plugins.androidLibrary) apply false`.

`app/build.gradle.kts`: `namespace com.example.capstone`, `compileSdk 37`,
`minSdk 26`, `targetSdk 35`, Java 17. `buildFeatures { compose = true;
buildConfig = true }`.

`buildConfigField("String", "BASE_URL", …)` is defined for both build types and
is `"http://localhost:3000/api/"` in each; the release entry is commented as a
placeholder because no server is deployed. `AppContainer` reads
`BuildConfig.BASE_URL`; no URL is hardcoded in Kotlin.

`testOptions.unitTests`: `isIncludeAndroidResources = true` (Robolectric),
`isReturnDefaultValues = true` (so `android.util.Log` no-ops in JVM tests).

Versions (`gradle/libs.versions.toml`): AGP 9.2.1, Kotlin 2.1.0, Compose BOM
2026.02.01, Retrofit 2.11.0, OkHttp logging 4.12.0, DataStore 1.1.1,
navigation-compose 2.8.5, Coil 2.6.0, CameraX 1.4.0, **litertlm 0.16.1**,
exifinterface 1.4.2, Truth 1.4.5, Robolectric 4.16.1, JUnit 4.13.2,
**opencv 4.11.0** (Android AAR) and **opencvJvm 4.9.0-0** (openpnp desktop
build, unit tests only), json 20260814.

`AndroidManifest.xml`: permissions `INTERNET` and `CAMERA`; `uses-feature
android.hardware.camera required="false"` — both retained for the now-dead
CameraX path (§3.13); inside `<application>`, two
`<uses-native-library>` entries — `libvndksupport.so` and `libOpenCL.so`, both
`required="false"`, commented as required by the LiteRT-LM GPU (OpenCL) vision
backend. `network_security_config.xml` sets `cleartextTrafficPermitted="true"`
for the base config.

## 3.2 Source inventory (line counts)

`:app` main — 45 files, 5,987 lines:

```
CapstoneApplication.kt                     14
MainActivity.kt                           125   modified
data/local/LocalGradingService.kt         246   untracked
data/local/LocalModelProvider.kt          505   untracked
data/local/ModelSpec.kt                   130   untracked
data/local/TokenManager.kt                 33
data/remote/ApiService.kt                  49   modified
data/remote/AssignmentModels.kt            88   modified
data/remote/AuthModels.kt                  31   modified
data/remote/GradeModels.kt                 55   modified
data/remote/LayoutModels.kt                82   untracked
data/repository/AssignmentRepository.kt   275   modified
data/repository/AuthRepository.kt         100   modified
di/AppContainer.kt                        124   modified
domain/grading/GradingService.kt           59   untracked
domain/grading/WorksheetGrade.kt          163   untracked
domain/grading/WorksheetGrader.kt         113   untracked
domain/model/Assignment.kt                 67   modified
domain/model/Grade.kt                       9
domain/model/Submission.kt                  8
domain/worksheet/MarkerCorners.kt          59   untracked
domain/worksheet/QuestionResolver.kt      265   untracked
domain/worksheet/WorksheetSession.kt       51   untracked
ui/screens/AssignmentDetailScreen.kt      105   modified
ui/screens/AssignmentDetailViewModel.kt    61
ui/screens/HomeScreen.kt                  119   modified
ui/screens/HomeViewModel.kt                74   modified
ui/screens/LoginScreen.kt                  83   modified
ui/screens/LoginViewModel.kt               64   modified
ui/screens/ModelTestScreen.kt             336   untracked
ui/screens/ModelTestViewModel.kt          337   untracked
ui/screens/RegisterScreen.kt               93   modified
ui/screens/RegisterViewModel.kt            94   modified
ui/screens/ResultScreen.kt                 86
ui/screens/ResultViewModel.kt              50
ui/screens/ScanScreen.kt                  359   modified
ui/screens/ScanViewModel.kt               310   modified
ui/screens/SubmitScreen.kt                100   ORPHANED (§3.13)
ui/screens/SubmitViewModel.kt              83   ORPHANED (§3.13)
ui/screens/WorksheetGradingScreen.kt      325   untracked
ui/screens/WorksheetGradingViewModel.kt   284   untracked
ui/theme/{Color,Theme,Type}.kt      10 / 57 / 33
util/ImagePrep.kt                         273   untracked
```

`:extractor` main — 6 files, 746 lines (§7):

```
extractor/ExtractionResult.kt              75
extractor/Layout.kt                        97
extractor/LayoutValidator.kt              136
extractor/OpenCvNative.kt                  32
extractor/PageExtractor.kt                131
extractor/Registration.kt                 275
```

Tests — 15 files, 2,010 lines:

```
app/test/data/local/GradeParsingTest.kt              222
app/test/data/remote/AssignmentDtoParsingTest.kt     186   untracked
app/test/domain/grading/MergeBoxResultsTest.kt       214   untracked
app/test/domain/worksheet/MarkerCornersTest.kt       109   untracked
app/test/domain/worksheet/QuestionResolverTest.kt    279   untracked
app/test/util/ImagePrepTest.kt                       121
app/test/util/ExifOrientationTest.kt                  97
app/test/ExampleUnitTest.kt                           16
app/androidTest/ExampleInstrumentedTest.kt            23
extractor/test/GoldenSampleTest.kt                    93
extractor/test/LayoutValidatorTest.kt                193
extractor/test/MarkerContractTest.kt                  95
extractor/test/PageExtractorTest.kt                  226
extractor/test/SampleFixture.kt                      111
extractor/test/OpenCvTestNative.kt                    25
extractor/test/resources/sample/layout.json                fixture
extractor/test/resources/sample/sample_page.png            fixture
```
## 3.3 Dependency graph (`di/AppContainer.kt`)

`CapstoneApplication.onCreate` builds one `DefaultAppContainer`. It exposes
`authRepository`, `assignmentRepository`, `tokenManager`, `localModelProvider`,
`gradingService`, `worksheetGrader` and `worksheetSession`, all `by lazy`.

```
ApiService ──> AssignmentRepository ──┐
                                      ├──> ScanViewModel, WorksheetGradingViewModel
WorksheetSession ─────────────────────┤
LocalModelProvider ──> LocalGradingService (: GradingService) ──> WorksheetGrader
```

`BASE_URL` now comes from `BuildConfig.BASE_URL`, set per build type in
`app/build.gradle.kts` (`http://localhost:3000/api/` for both, reached over
`adb reverse tcp:3000 tcp:3000`). The previously hardcoded
`http://10.168.26.160:3000/api/` is gone.

OkHttp carries an `HttpLoggingInterceptor(Level.BODY)` and an auth interceptor
that reads the token from DataStore with `runBlocking { tokenManager.token
.firstOrNull() }` and adds `Authorization: Bearer <token>` when non-null.
Timeouts are now named constants: connect 10s, read 60s, write 60s — connect
short so a missing `adb reverse` fails fast, read/write long because crop
uploads are large over USB. Retrofit uses `GsonConverterFactory`.

`localModelProvider` is on the interface with a comment saying it is exposed
only so `ModelTestScreen` can probe the engine directly. `gradingService` is
declared as the `GradingService` interface and constructed as
`LocalGradingService(localModelProvider)`. `worksheetSession` is process-scoped
by contract — one instance is the whole point (§3.14).

## 3.4 Navigation (`MainActivity.kt`)

```
login → register
login → home            (popUpTo login inclusive)
home  → assignment_detail/{assignmentId} → scan/{assignmentId} → grade/{assignmentId}
home  → results
home  → model_test      (marked TEMPORARY in both the route and the button)
```

`scan → grade` carries **only the assignment id**. The crops travel through
`WorksheetSession`, a process-scoped in-memory holder, because they are
megabytes of PNG and the previous `savedStateHandle["photo_bytes"]` hop crossed
a Binder transaction with a ~1 MB process-wide limit — a crash waiting for a big
enough photo. The `submit/{assignmentId}` route is gone; `grade/{assignmentId}`
renders `WorksheetGradingScreen`, and `onDone` pops back to `home`.

`model_test` is reachable only from the `HomeScreen` TopAppBar action labelled
"Model"; both that action and the route carry `// TEMPORARY … Remove with
ModelTestScreen`.

## 3.5 Network layer

`ApiService` declares **8 methods against the server's 15 routes**:

```
POST auth/login              @Body Map<String,String>   → Response<AuthResponse>
POST auth/register           @Body Map<String,String>   → Response<AuthResponse>
GET  assignments             ?status=                   → List<AssignmentDto>
GET  assignments/{id}                                   → AssignmentDto
GET  submissions/me                                     → List<SubmissionDto>
GET  submissions/me/{id}                                → SubmissionDto
POST submissions             @Multipart PartMap + Parts → Response<SubmissionDto>
POST submissions/{id}/grade  @Body SubmissionGradeRequest → Response<GradeDto>
```

Not declared: `POST assignments`, `POST assignments/import`,
`GET submissions/assignment/{id}`, `GET grades/me`,
`GET grades/assignment/{id}`, `PATCH grades/{id}/override`, `GET health`.

DTOs:

- `QuestionDto` — `id`, `questionText`, `marks: Int?`, `modelAnswer: String?`,
  `rubric: String?`, `externalAnswerBoxId: String?`. The four nullable fields
  are served by the detail endpoint only; null means "not fetched on this path",
  which is why nothing defaults them.
- `AssignmentDto` — `id`, `title`, `description: String?`, `totalMarks`,
  `questions: List<QuestionDto>?`, `submissionStatus: String?`,
  `externalQuestionId: String?`, `layout: LayoutDto?`.
- `LayoutDto` / `MarkerContractDto` / `LayoutAnswerBoxDto` — §8.5.
- `SubmissionAnswerDto` — `id`, `questionId`, `answerText`,
  `answerImagePath: String` (non-null against a nullable column).
- `SubmissionDto` — `id`, `assignmentId`, `studentId`, `submittedAt`,
  `answers?`, `grades?`, `status?`.
- `GradeDto` — `id`, `submissionId`, `assignmentId`, `grade: String`,
  `feedback: String`. Still divergent from the server (§4.2).
- `SubmissionGradeRequest` / `GradedAnswerRequest` — the grade call body (§4.4),
  with the server's constraints written out in the KDoc.
- `AuthResponse`, `UserDto`, `ErrorResponse`, and `ServerError` /
  `ServerErrorIssue` — the latter pair models both server error shapes with
  every field nullable.

`AuthRepository` saves the token on success and, on failure, builds a message
through `describeFailure()`: always names the HTTP status, flattens Zod
`issues[]` into `path: message` pairs, falls back to the raw body truncated at
300 chars. `register()` hardcodes `"role" to "student"`.

`AssignmentRepository`:

- `getAssignments()` / `getAssignmentDetail(id)` share one `toDomain()` mapper,
  which now carries `marks`, `modelAnswer`, `rubric` and `externalAnswerBoxId`
  through, and builds `Assignment.layout` via `toExtractorLayout` (§8.5).
- `getMySubmissions()` maps to `Submission(id, assignmentId, status ?: "pending",
  submittedAt)`.
- `getMyGrades()` calls `getMySubmissions()` and flattens `dto.grades`; there is
  still no `GET grades/me` client method.
- **`submitAnswers(assignmentId, answers: List<AnswerUpload>) : Result<Int>`** —
  builds the multipart body (`assignment_id`, an `answers` JSON array, one
  `image_<question_id>` part per upload) and **returns the submission id**. A
  2xx with an unparsable body is an explicit failure, because without the id
  there is nothing to attach a grade to. `AnswerUpload` overrides
  `equals`/`hashCode` to identity, since it holds a `ByteArray`.
- **`submitGrade(submissionId, obtainedMarks, feedback, confidence,
  transcriptions) : Result<Unit>`** — posts `SubmissionGradeRequest` with
  `graded_by = "local_model"` hardcoded as the only value the app is entitled to
  write, and treats `isSuccessful` (201 or 200) as success.
- The old `submitAssignment(Int, Map<Int, Uri>)` stub and
  `submitAssignmentWithBytes` are both gone.

Domain models: `Question(id, text, marks?, modelAnswer?, rubric?,
externalAnswerBoxId?)` with an `isGradeable` computed property;
`Assignment(id, title, description?, questions, isCompleted, externalQuestionId?,
layout: Layout?)`; `Submission(id, assignmentId, status, submittedAt)`;
`Grade(id, submissionId, assignmentId, grade: String, feedback: String)`.
## 3.6 Screens and ViewModels

- **Login / Register** — both run navigation inside `LaunchedEffect(uiState)`,
  with a comment noting that calling it from the composition body fires on every
  recomposition.
- **Home** — two tabs (Pending / Completed) computed in `HomeViewModel` by
  intersecting assignment ids with submission ids from two parallel `async`
  calls; the error state names the underlying exception message.
- **AssignmentDetail** — renders title, `description.orEmpty()`, and a
  `LazyColumn` of `question.text`. `QuestionItem` contains a stray
  `PaddingValues(16.dp)` expression statement whose value is discarded.
- **Scan** — CameraX only (`PreviewView` + `ImageCapture`, `DEFAULT_BACK_CAMERA`),
  writes to `cacheDir/capture-<millis>.jpg` and reads the raw bytes back.
  `ImageCapture.OnImageSavedCallback.onError` is `= Unit`, so a failed capture is
  silently ignored. Requests `CAMERA` at runtime; there is no gallery path.
- **Submit** — shows the decoded photo and one "Upload and Submit" button, which
  calls `viewModel.submit(questions.map { it.id })`. It reaches its own
  `AssignmentDetailViewModel` for the question list.
  `SubmitScreen.kt:95-97` calls `onSubmissionSuccess()` from the `Column` body
  when the state is `Success`, i.e. during composition rather than from a
  `LaunchedEffect` — the one screen where that pattern remains.
- **Result** — lists `Grade` cards rendering `"Grade: ${grade.grade}"` and
  `grade.feedback`.

Nothing in any of these screens references `GradingService`, `ImagePrep`,
`LocalModelProvider` or a grade upload.

## 3.7 Grading interface and implementation

`domain/grading/GradingService.kt`:

```kotlin
interface GradingService {
    suspend fun grade(
        photoPng: ByteArray,
        questionText: String,
        modelAnswer: String,
        rubric: String?,
        maxMarks: Int
    ): GradeResult                    // documented as never throwing

    suspend fun gradeRaw(             // debug: no extraction, no parsing, no trimming
        photoPng: ByteArray,
        questionText: String,
        modelAnswer: String,
        maxMarks: Int
    ): String
}

data class GradeResult(
    val transcription: String,
    val legible: Boolean,
    val marks: Int,                   // documented as within 0..maxMarks
    val certainty: Double,            // 0.0..1.0
    val feedback: String,
    val needsManualReview: Boolean    // when true, `marks` carries no meaning
)
```

**One implementation exists: `data/local/LocalGradingService.kt`.** There is no
cloud implementation, no fake, and no test double.

- Sampler: `SamplerConfig(topK = TOP_K 5, topP = TOP_P 0.95, temperature =
  TEMPERATURE 0.1)`.
- A **fresh `Conversation` per inference**, closed via `use`, so no context
  carries between questions.
- Retry policy: exactly two attempts — the base prompt, then the base prompt plus
  `RETRY_REMINDER`. Inference exceptions are logged and the loop continues;
  `CancellationException` is rethrown. After both attempts fail →
  `manualReviewResult()`.
- `runPrompt` always sends `Contents.of(Content.ImageBytes(photoPng),
  Content.Text(prompt))` — image first, text last, with a comment attributing the
  ordering requirement to Gemma 3n. The image is attached unconditionally,
  independent of `spec.supportsVision`.

The prompt built by `buildPrompt()` (the rubric line is omitted when the rubric
is null or blank):

```
You are marking one handwritten answer from a photo of a student worksheet.

Question: $questionText
Correct answer: $modelAnswer
Marking rubric: $rubric          ← only when rubric is not null/blank
Maximum marks: $maxMarks

Do this:
1. Read the handwriting in the image and transcribe the student's answer exactly as written.
2. If the handwriting is unclear, cut off, or you are not certain what it says, set "legible" to false and do NOT guess. Never invent an answer you cannot actually read.
3. Compare the transcription with the correct answer and award a whole number of marks from 0 to $maxMarks.
4. Write one short sentence of feedback addressed to the student.
5. Set "certainty" to how confident you are in this mark, from 0.0 to 1.0.

Reply with ONLY this JSON object. No preamble, no explanation, no markdown code fences:
{"transcription":"...","legible":true,"marks":0,"certainty":0.0,"feedback":"..."}
```

`RETRY_REMINDER`:

```
Your previous reply could not be parsed. Reply with the single JSON object and nothing else. It must start with { and end with }.
```

**JSON extraction** — `extractFirstJsonObject(raw)`: a hand-written brace-balance
scan from the first `{`, tracking `inString` and backslash escapes, returning the
first balanced object. Not a regex, so it survives markdown fences, preambles,
trailing commentary and braces inside string values.

**Parsing** — `parseGrade(raw, maxMarks)`: Gson into a private `GradeDto` whose
fields are all nullable. Returns `null` (→ retry, then manual review) when there
is no JSON object, when Gson throws, or when `marks` is absent — a missing mark
is treated as a parse failure, never a silent zero. `marks` is `Double?` because
models emit `2.0` and `"2"`. Then `marks.roundToInt().coerceIn(0, maxMarks)`,
`certainty.coerceIn(0.0, 1.0)`, `legible = dto.legible ?: false`,
`needsManualReview = !legible`.

`manualReviewResult()` returns `marks = 0` with a comment stating the zero is a
placeholder for a non-null Int, not a grade the model gave;
`needsManualReview` is the signal.

Both `parseGrade` and `manualReviewResult` are `internal` on the companion
object, which is how `GradeParsingTest` reaches them without an engine.

## 3.8 Model registry — `data/local/ModelSpec.kt`

An enum with two entries and no other source of model configuration:

```kotlin
LLAVA_OV_05B(
    fileName       = "LLaVA-OneVision-0.5B.litertlm",
    displayName    = "LLaVA-OneVision 0.5B (multimodal, small)",
    supportsVision = true,
    minRamGb       = 4,
    maxNumTokens   = 2048,        // commented: this model spends 730 tokens per image
    expectedBytes  = 829_262_144L
),
GEMMA3_1B(                        // documented as a text-only fallback for isolating
    fileName       = "gemma3-1b-it-int4.litertlm",   // engine faults from vision faults
    displayName    = "Gemma 3 1B (text only)",
    supportsVision = false,
    minRamGb       = 6,
    maxNumTokens   = 1024,
    expectedBytes  = 584_417_280L
);
```

- `MIN_VISION_TOKENS = 1500`; `hasTightVisionBudget = supportsVision &&
  maxNumTokens < MIN_VISION_TOKENS`. The companion `init` block logs a warning
  for every entry that trips it. **Neither current entry does** — LLaVA has 2048
  tokens, and Gemma3-1B is not a vision model.
- `minRamGb` is documented as advisory; nothing reads it except the debug
  screen's status dump.
- `DEFAULT = LLAVA_OV_05B`, mirrored by `LocalModelProvider.DEFAULT_SPEC`.
- `expectedBytes` is documented as guarding against a truncated `adb push`,
  because native LiteRT-LM tends to abort rather than throw on one.

## 3.9 Engine lifecycle — `data/local/LocalModelProvider.kt`

- `MODEL_DIR = "/data/local/tmp/llm"`, a companion constant and the single source
  of truth for `modelFile()`, `isModelPresent()` and `modelSizeBytes()`. Its
  KDoc states it is a DEV-ONLY path: outside the app sandbox, shared with other
  apps' debug tooling, and chosen because Android 11+ scoped storage blocks the
  adb shell user from writing into the app's external files dir.
- Exactly **one `Engine` per process**, created lazily by `initialize()` under
  `initMutex` with a double-check inside the lock. Construction runs on
  `Dispatchers.IO`. A failed `initialize()` calls `close()` on the half-built
  engine before rethrowing.
- `EngineConfig(modelPath = file.absolutePath, backend = Backend.CPU(),
  visionBackend = Backend.GPU(), maxNumTokens = spec.maxNumTokens, cacheDir =
  context.cacheDir.path)`.
- `createEngine()` runs two `check()`s **before** touching native code:
  `status.usable`, then `status.sizeMatchesExpected`.
- `withInference { }` serializes every decode behind `inferenceMutex` on
  `Dispatchers.IO`.
- `release()` runs under `NonCancellable`, takes `initMutex` then
  `inferenceMutex`, nulls the field before closing, and is idempotent.
- `useSpec(newSpec)` returns early when unchanged, otherwise releases first and
  then swaps the spec; the replacement engine is built lazily on the next
  `initialize()`.
- `runRawPrompt(prompt, imagePng?)` is a debug helper documented for
  `ModelTestScreen`. It inlines the sampler values `5 / 0.95 / 0.1` as literals
  rather than sharing `LocalGradingService`'s constants, and attaches the image
  only when one is passed.
- `Message.textOrEmpty()` — an `internal` extension that flattens `Content.Text`
  parts with no trimming.

`ModelFileStatus` records `path`, `expectedBytes`, `parentExists`,
`parentTraversable`, `exists`, `isFile`, `readable` and `sizeBytes` as separate
facts, with `usable`, `sizeMatchesExpected` and a `diagnosis` string that names
the most likely cause and the command that addresses it. The separation is
documented as necessary because `File.isFile` returns false both for an absent
file and for one the app's uid cannot stat. Logging: `I/LocalModelProvider: model
OK: …` or `W/LocalModelProvider: model NOT usable: <status> -- <diagnosis>`.

## 3.10 Image preprocessing — `util/ImagePrep.kt`

`object ImagePrep`, `MAX_LONG_EDGE = 1024`.
`toGradingPng(source: ByteArray, maxLongEdge: Int = MAX_LONG_EDGE): ByteArray?`:

1. Pass 1 — `inJustDecodeBounds = true`; returns null when the bounds come back
   non-positive.
2. Pass 2 — decode with `inSampleSize` (`calculateInSampleSize` picks the largest
   power of two keeping the long edge ≥ target) and
   `inPreferredConfig = ARGB_8888`.
3. EXIF orientation applied **before** resizing, so the resize sees the true
   edges.
4. `scaleToLongEdge` — exact downscale to the long edge, aspect preserved,
   **never upscales**.
5. `Bitmap.CompressFormat.PNG`. The header comment states PNG rather than JPEG
   because JPEG ringing destroys thin pen strokes.

Returns `null` rather than throwing for empty input, undecodable data, a failed
compress, or `OutOfMemoryError` (caught at three separate points; the rotate and
scale helpers fall back to the untransformed bitmap rather than failing).
Intermediate bitmaps are recycled.

`orientationTransform(Int): OrientationTransform?` is an `internal` pure lookup
over the eight EXIF tags, split out so it can be tested without a graphics
stack; `ORIENTATION_NORMAL`, `ORIENTATION_UNDEFINED` and anything unexpected map
to `null`. `exifMatrix` applies rotation first, then flips.

## 3.11 `ModelTestScreen` / `ModelTestViewModel` — debug harness

Both files open with a comment marking them temporary and not part of the
student flow.

Model picker: one `FilterChip` per `ModelSpec.entries`; selecting one calls
`useSpec`, which releases the engine.

One picker button — "Pick image from gallery" / "Pick a different image", using
`ActivityResultContracts.PickVisualMedia` (no storage permission). The picked
bytes go through `ImagePrep.toGradingPng` before anything else sees them.

Six probes, in the order laid out on screen:

| | left | right |
|---|---|---|
| row 1 | Check model file | Load engine |
| row 2 | Text only test | Describe image |
| row 3 | Transcribe handwriting | Grade it |

- "Check model file" dumps every `ModelFileStatus` field plus the diagnosis, off
  the main thread.
- "Load engine" calls `initialize()`.
- "Text only test" sends `"Reply with exactly the two characters: OK"` with no
  image.
- "Describe image" uses `DESCRIBE_PROMPT = "Describe what you see in this
  image."`.
- "Transcribe handwriting" is a **pure OCR probe**: it calls
  `LocalModelProvider.runRawPrompt` with `TRANSCRIBE_PROMPT` and does not go
  through `GradingService`. Its KDoc explains why — every grading prompt contains
  the expected answer and the marks available, so a model handed the answer key
  can reproduce it without reading a pen stroke, making the result worthless as
  transcription evidence.
- "Grade it" is the only probe that calls `gradingService.grade()`, and the only
  one that deliberately shows the model the expected answer. Its output is a
  harness-formatted summary of the parsed `GradeResult`.

Test constants, used only by "Grade it": `TEST_QUESTION = "What is 7 x 8?"`,
`TEST_MODEL_ANSWER = "56"`, `TEST_MAX_MARKS = 5`.

Output pane: `rawOutput` is `String?`, where null means no probe has completed —
kept distinct from a probe that returned `""`. `outputSource` is an
`OutputSource` enum (`NONE` / `MODEL` / `HARNESS` / `ERROR`) and the heading
names the source: `MODEL OUTPUT - verbatim, N chars` / `HARNESS OUTPUT -
composed by the app, not the model` / `HARNESS ERROR - stack trace` / `Output -
nothing run yet`. An empty model response gets an explicit error-coloured line.
The pane applies no `ifBlank`, no fallback text and no trimming. Alongside it:
a status line, `elapsed: N ms`, and a `LinearProgressIndicator` while busy.
`launchOperation` ignores taps while another probe runs and routes any
`Throwable` into the pane as a stack trace.

## 3.12 Test coverage — 126 unit tests, all passing (VERIFIED)

| Module | Suite | Tests | Kind | Covers |
|---|---|---:|---|---|
| `:app` | `data/local/GradeParsingTest` | 19 | plain JVM | `LocalGradingService.parseGrade` + `manualReviewResult` |
| `:app` | `data/remote/AssignmentDtoParsingTest` | 7 | plain JVM | Gson over a hand-written `AssignmentDto` + `LayoutDto` fixture |
| `:app` | `domain/grading/MergeBoxResultsTest` | 15 | plain JVM | `mergeBoxResults` |
| `:app` | `domain/worksheet/MarkerCornersTest` | 8 | plain JVM | `MarkerCorners.describe` / `.sentence` |
| `:app` | `domain/worksheet/QuestionResolverTest` | 16 | plain JVM | `QuestionResolver.forAssignment` + `.resolve` |
| `:app` | `util/ImagePrepTest` | 7 | Robolectric, `@Config(sdk=[34])`, `@GraphicsMode(NATIVE)` | `ImagePrep.toGradingPng` |
| `:app` | `util/ExifOrientationTest` | 11 | plain JVM | `ImagePrep.orientationTransform` |
| `:app` | `ExampleUnitTest` | 1 | plain JVM | `2 + 2 == 4` placeholder |
| **`:app` total** | | **84** | | 0 failures, 0 errors |
| `:extractor` | `GoldenSampleTest` | 3 | JVM + openpnp OpenCV | registration against the real ArUco sample page |
| `:extractor` | `LayoutValidatorTest` | 20 | plain JVM | every `LayoutValidator` refusal, by message |
| `:extractor` | `MarkerContractTest` | 4 | JVM + openpnp OpenCV | measured displacement from a wrong marker contract |
| `:extractor` | `PageExtractorTest` | 15 | JVM + openpnp OpenCV | `extractPage` result cases and crop geometry |
| **`:extractor` total** | | **42** | | 0 failures, 0 errors |
| **grand total** | | **126** | | **0 failures, 0 errors** |

`GradeParsingTest` cases: clean JSON; markdown fences; chatty preamble; trailing
commentary; two objects (first wins); braces inside string values; truncated
JSON; malformed JSON; missing `marks` field yields no grade rather than a silent
zero; empty/whitespace/prose response; the unparseable fallback flagged for
manual review with no marks; marks above max clamped; negative marks clamped to
zero; fractional marks rounded then clamped; certainty above 1 and below 0
clamped; illegible answer flagged; illegible-with-confident-marks still flagged;
missing `legible` field flagged.

`QuestionResolverTest` covers the six `ResolverCreation.Unavailable` cases (no
external question id, no questions, no box ids, partially linked, duplicate box
id) and the four `Resolution.Failed` cases (crop from another question, crop
with no question, question with no crop, duplicate crop), plus the ordering
guarantee that resolution comes back in question order regardless of crop order.

`MergeBoxResultsTest` pins the four merge rules: marks summed with review boxes
contributing zero, confidence as the minimum rather than the mean, feedback
joined one line per box, and one review box flagging the whole submission — plus
the empty-input case, which is flagged rather than reported as a zero-mark
worksheet.

`ImagePrepTest` cases: 4000×3000 → 1024 long edge; aspect ratio preserved;
800×600 left alone (never upscaled); portrait 3000×4000 keeps its long edge on
the height; no output edge exceeds the limit across a set of sizes; output is
valid PNG bytes; undecodable input returns null instead of throwing. Robolectric
runs with native graphics so the bytes are produced by real Skia encode/decode.

`ExifOrientationTest` pins all eight defined EXIF tags individually, plus
normal, undefined, out-of-range, and a property test that every defined tag maps
to a quarter turn.

The `:extractor` suites are detailed in §7.6.

`androidTest/ExampleInstrumentedTest` — 1 test, requires a device, **never run**.
## 3.13 Dead and deliberately retained code

- **`SubmitScreen.kt` (100 lines) and `SubmitViewModel.kt` (83 lines) are
  orphaned.** The `submit/{assignmentId}` route was replaced by
  `grade/{assignmentId}`; grep finds no reference to either symbol outside the
  two files. They still compile. `SubmitViewModel` was modified in this branch
  before being orphaned.
- **`ScanScreen.CameraPreview` (lines 295-359) is unreachable.** A private
  composable with no caller. The `androidx.camera.*` dependencies and the
  `CAMERA` permission plus `uses-feature android.hardware.camera` in the
  manifest are all still declared for it. `ScanViewModel`'s KDoc explains the
  choice — registration needs four sharp corner markers at full resolution, and
  the phone's own camera app has the framing aids, focus tap and review step
  this screen does not — but the code and the permission were left in place
  rather than removed. `ImageCapture.OnImageSavedCallback.onError` inside it is
  still `= Unit`.
- **`GradingService.gradeRaw` has no callers.** Declared on the interface and
  implemented in `LocalGradingService`; nothing invokes it. The debug probe that
  used to call it now uses `runRawPrompt` with a grading-free prompt (§3.11).
- **`ModelTestScreen` / `ModelTestViewModel` and the `model_test` route** —
  explicitly marked temporary in four places (both file headers, the HomeScreen
  button, the NavHost route).
- `ModelTestViewModel` imports `LocalModelProvider` and talks to it directly,
  which its KDoc calls a deliberate exception for a throwaway screen.
- `AppContainer.localModelProvider` is on the interface with a comment stating
  it is exposed only for that screen and that production code must not use it.
- `QuestionItem` in `AssignmentDetailScreen` evaluates and discards a
  `PaddingValues(16.dp)`.
- Two compiler deprecation warnings survive: `HomeScreen.kt:45` (`TabRow`, now
  `PrimaryTabRow`/`SecondaryTabRow`) and `ScanScreen.kt:300`
  (`LocalLifecycleOwner`, moved to `androidx.lifecycle.compose`) — the latter
  inside the dead `CameraPreview`.
- Parsed and never read on the layout path: `LayoutDto.layoutVersion`,
  `MarkerContractDto.{arucoDict, markerSizePx, markerMarginPx, source}`,
  `LayoutAnswerBoxDto.{orderIndex, label, points}` (§4.2).

## 3.14 The worksheet grading path (`domain/worksheet`, `domain/grading`)

Five files, 651 lines, all untracked, all new since the previous audit. This is
where extraction output becomes an upload.

**`WorksheetSession` (51 lines)** — a `@Synchronized` in-memory holder scoped by
assignment id, replacing the `SavedStateHandle` photo hop (§3.4). `crops(id)`
returns null when nothing is held or when what is held belongs to a different
assignment. It does **not** survive process death, and the grading screen
reports that as "scan again" rather than grading stale crops.

**`QuestionResolver` (265 lines)** — the crop-to-question join. Fully described
in §8.6.

**`MarkerCorners` (59 lines)** — turns `MarkersNotFound(missingIds)` into a
sentence a student can act on ("The top-left corner marker is not visible."),
by reading each marker's centre off the layout rather than from an id-to-corner
table. An id the layout does not name comes back as `"marker <id>"` rather than
being given a guessed corner.

**`WorksheetGrade` + `mergeBoxResults` (163 lines)** — per-box results merged
into the one row the server stores. `BoxOutcome` is a two-case sealed interface:
`Scored(marks, certainty, transcription, feedback)` and
`NeedsReview(reason, transcription, feedback)`. Keeping them distinct is the
whole point of the file — a box the model scored zero and a box nobody could
score are both worth zero marks, and must never look the same anywhere else.

The four merge rules, each pinned by `MergeBoxResultsTest`:

| Rule | Why |
|---|---|
| Marks summed; a review box adds 0 | The submission has one mark total; there is no other arithmetic available. The zero is not a judgement, which is why `needsManualReview` travels with it. |
| Confidence is the **minimum**, not the mean | A mean lets nine confident boxes bury the one the model was guessing at. A review box contributes 0.0. |
| Feedback joined one line per box, each naming its question | The single stored string can still be read back apart. |
| **Any** box needing review flags the submission | Not a majority, not a threshold. |

An empty box list is a flagged worksheet with `"No answers were graded."`, not a
zero-mark one.

**`WorksheetGrader` (113 lines)** — grades `List<ResolvedAnswer>` as a cold
`Flow<BoxResult>`, strictly sequential, sorted by `crop.orderIndex`, on
`Dispatchers.Default`. Sequential deliberately: `LocalModelProvider
.withInference` already serialises every decode behind a mutex, so parallelism
would buy nothing while holding several decoded bitmaps alive at once.

`gradeOne` has two pre-model refusals, both returning `NeedsReview` rather than
a zero — `!question.isGradeable` (no mark ceiling or a blank model answer) and
`ImagePrep.toGradingPng` returning null. **The first of these currently fires
for every imported question; see §6.2.**

**`WorksheetGradingViewModel` (284 lines)** — collects the flow, rendering each
`BoxResult` as it arrives. Its `uiState` has eight cases (`Preparing`,
`Grading`, `Graded`, `Uploading`, `Submitted`, `Cancelled`, `Failed`,
`UploadFailed`). Cancelling stops the collection between boxes; the box in
flight finishes, because interrupting a LiteRT-LM decode is not something the
API offers. `upload()` is reachable only from `Graded` and `UploadFailed`, so a
cancelled run can never be uploaded as if complete. A retry re-sends the same
marks and never re-runs the model. Upload order is forced by the server:
`submitAnswers` first for the id, then `submitGrade`; `WorksheetSession.clear()`
only after the grade lands.
---

# 4. Client ↔ server contract, as coded

Divergences between what the server sends and what the client declares. These
are statements about current behaviour, not a defect list. Re-read against both
sides this audit.

## 4.1 Resolved since the last audit

| Was | Now |
|---|---|
| `QuestionDto` had only `id` + `questionText`, so `marks` / `model_answer` / `rubric` were discarded | `QuestionDto` declares all four plus `external_answer_box_id`; `AssignmentRepository` maps every one onto `domain.Question` |
| `submitAssignmentWithBytes` returned `Result<Unit>` and threw the submission id away | `submitAnswers` returns `Result<Int>` and fails explicitly when the body carries no id |
| One camera photo duplicated into every question's image part | One rectified crop per question, named `<external_answer_box_id>.png`, declared `image/png` |
| No client for `POST /submissions/:id/grade` | `ApiService.submitGrade` + `AssignmentRepository.submitGrade`, treating 201 and 200 alike |
| No per-submission aggregation | `mergeBoxResults` (§3.14), 15 tests |

## 4.2 Still divergent

| Server sends | Client declares | Consequence in the code as written |
|---|---|---|
| Grade row with `obtained_marks: Int`, `confidence`, `graded_by` | `GradeDto` declares `grade: String`, plus `feedback` only | No `grade` key exists in the response, so Gson leaves the field null; `ResultScreen` renders `"Grade: ${grade.grade}"` as `"Grade: null"`. `confidence` and `graded_by` are not modelled. **Unchanged from the previous audit.** |
| `layout.layout_version: Int` | `LayoutDto.layoutVersion`, documented as a refusal signal | Parsed and never read. A version 2 layout would be consumed as if it were version 1. |
| `layout.markers.{aruco_dict, marker_size_px, marker_margin_px, source}` | `MarkerContractDto` declares all four | None is read. `Registration` hardcodes `DICT_4X4_50`; the size/margin are already baked into `centres` server-side, and `source` — which distinguishes a served contract from an unchecked computed one — is discarded rather than surfaced. |
| `layout.answer_boxes[].{order_index, label, points}` | `LayoutAnswerBoxDto` declares all three | None is read. `order_index` is re-derived from array position by the extractor, which `LayoutValidator` then cross-checks against `(page_index, bbox.y)`. |
| `answer_image_path: string \| null` | `SubmissionAnswerDto.answerImagePath: String` (non-null) | Gson can write null into the non-null field. |
| `description: string \| null` | `AssignmentDto.description: String?` throughout, `.orEmpty()` at both render sites | Handled. |

## 4.3 The multipart shape the client now sends

```
assignment_id : 12
answers       : [{"question_id":41,"answer_text":""},{"question_id":42,"answer_text":""}]
image_41      : <rectified crop for the box question 41 is linked to>   ab_xxx.png, image/png
image_42      : <rectified crop for the box question 42 is linked to>   ab_yyy.png, image/png
```

`answer_text` is empty on purpose: the transcription does not exist until
grading has run, and it is written afterwards by the grade call.

## 4.4 The grade call

```jsonc
POST /api/submissions/{id}/grade
{
  "obtained_marks": 7,            // sum over Scored boxes; review boxes add 0
  "feedback": "Q41 (3/5): …\nQ42 (not marked - needs review): …",
  "confidence": 0.0,              // MINIMUM over boxes; 0.0 if any needs review
  "graded_by": "local_model",     // the only value the app writes
  "answers": [ { "question_id": 41, "transcription": "…" }, … ]
}
```

Two constraints the client must satisfy and does, by construction:

- **`obtained_marks` must not exceed `assignment.total_marks`** (400 otherwise).
  `WorksheetGrade.obtainedMarks` sums `question.marks`, and at import
  `assignment.total_marks` is the sum of the same per-box `points`. The two can
  only diverge if either value is edited afterwards, and no route exists that
  can edit either (§1 NOT PRESENT). `MergeBoxResultsTest` pins the invariant.
- **Every `question_id` must already have an Answer row.** Guaranteed by
  `WorksheetGradingViewModel` ordering `submitAnswers` before `submitGrade` and
  building both lists from the same resolved set.
---

# 5. LiteRT-LM API surface actually called

`com.google.ai.edge.litertlm:litertlm-android:0.16.1`. Every signature below is
called from `LocalModelProvider` or `LocalGradingService` and therefore exists —
`compileDebugKotlin` succeeds against the real artifact (VERIFIED).

```kotlin
Backend.CPU()                                    // no-arg form used
Backend.GPU()                                    // no-arg

EngineConfig(                                    // named arguments used
    modelPath     = …,
    backend       = …,
    visionBackend = …,
    maxNumTokens  = …,
    cacheDir      = …
)

Engine(config)          : AutoCloseable
Engine.initialize()                              // blocking; called on Dispatchers.IO
Engine.createConversation(ConversationConfig)
Engine.close()

SamplerConfig(topK = Int, topP = Double, temperature = Double)
ConversationConfig(samplerConfig = …)            // only this parameter is passed

Contents.of(vararg Content)
Content.Text(text: String)
Content.ImageBytes(bytes: ByteArray)

Conversation.sendMessage(contents: Contents): Message   // blocking
Conversation.close()                                    // via `use`

Message.contents.contents : List<Content>
```

Not called anywhere in the app: `sendMessageAsync` in either form, `Session` /
`SessionConfig`, `ResponseFormat`, `ThinkingConfig`, `LoraConfig`, tool calling,
`Engine.isInitialized()`, `getBenchmarkInfo()`, `getTokenCount()`,
`cancelProcess()`, `Backend.NPU`, `Backend.GOOGLE_TENSOR`, `Content.ImageFile`,
`Content.AudioBytes`, `Content.AudioFile`, `audioBackend`, `maxNumImages`.

---

# 6. Where the on-device path stops

Stated as reachability, not as a plan. Re-traced this audit.

## 6.1 The path is now wired end to end

`home → assignment_detail → scan → grade` exists in `MainActivity` and every hop
is implemented:

```
ScanScreen          PickVisualMedia → ImagePrep.toRegistrationPng (orientation only)
                    → OpenCvNative.load() → PageExtractor.extractPage
                    → ScanUiState.Extracted(crops)
                    → WorksheetSession.put(assignmentId, crops)
WorksheetGrading    → WorksheetSession.crops(assignmentId)
                    → QuestionResolver.forAssignment(assignment).resolve(crops)
                    → WorksheetGrader.grade(answers) : Flow<BoxResult>
                       → ImagePrep.toGradingPng(crop.png) → GradingService.grade
                    → mergeBoxResults(collected) : WorksheetGrade
                    → AssignmentRepository.submitAnswers  → submission id
                    → AssignmentRepository.submitGrade    → 201/200
                    → WorksheetSession.clear()
```

The `submit/{assignmentId}` route and the `photo_bytes` `SavedStateHandle` hop
are gone. `ImagePrep`, `GradingService`, `LocalModelProvider` and the extractor
are all reachable from the student flow, not only from `ModelTestScreen`.

## 6.2 Where it actually stops today

**On `model_answer`.** `POST /assignments/import` is the only way an assignment
acquires a layout, and it creates every Question with `model_answer = ""`. No
route in the API can ever set it (§1 NOT PRESENT — VERIFIED by enumerating all
15 `Router.<verb>` registrations). So for every assignment the scan screen will
accept:

```
Question.isGradeable == (marks != null && !modelAnswer.isNullOrBlank())
                     == (points != null && !"".isNullOrBlank())
                     == false
```

`WorksheetGrader.gradeOne` short-circuits on that check and returns
`BoxOutcome.NeedsReview("This question has no marking information yet …")`
**without calling `gradingService.grade` at all**. `mergeBoxResults` then
returns `obtainedMarks = 0`, `confidence = 0.0`, `needsManualReview = true`, and
the upload posts that.

The model is never invoked on the only assignments the pipeline can reach. This
is a wiring gap, not a bug in any one file: `isGradeable` is doing exactly what
it documents, and refusing to grade against a blank model answer is correct. The
missing piece is a teacher-side write path.

**On the absence of an import.** No assignment in any database has ever been
imported (§1 UNVERIFIED), so `Assignment.layout` is null everywhere and
`ScanViewModel.blockingReason` returns "This assignment has no printed worksheet
layout" for every assignment that exists.

## 6.3 Other stopping points, unchanged

- `Grade` carries one mark, one feedback string and one confidence per
  submission, with no `question_id`. Per-question detail survives only in
  `Answer.answer_text` (the transcription) and in `WorksheetGrade.boxes`, which
  lives in memory and is rendered, never persisted.
- `LocalGradingService.runPrompt` attaches the image regardless of
  `spec.supportsVision`; nothing guards a text-only model against an image.
- `WorksheetSession` does not survive process death. The grading screen reports
  that honestly rather than grading stale crops.
- The app photographs one page. Any layout with boxes on more than page 0 is
  blocked before a photo is requested.

---

# 7. `:extractor` — the worksheet registration module

A second Gradle module in `Capstone_Android`, added by
`settings.gradle.kts: include(":extractor")` and consumed by `:app` as
`implementation(project(":extractor"))`. 6 files, 746 lines, no Android
framework dependency beyond the OpenCV AAR's native loader.

It is a Kotlin port of `v-2.1.1/backend/services/extractor.py` — the same ArUco
detection, the same homography/affine solve, the same bbox transform — with two
deliberate departures, both recorded in the source (§7.4).

## 7.1 What it does

Given a `Layout` (canonical page size, four marker centres, a list of answer-box
rectangles) and the bytes of a photograph of that page, it returns one rectified
PNG per answer box.

```kotlin
PageExtractor(inset = Inset.ANSWER_BOX)
    .extractPage(layout, pageIndex, imageBytes, modality = Modality.PHOTO)
    : ExtractionResult
```

`ExtractionResult` is a sealed interface with five cases and **no
success-with-an-error-string case** — `Success(crops)`, `MarkersNotFound(found,
missingIds)`, `InvalidLayout(reason)`, `Undecodable(cause)`,
`RegistrationFailed(reason)`. The Python returns HTTP 200 with an empty crop
list and an `error` field when registration fails; a caller here cannot reach
crops without having handled every way there are none.

`AnswerCrop` carries `externalQuestionId`, `externalAnswerBoxId`, `pageIndex`,
`orderIndex`, the `png` bytes, and `imageQuad` — where the crop was cut from, in
the pixels of the image passed in, as a genuine (non-axis-aligned)
quadrilateral. `imageQuad` is consumed by nothing in the extraction path; the
scan screen draws it as an overlay. Its `PointPx` type deliberately is not
`org.opencv.core.Point`: OpenCV is an `implementation` dependency, so it is
absent from the compile classpath of anything depending on this module.

## 7.2 Build configuration and the OpenCV split

`extractor/build.gradle.kts`: `namespace com.example.capstone.extractor`,
`compileSdk 37`, `minSdk 26`, Java 17, `ndk { abiFilters += "arm64-v8a" }` —
the other three ABIs OpenCV ships would add roughly 120 MB to the APK.

The module depends on **two different OpenCV builds**:

- `org.opencv:opencv:4.11.0` (the Android AAR) as `implementation`, for the
  device.
- `org.openpnp:opencv:4.9.0-0` as `testImplementation`, for JVM unit tests —
  identical `org.opencv.*` API with desktop `.dll`/`.so`/`.dylib` bundled and
  loaded by `nu.pattern.OpenCV.loadLocally()`.

A `configurations.matching { … }` block **excludes the Android AAR from every
`test*CompileClasspath` and `test*RuntimeClasspath`**, because the AAR carries
no host-loadable native library and `System.loadLibrary` would find only Android
`.so` files.

That is why `OpenCvNative` exists as a one-method object with nothing else in
the file: `org.opencv.android.OpenCVLoader` lives only in the AAR, so **nothing
on a test path may reference `OpenCvNative`**. JVM lazy class resolution then
never looks for the missing class. `OpenCvNative.load()` is consequently
**UNVERIFIED** — no test can reach it and no device run is recorded.

`testImplementation(libs.json)` pulls real `org.json` because `android.jar`
stubs it out and `SampleFixture` reads its layout from `sample/layout.json`
rather than transcribing it.

## 7.3 No hardcoded geometry — VERIFIED

The module's central design property. Grepped this audit across
`extractor/src/main` and `app/src/main` for `1240`, `1754`, `60`, `40`,
`marker_margin`, `markerSize`, `DICT_4X4`:

- **Zero page-dimension literals** in either module's main source. Every hit for
  1240/1754/60/40 is in a test file or a test fixture.
- `Layout`, `MarkerRef`, `AnswerBoxRef` and `Bbox` are plain data holders; every
  number arrives as a constructor argument.
- `Registration.registerPage` builds the canonical-to-image correspondence by
  **matching `MarkerRef.id` against the detected id**, walking `layout.markers`
  in whatever order the caller gave. There is no TL/TR/BL/BR table anywhere in
  the module to be wrong about.
- `MarkerCorners` (in `:app`) names corners for the student by reading the
  marker centres off the layout — a marker in the left half is "left" — rather
  than from a lookup table.

Two literals do exist in main source, both deliberate and both worth knowing
about:

| Literal | Where | Status |
|---|---|---|
| `Objdetect.DICT_4X4_50` | `Registration.kt:43` | The ArUco **dictionary**, an encoding rather than page geometry. A marker drawn from a different dictionary does not decode at all, so a mismatch surfaces as `MarkersNotFound` rather than a silent displacement. But the server *does* serve `aruco_dict` and `LayoutModels` *does* parse it, and nothing joins the two — see §4.2. |
| `Inset.ANSWER_BOX = Inset(10, 23, 10, 10)` | `Layout.kt` | **Genuine page geometry, hardcoded.** It is 2 px border + 8 px padding on each edge, plus a ~13 px caption line at the top, read off `v-2.1.1`'s `_PRINT_CSS_TEMPLATE` (audit §2.6). The teacher API serves none of it. A change to that CSS silently changes what fraction of each box is cropped. It is at least a single named constant with its derivation in the KDoc, and it is a `PageExtractor` constructor parameter, so a caller can override it. |

`MarkerContractTest` exists to measure what a wrong contract costs, against the
real sample markers: margin off by 10 px gives ~11 px displacement on every
crop; marker size off by 20 px gives ~10 px; ids 2 and 3 swapped gives ~990 px
with a mirrored homography. **All three still detect 4/4 markers and report
success.** That is the failure mode the whole no-hardcoded-geometry rule is
defending against.

## 7.4 Where it departs from the Python

1. **`warpPerspective`, not a bounding-rect crop.** `_crop_region` takes the
   axis-aligned bounding rectangle of the warped quad and slices it out, which
   keeps perspective distortion and, at mild skew, covers 1.32x the true box
   area. `PageExtractor.cut` instead warps the canonical inset rectangle onto
   its own `[w, h]`, so the crop is rectified and identically sized for every
   photo of the same box. A box running off the frame comes back black-padded
   rather than truncated, so a short crop never means "clipped photo".
2. **The inset is applied in canonical space, before the transform.** Insetting
   the warped quad afterwards would take a different amount off each edge,
   because a photo's scale varies across the page.

Not ported, deliberately: `_check_qr` and `_try_local_registration` (the
generated QR symbols sit at ~1.12 px per module and are undecodable before the
page is printed — confirmed independently by both prior audits), and the
`tablet` modality (no image and no markers, so not extraction).

Added, with no Python equivalent: `LayoutValidator`, and the
`RegistrationFailed` case — the Python cannot detect a degenerate solve and
produces crops from a garbage matrix while reporting `4/4` and `"homography"`.

Native memory is released explicitly throughout: every `Mat`, `MatOfPoint2f` and
`MatOfByte` is closed in a `finally`, `registerPage` releases the transform
before returning geometry rather than a native handle, and `extractPage`
releases the decoded image in a `finally` around the whole body.

## 7.5 `LayoutValidator`

Runs before a photo is even decoded. Returns a reason string or null; a reason
becomes `ExtractionResult.InvalidLayout`, which `ScanViewModel` surfaces as
`Blocked` — another photo will not help.

Checks, in order: positive page size; exactly four markers; marker ids exactly
`{0,1,2,3}`; no three canonical centres collinear (area < 0.5 px²); per box —
unique id, non-negative page index, positive bbox area, origin on the page,
right and bottom edges within the page, and positive area remaining after the
inset; no two boxes overlapping on one page (strict interior intersection, so
edge-to-edge is allowed); and finally **reading order**: sorting by
`(pageIndex, bbox.y)` must reproduce the served array order.

That last check is worth flagging as an operational risk. Array order is the
only ordering signal the teacher API provides, and cross-checking it against
geometry assumes a single-column, top-to-bottom worksheet. **A legitimate
two-column layout, or one whose document order is not vertical, is refused
outright** and the student is told the worksheet's layout is unusable.

`Registration.assertNotCollinear` repeats the collinearity check against the
*detected* centres, with a 1.0 px² epsilon — a photo carries more noise than a
rendered page.

## 7.6 `:extractor` test coverage — 42 tests, all passing (VERIFIED)

- **`GoldenSampleTest` (3)** — the recorded-output test. Runs the ported
  registration over `sample/sample_page.png` with `Inset.NONE` and asserts the
  warped bboxes match the numbers `NOTES.md` §5 recorded from the real Python
  `extract_page` (`[168,600,1128,850]`, `[168,1283,1128,1573]`), within 3 px per
  component; that all four markers are detected; and that detected centres land
  within a couple of pixels of canonical.
- **`MarkerContractTest` (4)** — the displacement measurements in §7.3, plus
  "the correct convention drifts by nothing".
- **`LayoutValidatorTest` (20)** — every refusal above, asserted by message,
  plus the accepted cases: edge-to-edge boxes, boxes at the same position on
  different pages, a box too short for the inset accepted when the inset is
  `NONE`, and the sample fixture passing.
- **`PageExtractorTest` (15)** — every `ExtractionResult` case (`Undecodable`
  from empty bytes and from non-image bytes, `MarkersNotFound` from a blank
  page, `RegistrationFailed` from collapsed centres, `InvalidLayout` refused
  before the decoder is reached, a marker id outside the contract refused), the
  scanner/affine path, crop geometry (a crop is rectified to exactly its inset
  canonical size; no inset means the bbox as served), that crop bytes are a real
  PNG, and that `orderIndex` is the position in the whole array rather than
  within the page.

**The one fixture is a rendered page, not a photograph** (1242x1756 against a
canonical 1240x1754). Everything above is verified arithmetic on a flat page.
Perspective, blur, glare, focus and occlusion remain UNVERIFIED (§1).

`GoldenSampleTest`'s KDoc references a `CropGeometryTest` that does not exist;
the crop-geometry cases it points at live in `PageExtractorTest`.

---

# 8. The layout / import path

How printed-page geometry gets from the teacher worksheet system into a crop.

## 8.1 Vocabulary

The two systems disagree, and every file involved restates the mapping:

```
teacher "question"   == ASC_Capstone Assignment   (Assignment.external_question_id)
teacher "answer box" == ASC_Capstone Question     (Question.external_answer_box_id)
```

## 8.2 Server: `POST /api/assignments/import`

Teacher-only, `importAssignmentSchema` = `{ external_question_id, title?,
description? }`. Everything else is fetched, never posted.

`services/teacher-worksheet.service.ts` (365 lines, untracked) is the only file
that talks to the teacher system. `fetchTeacherQuestion` GETs
`${TEACHER_API_BASE_URL}/api/questions/{id}` with an
`AbortSignal.timeout(TEACHER_API_TIMEOUT_MS)` and maps failures onto 404
(unknown id) and 502 (unreachable, non-JSON, or an unrecognised shape). Both env
vars are new, with defaults `http://localhost:8000` and `10_000`. The Zod schema
is `.passthrough()` so the teacher adding fields cannot break the import.

`toImportableLayout` refuses (400) rather than importing something partial: a
non-`finalized` question (its box ids still churn on every autosave), no answer
boxes, no page size, a duplicate box id, or any box with a null `bbox` or null
`page_index`. It captures each box's **array position as `order_index`** and
computes `pageCount = max(page_index) + 1`.

`resolveMarkers` prefers a `markers` object served by the teacher API and falls
back to `TEMPORARY_markerCentresFromTeacherConstants`, which reproduces his
`DICT_4X4_50` / `MARKER_SIZE_PX = 60` / `MARKER_MARGIN_PX = 40` and his integer
floor `m + s // 2`, bug-for-bug on purpose. Both branches log which one ran, at
`info` and `warn` respectively. The teacher API serves no `markers` today, so
**every import would take the warn branch** and store
`source: "computed_from_constants"` — a value the app parses and never reads
(§4.2).

The whole import is one `prisma.$transaction`:

- **First import** — create the Assignment (`total_marks` = sum of `box.points`),
  then create Questions **one at a time in served array order** so that
  ascending question id is teacher document order, each with `question_text` =
  the box label or `"Answer box <id>"`, `marks = box.points`,
  `question_type: "short_answer"`, `model_answer: ""`, `rubric: null`; then
  create the Layout row. **201.**
- **Re-import, same box id set** — refresh the Layout geometry only. Question
  rows are untouched because they hold marking data the teacher typed. **200.**
- **Re-import, changed box id set** — **409** carrying `added` / `removed` /
  `unchanged` id lists, via the new `HttpError` `details` payload (spread into
  the JSON body by `error-handler.ts`). Re-linking is refused, never guessed.

The response carries an `import` block with `created`, `page_count`,
`marker_source`, and `questions_awaiting_marking_data` — the question ids with a
blank `model_answer`. On a first import that is **every** question, and §6.2
explains why nothing can currently empty that list.

## 8.3 Schema (migration `20260830120000_teacher_worksheet_join`)

```prisma
Assignment.external_question_id      String? @db.VarChar(64)
Question.external_answer_box_id      String? @db.VarChar(64)

model Layout {                       // @@map("layouts")
  id, assignment_id Int @unique, page_w_px, page_h_px,
  aruco_dict String @db.VarChar(64), markers Json, answer_boxes Json,
  layout_version Int @default(1), created_at, updated_at
  assignment Assignment @relation(..., onDelete: Cascade)
}

@@unique([teacher_id, external_question_id])           // on Assignment
@@unique([assignment_id, external_answer_box_id])      // on Question
```

Both new unique indexes are **composite on purpose**. The assignment one is
scoped to the teacher because two teachers may import the same external
question, and it is what makes re-import detection well defined — at most one
assignment per (teacher, external question), so the route can find the previous
import instead of guessing. The question one exists because on the teacher side
a bare `answer_box.id` is globally unique today only in the sense that a
collision crashes his insert rather than coexisting; if that is ever fixed with
a composite key, bare box ids stop being unique and any single-column join here
would start joining across worksheets with no visible symptom. Postgres treats
NULLs as distinct, so locally created rows are unconstrained.

The Layout schema comment describes `bbox` as `[x,y,w,h] | null`; that reflects
the teacher API's shape, not what is stored — `toImportableLayout` rejects a
null bbox with a 400, so every stored box has four numbers.

**The pair is used everywhere the join happens — VERIFIED.** Every occurrence of
`external_answer_box_id` in `server/src`: selected (student detail), written
(import), and read off an already-assignment-scoped `existing.questions` array
during re-import. **There is no query anywhere that looks a question up by box
id, with or without the assignment.** The only `prisma.question` mutation in the
whole codebase is the `tx.question.create` in the import route.

## 8.4 Serving it: student `GET /api/assignments/:id`

The student branch now selects `external_question_id` on the assignment;
`marks`, `model_answer`, `rubric` and `external_answer_box_id` on each question
(`orderBy: { id: "asc" }`); and the `layout` relation (`page_w_px`, `page_h_px`,
`aruco_dict`, `markers`, `answer_boxes`, `layout_version`). `model_answer` and
`rubric` are exposed to the student deliberately — the phone grades on-device
and needs something to grade against. The list endpoint still serves
`question_text` only.

## 8.5 App: wire shape to extractor shape

`data/remote/LayoutModels.kt` (`LayoutDto`, `MarkerContractDto`,
`LayoutAnswerBoxDto`) mirrors the served JSON. `AssignmentRepository`'s private
`LayoutDto.toExtractorLayout(externalQuestionId)` adapts it onto the extractor's
`Layout`:

- Marker centres come from `markers.centres`, keyed by id **as a string**. A key
  that is not an integer, or a centre that is not a pair, is **dropped** — so
  `LayoutValidator` sees a marker set that is short (and refuses it by count)
  rather than one that is silently wrong.
- `answer_boxes` is mapped **in place, never sorted**; array order is load
  bearing and `LayoutValidator` cross-checks it against geometry.
- Nothing is repaired on the way through. A malformed bbox is passed along for
  the validator to refuse, because a layout quietly patched into plausibility
  still registers four markers and still reports success.
- `Assignment.layout` is built only when `externalQuestionId != null` — both
  halves of the join key or neither.

`AssignmentDtoParsingTest` (7 tests) pins this against a hand-written JSON
fixture: page size, dictionary and version parse; all four marker centres parse
in row-major order; answer box array order and geometry are preserved; the
detail response carries the external ids and the marking fields; the list
response leaves unsent fields null rather than defaulting them; and an imported
question with no model answer parses as `""`, not null — the distinction
`Question.isGradeable` turns on.

## 8.6 The join at grading time: `QuestionResolver`

The single place the teacher system's box ids meet this app's question ids.

A resolver is built per assignment and is **scoped to `(assignmentId,
externalQuestionId)`**. `forAssignment` refuses — with a logged reason, never a
partial resolver — when the assignment has no external question id, no
questions, no box ids at all, only *some* box ids, or two questions sharing one
box id (which the server's composite unique index is supposed to prevent; seeing
it means the join is untrustworthy).

`resolve(crops)` returns `Resolved` only when the crop set and the question set
correspond exactly one-to-one **and every crop carries this resolver's external
question id**. A crop whose box id matches but whose question id does not is
rejected. Anything else is `Failed`, naming four disjoint categories —
`cropsFromOtherQuestion`, `cropsWithoutQuestion`, `questionsWithoutCrop`,
`duplicateCropIds` — all reported at once, with a `message` written to be logged
verbatim. There is no silent skip and no partial result, because a caller handed
a shorter list than it asked for is very likely to grade it and report success.

**No single-column lookup exists on the app side either — VERIFIED.**
`externalAnswerBoxId` is read in exactly three places outside the resolver: the
DTO, the domain model, and `WorksheetGradingViewModel`'s upload filename. The
only map keyed by bare box id is `QuestionResolver.questionsByBoxId`, a private
field of an object already scoped to one assignment and one external question.
