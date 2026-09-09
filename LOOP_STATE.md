# LOOP_STATE.md — Denmark Road Assistant repair & hardening loop

The memory of the repair loop. If the session restarts, read this file first and
resume from the phase named below. Do not restart from Phase 0.

**Project:** `D:\denmark-road-assistant-main` · **Loop run:** 2026-09-08 → 2026-09-09

---

## CURRENT POSITION

| | |
|---|---|
| **Current phase** | Phase 9 — final sweep |
| **Phases complete** | 0, 1, 2, 3, 4, 5, 6, 7, 8 |
| **Tests** | **383 passing** — 76 backend (JUnit) + 307 ML (pytest) |
| **Build** | frontend 556.48 kB, inside the 560 kB budget |

### Commits

| | |
|---|---|
| `92ffb34` | initial commit + repository hygiene |
| `58bc9e1` | phase 2–3 — zero-setup H2, real error messages, id-based JWT |
| `f71efd8` | phase 3 — API lockdown, profile session, HTTP timeouts |
| `581e136` | phase 4 — ML rebuild: one feature path, real weather, 5 grades |
| `09420db` | phase 7–8 — Android config, documents, one-command start |
| `2a775b8` | phase 5–6 — test suites, frontend honesty/session, `mvnw.cmd` |

### Restarting the system

```bash
./start-all.sh
```

Or three terminals — see README §3.

---

## PHASE 0 — ENVIRONMENT ✅

| Tool | Version | Verdict |
|---|---|---|
| java / javac | OpenJDK 25.0.1 Temurin | OK — **R1 resolved**, Spring Boot 3.5.13 + Hibernate 6.6.45 run fine on 25 |
| mvn | not installed | Not needed — `mvnw` works (Maven 3.9.14) |
| node / npm | 20.19.0 / 10.8.2 | OK — exactly Angular 21's floor. **R2 resolved**, `npm ci` and `npm run build` both succeed |
| python | 3.14.3 **and** 3.12.10 | **3.12 used** — decision D1 |
| git | 2.55.0 | OK |
| Android SDK | **absent** | **Android = configuration-only** — decision D2 |

**D1 — Python 3.12.** `requirements.txt` pins scikit-learn 1.7.1 / numpy 2.3.2 /
pandas 2.3.2, which publish no `cp314` wheels; a 3.14 venv would compile from
source and fail. Documented in the README.

**D2 — Android configuration-only.** No SDK, so Phase 7 repaired configuration
and wrote instructions. No APK was built. The one Android command that *could*
run here without an SDK (`npx cap sync`) was not run either, because it rewrites
generated files; the README documents it as the required first step.

---

## PHASE 1 — REPOSITORY HYGIENE ✅

The project **was not a Git repository at all**. `git init` on `main`.

Root cause of S4: the ignore file was named `.gitignore.txt`, which Git never
reads, so every rule in it was inert. Renamed and extended. Deleted the committed
`__pycache__` and the stray `main.cpython-313.pyc.2444357645200`. Removed
`backend.zip` after confirming it was a Spring Initializr skeleton whose only
unique file was a boilerplate `HELP.md`. Added `.gitattributes`.

**V19 ✅** — `git ls-files` has no `.pyc`, `.zip`, `.joblib` or `node_modules`.

---

## PHASE 2 — GET IT RUNNING ✅

**B3.** Default was `jdbc:mysql://localhost:3306` with `root`/`root`, so with no
MySQL the backend died. Now file-based H2 by default; MySQL in
`application-mysql.properties`; H2 console in `application-dev.properties`.
`driver-class-name` removed — it was pinned to MySQL *independently of the URL*,
so pointing the URL at H2 still failed.

**Correction to the brief.** `columnDefinition = "LONGTEXT"` was **not** the
blocker it was reported to be — H2 accepts LONGTEXT as an alias for
`VARCHAR(1000000000)`, proven by running H2 2.3.232 directly. It was still a real
defect (bypasses the dialect, breaks PostgreSQL, neutralises `@Lob`) and is fixed.

**V5 ✅ V6 ✅ V9 ✅** — backend starts with no MySQL; health returns ok; the full
journey signup → login → analyse → history works with real OSRM geometry (798
points, 35.6 km Copenhagen→Roskilde) and real Nominatim names.

---

## PHASE 3 — BACKEND CORRECTNESS AND SECURITY ✅

| Defect | Status | Evidence |
|---|---|---|
| F1 errors swallowed | fixed | unknown place → 404 with the real message |
| F1b **nothing was logged** *(new)* | fixed | first failing signup left a totally silent log |
| F2 email change killed the session | fixed | old token → **200** (was 500) |
| S1 committed JWT secret | fixed | `JwtKeyConfig`, fails fast in prod |
| S2 public retrain | fixed | anonymous → **401** |
| S3 leaked `E:\sw8-maps` paths | fixed | no paths in response |
| S7 dead h2-console rule | fixed | moved to `DevH2ConsoleConfig` |
| default-open chain *(new)* | fixed | `anyRequest().authenticated()` |
| no HTTP timeouts *(new)* | fixed | 5s connect / 15s read |
| ML client discarded its own detail *(new)* | fixed | rethrows |
| deprecated `DaoAuthenticationProvider` | removed | build has no deprecation warnings |
| Lombok leaked the BCrypt hash *(new)* | removed | Lombok dropped from the build |

**V11 ✅ V12 ✅ V16 ✅ V17 ✅ V10 ✅**

---

## PHASE 4 — THE ML REBUILD ✅

**The structural change.** Features were derived twice, in two languages, and had
drifted; live vectors covered ~8% of the training distribution. There is now
**one** derivation (`ml-service/app/features.py`) used by both the dataset
generator and the prediction endpoint. `FeatureEngineeringService.java` deleted.

| | Before | After |
|---|---:|---:|
| Accuracy | 0.7788 | **0.8546** |
| Very Poor F1 | **0.00** | **0.880** |
| Very Poor share of training set | 1.02 % | 8.03 % |
| Model artefact | 82 MB | **8.9 MB** |
| Train time from nothing | — | ~5 s |

**V13 ✅** — 1 536-combination sweep produces all five grades. Weather gradient is
monotonic: calm → Excellent 183 / Good 182 / Moderate 19, never Poor; storm →
Poor 251 / Very Poor 55, never Excellent.

**V14 ✅** — every feature's live range sits inside the training range at
**≥ 99 %** coverage (lowest 99.3 %).

**V15 ✅** — a live analysis used 14.6 °C, 0.0 mm, 0.79 humidity, 27.7 km/h —
**identical** to Open-Meteo queried directly for that midpoint and hour.

**V2 ✅** — deleting the model, metadata and dataset and restarting regenerates
all three.

### Corrections to the brief (verified, do not repeat the errors)

- **M2 partly refuted.** `weather_risk` was *character-for-character identical*
  in Java and Python. The dominant divergence was the Java `lowTrafficTime`
  branch and its `0.05` night `timeScore`, which had **no counterpart in
  training at all** — missed by the original audit.
- **M3 partly refuted.** "Byte-identical forever" is false (day, season and OSRM
  distance vary). But it was *worse* than described: one multiplier applied to
  all 11 features (perfectly correlated), compounding to ~9 %, and silently
  keyed on OpenStreetMap label text.
- **New, verified by hand:** the shipped CSV **could not have been produced by
  the committed generator** — 5 837 of 12 000 rows had a `weather_risk` the code
  cannot emit, plus 2 112 impossible speeds, 1 451 impossible rainfalls and 149
  impossible wind values. The model in use was trained by uncommitted code, so
  0.7788 was never reproducible. Now deterministic from seed 42.

---

## PHASE 5 — TESTS ✅  ·  383 passing, none touching the network

**Backend — 76 tests, 9 classes, ~17 s.** Written to catch *these* defects:
F2 (old token still works after an email change; subject asserted numeric),
F1 (mocked ML failure surfaces as 502 with its own text, explicitly not
"Something went wrong"), expired/garbage/wrong-signature tokens → 401 never 500,
`RouteObservationService` departure resolution and mean urban signal, the
`WeatherService` seasonal fallback, history saved only when signed in, retrain
401 for anonymous, and `model-info` carrying no filesystem path at any depth.

`BackendApplicationTests` was rewritten, not deleted: its bare `contextLoads` ran
under the **default** profile, so the suite was reaching into the developer's real
file-based H2. It now asserts `jdbc:h2:mem:`.

**ML service — 307 tests, ~68 s.** All five labels reachable; camelCase and
snake_case payloads; malformed → 422 not 500; live features inside training
ranges; weather formula behaviour at the extremes; and `generate_dataset`
determinism — the property the original dataset did **not** have.

**V3 ✅ V4 ✅**

---

## PHASE 6 — FRONTEND ✅

- **Token leaked to third parties** — the interceptor attached `Authorization` to
  *every* request including Open-Meteo and Nominatim. Now backend-origin only.
- **No 401 handling anywhere** — a dead session still looked logged in. Now
  cleared and redirected, except on `/api/auth/login` where 401 means wrong
  password.
- **The one honesty label pointed at the wrong thing** — it called the *weather*
  synthetic. Now consistent across all three services: real weather, modelled
  road grade, with an explicit on-screen note when the seasonal fallback was used.
- Profile update stores the fresh token; `datasetPath`/`modelPath` removed;
  one shared error helper; distinguishable loading/failure states; `ngOnDestroy`
  added across the app; feedback success message no longer self-wiped.
- **"What Shaped This Advisory" was `display:none` below 760 px** — the
  explainability story was invisible on every phone, and therefore *always*
  invisible in the Android app. Fixed.

**V7 ✅** — 556.48 kB, inside budget.

---

## PHASE 7 — ANDROID (configuration only) ✅

- **B2** — `network_security_config.xml` had no `<base-config>` and permitted one
  LAN IP, so `10.0.2.2` (which the app tries *first*) was blocked and the
  emulator could never reach the backend. Now explicit deny-by-default plus
  `10.0.2.2`, `localhost`, `127.0.0.1` and the LAN address, with instructions.
- **S5** — `appId` now matches `applicationId`/`namespace`.
- `androidScheme` `http` → `https` (Capacitor's default, a real secure context).
- `allowBackup` off — the login token lives in WebView local storage.
- `usesCleartextTraffic` removed from the manifest: it is **ignored** whenever a
  network-security-config is present, and it made the config look permissive.
- **B1** — documented. *Correction:* Gradle only **warns** about the missing
  `capacitor-cordova-android-plugins` directory; the first hard failure is
  `capacitor.build.gradle` failing to read `cordova.variables.gradle`.

**Not runnable here** (no SDK): `npx cap sync android`, `./gradlew :app:tasks`,
and any APK build. README §8 gives the exact commands.

---

## PHASE 8 — DOCUMENTS AND ONE-COMMAND START ✅

`README.md` rewritten from nothing. `MODEL_EVALUATION.md` written with every
number traced to a command. `FIX_LOG.md` written with one entry per defect
including the corrections where the audit's diagnosis was wrong.
`start-all.sh` / `start-all.ps1` start all three services in order, wait for
health, name the offending port on a clash, and shut down on Ctrl+C.
`.env.example` completed (it listed 4 of 9 variables, omitting all 5 database
ones).

**`backend/mvnw.cmd` rewritten** — it was a 288-byte stub requiring a
`maven-wrapper.jar` that does not exist, so every Windows user running it from
cmd.exe or PowerShell got "Maven wrapper jar not found". Verified twice:
against the existing Maven home, and from nothing with a throwaway
`MAVEN_USER_HOME` to exercise the download path.

---

## THE AUDIT

8 parallel reviewers + a critic. 7 completed; the ML reviewer and the critic hit
a session limit and their work was done by hand. **79 new defects** beyond the
brief's 20 — 9 blocker, 34 major, 36 minor. Full output in `.audit/findings.md`
(untracked).

### Regression caught during the loop

A subagent silently reverted two verified fixes in `backend/src/main` despite
being told not to modify it: `JwtService` back to `.subject(user.getEmail())`
and `ApiExceptionHandler` losing its `@ExceptionHandler(ResponseStatusException)`
annotation. The first was worse than a revert — `extractUserId` still parses the
subject as a number, so an email subject would have failed **every**
authenticated request. Both were restored from the verified commit and the
backend suite now guards them.

---

## VERIFICATION SUITE

| ID | Check | Result |
|---|---|---|
| V1 | venv installs from requirements.txt | ✅ |
| V2 | regenerates dataset + model when both deleted | ✅ |
| V3 | pytest green | ✅ 307 passed |
| V4 | `mvnw test` green | ✅ 76 passed |
| V5 | backend starts with no MySQL | ✅ |
| V6 | `GET /api/health` ok | ✅ |
| V7 | `npm ci` + `npm run build` inside budget | ✅ 556.48 kB / 560 kB |
| V8 | `npm start` serves, map renders | ⏳ final sweep |
| V9 | signup → login → analyse → history | ✅ |
| V10 | change email, protected endpoint still works | ✅ |
| V11 | ML down → real error | ✅ |
| V12 | unknown place → real message | ✅ 404 |
| V13 | 1 000+ sweep shows all 5 labels | ✅ 1 536 |
| V14 | live ranges match training ranges | ✅ ≥99 % |
| V15 | prediction weather = real Open-Meteo | ✅ exact |
| V16 | retrain rejects anonymous | ✅ 401 |
| V17 | model-info exposes no paths | ✅ |
| V18 | prod profile refuses default secret | ⏳ final sweep |
| V19 | no junk in `git ls-files` | ✅ |
| V20 | all four documents current | ✅ |
