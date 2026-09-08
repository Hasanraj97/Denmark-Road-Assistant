# LOOP_STATE.md — Denmark Road Assistant repair & hardening loop

This file is the memory of the repair loop. If the session restarts, read this
file first and resume from the phase named below. Do not restart from Phase 0.

**Project location:** `D:\denmark-road-assistant-main`
**Loop started:** 2026-09-08

---

## CURRENT POSITION

| | |
|---|---|
| **Current phase** | Phase 4 — the machine-learning rebuild |
| **Phases complete** | 0, 1, 2, 3 |
| **Next up** | Phase 4, then 5 (tests), 6 (frontend), 7 (Android), 8 (docs), 9 (final sweep) |
| **Commits so far** | `92ffb34` initial + hygiene · `58bc9e1` phase 2–3 · `f71efd8` phase 3 |

**How to restart the services** (both were running and healthy at last check):

```bash
cd ml-service && ./.venv/Scripts/python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```
```bash
cd backend && ./mvnw spring-boot:run
```

---

## PHASE 0 — ENVIRONMENT SURVEY ✅

| Tool | Version | Verdict |
|---|---|---|
| `java` / `javac` | OpenJDK 25.0.1 LTS (Temurin) | OK — **risk R1 resolved**, see below |
| `JAVA_HOME` | unset | Fine, `java` is on PATH |
| `mvn` | not installed | Not needed — `backend/mvnw` works (downloads Maven 3.9.14) |
| `node` / `npm` | v20.19.0 / 10.8.2 | OK — exactly Angular 21's minimum |
| `python` | 3.14.3 (default) **and** 3.12.10 | **3.12 used** — decision D1 |
| `pip` | 25.3 | OK |
| `git` | 2.55.0.windows.5 | OK |
| Android SDK | **absent** | **Android = configuration-only** — decision D2 |

**D1 — Python 3.12 for the ML service.** `requirements.txt` pins
scikit-learn 1.7.1 / numpy 2.3.2 / pandas 2.3.2, which publish no `cp314`
wheels, so a 3.14 venv would try to compile from source and fail. Created with
`py -3.12 -m venv .venv`. Verified: all six packages install cleanly.

**D2 — Android configuration-only.** No SDK, so Phase 7 repairs configuration
and writes instructions rather than building an APK.

**R1 — RESOLVED.** Java 25 was a risk because the project targets Java 17. In
practice Spring Boot 3.5.13 + Hibernate 6.6.45 start and run correctly on JDK 25.
Observed, not assumed: the backend booted and served requests.

**R2 — Node 20.19.0 is Angular 21's floor.** `npm ci` succeeded. Full build not
yet re-run after the frontend changes; that is a Phase 6 exit check.

---

## PHASE 1 — REPOSITORY HYGIENE ✅

The project was **not a Git repository at all** — no `.git` directory existed.

- `git init` on branch `main`; local `user.name=hasan`,
  `user.email=hasanraz97@gmail.com` (change these if you prefer).
- `.gitignore.txt` → `.gitignore`. **This was the root cause of S4**: Git never
  reads a file called `.gitignore.txt`, so every ignore rule in it was inert.
- Extended it to cover `node_modules/`, `dist/`, `target/`, `__pycache__/`,
  `.venv/`, `.env`, `*.joblib`, `*.zip`, H2 database files, and the generated
  `frontend/android/capacitor-cordova-android-plugins/`.
- Deleted the committed `__pycache__` directories including the stray
  `main.cpython-313.pyc.2444357645200`.
- Added `.gitattributes` so line endings are stable across platforms.
- `backend.zip` (16 KB) left on disk but untracked. The audit confirmed it is a
  **stale Spring Initializr skeleton**, not a backup — its only unique file is
  boilerplate `HELP.md`. Safe to delete; deletion deferred to Phase 8.

**Exit checks:** `git status` clean · `.gitignore.txt` gone · `git ls-files`
shows **185 files, zero** `.pyc`, `.zip`, `.joblib` or `node_modules`. ✅

---

## PHASE 2 — GET IT RUNNING ✅

**B3 fixed.** `application.properties` defaulted to
`jdbc:mysql://localhost:3306/road_assistant` with `root`/`root`, so with no MySQL
the backend died at startup. Now:

- default = **file-based H2** at `backend/data/road-assistant`, created
  automatically, zero setup;
- `application-mysql.properties` holds MySQL, activated with
  `-Dspring-boot.run.profiles=mysql`;
- `application-dev.properties` adds the H2 console and SQL logging;
- `driver-class-name` is **no longer pinned separately from the URL** — that was
  its own bug: setting only `SPRING_DATASOURCE_URL` to H2 still loaded the MySQL
  driver and failed.

`RouteAnalysisHistory` no longer hard-codes `columnDefinition = "LONGTEXT"`.
**Correction to the brief:** the audit proved by running H2 2.3.232 directly that
H2 *accepts* `LONGTEXT` as an alias for `VARCHAR(1000000000)`, so this was **not**
the startup blocker it was reported to be. It is still a real defect — it bypasses
the dialect, gives H2 and MySQL structurally different columns, neutralises
`@Lob`, and would fail outright on PostgreSQL. Now `@Column(length = 4000)`.

### Evidence — services actually running

```
$ curl http://127.0.0.1:8080/api/health
{"service":"backend","status":"ok"}
```

```
$ curl -X POST .../api/route-and-predict -d '{"origin":"Copenhagen","destination":"Roskilde","selectedDepartureTime":"08:00"}'
  origin/dest     : København, Københavns Kommune, ... -> Roskilde, Roskilde Kommune, ...
  distanceKm      : 35.6043      durationMinutes : 37.25
  geometry points : 798          historyId       : 2
  label           : Moderate     confidence      : 0.4377
  classProbs      : {'Excellent': 0.0116, 'Good': 0.0942, 'Moderate': 0.4377, 'Poor': 0.4276, 'Very Poor': 0.0289}
```

Signup → login → analyse → history → duplicate-email (409) → wrong-password (401)
all behave correctly. Real Nominatim names, real OSRM geometry.

---

## PHASE 3 — BACKEND CORRECTNESS AND SECURITY ✅

| Defect | Fix | Evidence |
|---|---|---|
| **F1** helpful errors replaced by generic 500 | `ResponseStatusException` gets its own handler ahead of the catch-all; every 5xx now logged with a stack trace and a reference id | unknown place → `404 "Could not match \"Zzzqqxwv Nowhereville\" to a place in Denmark…"` |
| **F1b** *(new)* handler logged **nothing** | first failing signup produced a silent server log — now impossible | verified by log inspection |
| **F2** email change broke your session | JWT subject is the **user id**, not the email; `loadUserById`; filter never throws; `PUT /api/users/me` returns a fresh token | change email → protected call with the **old** token = **200** (was 500) |
| **S1** committed JWT secret | `JwtKeyConfig`: reads `APP_JWT_SECRET`, fails fast in prod profiles, rejects the leaked value and secrets < 32 chars, random dev key with a loud warning | app boots with a generated key + warning |
| **S2** public retrain | now `authenticated()` | anonymous POST → **401** |
| **S3** leaked `E:\sw8-maps\…` paths | removed from `ModelInfoDto` | response contains no paths |
| **S7** dead `/h2-console` rule | moved into `DevH2ConsoleConfig`, `@Profile("dev")` + `@ConditionalOnProperty` | — |
| default-open chain *(new)* | `anyRequest().permitAll()` → `authenticated()` | — |
| no HTTP timeouts *(new)* | `HttpClientConfig`: 5s connect / 15s read on Nominatim + OSRM | — |
| `MlServiceClient` swallowed its own detail *(new)* | rethrows its own `ResponseStatusException` | — |
| deprecated `DaoAuthenticationProvider` | bean removed entirely — it was never called | build has **no deprecation warnings** |
| Lombok `@Data` on `AppUser` *(new)* | removed; it put the BCrypt hash in `toString()`. Lombok dropped from the build | — |

**F3 is deliberately deferred into Phase 4**, because Phase 4 removes the Java
feature-derivation code that contains it.

```
$ curl -X POST /api/retrain-model            → 401 "You need to be logged in to do that."
$ curl /api/model-info                       → no filesystem paths
$ (ML service stopped) POST /api/route-and-predict
  502 "The synthetic ML service is unavailable. Start the Python ml-service on port 8000."
```

---

## THE AUDIT (8 parallel reviewers + critic)

7 of 9 agents completed; the ML-service reviewer and the completeness critic hit
a session limit and their work was done by hand instead. Full output:
`.audit/findings.md` (untracked). **79 new defects** beyond the brief's 20:
9 blocker, 34 major, 36 minor.

### Corrections the audit made to the brief — important, do not repeat the errors

- **B4 REFUTED.** `LONGTEXT` does **not** break H2 (see Phase 2 above).
- **M2 partly REFUTED.** `weather_risk` is **character-for-character identical**
  between Java and Python. Three of the four named features do differ
  (construction, accident, vehicle load). The dominant divergence is something
  the brief missed entirely: the Java `lowTrafficTime` branch and its `0.05`
  night `timeScore`, which have **no counterpart in training at all**.
- **M3 partly REFUTED.** "Byte-identical forever" is false — day, season and
  live OSRM distance still vary. But it is *worse* than described in two ways:
  the same multiplier is applied to all 11 noised features (perfectly correlated,
  where training draws independently), and it **compounds** through
  `urbanScore → trafficLevel → accidentRisk`, reaching ~9%, not 3%.
- **B1 partly REFUTED.** Gradle only *warns* about the missing
  `capacitor-cordova-android-plugins` directory; the first hard failure is
  `capacitor.build.gradle:10` failing to read `cordova.variables.gradle`.
- **B2 detail.** The environment file says `http://10.0.2.2:8080` (correct
  emulator alias). The real problem is that `network_security_config.xml` has
  **no `<base-config>`**, so every host except `192.168.1.103` is blocked.

### Biggest new discovery — verified by hand

**The shipped dataset and model were NOT produced by the current
`_generate_dataset`.** Measured directly on `synthetic_denmark_routes.csv`:

| Impossible under current code | Rows |
|---|---|
| `wind_speed_kmh < 5.0` (generator forces `max(5.0, …)`) | 149 |
| `avg_speed_kmh < 24.0` (lowest sampled band starts at 24) | 2 112 |
| `weather_risk` outside `[0.339, 0.670]` (a 3-valued season map ±3%) | **5 837** |
| `rainfall_mm > 6.18` (arithmetic max of `weather_risk × 8`) | 1 451 |

`weather_risk` has **89 distinct values spanning 0.00–0.90** in the CSV, but the
current generator can only emit three values ±3%. So the model answering requests
today was trained by **different, uncommitted code**, and the reported accuracy of
0.7788 belongs to that vanished dataset. This makes the honesty problem worse
than M5 states: the headline number is not merely circular, it is
**unreproducible**. Phase 4 fixes this by making the dataset and model
regenerate deterministically from committed code.

Confirmed class balance of the shipped CSV: Excellent 1584 (13.2%), Good 3936
(32.8%), Moderate 4629 (38.6%), Poor 1729 (14.4%), **Very Poor 122 (1.02%)**.

### Blocker-level new defects still outstanding

| # | Defect | Phase |
|---|---|---|
| 1 | Auth interceptor attaches the JWT to **third-party URLs** (Open-Meteo, Nominatim) — token leak | 6 |
| 2 | No 401 handling anywhere in the frontend — a dead session still looks logged in | 6 |
| 3 | The only synthetic disclosure in the UI **labels the real weather as synthetic** | 6 |
| 4 | Deleting a history item that has feedback → referential-integrity 500 | 5/6 |
| 5 | Live feature vectors occupy ~8% of the training distribution | 4 |
| 6 | `lowTrafficTime` branch has no counterpart in training | 4 |
| 7 | Shipped CSV/model not reproducible from code | 4 |
| 8 | `mvnw.cmd` reported broken — **partly refuted**, `./mvnw` works from Git Bash and downloaded Maven 3.9.14. `mvnw.cmd` still to be tested from cmd.exe | 8 |

---

## VERIFICATION SUITE

| ID | Check | Result |
|---|---|---|
| V1 | ml-service venv installs from requirements.txt | ✅ PASS |
| V2 | ml-service regenerates dataset + model when both deleted | ⏳ Phase 4 |
| V3 | pytest green | ⏳ Phase 5 |
| V4 | `mvnw clean verify` green | ⏳ Phase 5 |
| V5 | backend starts with no MySQL | ✅ PASS |
| V6 | `GET /api/health` returns ok | ✅ PASS |
| V7 | `npm ci` + `npm run build` inside budget | ⏳ Phase 6 |
| V8 | `npm start` serves, map renders | ⏳ Phase 6 |
| V9 | signup→login→analyse→history | ✅ PASS (feedback not yet exercised) |
| V10 | change email, protected endpoint still works | ✅ PASS |
| V11 | ML service down → real error on screen | ✅ PASS (API level) |
| V12 | unknown place → real message | ✅ PASS (404) |
| V13 | 1000+ input sweep shows all 5 labels | ⏳ Phase 4 |
| V14 | live feature ranges match training ranges | ⏳ Phase 4 |
| V15 | prediction weather = real Open-Meteo values | ⏳ Phase 4 |
| V16 | retrain rejects anonymous | ✅ PASS (401) |
| V17 | model-info exposes no filesystem paths | ✅ PASS |
| V18 | prod profile refuses the default secret | ⏳ needs an explicit test |
| V19 | `git ls-files` has no junk | ✅ PASS |
| V20 | README / MODEL_EVALUATION / FIX_LOG current | ⏳ Phase 8 |
