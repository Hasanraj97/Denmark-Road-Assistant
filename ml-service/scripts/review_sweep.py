"""Sensitivity checks on the 1,536-case scenario sweep (review, November 2026).

Run from anywhere with the ML venv:  python review_sweep.py <ml-service dir> <out.json>
"""
import sys, json
BASE = sys.argv[1]
sys.path.insert(0, BASE)
from dataclasses import replace
import numpy as np, pandas as pd, joblib
import app.features as F
from app.features import RouteObservation, derive_features, FEATURE_COLUMNS, NUMERIC_COLUMNS, CATEGORICAL_COLUMNS, clamp
import importlib.util
spec = importlib.util.spec_from_file_location('sw', f'{BASE}/scripts/live_distribution_sweep.py')
sw = importlib.util.module_from_spec(spec); spec.loader.exec_module(sw)
rng = np.random.default_rng(2026)
obs, meta = [], []
for route_name, d, dur, u in sw.ROUTE_PAIRS:
    for season, (wd, we) in sw.SEASON_DATES.items():
        mt, mh = sw.SEASON_BASELINE[season]
        for kind, base in (('weekday', wd), ('weekend', we)):
            for band, rain, wind, to, ho in sw.WEATHER_BANDS:
                for hour in sw.SWEEP_HOURS:
                    o = RouteObservation(distance_km=d, duration_min=dur, departure=base.replace(hour=hour), urban_signal=u,
                        temperature_c=float(mt + to + rng.normal(0, 2.0)),
                        rainfall_mm=float(max(0.0, rain + rng.normal(0, rain * 0.25))),
                        humidity=float(np.clip(mh + ho + rng.normal(0, 0.04), 0.3, 1.0)),
                        wind_speed_kmh=float(max(0.0, wind + rng.normal(0, wind * 0.15))))
                    obs.append(o); meta.append(band)
model = joblib.load(f'{BASE}/models/road_condition_model.joblib')
data = pd.read_csv(f'{BASE}/data/synthetic_denmark_routes.csv')
LABELS = ['Excellent','Good','Moderate','Poor','Very Poor']
def grades(os_, m=model):
    fr = pd.DataFrame([derive_features(o, rng=None) for o in os_])[FEATURE_COLUMNS]
    return m.predict(fr), fr
g0, f0 = grades(obs)
out = {'counts': {l: int((g0 == l).sum()) for l in LABELS}}
out['by_weather'] = {b: {l: int(((g0 == l) & (np.array(meta) == b)).sum()) for l in LABELS} for b in ['calm','typical','wet','storm']}
cov = {}
for c in NUMERIC_COLUMNS:
    lo, hi = data[c].min(), data[c].max(); p1, p99 = data[c].quantile(0.01), data[c].quantile(0.99)
    cov[c] = {'outside_minmax': int(((f0[c] < lo) | (f0[c] > hi)).sum()), 'outside_p1_p99': int(((f0[c] < p1) | (f0[c] > p99)).sum()),
              'live': [round(float(f0[c].min()), 2), round(float(f0[c].max()), 2)], 'train': [round(float(lo), 2), round(float(hi), 2)], 'p1p99': [round(float(p1), 2), round(float(p99), 2)]}
out['coverage'] = cov
out['urban'] = {str(dl): round(float((grades([replace(o, urban_signal=min(1, max(0, o.urban_signal + dl))) for o in obs])[0] != g0).mean()), 4) for dl in [-0.2, -0.1, 0.1, 0.2]}
orig = F.weather_risk_from_conditions
def weighted(w):
    def f(t, r, h, wk):
        rain = clamp(r / 10.0); ice = 1.0 if t <= 0 else 0.5 if t <= 3 else 0.0
        wind = clamp((wk - 15.0) / 45.0); fog = clamp((h - 0.70) / 0.30)
        return clamp(w[0]*rain + w[1]*ice + w[2]*wind + w[3]*fog)
    return f
ww = {}
for i, n in enumerate(['rain','ice','wind','fog']):
    for k in [0.8, 1.2]:
        w = [0.45, 0.30, 0.25, 0.10]; w[i] *= k
        F.weather_risk_from_conditions = weighted(w)
        ww[f'{n}x{k}'] = round(float((grades(obs)[0] != g0).mean()), 4)
F.weather_risk_from_conditions = orig
out['weights'] = ww
out['congestion'] = {str(k): round(float((grades([replace(o, duration_min=o.duration_min * k) for o in obs])[0] != g0).mean()), 4) for k in [1.10, 1.225, 1.45]}
from sklearn.model_selection import train_test_split
from sklearn.pipeline import Pipeline
from sklearn.compose import ColumnTransformer
from sklearn.preprocessing import StandardScaler, OneHotEncoder
from sklearn.linear_model import LogisticRegression
Xtr, Xte, ytr, yte = train_test_split(data[FEATURE_COLUMNS], data['road_condition_label'], test_size=0.2, random_state=42, stratify=data['road_condition_label'])
lr = Pipeline([('pre', ColumnTransformer([('n', StandardScaler(), NUMERIC_COLUMNS), ('c', OneHotEncoder(handle_unknown='ignore', sparse_output=False), CATEGORICAL_COLUMNS)])), ('clf', LogisticRegression(max_iter=5000, C=10.0))]).fit(Xtr, ytr)
gl = lr.predict(f0)
out['lr'] = {'agreement': round(float((gl == g0).mean()), 4), 'counts': {l: int((gl == l).sum()) for l in LABELS},
             'max_step': int(max(abs(LABELS.index(a) - LABELS.index(b)) for a, b in zip(gl, g0)))}
json.dump(out, open(sys.argv[2], 'w'), indent=1)
print(json.dumps({k: v for k, v in out.items() if k != 'coverage'}))
print({c: (v['outside_minmax'], v['outside_p1_p99']) for c, v in cov.items()})
