# `RUNBOOK.md` — running the whole system live, in one room

> Written 2026-09-02 for a single presentation: his worksheet system on a **Mac**,
> my server and phone on a **Windows PC**, phone attached by **USB**.
> Produced by reading every file involved and by executing every check that could
> be run from this Windows machine. Companion to `CLAUDE.md`,
> `INTEGRATION_AUDIT.md`, `v-2.1.1/TEACHER_NOTES.md` and
> `Capstone_Android/extractor/NOTES.md`; same tag vocabulary.
>
> | Tag | Meaning |
> |---|---|
> | **VERIFIED** | Observed directly in this session: a command run on the Windows PC, or the literal contents of a file read here (written "VERIFIED by reading") |
> | **CODE-READ** | Runtime *behaviour* traced through the source in this session, but never executed |
> | **UNVERIFIED** | Written, but nothing here proves it works |
>
> The line between the first two is deliberate: what a file *says* is verified;
> what it *does* when run is not. So "his CORS list is exactly these two origins"
> is VERIFIED, while "a request from any other origin is therefore blocked" is
> CODE-READ.
>
> Where a prior audit verified something and I did not re-run it, it says so with
> the date. Nothing is promoted to VERIFIED on the strength of another document.
> **No claim about the Mac's runtime state is VERIFIED anywhere in this file** —
> the Mac was never inspected.
>
> **No file in any repo was modified. This file is the only addition, and it sits
> at the parent-folder root, outside all three trees.**

---

## 0. The one thing to read if you read nothing else

Four facts, all established this session, that will decide whether the demo works:

1. **Postgres is down.** `DATABASE_URL` points at `127.0.0.1:5433`, which is the
   Docker container `capstone-db`. Docker Desktop is not running and nothing is
   listening on 5433 (VERIFIED). The Node server will exit 1 at boot until this
   is fixed. A *different*, native PostgreSQL 16 is running on 5432 and is **not**
   the demo database — it rejects the `capstone` role (VERIFIED).
2. **`TEACHER_API_BASE_URL` is not set**, so it defaults to
   `http://localhost:8000` (VERIFIED — the variable is absent from
   `server/.env`). On the demo night the Mac is not localhost. This single value
   is the difference between an import that works and a 502.
3. **Nothing in the API can write `model_answer`.** All 15 routes enumerated, and
   the only `prisma.question` mutation anywhere is `tx.question.create` inside
   the import route (VERIFIED by grep). "Fill in the marking data" in §5 is a raw
   SQL step. Skip it and the model is never invoked at all — see §6.1.
4. **Nobody has ever run the second half of this pipeline.** The FastAPI app has
   never booted, no PDF has ever been rendered, `POST /assignments/import` has
   never been called, and the extractor has only ever seen a rendered PNG, never
   a photograph. §6 ranks what that costs you.

---

## 0.1 Mac inventory — run this first, on the Mac

**The Mac was not inspected. Every Mac claim in this document is UNVERIFIED.**
Run this block on the Mac before anything else and compare against the notes
underneath. It is entirely read-only.

```
cd ~/path/to/capstone\ app/v-2.1.1          # wherever his copy lives

sw_vers -productVersion                      # macOS version
uname -m                                     # expect: arm64 (Apple Silicon)
python3 --version                            # expect: 3.11 or 3.12
node --version                               # expect: v20 or newer

python3 -c "import importlib.util as u; [print('%-20s %s' % (m, 'PRESENT' if u.find_spec(m) else 'ABSENT')) for m in ['fastapi','uvicorn','sqlalchemy','pydantic','pydantic_settings','multipart','cv2','numpy','qrcode','PIL','pyzbar','pdf2image','playwright']]"

python3 -c "from playwright.sync_api import sync_playwright; p=sync_playwright().start(); b=p.chromium.launch(); print('chromium OK'); b.close()"

ls -la backend/nlp_ocr.db backend/pdfs backend/uploads 2>&1
ls -d frontend/node_modules 2>&1
cat backend/.env 2>&1                        # MUST NOT EXIST - see 3.4
ipconfig getifaddr en0                       # the Mac LAN IP. Write it down.
```

**Everything must print PRESENT, and `chromium OK` must appear.** As of the
2026-08-30 audit, `sqlalchemy`, `playwright`, `qrcode` and `pyzbar` were all
absent on his side and the app had never booted. Those same four are still
absent on this Windows PC (VERIFIED this session), which is a hint about how the
project was set up, not proof about his machine.

---

## 1. Every process that must be running

Five processes. Start them in this order — each depends on the one above it.

### 1.1 PostgreSQL — Windows PC, port 5433

| | |
|---|---|
| Repo | none (a bare Docker container; **there is no `docker-compose.yml` in either repo** — VERIFIED by `find`, audit 2026-08-21) |
| Start | 1. Launch **Docker Desktop** from the Start menu, wait for the whale to go steady.<br>2. `docker start capstone-db` |
| Port | host `5433` → container `5432` |
| Depends on | Docker Desktop's Linux engine being up |
| Confirm | `docker ps --filter name=capstone-db --format "{{.Names}} {{.Status}} {{.Ports}}"` |
| Expect | `capstone-db  Up N seconds  0.0.0.0:5433->5432/tcp` |
| Also confirm | `psql "postgresql://capstone:capstone@127.0.0.1:5433/capstone" -c "select 1"` → a one-row table containing `1` |

**Status right now: Docker Desktop is NOT running and nothing is listening on
5433 (VERIFIED this session).** The container's existence is carried from the
2026-08-30 audit and could not be re-checked with the engine down — if
`docker start capstone-db` says *No such container*, see §6.2 for the recreate
command and the migration replay.

Do not be misled by the native `postgresql-x64-16` Windows service listening on
**5432** (VERIFIED running, PID 6712, bound `0.0.0.0` and `::`). It is a
different instance, it does not accept the `capstone` role, and pointing
`DATABASE_URL` at it fails authentication (VERIFIED — I tried).

`psql` 16.3 is installed on the PC at `C:\Program Files\PostgreSQL\16\bin`
(VERIFIED). Add it to `PATH` for the session if `psql` is not found.

### 1.2 Node API — Windows PC, port 3000

| | |
|---|---|
| Repo | `ASC_Capstone/server` |
| Start | `cd "C:\Users\HP\OneDrive\Desktop\capstone app\ASC_Capstone\server"` then `npm run dev` |
| Port | `3000` (from `PORT` in `.env`) |
| Depends on | Postgres on 5433; `.env` present; `node_modules` and the generated Prisma client (both **VERIFIED present**) |
| Confirm | `curl -s http://localhost:3000/api/health` |
| Expect | `{"status":"ok"}` |

`npm run dev` is `ts-node-dev --respawn --transpile-only src/index.ts`
(VERIFIED in `package.json`). `src/index.ts` awaits `prisma.$connect()` **before**
`app.listen`, and `process.exit(1)`s if the connection throws (CODE-READ) — so a
database that is down looks like a server that refuses to start, with a Prisma
stack trace, not like a server with a broken route.

`app.listen(env.PORT)` passes no host, so Node binds all interfaces (CODE-READ).
Nothing has to be changed for the phone to reach it.

Migrations were applied as of 2026-08-30 (`3 migrations found`, `Database schema
is up to date!`; not re-run this session). To re-confirm once the container is
up: `npx prisma migrate status`.

### 1.3 His FastAPI backend — Mac, port 8000

| | |
|---|---|
| Repo | `v-2.1.1/backend` |
| Start | `cd v-2.1.1/backend` then **`uvicorn main:app --host 0.0.0.0 --port 8000`** |
| Port | `8000` |
| Depends on | the Python packages in §2.1; Chromium for Playwright; internet, for finalize only |
| Confirm on the Mac | `curl -s http://localhost:8000/` |
| Expect | `{"status":"ok","project":"NLP-OCR Evaluation Subsystem"}` |
| **Confirm from the PC** | `curl -s http://<MAC-LAN-IP>:8000/` |
| Expect | the same JSON. **If the first works and the second does not, go to §4.2.** |

**`--host 0.0.0.0` is not optional.** uvicorn's default host is `127.0.0.1`, and
with the default the Mac reaches itself, his browser works perfectly, and the
Windows import call gets a connection refused that reads exactly like the Mac
being switched off. There is no start script, no Dockerfile and no Procfile
anywhere in his tree (CODE-READ), so this command is the only definition of how
his server starts.

`config.py` calls `Path(UPLOAD_DIR).mkdir(...)` and `Path(PDF_DIR).mkdir(...)` at
import time, relative to the **process CWD** (VERIFIED by reading). Start uvicorn
from `backend/`, or the PDFs land somewhere you will not find them.

`main.py` mounts `init_db()` on startup, which `create_all`s the three tables
into `sqlite:///./nlp_ocr.db` — again CWD-relative. There is no Alembic; a schema
change means deleting the file (CODE-READ). No `nlp_ocr.db` exists in the copy in
this folder (VERIFIED), so first boot creates an empty database and the question
list starts empty. That is expected, not a fault.

FastAPI's interactive docs are live at `http://localhost:8000/docs` — a useful
visible "the backend is up" artefact for the projector.

### 1.4 His React editor — Mac, port 5173

| | |
|---|---|
| Repo | `v-2.1.1/frontend` |
| Install | `npm install` — **has never been run; `node_modules` is absent** (VERIFIED in the copy here) |
| Start | `npm run dev` (= `vite`) |
| Port | `5173` (Vite default; `vite.config.js` sets no `server` block — VERIFIED) |
| Depends on | the FastAPI backend at **`http://localhost:8000`**, hardcoded |
| Confirm | open **`http://localhost:5173/`** in a browser **on the Mac** |
| Expect | the question list page renders; devtools → Network shows `GET http://localhost:8000/api/questions` returning `200 []` |

**Open it at `localhost`, never at the Mac's LAN IP.** `main.py` sets
`allow_origins=["http://localhost:5173","http://127.0.0.1:5173"]` and nothing
else (VERIFIED by reading), so a browser that loaded the page from
`http://192.168.x.x:5173` has every API call blocked by CORS: the app renders and
stays permanently empty, with red CORS errors in the console and no error on
screen.

`node_modules` being absent also means `npm install` pulls TipTap 3, React 19,
Vite 8 and the rest on first run. **Do this the day before, not in the room.**

### 1.5 The Android app — phone, over USB

| | |
|---|---|
| Repo | `Capstone_Android` |
| Build + install | `cd "C:\Users\HP\OneDrive\Desktop\capstone app\Capstone_Android"` then `.\gradlew installDebug` |
| Port hop | `adb reverse tcp:3000 tcp:3000` — **re-run after every replug** |
| Depends on | the Node API on 3000; the model file on the device; USB debugging authorised |
| Confirm device | `adb devices -l` → one line ending in `device` (not `unauthorized`, not `offline`) |
| Confirm the hop | `adb reverse --list` → `host-N tcp:3000 tcp:3000` |
| Confirm end to end | `adb shell curl -s http://localhost:3000/api/health` → `{"status":"ok"}` |
| Confirm the model | in the app: **Home → "Model" → "Check model file"** → the output pane must say `usable` with a size matching `expectedBytes` |

**`adb devices -l` currently lists no devices (VERIFIED this session)** — the
phone is not attached. `adb` 1.0.41 (platform-tools 37.0.0) and JDK 21.0.5 are
installed, and `local.properties` points at
`C:\Users\HP\AppData\Local\Android\Sdk` (all VERIFIED).

`BuildConfig.BASE_URL` is `"http://localhost:3000/api/"` for **both** build types
(VERIFIED in `app/build.gradle.kts`). That is correct for `adb reverse` and must
**not** be changed to an IP — see §3.6.

If `curl` is missing from the device shell (common), the equivalent check is to
open the app, log in, and watch the Node console print `POST /api/auth/login`.

---

## 2. Install prerequisites, per machine

### 2.1 Mac — everything UNVERIFIED

| Requirement | Command | Notes |
|---|---|---|
| Python 3.11 or 3.12 | `python3 --version` | 3.13 is a risk: not every pinned wheel is published for it yet |
| His Python deps | `pip3 install -r backend/requirements.txt` | 13 packages, listed in full below |
| **Playwright's Chromium** | `python3 -m playwright install chromium` | **~150 MB download, and it is a separate step from `pip install playwright`.** Nothing else in the project downloads it, and finalize is dead without it |
| Node 20+ | `node --version` | |
| Frontend deps | `cd frontend && npm install` | **VERIFIED absent in the copy here** |
| `pyzbar`'s native lib | `brew install zbar` | Only needed if you insist on `pyzbar`; see below |

His `requirements.txt`, verbatim (VERIFIED by reading): `fastapi>=0.110`,
`uvicorn[standard]>=0.29`, `sqlalchemy>=2.0`, `pydantic>=2.0`,
`pydantic-settings>=2.0`, `python-multipart>=0.0.9`, `opencv-contrib-python>=4.9`,
`numpy>=1.26`, `qrcode[pil]>=7.4`, `Pillow>=10.2`, `pyzbar>=0.1.9`,
`pdf2image>=1.17`, `playwright>=1.45`.

**Known absent at the last inspection of his side (2026-08-30): `sqlalchemy`,
`playwright`, `qrcode`, `pyzbar`.** Without `sqlalchemy` the app cannot import at
all. Without `playwright` (or its Chromium), `finalize` raises `ImportError`
inside `render_finalized_question` and returns 500. Without `qrcode`,
`doc_renderer` fails at module import — `import qrcode` is top-level (VERIFIED by
reading), so a missing `qrcode` breaks finalize even though the QR itself is
decorative and undecodable.

**Mac-specific notes:**

- **ARM wheels.** `opencv-contrib-python`, `numpy` and `Pillow` all publish
  `macosx_11_0_arm64` wheels for CPython 3.11/3.12, so on Apple Silicon nothing
  should compile from source. If pip starts building a wheel, the Python version
  is the likely cause — that is the signal to drop back to 3.12.
- **Playwright's Chromium** is downloaded into `~/Library/Caches/ms-playwright`,
  not into the venv, and is not covered by `pip install`. On first launch macOS
  may also prompt for permission to accept incoming network connections; approve
  it once, out of the room.
- **`pyzbar` is skippable.** It needs the `zbar` dylib from Homebrew, and on
  Apple Silicon it commonly fails to find it. `extractor.py` swallows the
  `ImportError` (CODE-READ), and the QRs on the page are undecodable anyway at
  1.12 px per module (`TEACHER_NOTES.md` §1.5). Nothing in the demo path reads a
  QR. If `pip install` chokes on `pyzbar`, install the rest without it.
- **`pdf2image`** additionally needs the `poppler` binary (`brew install
  poppler`). It is only used for PDF/TIFF *submission* ingest, which the demo
  does not touch — the phone extracts locally. Skippable.
- **Docker Desktop is not needed on the Mac.** His database is SQLite, in-process.

### 2.2 Windows PC — mostly VERIFIED present

| Requirement | Status |
|---|---|
| Node.js | **v24.19.0**, npm 11.17.0 — VERIFIED |
| JDK | **21.0.5 LTS** — VERIFIED |
| Android SDK + adb | **adb 1.0.41 / platform-tools 37.0.0**, SDK at `%LOCALAPPDATA%\Android\Sdk` — VERIFIED |
| Gradle | wrapper pins **9.4.1**, downloaded on first build — VERIFIED in `gradle-wrapper.properties` |
| `psql` | **16.3** at `C:\Program Files\PostgreSQL\16\bin` — VERIFIED |
| Server deps | `node_modules` + `.prisma/client` present — VERIFIED |
| **Docker Desktop** | installed (engine 27.2.0) but **NOT RUNNING** — VERIFIED |
| USB driver / OEM driver | UNVERIFIED — no device is attached to test with |

**Nothing needs installing on the PC.** The only action is starting Docker
Desktop and the container.

Python on the PC is 3.12.5, and `sqlalchemy` / `playwright` / `qrcode` / `pyzbar`
are absent here too (VERIFIED) — irrelevant, because his side runs on the Mac.
Do not be tempted to run his backend here as a fallback: it would need all four
of those installed plus a Chromium download, and it would put his frontend's
hardcoded `localhost:8000` on the wrong machine.

---

## 3. Every configuration value, both machines

### 3.1 The table

| # | Value | File | Current | Must become | Why |
|---|---|---|---|---|---|
| 1 | `TEACHER_API_BASE_URL` | `ASC_Capstone/server/.env` | **absent** → defaults to `http://localhost:8000` | `http://<MAC-LAN-IP>:8000` | The import route's only route to the Mac. **The single fatal value.** |
| 2 | uvicorn bind host | command line, Mac | default `127.0.0.1` | `--host 0.0.0.0` | Otherwise the Mac only answers itself |
| 3 | `DATABASE_URL` | `server/.env` | `postgresql://capstone:capstone@127.0.0.1:5433/capstone` | unchanged | Correct — **once the container is running**. 5433 is the container, 5432 is a decoy |
| 4 | `TEACHER_API_TIMEOUT_MS` | `server/.env` | absent → `10000` | unchanged | 10 s is ample on a LAN |
| 5 | `PORT` | `server/.env` | `3000` | unchanged | Must match the `adb reverse` pair |
| 6 | `STUDENT_SELF_REGISTER` | `server/.env` | `true` | unchanged | Lets the student account be created from the app if needed |
| 7 | `JWT_EXPIRES_IN` | `server/.env` | `7d` | unchanged | A login before the demo is still valid during it |
| 8 | `BuildConfig.BASE_URL` | `Capstone_Android/app/build.gradle.kts` | `http://localhost:3000/api/` | **unchanged** | `localhost` on the phone is the PC, via `adb reverse`. See §3.6 |
| 9 | `cleartextTrafficPermitted` | `app/src/main/res/xml/network_security_config.xml` | `true` | unchanged | Already set; without it every HTTP call fails on Android 9+ |
| 10 | CORS origins | `v-2.1.1/backend/main.py` | `localhost:5173`, `127.0.0.1:5173` | **unchanged** | Open the editor at `localhost` and it never matters |
| 11 | Frontend base URL | `v-2.1.1/frontend/src/api.js` | `http://localhost:8000/api`, hardcoded **×3** | **unchanged** | Keep the editor on the same machine as his backend |
| 12 | ArUco constants | `v-2.1.1/backend/config.py` | `DICT_4X4_50` / `60` / `40` | **unchanged, and no `backend/.env` may exist** | §3.4 — a silent mismatch is the worst failure in the system |
| 13 | `MODEL_DIR` | `LocalModelProvider.kt` | `/data/local/tmp/llm` | unchanged | Where the model must be pushed |

### 3.2 Setting #1 — the only edit anyone has to make

On the Mac: `ipconfig getifaddr en0` → say it prints `192.168.1.87`.

Then append two lines to `ASC_Capstone/server/.env` on the PC:

```
TEACHER_API_BASE_URL=http://192.168.1.87:8000
TEACHER_API_TIMEOUT_MS=10000
```

`env.ts` validates this with `z.string().url()` and `process.exit(1)`s on a bad
value (VERIFIED by reading), so a typo shows up immediately as a server that
will not start with `Invalid environment configuration`, not as a broken import.
That is a good failure — restart after editing, since `ts-node-dev` respawns on
source changes but `.env` is read once at import.

**Both LAN addresses are DHCP.** The PC's is `<PC-LAN-IP>` (VERIFIED). Write
both on paper, and re-check the Mac's after any network change — a lease renewal
between setup and showtime turns #1 back into a 502.

To find `<PC-LAN-IP>`: run `ipconfig` on Windows and read the IPv4 Address of the
active adapter; on macOS it is `ipconfig getifaddr en0`.

### 3.3 Everything still bound to `localhost` or `127.0.0.1`

| Binding | Where | Verdict |
|---|---|---|
| `TEACHER_API_BASE_URL` default `localhost:8000` | `server/src/config/env.ts` | **FATAL across machines.** Fix with §3.2 |
| uvicorn default host `127.0.0.1` | uvicorn's own default | **FATAL across machines.** Fix with `--host 0.0.0.0` |
| `DATABASE_URL` → `127.0.0.1:5433` | `server/.env` | Fine — Node and Postgres are on the same box |
| `BASE_URL` → `localhost:3000` on the phone | `app/build.gradle.kts` | Fine, and required — `adb reverse` is what makes it true |
| `http://localhost:8000/api` ×3 | `frontend/src/api.js` | Fine **only if** the editor runs on the Mac |
| CORS `localhost:5173` | `backend/main.py` | Fine **only if** the browser is on the Mac |
| adb server on `127.0.0.1:5037` | platform-tools | Fine, local by design |

There is exactly **one** hop that crosses machines, and it is the first row.
Every other `localhost` in the system is correct as written.

### 3.4 The one config trap that fails silently

His `config.py` declares `ARUCO_DICT`, `MARKER_SIZE_PX` and `MARKER_MARGIN_PX`
as `pydantic-settings` fields with `model_config = {"env_file": ".env"}`
(VERIFIED by reading). If a stray `backend/.env` exists on the Mac and sets any
of them, the printed page moves and **nothing anywhere notices**.

My server does not read the marker geometry from him — it recomputes it from the
same three constants, hardcoded in
`TEMPORARY_markerCentresFromTeacherConstants` (VERIFIED by reading), because his
API serves no `markers` key at all (`_question_to_out` emits none — VERIFIED by
reading `routers/questions.py:29-42`). So the two sides agree **by convention,
with nothing checking**.

Measured cost of a disagreement, from `INTEGRATION_AUDIT.md` §2.3 run J (their
verification, 2026-08-30, not re-run here): a 10 px margin change displaces every
crop by ~11 px; getting marker ids 2 and 3 backwards displaces them by ~990 px.
**Both still report 4/4 markers detected and `transform_type: "homography"`.**
The failure is invisible in every log on both sides.

Action: confirm `backend/.env` does not exist on the Mac (it is in the §0.1
inventory), and leave the three constants alone.

### 3.5 The teacher account cannot be created from the app

`AuthRepository.register()` hardcodes `"role" to "student"` (per `CLAUDE.md`
§3.5; the register route itself accepts either role — VERIFIED by reading
`auth.schemas.ts`). So the teacher account must be created by curl. §7 does this
in advance.

### 3.6 Do not "fix" the Android BASE_URL

It is tempting to change `localhost:3000` to `<PC-LAN-IP>:3000` so the phone
can use Wi-Fi. Resist it during the demo:

- it needs a rebuild and reinstall, minutes you will not have;
- it puts the phone on the venue Wi-Fi, which may have client isolation (§4.4);
- it needs a Windows Firewall inbound rule for port 3000;
- USB is faster and more reliable for the multi-megabyte crop upload.

Keep it on `adb reverse`. Carry a spare USB cable.

---

## 4. The network path

### 4.1 The map

```
  MAC                                          WINDOWS PC                      PHONE
  ---                                          ----------                      -----

  Browser  --(A) http localhost:5173-->  Vite dev server
     |                                        (Mac)
     |
     +------(B) XHR localhost:8000/api--> FastAPI  <---------+
                                          (Mac)              |
                                            |                |
                                    (C) SQLite file          |
                                        nlp_ocr.db           |
                                                             |
                                                    (D) HTTP GET /api/questions/{uuid}
                                                        PC --> MAC, port 8000
                                                        ** the only cross-machine hop **
                                                             |
                                                        Node API :3000
                                                             |
                                                    (E) TCP 127.0.0.1:5433
                                                        Node --> Postgres (Docker)
                                                             |
                                                             +<--(F) adb reverse, over USB
                                                                    phone:3000 -> PC 127.0.0.1:3000
                                                                        ^
                                                                        |
                                                                   Android app
```

| Hop | Direction | Transport | Port | Initiated by |
|---|---|---|---|---|
| A | Mac → Mac | HTTP | 5173 | the teacher's browser |
| B | Mac → Mac | HTTP/XHR | 8000 | the browser, on the teacher's behalf |
| C | Mac → Mac | file I/O | — | FastAPI |
| **D** | **PC → Mac** | **HTTP** | **8000** | **the Node import route** |
| E | PC → PC | TCP | 5433 | Prisma |
| F | phone → PC | USB (adb) | 3000 → 3000 | the Android app |

Only **D** leaves a machine. Everything else is loopback or USB.

### 4.2 Hop D — where it will be blocked, and the fix

Four things can block it. They produce nearly the same symptom, so test in this
order.

**1. uvicorn bound to 127.0.0.1.**
Symptom: `POST /api/assignments/import` returns
`502 {"error":"Teacher worksheet service unreachable at http://<mac-ip>:8000/api/questions/<uuid>: fetch failed"}`
(the message text is VERIFIED by reading `fetchTeacherQuestion`).
Discriminator: `curl http://localhost:8000/` **on the Mac** succeeds while
`curl http://<mac-ip>:8000/` **from the PC** fails.
Fix: restart uvicorn with `--host 0.0.0.0`.

**2. macOS firewall blocking inbound 8000.**
Symptom: identical 502.
Discriminator: from the Mac itself, `curl http://<mac-ip>:8000/` — its *own* LAN
address, not localhost. If that succeeds and the PC still fails, the process is
bound correctly and the firewall is the blocker.
Fix: System Settings → Network → Firewall → Options → allow incoming for Python,
or toggle the firewall off for the presentation. macOS usually raises a one-time
"accept incoming network connections" dialog on the first `0.0.0.0` bind — say
yes; if it was ever dismissed, the rule is remembered as a deny.

**3. `TEACHER_API_BASE_URL` still `localhost`.**
Symptom: 502 whose message names `http://localhost:8000/...` — read the URL in
the error, it tells you exactly which of these three you have.
Fix: §3.2, then restart the Node server.

**4. Client isolation on the venue Wi-Fi.**
Symptom: the PC cannot even `ping` the Mac, and neither can reach the other on
any port.
Discriminator: `ping <mac-ip>` from the PC fails while both machines have working
internet.
Fix: put both machines on a phone hotspot, or join them with a single ethernet
cable (modern NICs auto-MDIX; set static `169.254.x.x/16` addresses if no DHCP).

### 4.3 Hop F — `adb reverse`

`adb reverse tcp:3000 tcp:3000` makes the *device's* `localhost:3000` resolve to
the *PC's* `127.0.0.1:3000`. The traffic rides the USB cable, is terminated by
the adb daemon, and arrives at Node as a loopback connection. **No firewall on
either side is involved, and the Wi-Fi is irrelevant.** That is why this is the
transport to use.

It is not persistent. It is lost on unplug, on `adb kill-server`, and sometimes
when the phone reboots or the USB port renegotiates. Symptom when it is gone: the
app fails after roughly 10 seconds with a connect timeout — `AppContainer` sets
`connectTimeout` to 10 s deliberately so this fails fast and obviously (VERIFIED
by reading).
Fix: `adb reverse tcp:3000 tcp:3000`, then retry in the app. Nothing needs
restarting.

Check it any time with `adb reverse --list`.

### 4.4 CORS — what it does and does not block

His FastAPI allows exactly two origins, both `:5173` (VERIFIED by reading
`main.py`). This matters **only** for hop B, his own browser.

- Hop **D** is a Node `fetch` — a non-browser client. CORS is a browser
  mechanism; it does not apply, and no CORS change is needed for the import.
- My Node server uses bare `cors()`, i.e. wide open (VERIFIED by reading
  `app.ts`), so nothing browser-side is blocked there either.

The only way CORS bites you is opening his editor at the Mac's LAN IP instead of
`localhost`. See §1.4.

### 4.5 Windows Firewall — only if the teacher wants to read results from the Mac

Nothing needs to reach port 3000 from outside the PC for the demo to work. If
you want to run the closing `GET /api/grades/assignment/:id` from the Mac's
terminal for effect, you need an inbound rule:

```
New-NetFirewallRule -DisplayName "capstone-demo-3000" -Direction Inbound -Protocol TCP -LocalPort 3000 -Action Allow
```

(Requires an elevated PowerShell. **Not run this session** — plan mode forbade
state changes.) Simpler and less risky: run that final curl on the PC and put
the PC on the projector.

---

## 5. The demo script

Ten steps. Each names the process that handles it, what the presenter does, and
what proves it worked. Commands marked **[PC]** run in a PowerShell or Git Bash
window on the Windows machine; **[MAC]** on the Mac.

Have three terminals open on the PC before you start: one running `npm run dev`
(leave it visible — its log is your best diagnostic), one for curl, one for psql.

### Step 1 — Author the worksheet
*Process: his Vite frontend + FastAPI.*

At `http://localhost:5173/` on the Mac, create a new question, type a heading and
two prompts, and insert an answer box after each with the `☐ Answer Box` toolbar
button. Set each box's **label** (`1a`, `1b`) and **points**, and set
**minHeight to 130 or more** — a 90 px box leaves only 57 px of writing room once
the border, padding and caption are subtracted (`INTEGRATION_AUDIT.md` §2.6).

**Keep it to one page.** The app refuses any layout whose boxes are not all on
page 0 — `ScanViewModel.blockingReason` checks `pages != listOf(0)` and returns
a Blocked state before a photo is ever requested (VERIFIED by reading). Two boxes
at 130 px on a 1506 px usable page is nowhere near the boundary.

**Proof:** autosave fires ~500 ms after typing; the Node… sorry, the *FastAPI*
console logs `PUT /api/questions/{id}/blocks 200`.

### Step 2 — Finalize
*Process: FastAPI → Playwright → Chromium ×2.*

Click **🔒 Finalize Question** and accept the confirm dialog.

This is the heaviest single operation in the demo: it launches headless Chromium
twice, once to measure block positions and once to print, and writes
`backend/pdfs/{question_id}.pdf`. Both passes load KaTeX from
`cdn.jsdelivr.net` and then wait up to 15 s for `window.__ready` (VERIFIED by
reading `doc_renderer.py`). **It needs working internet** — see §6.11.

**Proof:** the button changes to **📄 Export PDF**; the response body shows
`"state": "finalized"` and every `answer_boxes[].bbox` is now four integers
instead of `null`; **[MAC]** `ls -la backend/pdfs/` shows a PDF.

**Write down the `question_id`** (a uuid4). Everything downstream needs it.

### Step 3 — Export and print
*Process: FastAPI `GET /api/questions/{id}/pdf`; a printer.*

Click **📄 Export PDF** — it opens the PDF as a browser download.

**Print it fit-to-page at about 64%.** The PDF's media box is
`1240×1754` CSS px = 328×464 mm, which is **1.56× A4** (`TEACHER_NOTES.md`
§1.3). Printing at actual size crops the corner markers off, and without all four
markers extraction cannot run at all. The homography absorbs the scale, so
fit-to-page costs nothing geometrically.

**Proof:** an A4 sheet with four black ArUco squares fully inside the paper
margins, and dashed boxes captioned `☐ 1a` / `☐ 1b`.

### Step 4 — Write the answers, photograph the sheet
*Process: a human and a phone camera.*

Fill in the boxes with a **dark pen**, staying clear of the dashed border.
Photograph the sheet with the **phone's own camera app** — flat, evenly lit, all
four markers in frame, shot straight on. The app deliberately does not use its
own camera; it picks from the gallery, because the stock camera has focus tap,
framing and a review step that `ScanScreen` does not (VERIFIED by reading
`ScanViewModel`'s KDoc).

**Proof:** you can see all four corner markers, sharp, in the photo preview.

### Step 5 — Import into my server
*Process: Node → Mac FastAPI → Postgres.*

**[PC]** Log in as the teacher and capture the token:

```
curl -s -X POST http://localhost:3000/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"teacher@demo.local","password":"demopass123"}'
```

Copy the `token` value into `$T` (PowerShell) or `T=` (bash). Then:

```
curl -s -X POST http://localhost:3000/api/assignments/import \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $T" \
  -d '{"external_question_id":"<THE-UUID-FROM-STEP-2>","title":"Kinematics worksheet"}'
```

**Proof — three things at once:**

1. HTTP **201**, with a body carrying `questions[]`, a `layout` object, and an
   `import` block.
2. The `import` block reads
   `"marker_source": "computed_from_constants"` and
   `"questions_awaiting_marking_data": [<ids>]` listing **every** question.
   Both are expected on a first import, not errors — his API serves no `markers`
   field and no marking data (VERIFIED by reading both sides).
3. The Node console prints
   `[import] markers: COMPUTED FROM HARDCODED CONSTANTS for question <uuid> ... a mismatch is silent`
   (VERIFIED by reading `resolveMarkers`). **This warn line is the single best
   confirmation that the import actually reached the Mac.**

**Write down the returned assignment `id`** (an integer) and the question ids.

This route has **never been called by anyone** (`CLAUDE.md` §1, UNVERIFIED). It
is the step most likely to surprise you. §6.3 and §6.4 cover the two 502s.

### Step 6 — Fill in the marking data — **raw SQL, no route exists**
*Process: psql against Postgres directly.*

Import creates every question with `model_answer = ""` (VERIFIED by reading
`assignment.routes.ts:444`), and **no route in the API can change it** — 15
routes enumerated, one `question` mutation in the entire codebase, and it is the
`create` in the import route (VERIFIED by grep this session). So:

```
psql "postgresql://capstone:capstone@127.0.0.1:5433/capstone"
```

```sql
-- See what you have. Note the external_answer_box_id values.
SELECT q.id, q.external_answer_box_id, q.question_text, q.marks, q.model_answer
FROM questions q
JOIN assignments a ON a.id = q.assignment_id
WHERE a.external_question_id = '<THE-UUID-FROM-STEP-2>'
ORDER BY q.id;

-- Fill each one in. Scope by assignment_id: a bare box id is not safe as a key.
UPDATE questions SET
  model_answer = 'Force equals mass times acceleration.',
  rubric       = '1 mark for the relationship, 1 for naming the quantities.'
WHERE assignment_id = <ASSIGNMENT-ID>
  AND external_answer_box_id = 'ab_xxxxxxxxxxxxx';

-- Repeat per box, then re-check that none is blank:
SELECT id, external_answer_box_id, marks, model_answer <> '' AS gradeable
FROM questions WHERE assignment_id = <ASSIGNMENT-ID> ORDER BY id;
```

**Proof:** every row shows `gradeable = t`. Also confirm over HTTP — the student
detail endpoint is what the phone actually reads:

```
curl -s -H "Authorization: Bearer $STUDENT_TOKEN" \
  http://localhost:3000/api/assignments/<ASSIGNMENT-ID>
```

and check that each question has a non-empty `model_answer` and that `layout` is
present with four `markers.centres` entries.

**If you skip this step the demo silently produces zero marks without ever
running the model.** See §6.1 — this is the single most likely failure.

### Step 7 — Student logs in
*Process: Android app → adb reverse → Node → Postgres.*

Open the app, enter `student@demo.local` / `demopass123`, tap Login.

**Proof:** the Home screen shows the Pending tab with the imported assignment's
title. The Node console shows `POST /api/auth/login`, then
`GET /api/assignments` and `GET /api/submissions/me`.

Login is rate limited to **10 attempts per 15 minutes per IP**, and every phone
request arrives as `127.0.0.1` through `adb reverse` (VERIFIED by reading
`auth.routes.ts`). Type the password carefully. See §6.10.

### Step 8 — Pick the photo and extract
*Process: entirely on-device — `ImagePrep` → OpenCV → `PageExtractor`.*

Tap the assignment → **Scan** → **"Choose worksheet photo"** → pick the photo
from Step 4.

Nothing leaves the phone here. `ImagePrep.toRegistrationPng` corrects EXIF
orientation without downscaling, `OpenCvNative.load()` initialises the native
library, and `PageExtractor.extractPage(layout, 0, bytes)` detects the four
markers, solves a homography, and warps each inset answer-box rectangle onto its
own canonical size (VERIFIED by reading).

**Proof:** the photo appears with a quadrilateral drawn over each answer box,
and a **Continue** button. If instead you get "Choose another photo" with a
message naming a corner, the markers were not all found — §6.5.

Tap **Continue**. The crops are handed to the grading screen through
`WorksheetSession`, an in-memory holder (VERIFIED by reading) — **do not
background the app or let the screen lock from here on; process death loses the
crops and the next screen will say "scan again".**

### Step 9 — Grade on device, then upload
*Process: `WorksheetGrader` → `LocalGradingService` → LiteRT-LM; then two HTTP calls.*

Grading starts automatically. Boxes are marked **one at a time, in the teacher's
document order**, each appearing as it finishes.

**Proof it is really running the model:** there is a visible pause per box —
seconds, not milliseconds — and the result carries a transcription of the
handwriting. Instant results with empty transcriptions mean Step 6 was skipped
(§6.1).

When every box is done, tap **Upload**.

**Proof:** the Node console shows `POST /api/submissions` (multipart, one
`image_<question_id>` part per box) followed by `POST /api/submissions/{id}/grade`
returning **201**. The order is forced by the server — the grade route rejects a
transcription for a question with no answer row yet (VERIFIED by reading). The
screen reaches **Submitted**.

### Step 10 — Teacher sees the result
*Process: Node → Postgres.*

**[PC]**, with the teacher token from Step 5:

```
curl -s -H "Authorization: Bearer $T" \
  http://localhost:3000/api/grades/assignment/<ASSIGNMENT-ID>
```

**Proof:** a grade row with `obtained_marks`, `feedback` reading one line per box
(`Q41 (3/5): ...`), `confidence` (the **minimum** across boxes, not the mean),
and `graded_by: "local_model"`.

The uploaded crops are on disk under
`ASC_Capstone/server/uploads/answers/` and are served at
`http://localhost:3000/uploads/answers/<filename>` — open one in a browser to
show the actual rectified crop the model was given. The path is on each answer
row: `curl .../api/submissions/assignment/<ID>` with the teacher token lists
them.

The `answers[].answer_text` values are the transcriptions the model read off the
handwriting. Those are the most persuasive thing on screen — show them.

---

## 6. Where it will actually break

Ranked by likelihood, from the code — not generic advice. Every entry names its
symptom, how to tell it apart from the one next to it, and the fastest thing to
do about it while people are watching.

### 6.1 Marking data never filled in — **most likely, and the most embarrassing**

*Status: CODE-READ. The gap is certain; the demo consequence has never been
observed because nothing has ever been imported.*

`WorksheetGrader.gradeOne` short-circuits on `!question.isGradeable`, where
`isGradeable = marks != null && !modelAnswer.isNullOrBlank()`, and returns
`NeedsReview` **without calling `gradingService.grade` at all** (VERIFIED by
reading). Import sets `model_answer = ""` on every question and no route can
change it.

**Symptom:** every box comes back "needs review", `obtained_marks: 0`,
`confidence: 0.0`, `needsManualReview: true`.

**Discriminator — and it is unmistakable:** the results appear **instantly**,
all of them, with **empty transcriptions**. Real inference takes seconds per box
and always produces some transcription, even a wrong one. Instant + empty =
this. Slow + transcribed but zero = the model genuinely marked it zero, which is
a different (and honest) outcome.

**Fix, mid-demo:** run the Step 6 `UPDATE`, then on the phone go Back and re-enter
the grading screen — `WorksheetGradingViewModel.start()` re-fetches the
assignment and re-resolves (VERIFIED by reading). The crops survive in
`WorksheetSession`, so **no re-scan is needed**. Roughly 30 seconds if psql is
already open. Keep it open.

### 6.2 Postgres not running — **VERIFIED down right now**

**Symptom:** `npm run dev` prints a Prisma connection error and the process
exits; nothing ever listens on 3000.

**Discriminator:** `docker ps` errors on the named pipe → Docker Desktop itself
is down. `docker ps` works but lists nothing → the engine is up, the container is
stopped.

**Fix:** start Docker Desktop, wait for it to go steady, `docker start
capstone-db`, then `npm run dev` again.

**If the container no longer exists** (`No such container`) you are recreating
the database from scratch, which loses every seeded account and imported
assignment. It is a five-minute job, not a thirty-second one — which is exactly
why §7 says confirm this the day before:

```
docker run -d --name capstone-db -p 5433:5432 \
  -e POSTGRES_USER=capstone -e POSTGRES_PASSWORD=capstone -e POSTGRES_DB=capstone \
  postgres:16
cd "C:\Users\HP\OneDrive\Desktop\capstone app\ASC_Capstone\server"
npx prisma migrate deploy
```

(The image and port mapping are carried from the 2026-08-30 audit; **not verified
this session**, because the engine was down.) Then re-seed §7's accounts and
re-run the import.

Do **not** repoint `DATABASE_URL` at the native 5432 instance. It rejects the
`capstone` role (VERIFIED) and has none of the schema.

### 6.3 `TEACHER_API_BASE_URL` still pointing at localhost

*Status: VERIFIED absent from `.env`; the 502 path is CODE-READ.*

**Symptom:** `POST /assignments/import` → **502**,
`"Teacher worksheet service unreachable at http://localhost:8000/api/questions/<uuid>: fetch failed"`.

**Discriminator:** the URL is printed in the error message. If it says
`localhost`, it is this. If it says the Mac's IP, it is §6.4.

**Fix:** append the line from §3.2 to `server/.env`, restart `npm run dev`.

### 6.4 uvicorn bound to 127.0.0.1, or the macOS firewall

**Symptom:** the same 502, but naming the Mac's IP.

**Discriminator:** three curls, in order —
`curl http://localhost:8000/` on the Mac (proves the process is alive),
`curl http://<mac-ip>:8000/` on the Mac (proves the bind address),
`curl http://<mac-ip>:8000/` from the PC (proves the firewall and the network).
The first one that fails names the cause.

**Fix:** restart uvicorn with `--host 0.0.0.0`; if that is already true, it is the
macOS firewall (§4.2 case 2).

### 6.5 Extraction against a real photograph — **never done, by anyone**

*Status: UNVERIFIED, emphatically. Every one of the 42 extractor tests runs
against `sample_page.png`, a **rendered** 1242×1756 page, not a photo
(`CLAUDE.md` §7.6). Perspective, blur, glare, focus and partial occlusion have
never been tested. This is the largest genuine unknown in the demo.*

Three distinct outcomes, and telling them apart matters:

| Symptom on screen | Meaning | Fix |
|---|---|---|
| "The top-left corner marker is not visible" (or another corner), with "N of 4 corner markers were found" | `MarkersNotFound` — framing, blur, glare, a finger, or the marker printed off the page edge | Re-shoot: flatter, more light, all four corners well inside frame, no flash. **This is the most likely photo failure.** |
| "The corners were found but the page could not be squared up" | `RegistrationFailed` — the four detected centres are near-collinear or degenerate | Almost always an extreme angle. Re-shoot straight on |
| Crops appear, look plausible, but are cut off or offset | The silent one — see §6.6 | Nothing fixable live |

**Mid-demo workaround:** have a **known-good photo already in the phone gallery**
that you extracted successfully during the dry run (§7). Pick that one. It is
still a real photo of a real printed sheet; you are only removing the
photography from the live path.

Also note fit-to-page printing shrinks a 60 px marker to about **10.2 mm** on A4
(`TEACHER_NOTES.md` §1.3). Still detectable, but do not print smaller than A4.

### 6.6 A silently wrong marker contract

*Status: CODE-READ here; the displacement figures are from `INTEGRATION_AUDIT.md`
§2.3 run J, verified 2026-08-30, not re-run.*

If his `MARKER_SIZE_PX`/`MARKER_MARGIN_PX` ever differ from the values my server
hardcodes, extraction **still reports success**: four markers found, homography
solved, crops produced — and every crop displaced by ~11 px for a 10 px constant
change, or ~990 px if marker ids 2 and 3 are swapped.

**Symptom:** crops that are systematically shifted — the caption included, the
first line of writing clipped, or (in the swapped case) crops of blank paper.

**Discriminator against §6.5:** the markers were *found*. The screen shows an
overlay and a Continue button. The quads sit at plausible-looking but wrong
places.

**Prevention, not a fix:** confirm no `backend/.env` exists on the Mac (§0.1,
§3.4). There is nothing to do about this live.

### 6.7 On-device LiteRT-LM — never executed on any device

*Status: UNVERIFIED. `CLAUDE.md` §1 is explicit: engine construction,
`initialize()`, inference, the GPU vision backend, latency, and whether the app
uid can read `/data/local/tmp/llm/` are all unrun. There is no benchmark and no
recorded output anywhere in either repo.*

**Symptoms and discriminators:**

- **The "Check model file" probe says not usable** → the file is missing,
  truncated, or unreadable by the app uid. The probe prints a `diagnosis` naming
  the likely cause and the exact `adb` command to fix it (VERIFIED by reading
  `ModelFileStatus`). Follow it.
- **"Load engine" throws** → native construction failed. Try the text-only
  `GEMMA3_1B` spec from the model picker to separate an engine fault from a
  vision fault; that is precisely what that entry exists for (VERIFIED by
  reading `ModelSpec`'s KDoc).
- **Inference is very slow** → LLaVA-OneVision 0.5B spends about 730 tokens on
  each image against a 2048-token budget. Two boxes is fine; ten would not be.
  Keep the worksheet to 2–3 boxes.
- **Every box says "the handwriting could not be read confidently"** → the model
  ran, the retry ran, and the reply did not parse or `legible` came back false.
  Distinguish from §6.1 by the elapsed time and by a non-empty transcription.

**Mid-demo workaround:** exercise the **Model** screen instead of hiding the
failure — "Check model file", "Load engine", "Transcribe handwriting" on a
gallery crop. That screen is a purpose-built probe and it demonstrates the
on-device path honestly even if the full worksheet run stalls.

Note `ModelSpec` actually has **three** entries — `LLAVA_OV_05B` (default),
`QWEN2_VL_2B`, `GEMMA3_1B` (VERIFIED by reading). `CLAUDE.md` §3.8 says two; it
is out of date on that point. `Qwen2-VL-2B.litertlm` is 1.78 GB and carries
`expectedBytes = null`, so its size is not checked — do not switch to it live.

### 6.8 Multi-page worksheet blocked before a photo is taken

**Symptom:** the Scan screen shows "This worksheet has answers on 2 pages (0, 1).
This screen reads a single page." and offers no picker.

**Discriminator:** it appears **before** you choose any photo. Every other
failure needs a photo first.

**Fix:** there is none live. This is why §5 Step 1 and §7 both insist on a
single-page worksheet. Fall back to the pre-imported assignment.

### 6.9 `LayoutValidator` refuses the layout

*Status: CODE-READ. Its 20 unit tests pass (2026-08-30), but never against a
served layout.*

**Symptom:** "This worksheet's printed layout is not usable: `<reason>`", shown
as Blocked, not Retake.

The refusal worth knowing about: `LayoutValidator` requires that sorting the
boxes by `(pageIndex, bbox.y)` reproduces the served array order. His editor
produces single-column, top-to-bottom documents, so this holds — but a layout
whose document order is not vertical is **refused outright** (`CLAUDE.md` §7.5).
Other refusals: fewer than four markers, marker ids not exactly `{0,1,2,3}`,
overlapping boxes, a box too short to survive the 23 px top inset.

**Discriminator:** the message names the exact reason and the state is Blocked —
another photo will not help.

**Fix:** none live. Fall back to the pre-imported assignment.

### 6.10 Login rate limit locks out the phone

**Symptom:** login fails with `{"error":"Too many login attempts. Try again later."}`
even with the right password.

**Discriminator:** the message is explicit and distinct from `Invalid credentials`.

**Fix:** wait 15 minutes — or, faster, restart `npm run dev`; the limiter is
in-memory, so a restart clears the counter (CODE-READ: `express-rate-limit` with
no store configured). Better: **log in once during setup.** The token is stored
in DataStore and `JWT_EXPIRES_IN=7d` (VERIFIED), so a login done that morning is
still valid.

### 6.11 Finalize fails — Playwright, Chromium, or the KaTeX CDN

*Status: CODE-READ. The render has never run anywhere.*

Three failure modes, all surfacing as a **500** on finalize:

| Cause | Symptom in the FastAPI traceback |
|---|---|
| `playwright` not installed | `ImportError` inside `render_finalized_question` |
| Chromium not downloaded | Playwright's own "Executable doesn't exist ... run `playwright install`" |
| KaTeX CDN unreachable | **hangs ~15 s**, then `TimeoutError` on `wait_for_function("window.__ready === true")` |

The third is the subtle one. `window.__ready = true` is set by an inline script
that runs *after* `renderMathInElement(...)`; if `cdn.jsdelivr.net` is
unreachable that call is undefined, the script throws, `__ready` is never set,
and the wait times out (VERIFIED by reading `doc_renderer.py`). **A captive
portal is enough to cause this** — the venue Wi-Fi may need its splash page
accepted on the Mac before anything reaches jsdelivr.

**Discriminator:** the 15-second hang. The other two fail instantly.

**Fix:** open any website on the Mac first to clear a captive portal. If it still
fails, **skip live authoring and use the pre-finalized worksheet** (§7) — this is
exactly the fallback it exists for.

### 6.12 Do not press Clone

`clone_question` reuses the original `AnswerBox.id` values, and `AnswerBox.id` is
the sole primary key, so cloning any question that has answer boxes raises
`IntegrityError` → unhandled → **500** (VERIFIED by the 2026-08-30 audit, run H,
over HTTP; confirmed by reading `questions.py:180-202` this session).

**Symptom:** on his frontend, the Clone button appears to do nothing — the error
is swallowed into `console.error`.

There is no recovery, and no route can delete a question either. Just never press
it. If a finalized worksheet turns out to be wrong, author a new one.

### 6.13 Re-import returns 409 — correct, but looks like a bug

If you import the same `external_question_id` twice **and the answer box id set
changed** (because he deleted and re-inserted a box between finalizes), the route
returns **409** with `added` / `removed` / `unchanged` id lists rather than
guessing which stored model answer belongs to which new rectangle (VERIFIED by
reading).

**Symptom:** a 409 mentioning "Re-import refused".

**Discriminator:** an *unchanged* box set re-imports fine and returns **200**,
refreshing the geometry only and leaving your marking data intact.

**Fix:** import under a new assignment — but note the composite unique index is
`(teacher_id, external_question_id)`, so re-importing the same uuid as the same
teacher will always find the existing row. Finalize a fresh worksheet, or run the
import as a second teacher account.

### 6.14 `ResultScreen` shows "Grade: null" — cosmetic

The server sends `obtained_marks: Int`; `GradeDto` declares `grade: String`, so
Gson leaves it null and the Results screen renders `"Grade: null"`
(`CLAUDE.md` §4.2, unchanged). The data is correct in the database and correct in
the `GET /grades/assignment/:id` response.

**Fix:** show the result via the curl in Step 10, not via the Results tab. Say
plainly that it is a known DTO mismatch on the read-back path.

### 6.15 A constant vertical offset in every `bbox_y`

*Status: CODE-READ, unquantifiable — neither Playwright pass has ever run.*

`_measure` wraps every block in `<div data-idx="i">` while the print pass emits
the same blocks as raw siblings (VERIFIED by reading). Adjacent-sibling margin
collapsing is not guaranteed to match across those two structures, so the printed
`y` can drift from the measured `y` stored in `bbox`.

**Symptom:** every crop shifted vertically by the same amount — the caption
included at the top, or the last line of writing cut off at the bottom.

**Discriminator against §6.6:** a marker-contract error usually shifts
horizontally too and scales with page position; this one is a uniform vertical
translation.

**Fix:** none live, and it is not fatal — the inset already trims 23 px off the
top of every box. Worth measuring properly after the demo.

### 6.16 Smaller things that will not stop the demo

- `page_count` is always `null` in his responses; my server infers it from
  `max(page_index)+1` (VERIFIED by reading both sides). Harmless for one page.
- `qr_check` will report `absent` for every box; the QRs are undecodable at
  1.12 px/module and nothing in the demo path reads one.
- `WorksheetSession` does not survive process death — background the app between
  Scan and Grade and you must re-scan. It says so honestly rather than grading
  stale crops.
- The app attaches the image regardless of `spec.supportsVision`, so selecting
  `GEMMA3_1B` (text-only) and then grading will produce nonsense rather than an
  error (`CLAUDE.md` §6.3). Use it for the "Load engine" probe only.
- Two Kotlin deprecation warnings at build time (`HomeScreen.kt:45`,
  `ScanScreen.kt:300`) are expected and harmless.

---

## 7. Prepare in advance

Ordered by how much time it saves you. Everything here removes a moving part
from the live run.

### The day before

1. **Start Docker Desktop and `docker start capstone-db`, and confirm it.**
   This is the one item verified broken right now. Confirm
   `psql "postgresql://capstone:capstone@127.0.0.1:5433/capstone" -c "select 1"`
   answers. If the container is gone, rebuild it now (§6.2), not on the day.
2. **Run the §0.1 Mac inventory** and install whatever prints ABSENT —
   especially `sqlalchemy`, `playwright` (**plus `python3 -m playwright install
   chromium`**), and `qrcode`.
3. **`npm install` in `v-2.1.1/frontend`.** `node_modules` is absent in the copy
   here (VERIFIED); assume it has never been run on the Mac either. It is a large
   first-time download.
4. **Boot all four server processes once and confirm each probe in §1.** The
   FastAPI app has never been booted by anyone; find out what it does with a
   day's slack, not five minutes'.
5. **Seed both accounts by curl** (register is not rate limited; login is):

   ```
   curl -s -X POST http://localhost:3000/api/auth/register \
     -H "Content-Type: application/json" \
     -d '{"email":"teacher@demo.local","password":"demopass123","role":"teacher"}'

   curl -s -X POST http://localhost:3000/api/auth/register \
     -H "Content-Type: application/json" \
     -d '{"email":"student@demo.local","password":"demopass123","role":"student"}'
   ```

   Each returns 201 with a `token`. The teacher account **must** be created this
   way — the app's register screen hardcodes `role=student`.

6. **Push the model and verify it from inside the app:**

   ```
   adb shell mkdir -p /data/local/tmp/llm
   adb push "C:\Users\HP\OneDrive\Desktop\capstone app\LLaVA-OneVision-0.5B.litertlm" /data/local/tmp/llm/
   adb shell ls -l /data/local/tmp/llm/
   ```

   Expect exactly **829,262,144** bytes. A truncated push is the failure this
   guards against, and native LiteRT-LM tends to abort rather than throw on one.
   Then open the app → **Model** → **Check model file** → must say usable, and
   **Load engine** → must succeed. That is the only evidence anyone will ever
   have that the engine constructs on this device.

7. **`.\gradlew installDebug`** and leave the app installed.

### The dry run — do the whole thing end to end, once

8. **Author, finalize, export and print a worksheet.** Keep it to **one page,
   2–3 answer boxes, all labels filled in, `minHeight ≥ 130`**. Print
   **fit-to-page**. Record its `question_id`.
9. **Fill the sheet in with a dark pen and photograph it.** Take several shots.
   Run each through the app's Scan screen and **keep the one that extracts
   cleanly in the phone's gallery.** That photo is your §6.5 insurance.
10. **Import it and fill in the marking data** (Steps 5 and 6). Record the
    assignment id. This assignment, fully marked up and proven to grade, is the
    **fallback the entire demo can restart from** if live authoring or finalize
    fails.
11. **Run Steps 7–10 completely.** Confirm a grade row exists and the
    transcriptions are non-empty. Then delete only the submission and grade rows
    so the assignment shows as pending again:

    ```sql
    DELETE FROM grades  WHERE assignment_id = <ASSIGNMENT-ID>;
    DELETE FROM answers WHERE submission_id IN
      (SELECT id FROM submissions WHERE assignment_id = <ASSIGNMENT-ID>);
    DELETE FROM submissions WHERE assignment_id = <ASSIGNMENT-ID>;
    ```

    Leave the questions and the layout alone — they hold the marking data.

12. **Author and finalize a *second* worksheet and leave it un-imported**, so the
    live "import" step has something genuinely new to import even if live
    authoring fails.

### On the day, before anyone is watching

13. Disable sleep and screen lock on **both** laptops and on the phone.
14. Plug the phone in, `adb devices`, then `adb reverse tcp:3000 tcp:3000`.
    **Re-run the reverse after the final replug**, whenever that is.
15. Get both LAN IPs (`ipconfig getifaddr en0` on the Mac; the PC is
    `<PC-LAN-IP>` today but DHCP) and **write them on paper.**
16. Set `TEACHER_API_BASE_URL` to the Mac's current IP and restart `npm run dev`.
17. Open a website on the Mac to clear any captive portal, so finalize can reach
    the KaTeX CDN.
18. Log in on the phone once, so the token is cached and the rate limiter is
    untouched.
19. Leave open: the Node console, a psql session on the demo database, and a curl
    terminal with `$T` already holding the teacher token.

### Carry

A spare USB cable · the printed, filled-in worksheet from the dry run · the
`question_id` and assignment id on paper · both IPs on paper.

---

## 8. T-minus checklist

Copy-pasteable, in start order.

```
--- WINDOWS PC ---
# 1. Docker Desktop (GUI), wait for steady whale
docker start capstone-db
docker ps --filter name=capstone-db
psql "postgresql://capstone:capstone@127.0.0.1:5433/capstone" -c "select 1"

# 2. set the Mac IP, then:
cd "C:\Users\HP\OneDrive\Desktop\capstone app\ASC_Capstone\server"
npm run dev
curl -s http://localhost:3000/api/health          # {"status":"ok"}

--- MAC ---
cd v-2.1.1/backend
uvicorn main:app --host 0.0.0.0 --port 8000
curl -s http://localhost:8000/                    # {"status":"ok","project":"..."}

cd ../frontend
npm run dev
# open http://localhost:5173/  (localhost, NOT the LAN IP)

--- WINDOWS PC, cross-machine check ---
curl -s http://<MAC-IP>:8000/                     # same JSON. If not -> 4.2

--- PHONE ---
adb devices -l                                    # one line ending "device"
adb reverse tcp:3000 tcp:3000
adb reverse --list                                # host-N tcp:3000 tcp:3000
# app: Model -> Check model file -> usable
# app: log in as student@demo.local
```

## 9. If it dies on stage

| What you see | Almost certainly | Fastest move |
|---|---|---|
| Every box "needs review", instantly, no transcriptions | §6.1 marking data blank | Run the Step 6 `UPDATE`, go Back, re-enter grading. No re-scan |
| `npm run dev` exits with a Prisma error | §6.2 Postgres down | Start Docker Desktop, `docker start capstone-db` |
| Import 502 naming `localhost:8000` | §6.3 | Fix `.env`, restart the server |
| Import 502 naming the Mac's IP | §6.4 | Restart uvicorn with `--host 0.0.0.0`; else macOS firewall |
| Finalize hangs ~15 s then 500 | §6.11 KaTeX CDN | Clear the captive portal; else use the pre-finalized worksheet |
| Finalize 500s instantly | §6.11 playwright/chromium/qrcode missing | Use the pre-finalized worksheet |
| "N of 4 corner markers were found" | §6.5 photo | Pick the known-good gallery photo from the dry run |
| Blocked before any photo is picked | §6.8 multi-page or §6.9 bad layout | Switch to the pre-imported assignment |
| App hangs ~10 s then a connect error | §4.3 `adb reverse` lost | `adb reverse tcp:3000 tcp:3000`, retry |
| "Too many login attempts" | §6.10 rate limit | Restart `npm run dev` to clear the counter |
| Grading screen says "scan again" | `WorksheetSession` lost to process death | Re-scan. Do not background the app |
| Results tab shows "Grade: null" | §6.14 known DTO mismatch | Show the result with the Step 10 curl instead |

---

## 10. What is genuinely unknown

Stated plainly, so nothing in this document reads as more certain than it is.

**Never executed by anyone, at any point, in either repo:**

- His FastAPI app booting, and every one of its 12 routes.
- The Playwright measure pass, the print pass, and the PDF.
- `POST /api/assignments/import` — the entire join between the two systems.
- The student `GET /assignments/:id` layout payload over HTTP. Its shape is
  pinned only by a unit test parsing a hand-written fixture.
- Extraction from a photograph. All 42 extractor tests use a rendered PNG.
- `OpenCvNative.load()` on a device — it is quarantined off every test classpath
  by construction, so no test can reach it.
- LiteRT-LM engine construction, inference, the GPU vision backend, and whether
  the app uid can read `/data/local/tmp/llm/`.
- The Android app completing a login, an assignment fetch, an upload, or a grade
  post against the server.

**Never inspected:** the Mac, entirely. Run §0.1.

**What *is* solid:** both Android modules compile and 126 unit tests pass; the
server typechecks and its schema is migrated; the registration arithmetic is
verified against a real ArUco page to within 3 px; and the 14 pre-existing HTTP
routes were exercised by curl on 2026-08-21. All of that is from the 2026-08-30
audit and was not re-run this session — the environment checks in §0 and §1 are
the only things executed today.
