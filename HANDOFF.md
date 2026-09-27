# `HANDOFF.md` — student-side integration, handed over

> Written 2026-09-02 for the teammate taking over the student side. Produced by
> reading `CLAUDE.md`, `INTEGRATION_AUDIT.md`, `RUNBOOK.md`,
> `v-2.1.1/TEACHER_NOTES.md`, `Capstone_Android/extractor/NOTES.md` and
> `ASC_Capstone/server/seed-demo.sql` end to end, and by re-checking the live
> state of both repos on this machine.
>
> **No file in any repo was modified. This file is the only addition, and it sits
> at the parent-folder root beside the other four.**
>
> | Tag | Meaning |
> |---|---|
> | **VERIFIED** | Executed and observed, or read directly out of a file, during this handover — or executed on a device by me and recorded here as such |
> | **CODE-READ** | Traced through the source and reasoned about, never executed |
> | **UNVERIFIED** | Written, compiles, but nothing anywhere proves it works |
>
> Section 3 is deliberately self-contained: it is the only part of this file you
> can follow without opening anything else. Everything else cites the other four
> documents by section rather than restating them.
>
> **One thing that supersedes the other documents:** `CLAUDE.md` §1 and
> `RUNBOOK.md` §10 both say the on-device path has never run. That is now out of
> date — see §2.5. Where this file and those disagree about what has executed,
> this file is newer.

---

# 0. Before you start — what to install

Everything below is needed to run the **student side** — the Node server, the
Postgres it talks to, and the Android app — on one machine. His teacher system
(`v-2.1.1/`) is not part of this list and does not need to run; see §1.3.

| Need | Version in use here | Why |
|---|---|---|
| **JDK** | **21.0.5 LTS** (VERIFIED, `RUNBOOK.md` §5) | Gradle and the Kotlin compiler. Android Studio bundles a JetBrains runtime that also works; `gradle/gradle-daemon-jvm.properties` is gitignored precisely so it does not pin you to mine |
| **Android Studio** | any current release | The IDE, the emulator manager, and the SDK installer. Everything here can also be driven from `gradlew` on the command line |
| **Android SDK + platform-tools** | **adb 1.0.41 / platform-tools 37.0.0**, SDK at `%LOCALAPPDATA%\Android\Sdk` (VERIFIED) | `adb` is not optional: it pushes the model files, forwards the server port (`adb reverse tcp:3000 tcp:3000`), pushes the test image and is the only window into logcat |
| **Node + npm** | **v24.19.0 / npm 11.17.0** (VERIFIED) | The `ASC_Capstone` server |
| **Docker Desktop** | **engine 27.2.0** (VERIFIED) | Runs the `capstone-db` Postgres 16 container on `127.0.0.1:5433`. Nothing in either repo defines it — there is no `docker-compose.yml`; §3.1 and `RUNBOOK.md` §6.2 carry the `docker run` that creates it |
| **Python + Pillow** | **3.12.5**, `Pillow>=10.2` | Optional. Only to turn `sample_page.png` into a printable PDF (§3.7) if you want to test against a real photograph rather than a screen |

**`psql` is not required.** `RUNBOOK.md` happens to use a host `psql` 16.3 because
one is installed on this machine, but every query in this handover can go through
the container instead, which ships its own client:

```
docker exec -it capstone-db psql -U capstone -d capstone -c "select 1"
```

## Two things that will cost you a day if you miss them

**1. The model files are not in the repo and never will be.** They are 810 MB,
1.7 GB and 570 MB, `*.litertlm` is gitignored in both the repo and the parent
folder, and cloning gets you none of them. Get them separately, then push the one
you intend to use to the device — the path is a constant in `LocalModelProvider`
and nothing searches anywhere else:

```
adb shell mkdir -p /data/local/tmp/llm
adb push LLaVA-OneVision-0.5B.litertlm /data/local/tmp/llm/
adb shell ls -l /data/local/tmp/llm/
```

`ModelSpec.expectedBytes` exists to catch a truncated push, because native
LiteRT-LM tends to abort rather than throw on one. §3.6 and §3.8 cover which model
to choose and why.

**Grading currently runs on Qwen and produces partial results with confidence
0.0 — read §3.8 before scanning anything.**

**2. A standard x86_64 emulator cannot run the extractor.**
`extractor/build.gradle.kts` sets `ndk { abiFilters += "arm64-v8a" }` — the other
three ABIs OpenCV ships would add roughly 120 MB to the APK. The consequence is
that the OpenCV native library is simply absent from the APK on an x86_64 image,
so `OpenCvNative.load()` fails at the scan step and no amount of re-photographing
helps. **Use a physical arm64 device, or an arm64 emulator system image.** Check
what you have with `adb shell getprop ro.product.cpu.abi` — it must print
`arm64-v8a`. §3.5 covers what else differs on an emulator.

## Confirm the whole set in under a minute

Run these from the parent folder. Each prints one line; anything that errors or
prints nothing is the thing to install.

```powershell
java -version                          # expect: 21.0.5
node -v ; npm -v                       # expect: v24.19.0 / 11.17.0
adb version                            # expect: Android Debug Bridge version 1.0.41
docker --version ; docker ps           # version, then a table = the daemon is up
python --version                       # optional; expect: Python 3.12.5
python -c "import PIL; print(PIL.__version__)"        # optional
dir "$env:LOCALAPPDATA\Android\Sdk\platform-tools"  # adb lives here
dir *.litertlm                         # the model files, obtained separately
adb shell getprop ro.product.cpu.abi   # must be arm64-v8a (device attached)
```

Then create the server's environment file. **`ASC_Capstone/server/.env.example`
lists every variable `src/config/env.ts` reads**, with a one-line comment each and
a note of which are required; the schema is parsed at import time and the process
exits 1 if it fails, so a missing value is a startup crash rather than a runtime
surprise. `.env` itself is gitignored and does not come with the clone — you must
create it:

```powershell
Copy-Item ASC_Capstone\server\.env.example ASC_Capstone\server\.env
```

Then fill in the two required values, `DATABASE_URL` and `JWT_SECRET` (minimum 16
characters). `TEACHER_API_BASE_URL` can stay at its default — nothing reads it
until you call `POST /api/assignments/import`, which has never been called (§6).

---

# 1. Where the system stands

## 1.1 Three codebases, and who owns what

```
capstone app/                     not a git repo; the parent folder only
├── v-2.1.1/          HIS.   Python/FastAPI + React/TipTap. Authors a worksheet,
│                            finalizes it, renders a PDF with four ArUco corner
│                            markers, and records the pixel rectangle of every
│                            answer box.        OWNS: PAGE GEOMETRY.
├── ASC_Capstone/     MINE.  Node + Express + Prisma + PostgreSQL. Accounts,
│                            assignments, the answer key, submissions, grades.
│                            OWNS: THE ANSWER KEY AND THE GRADE RECORD.
└── Capstone_Android/ MINE.  Kotlin + Compose. Two Gradle modules: :extractor
                             (ArUco registration + crop) and :app (everything
                             else, including on-device grading with LiteRT-LM).
                             OWNS: EXTRACTION AND GRADING, ON THE PHONE.
```

**The sentence that explains the split: his side owns where the ink goes on the
page; my side owns what the ink should have said and what it is worth.** He
produces geometry — a canonical page size, four marker centres, one rectangle per
answer box. He has no concept of a correct answer, a rubric, a mark total, a
student, or a login; `TEACHER_NOTES.md` §4.3 greps the whole tree for that
vocabulary and finds zero hits. My side has all of it and no ability to draw a
page.

The join between them is one HTTP call: `POST /api/assignments/import` on my
server fetches `GET /api/questions/{id}` from his and copies the geometry across.
That call has never been made (§6).

## 1.2 The vocabulary trap — read this twice

His nouns and mine collide, and they collide *inverted*. Every document in this
folder restates the mapping, because getting it backwards makes all five of them
read as nonsense:

| His word | Means | Maps to my |
|---|---|---|
| **Question** | one whole TipTap document → one whole printed worksheet, N answer boxes, one PDF | **Assignment** |
| **answer box** | one rectangle on the page that a student writes in | **Question** |

So *his* `question_id` (a uuid4, server-minted) lands in my
`assignments.external_question_id`, and *his* `answer_box.id` (an
`ab_`-prefixed, browser-minted string) lands in my
`questions.external_answer_box_id`. `TEACHER_NOTES.md` §3.1 puts it plainly:
"his 'question' is closer to your assignment, and his 'answer box' is the closest
thing to your question."

Concretely, from the fixture this project actually uses:

```
his question_id  32824d98-aa41-43f9-8eef-4f4c2fb3b956   ->  my assignment
his box id       ab_syzn1vsmmsrm6jat                    ->  my question #1
his box id       ab_uub03qhomsrm71en                    ->  my question #2
```

## 1.3 Repo state right now (VERIFIED today)

**`Capstone_Android`** — branch `feature/on-device-grading`, one commit
(`8281d9f Initial commit: Student grading app with CameraX and Auth`). 26 modified
files plus a large untracked set: the whole `extractor/` module, `data/local/`
(the three model files), `data/remote/LayoutModels.kt`, `domain/grading/`,
`domain/worksheet/`, `util/`, the two ModelTest screens, the two WorksheetGrading
screens, and every test directory. **Effectively the entire integration lives
uncommitted in the working tree.** That is the single biggest operational risk in
this handover — commit before you touch anything.

**`ASC_Capstone`** — branch `feature/local-grade-endpoint`, HEAD
`3bcf9cf before first eval`. 10 modified files, plus untracked:
`server/prisma/migrations/20260830120000_teacher_worksheet_join/`,
`server/src/services/teacher-worksheet.service.ts`, and `server/seed-demo.sql`.
Same warning: the migration and the import service are untracked.

**`v-2.1.1`** — not a git repo here at all, and this copy has never been run (no
`nlp_ocr.db`, `pdfs/` empty, `uploads/` absent — `TEACHER_NOTES.md` §0). It is a
read-only reference copy. His working copy is on his Mac and has never been
inspected by anyone.

**What `v-2.1.1/` is, and is not.** It is his teacher-side system, and it runs on
his server. The student app never calls it, and it is not a dependency of either
of my repos — nothing in `ASC_Capstone` or `Capstone_Android` imports, invokes or
ships a line of it. It is kept here, read-only, for exactly three reasons:
`v-2.1.1/backend/services/extractor.py` is the source the Kotlin `:extractor`
module was ported from; `TEACHER_NOTES.md` and `INTEGRATION_AUDIT.md` cite it by
file and line throughout, so deleting it would strip the citations of their
referent; and it is the only thing in this folder that can render a finalized
worksheet PDF, which is where a printable page carrying real ArUco markers comes
from. **The port is standalone — his Python never runs at grade time**, and the
phone's only network dependency is `ASC_Capstone`. A fourth copy,
`mobile_Extract/`, used to sit beside these three: a stale fork of the same
extractor with constants that had drifted from his. It was deleted once its
`sample/` fixtures had been harvested into
`Capstone_Android/extractor/src/test/resources/sample/`. **Anyone reading the
older documents will hit references to a folder that no longer exists** —
`extractor/NOTES.md` is written entirely about it, and §8 flags that.

---

# 2. What's built and proven

Test counts below are **VERIFIED by reading `build/test-results/testDebugUnitTest/*.xml`
on disk today** — 8 XML files for `:app` totalling `tests=84 failures=0 errors=0`,
4 files for `:extractor` totalling `tests=42 failures=0 errors=0`, all dated
2026-08-30 22:08. I did not re-run the suites during this handover (the brief was
no edits, and a Gradle run writes into `build/`). Re-run them first thing:

```
cd "C:\Users\HP\OneDrive\Desktop\capstone app\Capstone_Android"
.\gradlew cleanTestDebugUnitTest testDebugUnitTest
```

## 2.1 `:extractor` — ArUco registration and cropping — 42 tests

**Where:** `Capstone_Android/extractor/src/main/java/com/example/capstone/extractor/`
— 6 files, 746 lines: `PageExtractor`, `Registration`, `LayoutValidator`,
`Layout`, `ExtractionResult`, `OpenCvNative`.

**What it does:** given a `Layout` (page size, four marker centres, N answer-box
rectangles) and the bytes of a photo, it returns one rectified PNG per box.
`ExtractionResult` is a five-case sealed interface with **no
success-with-an-error-string case** — you cannot reach `crops` without having
handled `MarkersNotFound`, `InvalidLayout`, `Undecodable` and `RegistrationFailed`
first. That is the deliberate departure from the Python, which returns HTTP 200
with an empty crop list and an `error` field.

**What proves it:**

- `GoldenSampleTest` (3 tests) decodes the one real ArUco fixture,
  `extractor/src/test/resources/sample/sample_page.png`, detects all four markers
  and reproduces the warped bboxes `[168,600,1128,850]` and `[168,1283,1128,1573]`
  recorded from the real Python `extract_page`, within 3 px per component.
- `MarkerContractTest` (4) measures what a *wrong* contract costs on those same
  real markers — the numbers in §4.1.
- `PageExtractorTest` (15) covers every `ExtractionResult` case and the crop
  geometry.
- `LayoutValidatorTest` (20) asserts every refusal by message.

**The caveat you must carry forward: the golden sample was rendered under his OLD
constants.** It is 1242×1756 against a canonical 1240×1754, with boxes at `x = 168`
and content width `960`. His generator today produces `LEFT_MARGIN = 186` and
content width `930` (`INTEGRATION_AUDIT.md` §1.3 — the 168/186 split is that
document's load-bearing finding: the page-layout constants drifted between two
copies of this project and nothing noticed for months). So the golden sample
proves **the homography arithmetic**, not **the current page**. The *registration*
constants — dictionary, marker size, marker margin, id→corner order — did not
drift and are equal on both sides.

Command: `.\gradlew :extractor:testDebugUnitTest`

## 2.2 The layout / import path

**Server side.** `ASC_Capstone/server/src/services/teacher-worksheet.service.ts`
(365 lines, untracked) is the only file that talks to his system, and
`POST /api/assignments/import` in `assignment.routes.ts` is the only route that
uses it. Migration `20260830120000_teacher_worksheet_join` adds
`assignments.external_question_id`, `questions.external_answer_box_id`, the
`layouts` table, and the two composite unique indexes.

**What proves it:** `npx prisma migrate status` reported `3 migrations found` /
`Database schema is up to date!` on 2026-08-30, and `npx tsc --noEmit` exits 0.
Nothing else. **The route itself has never been called** (§6).

**App side.** `data/remote/LayoutModels.kt` mirrors the served JSON;
`AssignmentRepository.toExtractorLayout` adapts it onto the extractor's `Layout`.
`AssignmentDtoParsingTest` (7 tests) pins the wire shape — page size, dictionary,
version, all four marker centres in row-major order, answer-box array order
preserved, and the distinction that matters most: an imported question with no
answer key parses as `""`, not `null`.

That test parses a **hand-written fixture, not a server response** — the shape is
pinned by agreement between two files I wrote, which is exactly the kind of
agreement that drifts. The first real import will tell you whether it holds.

## 2.3 `QuestionResolver` — the crop-to-question join

**Where:** `app/src/main/java/com/example/capstone/domain/worksheet/QuestionResolver.kt`
(265 lines). **Proof:** `QuestionResolverTest`, 16 tests.

A resolver is built per assignment and scoped to `(assignmentId, externalQuestionId)`.
`forAssignment` refuses — with a logged reason, never a partial resolver — on six
conditions: no external question id, no questions, no box ids, only *some* box ids,
duplicate box id. `resolve(crops)` returns `Resolved` only on an exact one-to-one
correspondence where every crop also carries this resolver's external question id;
anything else is `Failed`, naming four disjoint categories at once
(`cropsFromOtherQuestion`, `cropsWithoutQuestion`, `questionsWithoutCrop`,
`duplicateCropIds`).

No silent skip, no partial result. The reasoning is in the file: a caller handed a
shorter list than it asked for is very likely to grade it and report success.

## 2.4 The grading loop

**Where:** `domain/grading/` (`GradingService`, `WorksheetGrader`, `WorksheetGrade`)
and `data/local/LocalGradingService.kt`.

`WorksheetGrader.grade(List<ResolvedAnswer>)` is a cold `Flow<BoxResult>`, strictly
sequential, sorted by `crop.orderIndex`. Sequential on purpose:
`LocalModelProvider.withInference` already serialises every decode behind a mutex,
so parallelism buys nothing while holding several decoded bitmaps alive.

`LocalGradingService` runs a fresh `Conversation` per box, two attempts (base
prompt, then base prompt plus a reminder that the reply must be one JSON object),
and extracts the first balanced JSON object with a hand-written brace-balance scan
rather than a regex — so it survives markdown fences, preambles, trailing
commentary and braces inside string values.

**Proof:** `GradeParsingTest` (19 tests) and `MergeBoxResultsTest` (15). Between
them they pin: markdown fences, chatty preambles, two objects, truncated JSON, a
missing `marks` field yielding *no grade* rather than a silent zero, clamping above
and below range, fractional marks, and the four merge rules in §4.5.

## 2.5 What has actually run on a phone (NEW — supersedes `CLAUDE.md` §1)

**A complete Scan → Extract → Grade run has been executed on a real device**
against a phone photograph of `sample_page.png` **displayed on a monitor**, using
the seeded demo assignment. Recorded here as VERIFIED: once, no log retained.

That single run establishes, for the first time:

- `OpenCvNative.load()` works on the device — the arm64 OpenCV native library
  loads. It is quarantined off every test classpath by construction, so no test
  can ever reach it.
- `PageExtractor.extractPage` returns `Success` on a **camera photo**, not just a
  rendered PNG — real perspective, real focus, real sensor noise.
- LiteRT-LM engine construction, `initialize()` and inference all work on device.

What it does **not** establish: printed paper, ambient lighting, glare, the
fit-to-page print scaling, or the current 186/930 page geometry. A monitor is
flat, evenly lit and self-illuminated. Keep §6 in mind.

The Qwen finding in §3.8 comes from that run.

---

# 3. Running it locally, start to finish

**This section assumes one Windows machine, no Mac, and no teacher server
running.** `RUNBOOK.md` covers the two-machine demo; nothing until now covered a
developer sitting alone with a laptop and a phone. Everything here is
copy-pasteable.

Shell note, and it bites: in **PowerShell 5.1**, `curl` is an alias for
`Invoke-WebRequest`, not curl. Use `curl.exe` explicitly, or run the curl blocks
in **Git Bash**. PowerShell 5.1 also has no `<` input redirection and no `&&`.
Each block below says which shell it assumes.

## 3.1 PostgreSQL

The database is a bare Docker container. **There is no `docker-compose.yml` in
either repo** (VERIFIED by `find`, 2026-08-21) — the container is the only
definition of it.

```
# 1. Launch Docker Desktop from the Start menu. Wait for the whale to go steady.
docker start capstone-db
docker ps --filter name=capstone-db --format "{{.Names}} {{.Status}} {{.Ports}}"
```

Expect exactly:

```
capstone-db  Up N seconds  0.0.0.0:5433->5432/tcp
```

**5433 is the one that matters.** A *different*, native PostgreSQL 16 Windows
service listens on **5432** and is not the demo database — it rejects the
`capstone` role. Do not repoint `DATABASE_URL` at it.

**`psql` is not on PATH on this machine (VERIFIED today).** It is installed at
`C:\Program Files\PostgreSQL\16\bin`, but the simpler route is the client inside
the container, which is what every SQL block below uses:

```
docker exec -i capstone-db psql -U capstone -d capstone -c "select 1"
```

**If `docker start` says `No such container`,** you are rebuilding from scratch —
this loses every account, assignment and grade. One line:

```
docker run -d --name capstone-db -p 5433:5432 -e POSTGRES_USER=capstone -e POSTGRES_PASSWORD=capstone -e POSTGRES_DB=capstone postgres:16
```

then:

```
cd "C:\Users\HP\OneDrive\Desktop\capstone app\ASC_Capstone\server"
npx prisma migrate deploy
```

**Confirm the schema either way:**

```
cd "C:\Users\HP\OneDrive\Desktop\capstone app\ASC_Capstone\server"
npx prisma migrate status
```

Expect `3 migrations found` and `Database schema is up to date!`. The three are
`20260719193603_idk`, `20260818000000_add_answer_image_path`, and
`20260830120000_teacher_worksheet_join`.

## 3.2 The Node server

```
cd "C:\Users\HP\OneDrive\Desktop\capstone app\ASC_Capstone\server"
npm run dev
```

That is `ts-node-dev --respawn --transpile-only src/index.ts`. `src/index.ts`
awaits `prisma.$connect()` **before** `app.listen` and `process.exit(1)`s if the
connection throws — so **a database that is down looks like a server that refuses
to start**, with a Prisma stack trace, not like a server with a broken route.

Health check, in a second terminal:

```
curl.exe -s http://localhost:3000/api/health
```

Expect `{"status":"ok"}`. That is the whole proof: the process is up, Express is
wired, and Prisma connected.

`server/.env` currently holds (VERIFIED today): `NODE_ENV=development`,
`PORT=3000`, `DATABASE_URL=postgresql://capstone:capstone@127.0.0.1:5433/capstone`,
`JWT_SECRET`, `JWT_EXPIRES_IN=7d`, `CONFIDENCE_THRESHOLD=0.8`,
`STUDENT_SELF_REGISTER=true`. **`TEACHER_API_BASE_URL` and `TEACHER_API_TIMEOUT_MS`
are absent** and fall back to `http://localhost:8000` / `10000`. That is harmless
for everything in this section, because nothing here calls the import route — but
it is the single fatal value the day you do (§7 step 4).

## 3.3 Seeding a demo assignment without the teacher server

### What `seed-demo.sql` is

`ASC_Capstone/server/seed-demo.sql`, 15,974 bytes, untracked. It writes by hand the
exact row set that *a successful import followed by the teacher typing in the
marking data* would have produced. It exists because two things are missing at
once: nothing in either repo starts his FastAPI service, and **no route in my API
can ever write `model_answer`** (§5.1). Without it you get an assignment the grader
refuses to grade.

Read the header comment in the file — it is long and it justifies every value.
What it creates:

| Rows | Values |
|---|---|
| 2 `users` | `teacher@demo.local` and `student@demo.local`, both bcrypt cost 10 over the plaintext **`demo1234`**. `ON CONFLICT (email) DO UPDATE` rewrites the hash, so re-running **resets both passwords to `demo1234`** |
| 1 `assignments` | title "Demo worksheet: states of matter", `external_question_id = 32824d98-aa41-43f9-8eef-4f4c2fb3b956` — the fixture's own `layout_id`, so the seeded assignment and `sample_page.png` are the same worksheet *by identity, not by coincidence*. `total_marks` is recomputed from the question rows at the end of the block |
| 2 `questions` | 5 marks each, `question_type = short_answer`, joined by `external_answer_box_id` `ab_syzn1vsmmsrm6jat` and `ab_uub03qhomsrm71en`, each with a **real `model_answer` and a real `rubric`** (naming the states of matter; particle behaviour on melting). Inserted in served array order so ascending question id is document order |
| 1 `layouts` | `1240 × 1754`, `DICT_4X4_50`, four marker centres computed the way both the fixture and the server compute them (`m + s//2`, integer floor), `source: "computed_from_constants"`, and the two boxes at `[168,600,960,250]` / `[168,1282,960,290]`, both `page_index 0`, `order_index` 0 and 1 |

### What the seeded assignment is *not* evidence of

Its `layouts` row was copied from `extractor/src/test/resources/sample/`, and that
fixture was rendered by an **older build of his generator**. `LEFT_MARGIN` is 168
there; his current code computes **186** (`_MARKER_ZONE 100 + _QR_GUTTER 66 + 20`),
with `RIGHT_MARGIN` 112 vs 124 and content width 960 vs 930 —
`INTEGRATION_AUDIT.md` §1.3 lays the two column-by-column and calls the 168/186
split the load-bearing finding. What drifted is the page layout; the *registration*
constants did not, and the four marker centres are identical in both
(`INTEGRATION_AUDIT.md` §1.3, run C). That is exactly why this fixture cannot tell
you the difference: registration succeeds either way, and the boxes are simply
somewhere else on his current page.

So the §2.5 device run establishes three real things — the OpenCV native library
loads on the device, `PageExtractor` returns `Success` on real hardware, and
LiteRT-LM constructs an engine and infers. It establishes **nothing** about his
current page geometry, **nothing** about the import route (still never called,
§6), and **nothing** about paper under a camera. Seeding proves the machinery
downstream of a layout. It cannot prove the layout.

Every insert lands on a real unique index (`users_email_key`,
`assignments(teacher_id, external_question_id)`,
`questions(assignment_id, external_answer_box_id)`, `layouts(assignment_id)`) and
does `DO UPDATE`, so **it is idempotent** — re-running converges rows rather than
duplicating them, and question ids stay stable across runs. That matters: they are
the ids the phone posts back in the grade call.

One field is not verbatim from the fixture: `points`. The fixture says 1 on both
boxes; the seed says 5, so each box is worth marking against a real rubric rather
than a coin flip. Nothing in `:app` or `:extractor` reads `points` off the layout
at all, and it is kept equal to `questions.marks` so the two cannot disagree.
Everything registration or cropping touches is untouched.

### Running it

`psql` is not on PATH, and PowerShell 5.1 has no `<` redirect, so copy the file
into the container and run it there. Identical in both shells:

```
docker cp "C:\Users\HP\OneDrive\Desktop\capstone app\ASC_Capstone\server\seed-demo.sql" capstone-db:/tmp/seed-demo.sql
docker exec -i capstone-db psql -U capstone -d capstone -f /tmp/seed-demo.sql
```

(If you prefer piping: Git Bash takes
`docker exec -i capstone-db psql -U capstone -d capstone < seed-demo.sql`;
PowerShell needs
`Get-Content seed-demo.sql | docker exec -i capstone-db psql -U capstone -d capstone`.)

Expect a `NOTICE` line naming the ids, then a one-row summary:

```
NOTICE:  seed-demo: teacher_id=1 student_id=2 assignment_id=1 total_marks=10

 assignment_id | total_marks | sum_question_marks | questions | answer_boxes | marker_centres | questions_without_model_answer
---------------+-------------+--------------------+-----------+--------------+----------------+--------------------------------
             1 |          10 |                 10 |         2 |            2 |              4 |                              0
```

The two columns that decide whether anything works are the last two:
**`marker_centres` must be 4** and **`questions_without_model_answer` must be 0**.
Write down the `assignment_id` — it is a serial, so it is not necessarily 1.

### Verifying it over HTTP — this is what the phone actually reads

The database being right is not the same as the wire being right. Log in as the
student and fetch the detail endpoint. **Git Bash:**

```
S=$(curl.exe -s -X POST http://localhost:3000/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"student@demo.local","password":"demo1234"}' \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

curl.exe -s -H "Authorization: Bearer $S" http://localhost:3000/api/assignments/1
```

**PowerShell:**

```
$r = curl.exe -s -X POST http://localhost:3000/api/auth/login -H "Content-Type: application/json" -d '{\"email\":\"student@demo.local\",\"password\":\"demo1234\"}' | ConvertFrom-Json
$S = $r.token
curl.exe -s -H "Authorization: Bearer $S" http://localhost:3000/api/assignments/1
```

In the response body, check four things:

1. `layout` is present and not null.
2. `layout.markers.centres` has **four** keys — `"0"`, `"1"`, `"2"`, `"3"` — each a
   two-element array: `[70,70]`, `[1170,70]`, `[70,1684]`, `[1170,1684]`.
3. every entry in `questions[]` has a **non-empty `model_answer`** and a non-null
   `marks`. That pair is exactly `Question.isGradeable`; if either is missing, the
   grader refuses the box before the model is ever called (§5.1).
4. every question has an `external_answer_box_id`, and the assignment has an
   `external_question_id`. `QuestionResolver` refuses to build without both.

If `layout` comes back null, the assignment has no `external_question_id` —
`AssignmentRepository` builds `Assignment.layout` only when both halves of the
join key are present.

### Resetting so the assignment is scannable again

`HomeViewModel` computes the Pending/Completed tabs by intersecting assignment ids
with submission ids, so one scan moves the assignment to Completed and you cannot
scan it again. Delete the submission; **answers and grades cascade** (every
relation in the schema is `onDelete: Cascade`):

```
docker exec -i capstone-db psql -U capstone -d capstone -c "DELETE FROM submissions WHERE assignment_id = (SELECT id FROM assignments WHERE external_question_id = '32824d98-aa41-43f9-8eef-4f4c2fb3b956') AND student_id = (SELECT id FROM users WHERE email = 'student@demo.local');"
```

If you would rather be explicit than rely on the cascade:

```sql
DELETE FROM grades      WHERE assignment_id = <ID>;
DELETE FROM answers     WHERE submission_id IN (SELECT id FROM submissions WHERE assignment_id = <ID>);
DELETE FROM submissions WHERE assignment_id = <ID>;
```

**Leave `questions` and `layouts` alone** — they hold the marking data and the
geometry. The same block sits at the bottom of `seed-demo.sql`, commented out and
deliberately not run.

## 3.4 Phone over USB

```
adb devices -l
```

One line ending in `device` — not `unauthorized` (accept the RSA prompt on the
phone), not `offline` (replug). `adb` is on PATH at
`%LOCALAPPDATA%\Android\Sdk\platform-tools` (VERIFIED today).

```
adb reverse tcp:3000 tcp:3000
adb reverse --list          # host-N tcp:3000 tcp:3000
```

**What this does:** it makes the *phone's* `localhost:3000` resolve to the *PC's*
`127.0.0.1:3000`. The traffic rides the USB cable, is terminated by the adb
daemon, and arrives at Node as a loopback connection. No firewall on either side
is involved and the Wi-Fi is irrelevant. That is why it is the transport to use.

**`BuildConfig.BASE_URL` must NOT be changed.** It is `"http://localhost:3000/api/"`
for **both** build types (VERIFIED in `app/build.gradle.kts:25,31`), and
`localhost` on the phone *is* the PC because of `adb reverse`. **The server
README's advice about pointing the app at a LAN IP is stale** — it predates the
reverse-tunnel setup. Changing it costs a rebuild and reinstall, puts the phone on
whatever Wi-Fi is around (client isolation is common), and needs a Windows
Firewall inbound rule for 3000. `AppContainer` reads `BuildConfig.BASE_URL`; no URL
is hardcoded in Kotlin.

**The forward drops silently.** It is lost on unplug, on `adb kill-server`,
sometimes when the phone reboots or the USB port renegotiates — **and after
`.\gradlew installDebug`**. Nothing tells you; the app just fails after ~10 seconds
with a connect timeout. `AppContainer` sets `connectTimeout` to 10 s deliberately
so this fails fast rather than hanging. **Re-run `adb reverse tcp:3000 tcp:3000`
after every install and every replug.** Nothing needs restarting afterwards — just
retry in the app.

Build and install:

```
cd "C:\Users\HP\OneDrive\Desktop\capstone app\Capstone_Android"
.\gradlew installDebug
adb reverse tcp:3000 tcp:3000
```

End-to-end check, if the device shell has curl (many do not):

```
adb shell curl -s http://localhost:3000/api/health
```

If it does not, open the app and log in, and watch the Node console print
`POST /api/auth/login`. Same proof.

## 3.5 Emulator — what differs, and what does not work

**Networking:** `adb reverse` works on an emulator exactly as it does on a phone,
so leave `BASE_URL` alone. `10.0.2.2` is the emulator's alias for the host
loopback and is the alternative, but using it means editing
`app/build.gradle.kts` and rebuilding — don't; use `adb reverse` and keep one
configuration. (CODE-READ: standard emulator behaviour, not exercised in this
project.)

**Extraction: almost certainly broken, for a concrete reason.**
`extractor/build.gradle.kts:11-15` sets `ndk { abiFilters += "arm64-v8a" }`
(VERIFIED by reading) — the other three ABIs OpenCV ships would add ~120 MB to the
APK. A standard x86_64 AVD therefore gets **no OpenCV native library at all**,
`OpenCVLoader.initLocal()` returns false, and `OpenCvNative.load()` throws
`IllegalStateException("OpenCV native library failed to load")`, which
`ScanViewModel` catches and logs. Working around it needs an arm64 system image,
which on an x86 Windows host runs under full instruction emulation and is
impractically slow. **This has not been tried. I am telling you the build config,
not the outcome.**

**Models:** the three files are 557 MB – 1.7 GB. Whether they fit on a default AVD
data partition, and whether `/data/local/tmp/llm/` is readable by the app uid
there, is **untested**.

**LiteRT-LM on an emulator: untested, and I would not plan on it.** The engine is
configured `backend = Backend.CPU(), visionBackend = Backend.GPU()` — an OpenCL
vision backend on an emulated GPU is not something anyone here has attempted.

**Bottom line: use the emulator for login, the assignment list, navigation and
layout work. Use a physical arm64 phone for anything touching OpenCV or the
model.** Said plainly because guessing here costs a day.

## 3.6 Model files on the device

They live in `/data/local/tmp/llm/` — `LocalModelProvider.MODEL_DIR`, a companion
constant and the single source of truth for `modelFile()`, `isModelPresent()` and
`modelSizeBytes()`. Its KDoc records why: it is a **dev-only** path, outside the
app sandbox and shared with other apps' debug tooling, chosen because Android 11+
scoped storage blocks the adb shell user from writing into the app's external
files dir.

```
adb shell mkdir -p /data/local/tmp/llm
adb push "C:\Users\HP\OneDrive\Desktop\capstone app\LLaVA-OneVision-0.5B.litertlm" /data/local/tmp/llm/
adb push "C:\Users\HP\OneDrive\Desktop\capstone app\Qwen2-VL-2B.litertlm"          /data/local/tmp/llm/
adb push "C:\Users\HP\OneDrive\Desktop\capstone app\gemma3-1b-it-int4.litertlm"    /data/local/tmp/llm/
adb shell ls -l /data/local/tmp/llm/
```

Exact sizes (VERIFIED by `ls -l` today):

| File | Bytes | `ModelSpec.expectedBytes` |
|---|---:|---|
| `LLaVA-OneVision-0.5B.litertlm` | 829,262,144 | `829_262_144L` — **checked** |
| `gemma3-1b-it-int4.litertlm` | 584,417,280 | `584_417_280L` — **checked** |
| `Qwen2-VL-2B.litertlm` | 1,784,096,288 | **`null` — NOT checked** |

`createEngine()` runs two `check()`s before touching native code: `status.usable`,
then `status.sizeMatchesExpected`. The size check is skipped for any entry whose
`expectedBytes` is null.

**So a truncated Qwen push is the one failure this guard does not cover.** A short
`adb push` — cable knocked, no space left — leaves a truncated but perfectly
openable file, and native LiteRT-LM tends to **abort the process** on that rather
than raise something catchable. You get a dead app, not an exception. After
pushing Qwen, compare `adb shell ls -l` against 1,784,096,288 by eye.

Verify from inside the app: **Home → "Model" → "Check model file"**. It dumps every
`ModelFileStatus` field plus a `diagnosis` string naming the likely cause and the
exact adb command that fixes it. Then **"Load engine"**.

## 3.7 Getting a test image onto the device

`ScanScreen` uses `ActivityResultContracts.PickVisualMedia` — the system photo
picker, no storage permission, reading from MediaStore. So a pushed file has to be
indexed before it appears.

```
adb push "C:\Users\HP\OneDrive\Desktop\capstone app\Capstone_Android\extractor\src\test\resources\sample\sample_page.png" /sdcard/Pictures/
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/sample_page.png
```

**The broadcast is unreliable on modern Android** — deprecated, and often silently
ignored. When the picker doesn't show the file, **reboot the phone**
(`adb reboot`); the media scanner re-indexes at boot and it appears. That is the
fix that actually works.

Note what that image is, though: `sample_page.png` is a **rendered** page, not a
photograph. Pushing and picking it exercises registration on a perfect input. The
realistic test is to display it full-screen on a monitor (or print it) and
photograph it with the phone's camera app — which is what the §2.5 run did. The app
deliberately has no camera of its own: `ScanViewModel`'s KDoc explains that
registration needs four sharp corner markers at full resolution, and the stock
camera app has the framing aids, focus tap and review step `ScanScreen` does not.

## 3.8 Choosing the model — and the Qwen parser gap

ModelSpec.DEFAULT is deliberately left at QWEN2_VL_2B, and grading is currently
broken because of it. A first run will produce a partial result with confidence
0.0. This is known, not a surprise — fixing the parser is the first task.
LLAVA_OV_05B is the fallback that works today: change the one line in
ModelSpec.kt and rebuild if a working grader is needed before the parser is
fixed.

### What happened

On the §2.5 device run — one complete Scan → Extract → Grade against a phone
photograph of `sample_page.png` on a monitor, using the seeded demo assignment:

- The worksheet had **two answer boxes**. Both were extracted and both reached
  the model; extraction was not the problem.
- **Box 1 graded normally: 3 out of 5.** The model read it, `parseGrade`
  returned a `GradeResult`, and it merged as a `Scored` box.
- **Box 2 came back unparseable.** `parseGrade` returned null, the single retry
  with `RETRY_REMINDER` did not recover it, and the box merged as
  `BoxOutcome.NeedsReview`.
- **Overall confidence was exactly 0.0**, because `mergeBoxResults` takes the
  **minimum** across boxes and a review box contributes 0.0 (§2.4, and
  `MergeBoxResultsTest` pins the rule). The 0.0 is not the model reporting no
  confidence in box 1 — it is box 2 having no confidence to report at all.

So the observable symptom of this bug is a submission that uploads successfully
with a partial mark and `confidence: 0.0`. It does not crash and it does not
look like a parse failure from the outside.

### The diagnosis — CODE-READ, not VERIFIED

`extractFirstJsonObject`, `parseGrade` and all 19 cases in `GradeParsingTest`
were written against **LLaVA's** output format, and Qwen phrases its responses
differently. The parser is tuned to one model's habits, so swapping the model
silently swaps the failure rate.

**This is inferred from reading the parser and its tests, not proven. Nobody
captured the raw Qwen output on that run** — there is no retained log, no saved
response string, and nothing in either repo records what box 2 actually
returned. Treat the diagnosis as the most likely explanation, not as a finding.
The first step below exists precisely to convert it into one.

### Why Qwen is still the right target

Do not read the above as a reason to abandon it. `ModelSpec` records the
numbers:

| | `LLAVA_OV_05B` | `QWEN2_VL_2B` |
|---|---|---|
| `maxNumTokens` | 2048 | **4096** |
| `imageTokens` | 730 | **576** |
| tokens left for prompt + answer | ~1318 | **~3520** |

Twice the budget while spending fewer of it on the image — far more headroom on
a cropped answer box, which is the shape of every image this app will ever send.
Its own `displayName` in `ModelSpec.kt` says **"Qwen2-VL 2B (multimodal, best
OCR)"**. The model is the better fit; the parser is what has not caught up.

### The task, in order

1. **Log the raw response before parsing.** In `LocalGradingService`, log the
   string exactly as returned — before `extractFirstJsonObject`, before any
   trimming — so what comes off logcat is the model's own bytes and not a
   half-processed version of them. Filter with
   `adb logcat -s LocalGradingService:*` (§3.9).
2. **See what Qwen actually emits.** Run the two-box worksheet again and read
   both responses, the one that parses and the one that does not. Until this
   step has run, the section above is a hypothesis.
3. **Extend the parser to handle both formats.** Widen
   `extractFirstJsonObject` / `parseGrade` to accept what Qwen emits **without
   narrowing it to Qwen** — LLaVA must keep passing. All 19 existing
   `GradeParsingTest` cases stay green; that is the check on this step.
4. **Add test cases for both.** New cases covering the real Qwen strings from
   step 2, alongside the existing LLaVA ones, so the next model swap is a test
   failure rather than a confidence of 0.0 in production.

### Getting the model file on the device first

**`Qwen2-VL-2B.litertlm` is not in the repo and never will be** — `*.litertlm`
is gitignored in both the repo and the parent folder (§0). Obtain it separately.
It is **1.7 GB**, so the push is slow, and it goes to the same directory as every
other model:

```
adb shell mkdir -p /data/local/tmp/llm
adb push Qwen2-VL-2B.litertlm /data/local/tmp/llm/
adb shell ls -l /data/local/tmp/llm/          # <- do not skip this
```

**That last line matters more for this entry than for the others.** `ModelSpec`
sets `expectedBytes = null` for `QWEN2_VL_2B` (no published byte count was
recorded), so the truncated-push check does not apply to it. For `LLAVA_OV_05B`
and `GEMMA3_1B`, `createEngine()` fails the `status.sizeMatchesExpected` check in
Kotlin and you get a readable error. For Qwen there is no such check: a truncated
or interrupted push is handed straight to native LiteRT-LM, which **aborts rather
than throwing** — the process dies and nothing catchable is raised. Verify the
size with `adb shell ls -l` after pushing; the on-device byte count must match
the file on the host exactly.

### Switching the model

One line, in `data/local/ModelSpec.kt`, in the companion object:

```kotlin
/** The spec used unless a caller picks another one. */
val DEFAULT = QWEN2_VL_2B
```

`LocalModelProvider.DEFAULT_SPEC = ModelSpec.DEFAULT` (line 400) is the only
reader; changing that one line changes what the whole app loads. Then rebuild:

```
cd "C:\Users\HP\OneDrive\Desktop\capstone app\Capstone_Android"
.\gradlew installDebug
adb reverse tcp:3000 tcp:3000
```

There is also a no-rebuild path: the **Model** screen has a `FilterChip` per
`ModelSpec` entry, and selecting one calls `useSpec`, which releases the engine and
rebuilds it lazily on the next `initialize()`. Use that to compare models and to
collect Qwen's raw output beside LLaVA's; use the `DEFAULT` line for what the
student flow actually gets.

`ModelSpec` has **three** entries — `LLAVA_OV_05B`, `QWEN2_VL_2B`, `GEMMA3_1B`
(VERIFIED by reading today). `CLAUDE.md` §3.8 says two; it is out of date.

`GEMMA3_1B` is text-only and exists purely to isolate an engine fault from a vision
fault. Do not grade with it: `LocalGradingService.runPrompt` attaches the image
regardless of `spec.supportsVision`, so it produces confident nonsense rather than
an error (§5.7).

## 3.9 Logcat — what to filter and what to look for

Every app tag is a `private const val TAG` (VERIFIED by reading):

```
adb logcat -c
adb logcat -s ScanViewModel:* WorksheetGrading:* LocalGradingService:* LocalModelProvider:* ModelSpec:* ImagePrep:* AndroidRuntime:E
```

**Did OpenCV load?** `OpenCvNative.load()` is deliberately silent on success — it
throws on failure, so that every later OpenCV call doesn't die with an
`UnsatisfiedLinkError` from somewhere much less obvious. `ScanViewModel` catches it:

```
E/ScanViewModel: extraction failed
  java.lang.IllegalStateException: OpenCV native library failed to load
```

If you see that, you are on the wrong ABI (§3.5) or the AAR did not package.
Silence from `ScanViewModel` plus a crop overlay on screen means it loaded.

**Which `ExtractionResult` came back?** Be aware of the honest limitation: the
variant is **not logged**. Only an unexpected throwable reaches logcat, via
`E/ScanViewModel: extraction failed`. The variant is a *screen* fact, and the
messages map one-to-one:

| On screen | Variant |
|---|---|
| "The top-left corner marker is not visible" + "N of 4 corner markers were found" | `MarkersNotFound` |
| "The corners were found but the page could not be squared up" | `RegistrationFailed` |
| "That file is not an image this app can read" | `Undecodable` |
| "This worksheet's printed layout is not usable: `<reason>`" — Blocked, not Retake | `InvalidLayout` |
| the photo with a quadrilateral over each box, and a **Continue** button | `Success` |

If you want the variant in the log, that is a two-line change worth making.

**Did the join succeed?** `W/WorksheetGrading: <reason>` carries the
`QuestionResolver` refusal verbatim — it is written to be logged as-is and names
every failing category at once.

**Did grading actually invoke the model?** This is the important one:

```
I/LocalGradingService: <label> elapsedMs=1842 pngBytes=48213 rawResponse={"transcription":...
I/LocalGradingService: grade#1 parsed ok marks=4 legible=true
```

`elapsedMs` and `rawResponse` are the proof. Failure lines from the same tag:
`grade#N could not be parsed`, `no JSON object found in response`,
`Gson could not parse extracted JSON: <json>`, `parsed JSON has no marks field`, and
finally `grading gave up after N attempts; flagging for manual review`.

**No `LocalGradingService` lines at all means the model never ran.** The give-away
on screen is unmistakable: **results that appear instantly, with a confidence of
exactly 0.0 and empty transcriptions, mean `WorksheetGrader.gradeOne`
short-circuited on `!question.isGradeable` and never called the model** (§5.1). Real
inference takes seconds per box and always produces *some* transcription, even a
wrong one. Slow + transcribed + zero marks is a different and honest outcome: the
model read it and marked it zero.

Also watch `LocalModelProvider`: `I/... model OK: <status>` or
`W/... model NOT usable: <status> -- <diagnosis>`.

## 3.10 The whole loop, condensed

```
docker start capstone-db
docker exec -i capstone-db psql -U capstone -d capstone -c "select 1"
cd "...\ASC_Capstone\server" ; npx prisma migrate status ; npm run dev
curl.exe -s http://localhost:3000/api/health
docker cp "...\server\seed-demo.sql" capstone-db:/tmp/seed-demo.sql
docker exec -i capstone-db psql -U capstone -d capstone -f /tmp/seed-demo.sql
adb devices -l
cd "...\Capstone_Android" ; .\gradlew installDebug
adb reverse tcp:3000 tcp:3000
# app: Model -> Check model file -> usable ; Load engine -> ok
# app: log in student@demo.local / demo1234
# app: assignment -> Scan -> pick the photo -> Continue -> grading -> Upload
docker exec -i capstone-db psql -U capstone -d capstone -c "DELETE FROM submissions WHERE assignment_id = <ID>;"   # to go again
```

---

# 4. Design decisions, and why

These are the ones whose reasons live in commit-less working-tree code and in my
head. Undo any of them silently and the system keeps reporting success while
producing garbage — which is exactly why they are written down.

## 4.1 Marker centres come from the layout. They are never computed in the app.

`:extractor` contains **zero page-dimension literals** (VERIFIED by grep across
`extractor/src/main` and `app/src/main` for `1240`, `1754`, `60`, `40`,
`marker_margin`, `markerSize` — every hit is in a test or a fixture).
`Registration.registerPage` builds the canonical→image correspondence by
**matching `MarkerRef.id` against the detected id**, walking `layout.markers` in
whatever order the caller gave. There is no TL/TR/BL/BR table anywhere in the
module to be wrong about.

**The measured cost of getting it wrong**, from `MarkerContractTest` against the
real sample markers, corroborated by `INTEGRATION_AUDIT.md` §2.3 run J against a
real perspective warp:

| Wrong assumption | Displacement | Still reports |
|---|---|---|
| marker margin off by 10 px | **~11 px** on every crop | 4/4 markers, `homography`, **success** |
| marker size off by 20 px | ~10 px | 4/4, success |
| **marker ids 2 and 3 swapped** (clockwise instead of row-major) | **~990 px**, mirrored homography | 4/4, success |
| geometrically-true centre instead of his `m + s//2` integer floor | 0.66 px | — |

Eleven pixels is enough to clip the descenders off the top line of a 57 px
writable strip. Nine hundred and ninety pixels is crops of blank paper. **All
three still detect four markers and report success** — there is no residual check,
no reprojection error, no assertion, anywhere on either side. That failure mode is
the entire reason for the no-hardcoded-geometry rule, and `MarkerContractTest`
exists to keep the number in front of whoever is tempted.

Note the last row: reproduce his **integer floor `m + s//2`**, not the true centre.
A 60 px marker at x=40 spans pixels 40–99, true centre 69.5, and his code reports
70. Being *correct* costs you 0.66 px of disagreement with him.

Two literals do exist in `:extractor` main source, both deliberate:
`Objdetect.DICT_4X4_50` at `Registration.kt:43` (an encoding, not geometry — a
mismatch surfaces as `MarkersNotFound`, not a silent displacement), and the inset
in §4.4.

## 4.2 The join is `(assignment_id, external_answer_box_id)` — never the box id alone

Both new unique indexes are composite on purpose:

```
@@unique([teacher_id, external_question_id])        on Assignment
@@unique([assignment_id, external_answer_box_id])   on Question
```

The assignment one is scoped to the teacher because two teachers may import the
same external question, and it is what makes re-import detection well defined — at
most one assignment per (teacher, external question), so the route can *find* the
previous import instead of guessing.

The question one is the load-bearing one. On his side `answer_box.id` is a
**browser-generated, client-supplied, globally-unique primary key** —
`'ab_' + Math.random().toString(36).slice(2,10) + Date.now().toString(36)`, with no
server-side validation and no collision check. It is globally unique today *only in
the sense that a collision crashes his insert rather than coexisting*
(`INTEGRATION_AUDIT.md` §2.5, run I: two questions carrying the same box id → 500).
**If clone is ever fixed with a composite PK `(question_id, id)`, bare box ids stop
being unique — and any single-column join here starts joining across worksheets
with no visible symptom.**

So: **there is no query anywhere, on either side, that looks a question up by box
id alone.** VERIFIED both directions this handover — on the server, every
occurrence of `external_answer_box_id` is either selected, written at import, or
read off an already-assignment-scoped array; in the app, the only map keyed by bare
box id is `QuestionResolver.questionsByBoxId`, a private field of an object already
scoped to one assignment and one external question. Keep it that way.

Postgres treats NULLs as distinct, so locally created rows (no external ids) are
unconstrained by either index.

## 4.3 `warpPerspective` replaced the Python's bounding-rect crop

His `_crop_region` takes the **axis-aligned bounding rectangle** of the warped quad
and slices it out of the photo. At mild skew that is **1.32× the true box area**,
with the perspective distortion left in and whatever surrounds the box pulled along
with it (`TEACHER_NOTES.md` §9C).

`PageExtractor.cut` instead warps the canonical inset rectangle onto its own
`[w, h]`. Consequences worth knowing:

- the crop is **rectified** — the model sees flat text, not a trapezoid;
- it is **identically sized for every photo of the same box**, so crops are
  comparable across submissions;
- a box running off the frame comes back **black-padded rather than truncated**, so
  a short crop never silently means "clipped photo".

`INTEGRATION_AUDIT.md` Part 3 item 13 recommends the same change on his side if his
extractor is ever used downstream; ours already does it.

## 4.4 The inset `(10, 23, 10, 10)`, applied in canonical space

`Inset.ANSWER_BOX = Inset(10, 23, 10, 10)` in `Layout.kt`. It is **the one piece of
genuine page geometry hardcoded in the module**, and it is derived, not guessed:
2 px border + 8 px padding on each edge, plus a **~13 px caption line at the top**
(the `☐ <label>` caption at 11 px bold, `line-height: normal` ≈ 1.2), read off his
`_PRINT_CSS_TEMPLATE` (`INTEGRATION_AUDIT.md` §2.6). The teacher API serves none of
it — every client has to re-derive it from CSS it cannot see.

**Two rules, in order of importance:**

1. **Apply it in canonical page space, to `bbox`, before the homography.**
   Insetting the warped quad afterwards takes a different amount off each edge,
   because a photo's scale varies across the page.
2. It is a `PageExtractor` constructor parameter, so a caller can override it — and
   `GoldenSampleTest` uses `Inset.NONE` precisely so the golden numbers compare
   against the raw bbox.

The risk to carry: **a change to his print CSS silently changes what fraction of
each box is cropped.** At `(10,23,10,10)` a 90 px box — his editor's effective
minimum — leaves **57 px** of writing room, 62% of its area. Tell whoever authors
worksheets to set `minHeight ≥ 130`.

## 4.5 Extraction is fully deterministic. The model has no say in what gets cropped.

Nothing in the extraction path calls a model, and nothing in the grading path can
change a crop. The sequence is: validate the layout → decode → detect markers →
solve the transform → warp fixed rectangles. `LayoutValidator` runs **before the
photo is even decoded**, so an unusable layout is `Blocked` (another photo will not
help) rather than `Retake`.

This is the property that makes a wrong crop debuggable: given the same layout and
the same photo you get the same crops every time, and you can diff them. It also
means the model can be swapped, or fail, or hallucinate, without the crops moving
underneath you.

The same discipline runs through the merge step. `mergeBoxResults` turns N box
outcomes into the one row the server stores, by four rules, each pinned by
`MergeBoxResultsTest`:

| Rule | Why |
|---|---|
| Marks summed; a box needing review adds **0** | The submission has one mark total; there is no other arithmetic available. The zero is not a judgement, which is why `needsManualReview` travels with it |
| Confidence is the **minimum**, never the mean | A mean lets nine confident boxes bury the one the model was guessing at |
| Feedback joined one line per box, each naming its question | The single stored string can still be read back apart |
| **Any** box needing review flags the whole submission | Not a majority, not a threshold |

`BoxOutcome` is a two-case sealed interface — `Scored` and `NeedsReview` — and
keeping them distinct is the whole point of the file: **a box the model scored zero
and a box nobody could score are both worth zero marks, and must never look the
same anywhere else.**

---

# 5. What's broken or missing, ranked

## 5.1 No write path for marking data — **the one that stops everything**

*Status: VERIFIED. I re-enumerated all 15 `Router.<verb>` registrations today.*

`POST /api/assignments/import` creates every Question with `model_answer = ""`, and
its own docblock says "the teacher fills those in here afterwards." **No such route
exists.** There is no PUT and no PATCH on assignments or questions anywhere in the
API; the only `prisma.question` mutation in the entire server is the
`tx.question.create` inside the import route. The single PATCH in the codebase is
`/api/grades/:id/override`.

The consequence, traced:

```
Question.isGradeable == (marks != null && !modelAnswer.isNullOrBlank())
                     == (points != null && !"".isNullOrBlank())
                     == false
```

`WorksheetGrader.gradeOne` short-circuits on that and returns
`NeedsReview("This question has no marking information yet …")` **without calling
`gradingService.grade` at all**. `mergeBoxResults` then uploads
`obtainedMarks = 0`, `confidence = 0.0`, `needsManualReview = true`.

**So the model is never invoked on the only assignments the import path can
produce.** This is a wiring gap, not a bug in any file: `isGradeable` is doing
exactly what it documents, and refusing to grade against a blank answer key is
correct. What is missing is a teacher-side write path.

`seed-demo.sql` fills it in with raw SQL. That is a workaround for a single-machine
demo, not a design. §7 step 3 puts the real fix first.

## 5.2 The QR path is dead, and it costs 66 px

His generator draws one QR per answer box. At the rendered size a JSON payload of
~72 bytes forces a version-5 symbol at **1.12 px per module** — undecodable *before
the page is even printed* (`TEACHER_NOTES.md` §1.5), independently confirmed by
`mobile_Extract` failing to decode its own sample at 1×, 2×, 4× and 8×
(`NOTES.md` §5). Nothing in our path reads one; `_check_qr` and
`_try_local_registration` were deliberately not ported.

The cost is not zero: the QR gutter consumes 66 px and is what pushed his
`LEFT_MARGIN` from 120 to 186. **Fixing it by enlarging `_QR_SIZE` moves
`LEFT_MARGIN` and therefore invalidates the geometry of every already-finalized
question** — so it is a before-anything-is-finalized-in-anger decision, or a never
decision. If it is kept, `mobile_Extract`'s short pipe-form payload
(`q|b|part|order`) is the only shrink path written down anywhere, and a per-copy
payload would be the natural carrier for the missing student identity
(`TEACHER_NOTES.md` §6: every printed copy is byte-identical, and nothing on a
returned photo identifies who wrote on it).

## 5.3 `markers.source` — stored, served, read by nobody

The import route's `resolveMarkers` records honestly where the marker contract came
from: `"served"` when his API provides a `markers` object, or
`"computed_from_constants"` when it falls back to
`TEMPORARY_markerCentresFromTeacherConstants` (which reproduces his
`DICT_4X4_50` / `60` / `40` and his integer floor, bug-for-bug on purpose). Both
branches log — `info` and `warn` respectively.

His API serves no `markers` key today, so **every import will take the warn
branch**, and the value is then stored, served to the phone, parsed by
`MarkerContractDto`, and **read by nothing**. It is the one field that could
distinguish a checked contract from an unchecked convention, and it is discarded.
Surfacing it — even as a log line on the phone — is cheap and worth doing.

## 5.4 `layoutVersion` and `aruco_dict` — parsed, unenforced

`LayoutDto.layoutVersion` is parsed and its own KDoc says a client that does not
know the version should refuse the layout. Nothing reads it. **A version-2 layout
would be consumed as if it were version 1.**

`MarkerContractDto.arucoDict` is likewise parsed and ignored; `Registration.kt:43`
hardcodes `Objdetect.DICT_4X4_50`. This one is less dangerous than it looks — a
marker from a different dictionary does not decode at all, so a mismatch surfaces
as `MarkersNotFound` rather than a silent displacement. But the server *does* serve
the value and the app *does* parse it, and nothing joins the two.

Same for `markerSizePx` / `markerMarginPx` (already baked into `centres`
server-side) and `LayoutAnswerBoxDto.orderIndex` / `.label` / `.points` (order
index is re-derived from array position).

## 5.5 Two-column layouts are refused outright

`LayoutValidator`'s last check: sorting the boxes by `(pageIndex, bbox.y)` must
reproduce the served array order. Array order is the only ordering signal the
teacher API provides, and cross-checking it against geometry assumes a
**single-column, top-to-bottom** worksheet.

His editor produces exactly that, so the check holds today. But **a legitimate
two-column layout, or any document whose order is not vertical, is refused
outright** and the student is told the worksheet's layout is unusable. It is a
Blocked state — another photo will not help. Worth revisiting the day he adds
columns; the fix is to trust array order and drop the geometric cross-check, at the
cost of losing a real safety net.

## 5.6 His clone is broken, and that is load-bearing in an ugly way

`POST /api/questions/{id}/clone` reuses the original `AnswerBox.id` values, and
`AnswerBox.id` is the sole primary key → `IntegrityError` → unhandled → **500**
(VERIFIED over HTTP, `INTEGRATION_AUDIT.md` run H; his frontend swallows it into
`console.error`, so the button appears to do nothing). There is no DELETE route for
a question either.

**A finalized worksheet can therefore never be revised**, and the 404 message's own
advice ("try re-finalizing (clone + finalize)") is the broken path.

The uncomfortable part: every mutation path out of `finalized` is closed — 409 on
`PUT /blocks`, 409 on re-`finalize`, 500 on clone, no DELETE — which is *why*
`(question_id, answer_box_id) → bbox` is immutable and therefore a sound foreign
key. **The broken clone is part of what currently guarantees our join.** If he fixes
it, ask which way: minting fresh box ids keeps bare ids unique; a composite PK does
not, and §4.2 explains what that breaks on our side. **He must tell us before he
changes it.**

## 5.7 Smaller, but they will waste your afternoon

- **`ResultScreen` renders "Grade: null".** The server sends `obtained_marks: Int`;
  `GradeDto` declares `grade: String`, so Gson leaves it null. The data is correct
  in the database and in `GET /grades/assignment/:id`. Cosmetic, read-back path
  only. Unchanged across two audits.
- **No guard against sending an image to a text-only model.**
  `LocalGradingService.runPrompt` always attaches the image regardless of
  `spec.supportsVision`, so grading with `GEMMA3_1B` produces confident nonsense
  rather than an error.
- **`SubmitScreen` / `SubmitViewModel` are orphaned** (183 lines) — the
  `submit/{assignmentId}` route was replaced by `grade/{assignmentId}`. They still
  compile; nothing references either symbol.
- **`ScanScreen.CameraPreview` is unreachable** (lines 295-359, private, no caller)
  — and the CameraX dependencies, the `CAMERA` permission and
  `uses-feature android.hardware.camera` are all still declared for it.
- **`Grade` is per-submission, not per-question** — one `obtained_marks`, one
  `feedback`, one `confidence`, no `question_id`. Per-question detail survives only
  in `Answer.answer_text` (the transcription) and in `WorksheetGrade.boxes`, which
  lives in memory and is rendered, never persisted. If per-question marks matter
  later, that is a schema change.
- **`WorksheetSession` does not survive process death.** Background the app between
  Scan and Grade and you must re-scan. It says so honestly rather than grading
  stale crops.
- **No `docker-compose.yml` in either repo.** The database container is defined by
  no file anywhere. Writing one is an hour and removes a whole class of "the
  container is gone" panic.
- **No server tests at all.** The `test` script is a failing stub; there are no test
  files and no test framework in `devDependencies`.

---

# 6. What has never been executed by anyone

Blunt, because the point of this document is knowing which is which.

**Never run, full stop:**

- **`POST /api/assignments/import`.** The entire join between the two systems. It is
  the only way an assignment can acquire a layout, an `external_question_id` or an
  `external_answer_box_id` — so **every row those columns exist for is
  hypothetical**, and every one that exists today was written by `seed-demo.sql` by
  hand.
- **His FastAPI app booting**, in this copy or on his Mac. No `nlp_ocr.db`
  anywhere, `pdfs/` empty, `uploads/` absent. As of 2026-08-30, `sqlalchemy`,
  `playwright`, `qrcode` and `pyzbar` were all absent on his side.
- **The Playwright measure pass, the print pass, and the PDF.** Nobody has ever
  rendered a worksheet. The `bbox` integers in `INTEGRATION_AUDIT.md` §2.1 are
  composed from his real constants, not measured — the *serialization path* is
  VERIFIED, the *numbers inside `bbox`* are CODE-READ.
- **The two sides running together on separate machines.** Exactly one hop crosses
  machines (PC → Mac, port 8000) and it has never carried a request. Two things will
  block it and both look identical: uvicorn defaulting to `127.0.0.1`, and
  `TEACHER_API_BASE_URL` still saying `localhost`.
- **The student `GET /assignments/:id` layout payload as produced by a real
  import.** The seeded version has been read over HTTP; the imported version has
  not, and its shape is pinned only by a unit test parsing a fixture I wrote.
- **`AssignmentRepository.submitAnswers` / `submitGrade`.** Both compile; as far as
  anything here records, neither has ever sent a request. The §2.5 device run
  reached grading; whether it completed the two-call upload is not recorded — **ask
  me, or just watch the Node console for `POST /api/submissions` followed by
  `POST /api/submissions/:id/grade`.**
- **`ExampleInstrumentedTest`**, and every screen and ViewModel — no UI or
  instrumentation tests exist.

**Run exactly once, no log retained (§2.5):** `OpenCvNative.load()` on a device;
`PageExtractor.extractPage` against a camera photograph; LiteRT-LM engine
construction and inference.

**Never tested, and this is the largest genuine unknown in the project: extraction
from a photograph of a printed sheet.** All 42 extractor tests run against a
rendered PNG. The one device run photographed a **monitor**. Untested: paper,
ambient and directional lighting, glare, print fit-to-page scaling (his PDF is
1.56× A4, so a 60 px marker lands at ~10.2 mm), partial marker occlusion, a folded
or curled sheet. Registration is verified as *arithmetic* and unverified as an
answer to a real photograph of real paper.

**Never measured:** whether the stored `bbox_y` matches where the dashed borders
actually print. `_measure` wraps every block in a `<div data-idx="i">` while the
print pass emits the same blocks as raw siblings, and adjacent-sibling margin
collapsing is not guaranteed to match across those two structures. If there is
drift it will be a **constant vertical offset**, which folds into the inset — but it
has to be measured once, by rasterizing a real PDF.

---

# 7. Where to start — the merge work, in order

The goal is one system: he authors and prints, we import, the phone scans and
grades. Six steps. Each says what must be true on his side, what changes on mine,
and what proves the step is done before you move on.

**What counts as the first real integration test:** a page from *his current
renderer*, imported through `POST /api/assignments/import` and scanned off paper —
Steps 4 and 5. Another run against the seeded fixture is not that, however clean
it comes out. It re-tests the machinery on geometry his generator no longer
produces (§3.3), and the failure it cannot show you is the one that matters.

## Step 0 — Commit, before anything

Both repos carry the entire integration as uncommitted working-tree changes (§1.3).
Branch and commit both, today. Everything below assumes you can get back.

## Step 1 — Get his side running at all

**His side:** Python 3.11 or 3.12; `pip3 install -r backend/requirements.txt`; then
the step that is separate from pip and that people miss —
`python3 -m playwright install chromium` (~150 MB). `pyzbar` is skippable (§5.2);
`pdf2image`/poppler is skippable (submission ingest, which we don't use). Then
`cd backend && uvicorn main:app --host 0.0.0.0 --port 8000` — `--host 0.0.0.0` is
not optional; uvicorn defaults to `127.0.0.1`, and with the default the Mac answers
only itself while our import gets a connection refused that reads exactly like the
machine being switched off. Start uvicorn **from `backend/`**: `config.py` mkdirs
`UPLOAD_DIR` and `PDF_DIR` relative to the process CWD at import time.

**Confirm `backend/.env` does not exist.** `ARUCO_DICT`, `MARKER_SIZE_PX` and
`MARKER_MARGIN_PX` are `pydantic-settings` fields with `env_file=".env"`. A stray
file setting any of them moves the printed page and **nothing anywhere notices**
(§4.1).

**My side:** nothing.

**Proof:** `curl http://localhost:8000/` on his machine returns
`{"status":"ok","project":"NLP-OCR Evaluation Subsystem"}`, and `/docs` renders.
Then, from *my* machine, `curl http://<his-ip>:8000/` returns the same. **If the
first works and the second does not, it is the bind address or his firewall — not
our code.**

## Step 2 — Make him serve the marker contract

**This is the cheapest item on the list and the one that converts an unchecked
convention into a checked one.** `render_finalized_question` already computes
`marker_positions` and throws them away after writing the PDF. He needs to emit them
into `QuestionOut`:

```jsonc
"markers": {
  "aruco_dict": "DICT_4X4_50",
  "marker_size_px": 60,
  "marker_margin_px": 40,
  "centres": { "0": [70,70], "1": [1170,70], "2": [70,1684], "3": [1170,1684] }
}
```

**Row-major, not clockwise** — ids 2 and 3 are bottom-left and bottom-right, and
swapping them costs ~990 px (§4.1). The centres must use his integer floor
`m + s//2`.

**My side:** already ready — `servedMarkersSchema` is declared, `resolveMarkers`
prefers a served contract and only falls back to
`TEMPORARY_markerCentresFromTeacherConstants`, and the fallback's docblock spells
out the three-stage retirement (`computed` → `served ?? computed` → `served`, then
delete the function). The moment he ships it, delete it.

While he is in that file, two one-liners worth asking for in the same change:
serialize **`page_count`** (the value is already in the row and the schema drops it
— `INTEGRATION_AUDIT.md` §2.2), and add **`order_index`** to `AnswerBoxOut` (§2.4)
so array order stops being the only ordering signal.

**Proof:** `GET /api/questions/{id}` contains a `markers` key; after an import, the
stored `markers.source` reads `"served"` and the Node console logs the `info` line
rather than the `warn` line.

## Step 3 — Build the marking-data write path

**This is the blocker (§5.1), and it is a design decision, so make it
deliberately.** The split in §1.1 says the answer key is *ours*. So the right fix is
on my side, not his: a teacher-authenticated route that can set `model_answer`,
`rubric` and `marks` on a question.

Sketch: `PATCH /api/assignments/:id/questions/:questionId`, teacher-only,
ownership-checked, Zod-validated, body `{ model_answer?, rubric?, marks? }` — and if
`marks` is writable, **re-derive `assignments.total_marks` in the same
transaction**, because the grade route 400s when `obtained_marks` exceeds it and
that invariant currently holds only by construction at import.

The alternative — his side gains `model_answer`/`rubric` columns on `answer_boxes`,
a control in the `AnswerBoxNode` node view, and plumbing through `_extractPayload`
→ `AnswerBoxIn` → `_upsert_answer_boxes` → `AnswerBoxOut` — is the largest item in
his own gap list by a wide margin (new schema, new UI, new API), and it puts the
answer key on the side of the system that has no accounts and no auth. **Do it on my
side.**

**Proof:** import a fresh worksheet, PATCH each question's answer key, and confirm
`questions_without_model_answer = 0`. Then `seed-demo.sql` leaves the critical path
and survives only as a fixture.

## Step 4 — Run the import for real, once

**My side:** append to `ASC_Capstone/server/.env`:

```
TEACHER_API_BASE_URL=http://<his-lan-ip>:8000
TEACHER_API_TIMEOUT_MS=10000
```

`env.ts` validates it with `z.string().url()` and `process.exit(1)`s on a bad value,
so a typo is a server that won't start — a good failure. `.env` is read once at
import, so **restart** `npm run dev`; `ts-node-dev` respawns on source changes, not
on env changes.

Then, as a teacher (the app's register screen hardcodes `role=student`, so use the
seeded teacher or create one by curl):

```
curl.exe -s -X POST http://localhost:3000/api/assignments/import \
  -H "Content-Type: application/json" -H "Authorization: Bearer $T" \
  -d '{"external_question_id":"<his-uuid>","title":"..."}'
```

**Expect on a first import: 201**, a body with `questions[]`, a `layout`, and an
`import` block carrying `created`, `page_count`, `marker_source`, and
`questions_awaiting_marking_data` — which on a first import lists **every**
question. That is expected, not an error.

**Read the error; it names the cause.** A 502 whose message says
`http://localhost:8000/...` is the env var; a 502 naming his IP is uvicorn's bind
address or his firewall; a 400 is `toImportableLayout` refusing something partial
(not `finalized`, no answer boxes, no page size, a duplicate box id, a null `bbox`
or a null `page_index`); a 409 is a re-import whose **box id set changed**, carrying
`added`/`removed`/`unchanged` lists rather than guessing which stored answer key
belongs to which new rectangle.

**Proof:** 201, then the student detail endpoint passes the four checks in §3.3.

## Step 5 — Print a real worksheet and extract from paper

The single largest unknown (§6). Everything before this is arithmetic.

**Print fit-to-page at ~64%** — his PDF's media box is 1240×1754 CSS px =
328×464 mm, **1.56× A4**. Printing at actual size crops the corner markers off the
paper, and without all four the extractor cannot run at all. The homography absorbs
the scale, so fit-to-page costs nothing geometrically; it only shrinks a 60 px
marker to ~10.2 mm, still detectable. **Do not print smaller than A4.**

Keep the first one to **one page, 2–3 boxes, `minHeight ≥ 130`, labels filled in.**
`ScanViewModel` blocks any layout whose boxes are not all on page 0, before it even
asks for a photo. A 90 px box leaves 57 px of writing room.

**What to check on the crops — this is the point of the step.** The uploaded crops
land in `ASC_Capstone/server/uploads/answers/` and are served at
`http://localhost:3000/uploads/answers/<filename>`. Open them and look for a
**systematic offset**: a caption included at the top of every crop, or a first line
clipped off every crop, is the §6 vertical-drift question answered — and it is a
constant, so it folds into the inset. Crops of blank paper mean a marker-order
problem. **Crops that look right are the first real evidence the whole geometry
chain is correct**, and nothing before this step can give you that.

Take several photos and keep the one that extracts cleanly. Then vary it
deliberately: angle, low light, glare, one marker partially covered. That is the
test matrix nobody has run.

## Step 6 — Close the loop, then decide about the model

Confirm the two-call upload (`POST /api/submissions` then
`POST /api/submissions/:id/grade`) actually lands — watch the Node console. The
order is forced by the server, which rejects a transcription for a question with no
answer row yet.

Then, and only then, revisit §3.8: set `DEFAULT` back to `LLAVA_OV_05B`, or do the
work to make the parser model-agnostic. Collect raw responses off logcat, turn them
into `GradeParsingTest` cases, widen `parseGrade` until they pass. The parser is
currently tuned to one model's output habits, and that is a quiet dependency worth
making explicit before anyone else picks a model.

---

# 8. The map — which document answers which question

| Read this | When you want to know |
|---|---|
| **`Capstone_Android/HANDOFF.md`** (this file) | How to run the whole thing on one machine (§3), why the geometry decisions are what they are (§4), and what to do first (§7) |
| **`Capstone_Android/CLAUDE.md`** — must stay at the repo root | What every file in *my* two repos is, line count by line count: the route inventory, the Prisma schema, the DTO-by-DTO client/server contract, the test-by-test coverage table, the extractor's internals. The reference manual for my side. Note §1 and §3.8 are out of date — see §2.5 and §3.8 here |
| **`Capstone_Android/INTEGRATION_AUDIT.md`** | Anything about the *numbers*: the measured cost of a wrong marker contract (§2.3), whether `answer_box.id` is a safe foreign key (§2.5), how much of a bbox is border and caption (§2.6), how to recover a prompt from the TipTap tree (§2.7), and the ranked list of what must change on his side (Part 3) |
| **`../v-2.1.1/TEACHER_NOTES.md`** — outside the repo | What *his* system is and does: the render pipeline, the exact marker positions and why the PDF is 1.56× A4 (§1.3-1.4), question vs answer-box identity (§3), what does **not** exist over there — no model answer, no rubric, no student, no auth (§4.3), and what a student physically receives (§6) |
| **`Capstone_Android/RUNBOOK.md`** | The two-machine live demo: every process, every config value, the network map and which single hop crosses machines (§4), the ten-step script, and 16 ranked failure modes with discriminators (§6). Read §6 even if you never do the demo — it is the best failure-symptom index in the project |
| **`Capstone_Android/extractor/NOTES.md`** — stale, see below | The Python `mobile_Extract` prototype our extractor was ported from: what it did, what was deliberately not ported, and the three things worth harvesting before it is deleted |

**Where these files live.** All four of mine (`CLAUDE.md`,
`INTEGRATION_AUDIT.md`, `RUNBOOK.md`, this file) sit in the parent folder today,
outside all three repos, which is why the older ones refer to each other by bare
filename. **They move into `Capstone_Android/` before the repo is pushed**, and
the paths above are written for where they land, not where they are now.
`CLAUDE.md` must sit at the repo root specifically — Claude Code loads a root
`CLAUDE.md` automatically, and it stops doing so from a subdirectory.

Two paths in that table are not like the others. `../v-2.1.1/TEACHER_NOTES.md`
does **not** move and is **not** in any repo: it is a read-only copy of his
system living in the parent folder, so from inside `Capstone_Android` it is one
level up, and a fresh clone will not have it at all (§1.3). And
`extractor/NOTES.md` is now the stalest document in the set — it describes the
`mobile_Extract/` prototype as something still on disk to be harvested, and that
folder has since been deleted (§1.3). Read it for the *reasoning* about what was
and was not ported; do not go looking for the files it names.

**If you read one other section today, read `RUNBOOK.md` §6.1** — it is the failure
you will hit first, and §5.1 here is why.

---

*Ping me on anything. The two most worth asking rather than deriving: whether the
§2.5 device run completed the upload, and what he intends to do about clone (§5.6)
— that one changes our join.*
