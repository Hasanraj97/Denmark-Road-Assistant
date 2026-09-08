# Denmark Road Assistant

**An Explainable Machine-Learning Route Advisory System**

You type where you are starting from, where you are going, and when you plan to
leave. The app finds both places in Denmark, works out the driving route, draws
it on a map, fetches the real weather forecast for the middle of that route at
that hour, and asks a machine-learning model to grade the driving conditions as
**Excellent, Good, Moderate, Poor** or **Very Poor** — with a plain-English
advisory, a suggestion of how many minutes early to leave, and an explanation of
which factors actually drove the grade.

You can create an account to save your analyses and leave feedback on them.

### An honest word about what is real

> The **weather is real** — a live forecast from Open-Meteo, and the model
> genuinely uses it.
>
> The **road-condition grade is modelled on generated data**. There is no live
> traffic feed and no roadworks feed behind it. Treat the grade as a
> demonstration of the method, not as a measurement of Danish roads.

`MODEL_EVALUATION.md` sets out exactly what this means, with numbers.

---

## Architecture

Three services that each do one job.

```
                 ┌──────────────────────────────────────────┐
   browser  ───► │  frontend/     Angular 21 + Leaflet      │  :4200
   or phone      │                (Capacitor wraps it for   │
                 │                 Android)                 │
                 └───────────────────┬──────────────────────┘
                                     │  REST + JWT
                                     ▼
                 ┌──────────────────────────────────────────┐
                 │  backend/      Spring Boot 3.5, Java 17  │  :8080
                 │                Spring Security, JPA      │
                 │                                          │
                 │  • accounts, login, history, feedback    │
                 │  • asks Nominatim  → where is this place?│
                 │  • asks OSRM       → what is the route?  │
                 │  • asks Open-Meteo → what is the weather?│
                 └───────────────────┬──────────────────────┘
                                     │  raw observation
                                     │  (distance, duration, departure,
                                     │   urban signal, real weather)
                                     ▼
                 ┌──────────────────────────────────────────┐
                 │  ml-service/   Python FastAPI            │  :8000
                 │                scikit-learn RandomForest │
                 │                                          │
                 │  • derives the 19 features               │
                 │  • predicts the grade + explanation      │
                 └──────────────────────────────────────────┘
```

**Why the backend sends raw facts rather than features.** The backend used to
compute the 19 model features itself, in Java, while the Python service computed
them differently when building its training data. The two drifted apart, so the
model was tested on one distribution and used on another. Now there is one
definition of every feature (`ml-service/app/features.py`), used both to generate
training data and to serve predictions, so the two cannot disagree.

Data stores: a **file-based H2 database** by default (created automatically, no
setup), or **MySQL** if you prefer — see §6.

---

## 1. Prerequisites

| You need | Version | Check with | Notes |
|---|---|---|---|
| **Java JDK** | 17 or newer | `java -version` | Developed and verified on JDK 25. Maven itself is not needed — the project ships a wrapper. |
| **Node.js** | 20.19+, 22.12+ or 24+ | `node -v` | Angular 21 will refuse to build on anything older. |
| **Python** | 3.10 – 3.13 | `python --version` | **Not 3.14 yet** — see the warning below. |
| **Git** | any recent | `git --version` | |
| Android Studio | Ladybug or newer | — | **Only** if you want to build the mobile app. Everything else works without it. |

> **Python version warning.** `ml-service/requirements.txt` pins
> scikit-learn 1.7.1, numpy 2.3.2 and pandas 2.3.2. Those releases do not publish
> pre-built packages for Python 3.14, so a 3.14 environment tries to compile them
> from source and fails unless you have a full C/C++ toolchain. Use 3.12 (or any
> 3.10–3.13). If you have several Pythons on Windows, `py -0p` lists them and
> `py -3.12` selects one.

No API keys are needed. Nominatim, OSRM and Open-Meteo are all free and open, and
the app is a polite user of them.

---

## 2. First-time setup

From the project root, once.

**a. The ML service** — creates an isolated Python environment:

```bash
cd ml-service && py -3.12 -m venv .venv && ./.venv/Scripts/python.exe -m pip install -r requirements.txt
```

On macOS or Linux the venv layout differs — use `.venv/bin/python` instead of
`.venv/Scripts/python.exe` everywhere in this README:

```bash
cd ml-service && python3.12 -m venv .venv && ./.venv/bin/python -m pip install -r requirements.txt
```

**b. The frontend** — installs the Angular dependencies:

```bash
cd frontend && npm ci
```

**c. The backend** — nothing to do. The Maven wrapper downloads what it needs on
first run, and the database creates itself.

---

## 3. Running it

### The easy way — one command

```bash
./start-all.sh
```

On Windows, from PowerShell or File Explorer:

```powershell
.\start-all.ps1
```

The script starts all three services in the right order, waits for each to
become healthy before starting the next, tells you clearly if a port is already
in use, and shuts everything down cleanly when you press **Ctrl+C**.

Then open **http://localhost:4200**.

### The manual way — three terminals

Useful when you want to watch one service's logs.

**Terminal 1 — ML service** (port 8000). The first start trains the model, which
takes a few seconds and prints its accuracy:

```bash
cd ml-service && ./.venv/Scripts/python.exe -m uvicorn app.main:app --port 8000
```

**Terminal 2 — backend** (port 8080):

```bash
cd backend && ./mvnw spring-boot:run
```

**Terminal 3 — frontend** (port 4200):

```bash
cd frontend && npm start
```

### Checking it is alive

```bash
curl http://localhost:8000/health
```
```bash
curl http://localhost:8080/api/health
```

Both should answer `{"status":"ok",...}`.

---

## 4. Running the tests

**Backend** — JUnit 5, Spring Boot Test and MockMvc, against an in-memory
database. No test touches the network:

```bash
cd backend && ./mvnw test
```

**ML service** — pytest against the FastAPI app in-process:

```bash
cd ml-service && ./.venv/Scripts/python.exe -m pip install -r requirements-dev.txt
```
```bash
cd ml-service && ./.venv/Scripts/python.exe -m pytest -q
```

**The evidence sweep** — proves all five grades are reachable and that the live
feature ranges still match the training ranges. This is the check that would
catch train/serve skew creeping back in:

```bash
cd ml-service && ./.venv/Scripts/python.exe scripts/live_distribution_sweep.py
```

**Frontend production build**, which also type-checks every template:

```bash
cd frontend && npm run build
```

---

## 5. Retraining the model

The model regenerates itself if `ml-service/models/` is empty. To force a fresh
one:

```bash
cd ml-service && rm -f models/road_condition_model.joblib models/road_condition_model_metadata.json data/synthetic_denmark_routes.csv
```

then restart the ML service. Training takes about 5 seconds and prints a full
classification report.

There is also `POST /api/retrain-model`, but **it requires you to be logged in**.
It regenerates 12 000 rows and refits the forest, overwriting the files on disk,
so it is deliberately not something an anonymous visitor can trigger.

Neither the dataset nor the model file is committed to Git — they are build
outputs, and the model was 82 MB before this was fixed. Both are reproducible
from seed 42.

---

## 6. Switching from H2 to MySQL

All database configuration lives in two files —
`backend/src/main/resources/application.properties` and
`application-mysql.properties`. No entity or service in the codebase knows which
database is in use, and the entities use portable column types only, so moving to
another database (a hosted MySQL, PostgreSQL, …) is a configuration change rather
than a code change.

**Default (H2).** Nothing to do. A file-based database appears at
`backend/data/road-assistant.mv.db` on first start.

**MySQL.** Create the database once:

```sql
CREATE DATABASE road_assistant CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

Then run with the `mysql` profile, supplying credentials through the environment
so no password is ever committed:

```bash
cd backend && SPRING_DATASOURCE_USERNAME=youruser SPRING_DATASOURCE_PASSWORD=yourpassword ./mvnw spring-boot:run -Dspring-boot.run.profiles=mysql
```

**Looking inside the H2 database.** The web console is off by default, and is
enabled only in the `dev` profile so it can never be left exposed by accident:

```bash
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Then open http://localhost:8080/h2-console with JDBC URL
`jdbc:h2:file:./data/road-assistant`, user `sa`, and an empty password.

---

## 7. Configuration

Copy `.env.example` if you want to change anything. Every setting has a working
default except the JWT secret, which is explained below.

| Variable | Default | What it does |
|---|---|---|
| `SERVER_PORT` | `8080` | Backend port |
| `ML_SERVICE_BASE_URL` | `http://localhost:8000` | Where the backend finds the ML service |
| `FRONTEND_ORIGINS` | localhost variants | CORS allow-list |
| `APP_JWT_SECRET` | *(none)* | Login token signing key — see below |
| `APP_JWT_EXPIRATION_MS` | `86400000` (24 h) | How long a login lasts |
| `SPRING_DATASOURCE_URL` | H2 file | Database location |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | `sa` / *(empty)* | Database credentials |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | `update` | Schema handling |
| `WEATHER_BASE_URL` | Open-Meteo forecast API | Weather source |

### About `APP_JWT_SECRET`

There is deliberately **no default**. The project used to ship a real-looking
fallback key inside a committed file, which meant the signing key was public and
anyone reading the repository could forge a login token for any account.

Now:

* **Local development** — leave it unset. A random key is generated at startup
  and a warning explains that everyone gets logged out when you restart. That is
  expected; just log in again.
* **Keeping logins across restarts** — set it to any random string of 32
  characters or more: `openssl rand -base64 48`.
* **Production** — the application **refuses to start** without it under a
  `prod`, `production` or `staging` profile, rather than quietly generating a key
  nobody controls.

---

## 8. Building the Android app

> **The Android project will not open or build until you have run `npx cap sync
> android` at least once.** The folder
> `frontend/android/capacitor-cordova-android-plugins/` is *generated*, not
> committed, and the Gradle build reads a file from inside it. Cloning the
> repository and opening `frontend/android` in Android Studio straight away fails
> with `Could not read script .../cordova.variables.gradle as it does not exist`.

1. Install Android Studio, and through it an Android SDK with platform 35.
2. Build the web app and sync it into the Android project:

```bash
cd frontend && npm run build:android && npx cap sync android
```

3. Open the project:

```bash
cd frontend && npx cap open android
```

4. Run the backend and ML service on your computer, then start the emulator. The
   app reaches your machine at `10.0.2.2`, which is the emulator's alias for the
   host's `localhost`.

`npm run android` does steps 2 and 3 together.

### Using a real phone over Wi-Fi

The phone needs your computer's LAN address instead of `10.0.2.2`. Find it with
`ipconfig` (Windows), `ipconfig getifaddr en0` (macOS) or `hostname -I` (Linux),
then change it in **both** of these files:

* `frontend/src/environments/environment.android.ts` — the `apiBaseCandidates` list
* `frontend/android/app/src/main/res/xml/network_security_config.xml` — the
  `192.168.1.103` entry

Both are needed. Android blocks unencrypted HTTP by default from SDK 28 onwards,
and the network-security-config is the only thing that can permit it — the
`android:usesCleartextTraffic` manifest flag is ignored whenever that file is
present. Then re-run `npm run build:android && npx cap sync android`.

**Note on JDK for Android.** Gradle 8.9 in this project supports up to JDK 22.
Android Studio builds with its own bundled JDK, so this is normally invisible. If
you run `./gradlew` yourself from a terminal with a newer JDK on your PATH and it
complains about an unsupported class file version, point `JAVA_HOME` at a
JDK 17 or 21.

---

## 9. Troubleshooting

Real messages you may see, and what they mean.

**`The road-condition model service is unavailable. Start the Python ml-service
on port 8000.`**
The backend is running but the Python service is not. Start it (§3). This
message reaching you at all is deliberate — it used to be replaced by a blank
"Something went wrong on the server."

**`Could not match "…" to a place in Denmark.`**
Nominatim found nothing. The app only searches Denmark. Try a fuller address, a
postcode, or a nearby larger town.

**`Could not build a Denmark road route for those locations.`**
Both places were found but OSRM could not connect them by road — usually one of
them is on an island with no bridge, or is not near a road.

**Backend fails at startup with `Communications link failure` or
`Connection refused: localhost:3306`**
It is trying to reach MySQL. Either you set `SPRING_DATASOURCE_URL` to a MySQL
address, or you started it with `-Dspring-boot.run.profiles=mysql`. Drop the
profile to use the default H2 database.

**`APP_JWT_SECRET is not set, and the active profile … is a production profile.`**
Working as intended — see §7.

**`APP_JWT_SECRET is too short.`**
It must be at least 32 characters so the signing key is a full 256 bits.

**A warning at startup that a random signing key was generated**
Normal in development. It only means you will need to log in again after a
restart.

**`Your session has expired. Please log in again.`**
Tokens last 24 hours by default. The app now sends you back to the login page
instead of silently appearing to still be logged in.

**Frontend build fails with a Node engine error**
Angular 21 needs Node 20.19+, 22.12+ or 24+. Check with `node -v`.

**`pip install` fails building numpy, pandas or scikit-learn from source**
You are on Python 3.14. Use 3.12 — see §1.

**Port already in use**
`start-all.sh` / `start-all.ps1` names the port and the process. To find it
manually: `netstat -ano | grep 8080` on Windows, `lsof -i :8080` elsewhere.

**Analyses do not appear in History**
History is only saved when you are logged in. An anonymous analysis works, but
is not stored.

---

## 10. Project layout

```
.
├── README.md                  this file
├── MODEL_EVALUATION.md        dataset, metrics, and an honest limitations section
├── FIX_LOG.md                 every defect found and how it was fixed
├── LOOP_STATE.md              the running log of the repair work
├── start-all.sh / .ps1        start all three services
├── .env.example               every environment variable, with defaults
│
├── backend/                   Spring Boot
│   └── src/main/java/com/example/backend/
│       ├── config/            security, JWT key, HTTP timeouts, CORS
│       ├── controller/        the REST endpoints
│       ├── dto/               request and response shapes
│       ├── entity/            JPA entities
│       ├── exception/         one place where errors become HTTP responses
│       ├── ml/                client for the Python service
│       ├── repository/        Spring Data repositories
│       ├── routing/           geocoding, OSRM routing, observation building
│       ├── security/          JWT filter and token handling
│       ├── service/           accounts, profile, history, feedback
│       └── weather/           Open-Meteo client
│
├── frontend/                  Angular 21
│   ├── src/app/               pages, services, components
│   ├── src/environments/      per-target API addresses
│   └── android/               Capacitor Android wrapper
│
└── ml-service/                FastAPI + scikit-learn
    ├── app/features.py        THE single feature definition — read this first
    ├── app/dataset.py         builds training data by calling features.py
    ├── app/main.py            the API
    ├── scripts/               the live-distribution sweep
    └── tests/                 pytest suite
```

### The API

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/api/auth/signup` | — | Create an account |
| POST | `/api/auth/login` | — | Log in, returns a token |
| GET | `/api/health` | — | Liveness |
| POST | `/api/route` | — | Route only |
| POST | `/api/predict` | — | Predict from a supplied feature vector |
| POST | `/api/route-and-predict` | optional | The main flow. Saves to history if logged in |
| GET | `/api/model-info` | — | Model name, accuracy, labels, feature importances |
| POST | `/api/retrain-model` | **yes** | Regenerate the dataset and refit |
| GET | `/api/users/me` | **yes** | Your profile |
| PUT | `/api/users/me` | **yes** | Update profile — returns a **fresh token** |
| GET | `/api/history` | **yes** | Your saved analyses |
| GET | `/api/history/{id}` | **yes** | One analysis in detail |
| DELETE | `/api/history/{id}` | **yes** | Delete one |
| POST | `/api/history/{id}/feedback` | **yes** | Leave feedback |

Route analysis is public on purpose, so the project can be demonstrated without
anyone creating an account.
