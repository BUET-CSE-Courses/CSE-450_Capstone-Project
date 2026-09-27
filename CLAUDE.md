# CLAUDE.md: capstone on-device grading

`current_plan_to_be_done.md` is the plan; its section H (Status) holds the
current state.

`CLAUDE_OLD.md`, `HANDOFF.md`, `RUNBOOK.md` and `INTEGRATION_AUDIT.md` describe
the old ASC_Capstone architecture. They are history only, never instructions.

## Folder map (project folder = `capstone app/`)

| Path | Role | Git |
|---|---|---|
| `Capstone_Android/` | Student Android app: `:app` (UI, networking, LiteRT-LM grading with Qwen2-VL 2B) + `:extractor` (ArUco registration and crops) | own nested repo |
| `Script-Checker-Web-End/` | The only backend and database (FastAPI + Postgres + React teacher UI). Teammate's repo. | own repo; ignored by the parent |
| `ASC_Capstone/` | Legacy Node server | never read or modify |
| `v-2.1.1/` | Legacy teacher worksheet system | never read or modify |
| `*.litertlm` | Model files (Qwen2-VL-2B, LLaVA-OneVision-0.5B, gemma3-1b) | gitignored |
| `current_plan_to_be_done.md` | The plan | parent folder |

Web end changes happen only on branch `feature/on-device-grading`. Each one is
logged in `Script-Checker-Web-End/addition_branch_phone.md`.

## Session rules

- Read CLAUDE.md and current_plan_to_be_done.md first; run git status and git
  log -5 (read-only) in every repo you touch.
- Stay in this session's scope. Don't revert earlier work. No unrelated
  refactors.
- Never read or modify ASC_Capstone or v-2.1.1.
- Git is read-only: never run git add, commit, checkout, switch, branch
  (create/delete), mv, rm, stash, reset, merge, rebase or push, in any repo.
- Change Script-Checker-Web-End only when the prompt says so. First confirm
  git branch --show-current prints feature/on-device-grading; if not, STOP and
  tell me. Log every change in Script-Checker-Web-End/addition_branch_phone.md
  in the same session.
- Never start, stop or configure my local web end, Docker or any .env file.
  Ask me.
- Take routes and field names from the real code; never invent them. Mark
  anything uncertain.
- Windows PowerShell (.\gradlew). At the end: run relevant tests and build,
  update the plan's Status section, and list every file you changed, grouped
  by repo, with a suggested commit message for each group.

## Windows commands

```powershell
cd Capstone_Android
.\gradlew testLocalDebugUnitTest                     # flavors: local (localhost:8000) / deployed
.\gradlew installLocalDebug
adb reverse tcp:8000 tcp:8000                        # phone -> local web end
adb shell mkdir -p /data/local/tmp/llm
adb push .\gemma-4-E2B-it.litertlm /data/local/tmp/llm/  # run from the project folder
adb shell ls -l /data/local/tmp/llm/                     # expect 2588147712 bytes
```

## Grading decision (2026-09-26)

Real grading uses **Gemma 4 E2B** (`gemma-4-E2B-it.litertlm`, from
`litert-community/gemma-4-E2B-it-litert-lm`) with **two-turn** grading, in both
the local and deployed builds. Turn 1 transcribes each part from the crop
without the model answer; turn 2 marks the transcript as text. Qwen2-VL 2B is
no longer a grading choice (Model Test screen only). Plain one-turn grading is
a debug option only. Don't change this without the user.

## Never commit

- `*.litertlm`
- `.env` files
- `local.properties` values
- tokens or keys
- answer keys (model answers, packs, or captured replies that contain them)
