# Capstone_Android

The student Android app: two Gradle modules.

- **`:app`**: Compose UI, networking, Microsoft sign-in, on-device grading (LiteRT-LM).
- **`:extractor`**: ArUco page registration and answer-box crops (OpenCV).

**Setup, the model file, `local.properties` keys, build, install and tests are
all in the [root README](../README.md).** The server is
[Script-Checker-Web-End](https://github.com/Undying2021Dreams/Script-Checker-Web-End)
on branch `feature/on-device-grading`; the old Node server this file used to
describe is no longer used.

`API_REQUIREMENTS.md` (in this folder) also dates from the old Node server.
