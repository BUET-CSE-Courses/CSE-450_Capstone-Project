# Capstone: on-device grading, student app

An Android app for students. The student signs in with a Microsoft account,
joins a course, and opens an assignment. They print it, solve it on paper,
photograph the pages and hand them in. The phone then crops each answer box
from its own photos and **grades it on the device** with a local vision model
(Gemma 4 E2B through LiteRT-LM). The marks are posted to the server as
provisional grades for the teacher to review and release.

## How it fits with the web end

The app has no backend of its own. Its only server is
**[Script-Checker-Web-End](https://github.com/Undying2021Dreams/Script-Checker-Web-End)**
(FastAPI + Postgres + a React teacher UI), which is a separate repo owned by a
teammate. The app needs the routes on the web end's branch
**`feature/on-device-grading`**.

```
 Teacher (browser)                        Student (this app)
 writes and finalizes the paper           Microsoft sign-in (MSAL)
 reviews, overrides and releases marks    joins a course, lists assignments
            |                             downloads the assignment pack
            v                             uploads page photos, hands in
 Script-Checker-Web-End  <--------------  crops boxes on the phone (:extractor)
 FastAPI + Postgres, under /api           grades each box on the phone
            |                             posts the results, shows grades
            v
 self-hosted LLM: re-marks only the boxes the phone flagged (optional)
```

The full design, the route contract and the session history are in
[`current_plan_to_be_done.md`](current_plan_to_be_done.md). Section H (Status)
holds the current state.

## Repository layout

| Path | What it is |
|---|---|
| `Capstone_Android/app` | `:app`: Compose UI, networking, MSAL sign-in, grading with LiteRT-LM, WorkManager |
| `Capstone_Android/extractor` | `:extractor`: ArUco page registration and answer-box crops (OpenCV) |
| `current_plan_to_be_done.md` | The plan and the status log |
| `CLAUDE.md` | Working rules for AI-assisted sessions |
| `HANDOFF.md`, `RUNBOOK.md`, `INTEGRATION_AUDIT.md`, `CLAUDE_OLD.md` | History only. They describe an older architecture (a Node server) that has been replaced. Don't follow them as instructions. |

`Capstone_Android/README.md` and `API_REQUIREMENTS.md` date from that older
architecture too. Where they disagree with this file, this file is right.

## The model file (not in this repo)

Model weights are gitignored. Download the model and push it to the phone
yourself.

| | |
|---|---|
| File | `gemma-4-E2B-it.litertlm` |
| Size | 2,588,147,712 bytes (about 2.6 GB) |
| Source | Hugging Face, [`litert-community/gemma-4-E2B-it-litert-lm`](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) (Apache-2.0, not gated) |
| Path on the phone | `/data/local/tmp/llm/gemma-4-E2B-it.litertlm` |

From the folder that holds the downloaded file:

```powershell
adb shell mkdir -p /data/local/tmp/llm
adb push .\gemma-4-E2B-it.litertlm /data/local/tmp/llm/
adb shell ls -l /data/local/tmp/llm/     # expect 2588147712 bytes
```

If the app can't read the file, it shows the `chmod` command to run.
`/data/local/tmp` is a development-only location.

The Model Test screen can also load `Qwen2-VL-2B.litertlm`,
`LLaVA-OneVision-0.5B.litertlm` and `gemma3-1b-it-int4.litertlm` from the same
folder. Real grading doesn't need them.

## Requirements

- Android Studio, with JDK 21 to run Gradle. The modules compile to Java 17.
- An **arm64** phone (or an arm64 emulator image). `:extractor` ships OpenCV
  for `arm64-v8a` only.
- A running Script-Checker-Web-End on the `feature/on-device-grading` branch:
  your own local copy for the `local` build, or the teammate's deployment for
  the `deployed` build.

## `Capstone_Android/local.properties`

This file is gitignored and specific to each machine. Android Studio writes
`sdk.dir`; add the rest yourself. Key names only:

| Key | Used by | What it holds |
|---|---|---|
| `sdk.dir` | Gradle | Path to your Android SDK |
| `webend.local.clientId` | `local` flavor | The Entra application (client) ID that **your** local web end validates tokens against |
| `webend.deployed.clientId` | `deployed` flavor | The Entra application (client) ID of the deployed web end |
| `webend.deployedUrl` | `deployed` flavor | The deployed web end's URL, with or without the trailing `/api` |
| `webend.signatureHash` | both | Base64 SHA-1 of the signing certificate (not URL-encoded). It forms the Android redirect `msauth://com.example.capstone/<hash>`, which must be registered in the Entra app. |

A build with a key missing still succeeds. The app then refuses to sign in and
names the missing key.

## Build, install and test

Run these from `Capstone_Android` in Windows PowerShell:

```powershell
cd Capstone_Android

# Unit tests (JVM + Robolectric; no phone needed)
.\gradlew testLocalDebugUnitTest        # :app, local flavor
.\gradlew testDeployedDebugUnitTest     # :app, deployed flavor
.\gradlew :extractor:testDebugUnitTest  # :extractor (ArUco + crop contract)

# Local build: talks to http://localhost:8000/api/ on the phone
.\gradlew installLocalDebug
adb reverse tcp:8000 tcp:8000           # phone -> your local web end; re-run after every replug

# Deployed build: talks to webend.deployedUrl
.\gradlew assembleDeployedDebug
.\gradlew installDeployedDebug
```

Both flavors install as `com.example.capstone`, so installing one replaces the
other.

| Flavor | Server | Entra client ID | Server re-marking of flagged boxes |
|---|---|---|---|
| `local` (default) | `http://localhost:8000/api/` via `adb reverse` | `webend.local.clientId` | off (debug builds can turn it on) |
| `deployed` | `webend.deployedUrl` | `webend.deployed.clientId` | on |

## Grading, briefly

- **Model:** Gemma 4 E2B, in both flavors.
- **Two turns per box.** Turn 1 sends only the crop and the question text, with
  no model answer, and asks for a transcript of each part. Turn 2 is text only:
  it marks that transcript against the model answer. The phone adds up the
  part marks.
- **Review:** a box goes to review (and, if enabled, the server's
  self-hosted re-marking) when the reply can't be read or no part was
  transcribed.
- **Debug switches** on the Home screen of a debug build: plain one-turn
  grading, crop trim, and forced server re-marking.

## Not in this repo, on purpose

The web end, the legacy folders (`ASC_Capstone`, `v-2.1.1`), model files,
build outputs, `local.properties`, `.env` files, keystores, and any real answer
keys or student work. Test fixtures use synthetic pages and placeholder
answers.
