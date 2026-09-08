# LOOP_STATE.md — Denmark Road Assistant repair & hardening loop

This file is the memory of the repair loop. If the session restarts, read this
file first and resume from the phase named below. Do not restart from Phase 0.

**Project location:** `D:\denmark-road-assistant-main`
**Loop started:** 2026-09-08

---

## CURRENT POSITION

| | |
|---|---|
| **Current phase** | Phase 1 — Repository hygiene and safety net |
| **Phases complete** | Phase 0 |
| **Next up** | Phase 2 — Get it running |

---

## PHASE 0 — ENVIRONMENT SURVEY  ✅ COMPLETE

Ran on 2026-09-08. No code changed.

### Tool versions found

| Tool | Version found | Needed | Verdict |
|---|---|---|---|
| `java` | OpenJDK 25.0.1 LTS (Temurin-25.0.1+8) | 17+ | OK (see risk R1) |
| `javac` | 25.0.1 | 17+ | OK |
| `JAVA_HOME` | **unset** | — | Not fatal; `java` is on PATH |
| `mvn` | **not installed** | — | Not needed: `backend/mvnw` wrapper is present |
| `backend/mvnw` | present (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`) | — | OK |
| `node` | v20.19.0 | Angular 21 needs ^20.19 \|\| ^22.12 \|\| ^24 | OK — exactly on the minimum |
| `npm` | 10.8.2 | — | OK |
| `python` | 3.14.3 (default on PATH) and 3.12.10 | 3.10+ | OK — **3.12 chosen**, see decision D1 |
| `pip` | 25.3 | — | OK |
| `git` | 2.55.0.windows.5 | — | OK |
| Android SDK | **absent** — `ANDROID_HOME` and `ANDROID_SDK_ROOT` unset, no `%LOCALAPPDATA%\Android\Sdk`, no Android Studio | — | **Android build: configuration-only** |

Nothing is missing or too old, so the loop did **not** stop for an install list.

### Repository state at survey time
- **Not a Git repository** — no `.git` directory exists. Phase 1 runs `git init`.
- 189 files excluding `node_modules`, `target`, `dist`, `.angular`, `build`, `.gradle`.
- Large / unwanted files present: `ml-service/models/road_condition_model.joblib` (**82 MB**),
  `ml-service/data/synthetic_denmark_routes.csv` (1.4 MB), `backend.zip` (16 KB),
  three `__pycache__` files including the stray `main.cpython-313.pyc.2444357645200`.
- Root ignore file is misnamed `.gitignore.txt` (defect S4) — confirmed by direct listing.

### Decisions recorded

**D1 — Python 3.12, not 3.14, for the ML service virtual environment.**
Two Pythons are installed. `requirements.txt` pins `scikit-learn==1.7.1`,
`numpy==2.3.2`, `pandas==2.3.2`. Those exact pins predate CPython 3.14 and do not
publish `cp314` wheels, so a 3.14 environment would try to compile them from
source and fail on a machine without a C/C++ toolchain. Python 3.12.10 has wheels
for all of them. The venv is created with `py -3.12 -m venv .venv`. This will be
written into the README so it reproduces on another machine.

**D2 — Android is configuration-only.**
No Android SDK on this machine, so Phase 7 repairs configuration and writes
instructions, and does **not** attempt to build an APK. Every Phase 7 exit check
that requires the SDK is recorded as "not runnable here" with the exact command
the owner should run once Android Studio is installed.

### Risks flagged for later phases

**R1 — JDK 25 versus Spring Boot 3.5.13.** The only JDK on this machine is Java 25;
the project sets `<java.version>17</java.version>`. Compilation is fine (`--release 17`),
but the app also *runs* on JDK 25, and Spring/Hibernate use bytecode generation that
can reject unknown class-file versions. This is unverified until the backend actually
starts in Phase 2. If it fails, the fix is to pin a toolchain or document a JDK 17/21
requirement — it will not be assumed away.

**R2 — Node 20.19.0 is exactly Angular 21's floor.** Any Angular tooling that needs
20.19.1+ would fail. Unverified until `npm ci` and `npm run build` run in Phase 2.

---

## PHASE 1 — REPOSITORY HYGIENE  🔄 IN PROGRESS

(updated as work lands)

---

## VERIFICATION SUITE RESULTS

Not yet run — starts at the end of Phase 2.

| ID | Check | Result |
|---|---|---|
| V1–V20 | see section 5 of the loop brief | not yet run |
