"""Extra analyses requested in the November 2026 review (Samuel Agbesi).

Run from anywhere with the ML venv:  python review_analyses.py <ml-service dir> <out.json>
"""
import json
import sys
import time
from dataclasses import replace

sys.path.insert(0, sys.argv[1])

import joblib
import numpy as np
import pandas as pd
from sklearn.calibration import CalibratedClassifierCV
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import RandomForestClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, f1_score
from sklearn.model_selection import StratifiedKFold, cross_val_score, train_test_split
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler

import app.dataset as ds
import app.features as fx
from app.features import (CATEGORICAL_COLUMNS, FEATURE_COLUMNS, LABELS, NUMERIC_COLUMNS,
                          condition_score, derive_features, label_for_score)

BASE = sys.argv[1]
OUT = {}
T0 = time.time()


def log(*a):
    print(f"[{time.time() - T0:6.1f}s]", *a, flush=True)


def pipe(clf, scale):
    num = StandardScaler() if scale else "passthrough"
    pre = ColumnTransformer([("n", num, NUMERIC_COLUMNS),
                             ("c", OneHotEncoder(handle_unknown="ignore", sparse_output=False), CATEGORICAL_COLUMNS)])
    return Pipeline([("pre", pre), ("clf", clf)])


def rf(depth=18, leaf=4, n=200):
    return pipe(RandomForestClassifier(n_estimators=n, max_depth=depth, min_samples_leaf=leaf, random_state=42,
                                       class_weight="balanced_subsample", n_jobs=-1), False)


def lr():
    return pipe(LogisticRegression(max_iter=5000, C=10.0), True)


def rule_labels(frame):
    return frame.apply(lambda r: label_for_score(condition_score(r.to_dict())), axis=1)


def generate(sigma=0.025, jitter=True, seed=42, n=12000, severity_probs=None, ww=None):
    """Same generator as app.dataset, with the knobs the review asks about.

    Observations, jitter and label noise use three separate random streams, so
    switching jitter or noise off leaves the trips themselves unchanged."""
    rng_obs = np.random.default_rng(seed)
    rng_jit = np.random.default_rng(seed + 1)
    rng_noise = np.random.default_rng(seed + 2)
    old_probs = ds.SEVERITY_PROBS
    if severity_probs is not None:
        ds.SEVERITY_PROBS = severity_probs
    rows, clean_labels = [], []
    try:
        for _ in range(n):
            obs = ds._sample_observation(rng_obs)
            feats = derive_features(obs, rng=rng_jit if jitter else None)
            score = condition_score(feats)
            clean_labels.append(label_for_score(score))
            feats["road_condition_label"] = label_for_score(score + float(rng_noise.normal(0.0, sigma)))
            rows.append(feats)
    finally:
        ds.SEVERITY_PROBS = old_probs
    frame = pd.DataFrame(rows)
    return frame[FEATURE_COLUMNS + ["road_condition_label"]], np.array(clean_labels)


def split(frame):
    return train_test_split(frame[FEATURE_COLUMNS], frame["road_condition_label"], test_size=0.2,
                            random_state=42, stratify=frame["road_condition_label"])


def boot_ci(yt, preds_a, preds_b=None, n=1000, seed=7):
    rng = np.random.default_rng(seed)
    yt = np.asarray(yt)
    a = np.asarray(preds_a) == yt
    b = None if preds_b is None else (np.asarray(preds_b) == yt)
    stats = []
    for _ in range(n):
        idx = rng.integers(0, len(yt), len(yt))
        stats.append(a[idx].mean() - (0 if b is None else b[idx].mean()))
    return [round(float(np.percentile(stats, 2.5)), 4), round(float(np.percentile(stats, 97.5)), 4)]


# ---------------------------------------------------------------------------
# 0. The shipped data and model
# ---------------------------------------------------------------------------
data = pd.read_csv(f"{BASE}/data/synthetic_denmark_routes.csv")
Xtr, Xte, ytr, yte = split(data)
model = joblib.load(f"{BASE}/models/road_condition_model.joblib")
p_rf = model.predict(Xte)
lr_model = lr().fit(Xtr, ytr)
p_lr = lr_model.predict(Xte)
p_rule = rule_labels(Xte)
OUT["test_set"] = {
    "n": int(len(yte)),
    "rf_acc": round(accuracy_score(yte, p_rf), 4), "rf_ci": boot_ci(yte, p_rf),
    "lr_acc": round(accuracy_score(yte, p_lr), 4), "lr_ci": boot_ci(yte, p_lr),
    "rule_acc": round(accuracy_score(yte, p_rule), 4), "rule_ci": boot_ci(yte, p_rule),
    "rf_minus_lr_ci": boot_ci(yte, p_rf, p_lr),
    "rf_lr_agreement": round(float((p_rf == p_lr).mean()), 4),
    "rf_macro_f1": round(f1_score(yte, p_rf, average="macro"), 4),
    "lr_macro_f1": round(f1_score(yte, p_lr, average="macro"), 4),
}
# Can a model beat the noise-free rule on noisy labels? (finite-sample check)
OUT["test_set"]["rf_beats_rule_on_rows"] = int(((p_rf == yte) & (p_rule != yte)).sum())
OUT["test_set"]["rule_beats_rf_on_rows"] = int(((p_rf != yte) & (p_rule == yte)).sum())
log("test set", OUT["test_set"])

# ---------------------------------------------------------------------------
# 1. Label noise: how often it flips a grade, and robustness to other sigmas
# ---------------------------------------------------------------------------
noise = {}
for sigma in [0.0, 0.0125, 0.025, 0.05, 0.075]:
    frame, clean = generate(sigma=sigma)
    flipped = float((frame["road_condition_label"].to_numpy() != clean).mean())
    a, b, c, d = split(frame)
    m_rf = rf().fit(a, c)
    m_lr = lr().fit(a, c)
    pr, pl, pu = m_rf.predict(b), m_lr.predict(b), rule_labels(b)
    noise[str(sigma)] = {
        "labels_changed_by_noise": round(flipped, 4),
        "rf_acc": round(accuracy_score(d, pr), 4), "rf_macro_f1": round(f1_score(d, pr, average="macro"), 4),
        "lr_acc": round(accuracy_score(d, pl), 4), "rule_acc": round(accuracy_score(d, pu), 4),
        "rf_acc_vs_clean_rule": round(accuracy_score(rule_labels(b), pr), 4),
    }
    log("noise", sigma, noise[str(sigma)])
OUT["noise"] = noise

# ---------------------------------------------------------------------------
# 2. Model selection on the training part only (5-fold CV)
# ---------------------------------------------------------------------------
cv = StratifiedKFold(5, shuffle=True, random_state=42)
grid = {}
for depth in [12, 18, None]:
    for leaf in [1, 4, 8]:
        s = cross_val_score(rf(depth, leaf), Xtr, ytr, cv=cv, scoring="accuracy", n_jobs=1)
        grid[f"depth={depth},leaf={leaf}"] = [round(float(s.mean()), 4), round(float(s.std()), 4)]
        log("cv", depth, leaf, grid[f"depth={depth},leaf={leaf}"])
s = cross_val_score(lr(), Xtr, ytr, cv=cv, scoring="accuracy", n_jobs=1)
grid["logistic_regression"] = [round(float(s.mean()), 4), round(float(s.std()), 4)]
OUT["cv_selection"] = grid

# ---------------------------------------------------------------------------
# 3. Training-only jitter: size, distribution effect, robustness
# ---------------------------------------------------------------------------
with_j, _ = generate(jitter=True)
without_j, _ = generate(jitter=False)
jit = {}
for col in ["urban_density", "road_type_score", "traffic_level", "construction_risk_score",
            "accident_risk_score", "vehicle_load_factor", "elevation_variation"]:
    jit[col] = {"sd_with": round(float(with_j[col].std()), 4), "sd_without": round(float(without_j[col].std()), 4),
                "mean_abs_diff": round(float((with_j[col] - without_j[col]).abs().mean()), 4)}
p_j = model.predict(with_j[FEATURE_COLUMNS])
p_nj = model.predict(without_j[FEATURE_COLUMNS])
jit["_grade_changed_when_jitter_removed"] = round(float((p_j != p_nj).mean()), 4)
jit["_acc_on_jittered"] = round(accuracy_score(with_j["road_condition_label"], p_j), 4)
jit["_acc_on_unjittered"] = round(accuracy_score(without_j["road_condition_label"], p_nj), 4)
OUT["jitter"] = jit
log("jitter", jit)

# ---------------------------------------------------------------------------
# 4. Explanations: fidelity, stability, grouped perturbation, counter factors
# ---------------------------------------------------------------------------
baseline = {c: float(data[c].median()) for c in NUMERIC_COLUMNS}
baseline.update({c: str(data[c].mode().iloc[0]) for c in CATEGORICAL_COLUMNS})
classes = list(model.named_steps["classifier"].classes_)
GROUPS = {
    "weather": ["weather_risk", "rainfall_mm", "wind_speed_kmh", "temperature_c", "humidity"],
    "traffic": ["traffic_level", "vehicle_load_factor", "time_of_day", "day_of_week", "accident_risk_score"],
    "place": ["urban_density", "region_type", "construction_risk_score", "season"],
    "road": ["avg_speed_kmh", "road_type_score", "distance_km", "estimated_duration_min", "elevation_variation"],
}
group_of = {f: g for g, fs in GROUPS.items() for f in fs}


def contributions(row, idx):
    variants = [row] + [{**row, c: baseline[c]} for c in FEATURE_COLUMNS]
    pr = model.predict_proba(pd.DataFrame(variants))[:, idx]
    return pr[0], {c: pr[0] - pr[i + 1] for i, c in enumerate(FEATURE_COLUMNS)}


rng = np.random.default_rng(3)
sample = Xte.sample(300, random_state=11)
fid_ok, top1_same, jacc, grp_agree, has_counter, counter_sizes, labels_strength = 0, 0, [], 0, 0, [], []
impossible = 0
for _, r in sample.iterrows():
    row = r.to_dict()
    probs = model.predict_proba(pd.DataFrame([row]))[0]
    idx = int(np.argmax(probs))
    base, con = contributions(row, idx)
    ranked = sorted(con, key=con.get, reverse=True)
    top = ranked[0]
    # fidelity: removing the top factor should hurt more than removing a random one
    random_drops = [con[c] for c in rng.choice(FEATURE_COLUMNS, 5, replace=False)]
    if con[top] > np.median(random_drops):
        fid_ok += 1
    # stability: nudge numeric inputs by up to 2 percent
    nudged = {k: (v * (1 + rng.uniform(-0.02, 0.02)) if k in NUMERIC_COLUMNS else v) for k, v in row.items()}
    _, con2 = contributions(nudged, idx)
    ranked2 = sorted(con2, key=con2.get, reverse=True)
    top1_same += int(ranked2[0] == top)
    s1, s2 = set(ranked[:3]), set(ranked2[:3])
    jacc.append(len(s1 & s2) / len(s1 | s2))
    # grouped perturbation: reset whole groups of related features together
    gvariants = [row] + [{**row, **{c: baseline[c] for c in fs}} for fs in GROUPS.values()]
    gp = model.predict_proba(pd.DataFrame(gvariants))[:, idx]
    gdrop = {g: gp[0] - gp[i + 1] for i, g in enumerate(GROUPS)}
    grp_agree += int(max(gdrop, key=gdrop.get) == group_of[top])
    # counter factors
    neg = [v for v in con.values() if v < -0.005]
    has_counter += int(bool(neg))
    counter_sizes += [-v for v in neg]
    for c in ranked[:4]:
        if con[c] > 0.005:
            labels_strength.append("strong" if con[c] >= 0.10 else "moderate" if con[c] >= 0.04 else "slight")
    # implausible combination: weather_risk reset while raw rain or wind stays high, or vice versa
    if top == "weather_risk" and (row["rainfall_mm"] > 3 or row["wind_speed_kmh"] > 40):
        impossible += 1
n = len(sample)
OUT["explanations"] = {
    "n": n,
    "fidelity_top_beats_random_pct": round(100 * fid_ok / n, 1),
    "stability_top1_same_pct": round(100 * top1_same / n, 1),
    "stability_top3_jaccard_mean": round(float(np.mean(jacc)), 3),
    "grouped_vs_single_top_group_agree_pct": round(100 * grp_agree / n, 1),
    "with_counter_factor_pct": round(100 * has_counter / n, 1),
    "counter_size_median": round(float(np.median(counter_sizes)), 3) if counter_sizes else 0,
    "counter_size_max": round(float(np.max(counter_sizes)), 3) if counter_sizes else 0,
    "strength_labels": {k: labels_strength.count(k) for k in ["strong", "moderate", "slight"]},
    "top_is_weather_risk_with_heavy_raw_weather": impossible,
}
log("explanations", OUT["explanations"])

# ---------------------------------------------------------------------------
# 5. Calibration
# ---------------------------------------------------------------------------
def ece(probs, y_true, bins=10):
    conf = probs.max(axis=1)
    pred = np.array(classes)[probs.argmax(axis=1)]
    correct = (pred == np.asarray(y_true)).astype(float)
    edges = np.linspace(0, 1, bins + 1)
    e = 0.0
    for lo, hi in zip(edges[:-1], edges[1:]):
        m = (conf > lo) & (conf <= hi)
        if m.any():
            e += m.mean() * abs(correct[m].mean() - conf[m].mean())
    return round(float(e), 4)


cal = CalibratedClassifierCV(rf(), method="isotonic", cv=5).fit(Xtr, ytr)
pc = cal.predict_proba(Xte)
OUT["calibration"] = {"ece_shipped": ece(model.predict_proba(Xte), yte), "ece_isotonic": ece(pc, yte),
                      "acc_isotonic": round(accuracy_score(yte, np.array(classes)[pc.argmax(axis=1)]), 4)}
log("calibration", OUT["calibration"])

# ---------------------------------------------------------------------------
# 6. Scenario sweep (same grid as scripts/live_distribution_sweep.py):
#    coverage, urban-signal sensitivity, weather-weight sensitivity,
#    congestion mismatch, LR vs RF
# ---------------------------------------------------------------------------
import importlib.util
spec = importlib.util.spec_from_file_location("sweep", f"{BASE}/scripts/live_distribution_sweep.py")
sweep = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sweep)
from datetime import datetime as _dt

obs = []
for name, dist, dur, urban in sweep.ROUTE_PAIRS:
    for season, (wd, we) in sweep.SEASON_DATES.items():
        temp, hum = sweep.SEASON_BASELINE[season]
        for day in (wd, we):
            for band, rain, wind, toff, hoff in sweep.WEATHER_BANDS:
                for hour in sweep.SWEEP_HOURS:
                    obs.append(fx.RouteObservation(distance_km=dist, duration_min=dur,
                                                   departure=day.replace(hour=hour), urban_signal=urban,
                                                   temperature_c=temp + toff, rainfall_mm=rain,
                                                   humidity=min(1.0, hum + hoff), wind_speed_kmh=wind))
log("sweep scenarios", len(obs))


def grades(observations, weights=None, m=model):
    if weights is not None:
        old = fx.weather_risk_from_conditions

        def patched(t, r, h, w):
            rc = fx.clamp(r / 10.0)
            ic = 1.0 if t <= 0 else 0.5 if t <= 3 else 0.0
            wc = fx.clamp((w - 15.0) / 45.0)
            fc = fx.clamp((h - 0.70) / 0.30)
            return fx.clamp(weights[0] * rc + weights[1] * ic + weights[2] * wc + weights[3] * fc)
        fx.weather_risk_from_conditions = patched
    try:
        frame = pd.DataFrame([derive_features(o) for o in observations])
    finally:
        if weights is not None:
            fx.weather_risk_from_conditions = old
    return m.predict(frame[FEATURE_COLUMNS]), frame


g0, f0 = grades(obs)
OUT["sweep_counts"] = {l: int((g0 == l).sum()) for l in LABELS}

# coverage, defined per value: how many live values fall outside the training min-max
cover = {}
for col in NUMERIC_COLUMNS:
    lo, hi = float(data[col].min()), float(data[col].max())
    out_n = int(((f0[col] < lo) | (f0[col] > hi)).sum())
    p1, p99 = float(data[col].quantile(0.01)), float(data[col].quantile(0.99))
    out_p = int(((f0[col] < p1) | (f0[col] > p99)).sum())
    cover[col] = {"outside_minmax": out_n, "outside_p1_p99": out_p,
                  "live_min": round(float(f0[col].min()), 2), "live_max": round(float(f0[col].max()), 2),
                  "train_min": round(lo, 2), "train_max": round(hi, 2)}
OUT["coverage"] = cover
log("coverage", {k: v["outside_minmax"] for k, v in cover.items() if v["outside_minmax"]})

# urban signal sensitivity
urb = {}
for delta in [-0.2, -0.1, 0.1, 0.2]:
    g, _ = grades([replace(o, urban_signal=min(1, max(0, o.urban_signal + delta))) for o in obs])
    urb[str(delta)] = round(float((g != g0).mean()), 4)
OUT["urban_sensitivity_grade_changed"] = urb

# weather-risk weight sensitivity (each weight +-20 percent, model fixed)
base_w = [0.45, 0.30, 0.25, 0.10]
ww = {}
for i, name in enumerate(["rain", "ice", "wind", "fog"]):
    for f in [0.8, 1.2]:
        w = list(base_w)
        w[i] *= f
        g, _ = grades(obs, weights=w)
        ww[f"{name}x{f}"] = round(float((g != g0).mean()), 4)
OUT["weather_weight_sensitivity_grade_changed"] = ww
# and the effect on the training labels themselves
lab = {}
for i, name in enumerate(["rain", "ice", "wind", "fog"]):
    w = list(base_w)
    w[i] *= 1.2
    tmp = data.copy()
    tmp["weather_risk"] = [fx.clamp(w[0] * fx.clamp(r / 10) + w[1] * (1.0 if t <= 0 else 0.5 if t <= 3 else 0.0)
                                    + w[2] * fx.clamp((ws - 15) / 45) + w[3] * fx.clamp((h - 0.7) / 0.3))
                           for r, t, ws, h in zip(tmp.rainfall_mm, tmp.temperature_c, tmp.wind_speed_kmh, tmp.humidity)]
    lab[f"{name}x1.2"] = round(float((rule_labels(tmp[FEATURE_COLUMNS]) != rule_labels(data[FEATURE_COLUMNS])).mean()), 4)
OUT["weather_weight_effect_on_rule_labels"] = lab
log("weights", ww, lab)

# congestion: OSRM gives roughly free-flow times; training durations include 0-45 percent delay
cong = {}
for f in [1.10, 1.225, 1.45]:
    g, _ = grades([replace(o, duration_min=o.duration_min * f) for o in obs])
    cong[str(f)] = round(float((g != g0).mean()), 4)
OUT["congestion_grade_changed_if_slower"] = cong
OUT["speed_ranges"] = {"train_avg_speed_p1_p99": [round(float(data.avg_speed_kmh.quantile(q)), 1) for q in (0.01, 0.99)],
                       "sweep_avg_speed": [round(float(f0.avg_speed_kmh.min()), 1), round(float(f0.avg_speed_kmh.max()), 1)]}
log("congestion", cong)

# LR as the advisory model: same sweep, agreement with RF
gl, _ = grades(obs, m=lr_model)
OUT["sweep_lr_vs_rf"] = {"agreement": round(float((gl == g0).mean()), 4),
                         "lr_counts": {l: int((gl == l).sum()) for l in LABELS},
                         "max_step_difference": int(max(abs(LABELS.index(a) - LABELS.index(b)) for a, b in zip(gl, g0)))}
log("lr vs rf", OUT["sweep_lr_vs_rf"])

# class balance under a calmer, more realistic weather mix
realistic, _ = generate(severity_probs=[0.50, 0.30, 0.14, 0.05, 0.01])
OUT["class_balance"] = {
    "shipped": {l: int((data.road_condition_label == l).sum()) for l in LABELS},
    "calm_heavy_mix": {l: int((realistic.road_condition_label == l).sum()) for l in LABELS},
}
OUT["temperature_by_season"] = {s: round(float(data[data.season == s].temperature_c.mean()), 1)
                                for s in ["winter", "spring", "summer", "autumn"]}
OUT["model_file_mb"] = round(__import__("os").path.getsize(f"{BASE}/models/road_condition_model.joblib") / 1e6, 1)
json.dump(OUT, open(sys.argv[2], "w"), indent=1)
log("done")
