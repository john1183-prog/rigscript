#!/usr/bin/env python3
"""WP2.2 prototype of the acting motion model (per-joint timing, anticipation, overshoot, retarget, moving holds).

Pure functions of (keyframes, bone, t): no state, so seek == sequential (I2). Constants mirror ActingParams.
Run:  python3 acting_proto.py        (writes PNGs to ./out, prints the numeric checks)
The PIL drawer below validates MOTION SHAPE only, not final app pixels.
"""
import math, os, sys
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
os.makedirs(OUT, exist_ok=True)

# ---- parameters (mirror ActingParams / the brief's table) ---------------------------------------------------------
LEVELS = {
    "SUBTLE": dict(delay=0.012, stretch=0.03, antF=0.03, antCap=4.0, osF=0.025, osCap=3.0, hold=0.15, lead=0.07),
    "NORMAL": dict(delay=0.025, stretch=0.06, antF=0.06, antCap=6.0, osF=0.05, osCap=5.0, hold=0.25, lead=0.10),
    "BOLD":   dict(delay=0.040, stretch=0.09, antF=0.09, antCap=9.0, osF=0.08, osCap=8.0, hold=0.30, lead=0.13),
}
ANT_MIN_MOVE, ANT_MIN_DUR = 12.0, 0.25   # anticipation: a wind-back that ends exactly when the joint starts to move (pre-roll of P["lead"] s)
OS_LEAD = 0.04      # s: the settle kick starts this long BEFORE the shape arrives, so it rides the arrival
GRID = 128          # SHAPE_GRID in ActingParams
EASE_ALL = True     # AmplitudeSettings.easeAllWithSpring (default true): the legacy shape of every non-rigid ease is the spring
OMEGA, ZETA = 14.0, 0.55
OS_RAMP = 0.05          # s: the overshoot term is ramped in so the velocity has no step at arrival
SETTLE = 0.9            # s: every acting term is exactly zero this long after arrival (exact landing)
CARRY_TAU, CARRY_MAX = 0.07, 720.0   # velocity carry on retarget (time constant s, cap deg/s)
HOLD_RAMP = 0.6         # s: the moving hold fades in after arrival
CHAIN_DEPTH = 6
VEL_H = 1.0 / 480.0
OWN_OVERSHOOT = ("spring", "back", "elastic_out", "bounce")   # these eases already overshoot: no extra overshoot

BONES = ["torso", "head", "upper_arm_r", "lower_arm_r", "upper_arm_l", "lower_arm_l",
         "upper_leg_r", "lower_leg_r", "upper_leg_l", "lower_leg_l"]
DEFAULT = [-90, 0, 160, 10, -160, -10, 80, 5, 100, -5]
PARENT = [-1, 0, 0, 2, 0, 4, -1, 6, -1, 8]
LENGTH = [.22, .05, .12, .10, .12, .10, .14, .14, .14, .14]
LEVEL = [0, 1, 1, 2, 1, 2, 1, 2, 1, 2]     # chain level per bone: torso 0; head, upper arms/legs 1; forearms, lower legs 2


def smooth(x):
    x = min(max(x, 0.0), 1.0)
    return x * x * (3 - 2 * x)


def fade(x):
    return 1.0 - smooth((x - 0.5 * SETTLE) / (0.5 * SETTLE))


# ---- the engine's easings (copy of EasingMath) --------------------------------------------------------------------
def spring(t, k=280.0, c=28.0):
    if t <= 0: return 0.0
    if t >= 1: return 1.0
    ts = t * 2.2; wn = math.sqrt(k); z = c / (2 * wn)
    if z < 1:
        wd = wn * math.sqrt(1 - z * z)
        return min(max(1 - math.exp(-z * wn * ts) * (math.cos(wd * ts) + z / math.sqrt(1 - z * z) * math.sin(wd * ts)), 0.0), 1.5)
    return 1 - math.exp(-wn * ts)

def ease(kind, t):
    if kind == "linear": return t
    if kind == "ease_in": return t ** 3
    if kind == "ease_out": return 1 - (1 - t) ** 3
    if kind == "ease_in_out": return 4 * t ** 3 if t < .5 else 1 - (-2 * t + 2) ** 3 / 2
    if kind == "back":
        c1 = 1.70158; c3 = c1 + 1; x = t - 1; return 1 + c3 * x ** 3 + c1 * x * x
    if kind == "elastic_out":
        if t <= 0: return 0.0
        if t >= 1: return 1.0
        return 2.0 ** (-10 * t) * math.sin((t * 10 - .75) * (2 * math.pi / 3)) + 1
    if kind == "bounce":
        n = 7.5625; x = t
        if x < 1 / 2.75: return n * x * x
        if x < 2 / 2.75: x -= 1.5 / 2.75; return n * x * x + .75
        if x < 2.5 / 2.75: x -= 2.25 / 2.75; return n * x * x + .9375
        x -= 2.625 / 2.75; return n * x * x + .984375
    if kind == "spring": return spring(t)
    return t


class KF:
    def __init__(self, ts, d, frm, to, ease_="ease_in_out", k=280.0, c=28.0, fp="stand", tp="stand"):
        self.ts, self.d, self.frm, self.to, self.ease, self.k, self.c, self.fp, self.tp = ts, d, frm, to, ease_, k, c, fp, tp
    @property
    def rigid(self): return self.ease == "rigid"
    @property
    def loco(self): return self.fp.startswith(("walk_", "jog_")) or self.tp.startswith(("walk_", "jog_"))


def build(events, default=DEFAULT):
    """events: (ts, d, offsets list, ease, poseId). Same chaining as TimelineCompiler (from = previous to)."""
    kfs, prev, pp = [], list(default), "stand"
    for ts, d, off, e, pid in events:
        to = [default[i] + off[i] for i in range(10)]
        kfs.append(KF(ts, max(d, 0.016), prev, to, e, fp=pp, tp=pid)); prev, pp = to, pid
    return kfs


# ---- LEGACY (acting OFF) evaluation: what PlaybackEngine.resolveBaseAngles does ------------------------------------
def legacy(kfs, b, t, easeAll=True):
    idx = -1
    for i, k in enumerate(kfs):
        if k.ts <= t: idx = i
    k = kfs[idx] if idx >= 0 else kfs[0]
    p = min(max((t - k.ts) / k.d, 0.0), 1.0)
    if k.rigid: e = 1.0
    elif k.ease == "spring" or easeAll: e = spring(p, k.k, k.c)
    else: e = ease(k.ease, p)
    return k.frm[b] + (k.to[b] - k.frm[b]) * e


# ---- ACTED evaluation ---------------------------------------------------------------------------------------------
_wd = OMEGA * math.sqrt(1 - ZETA ** 2)
def _g_raw(tau):
    return smooth(tau / OS_RAMP) * math.exp(-ZETA * OMEGA * tau) * math.sin(_wd * tau) * fade(tau)
GPEAK = max(_g_raw(i * 1e-4) for i in range(int(SETTLE * 1e4) + 1))   # the peak of the shaped term, so the cap is exact

def shape(k, p):
    # SAME eased progress as the legacy engine (rigid 1, spring, implicit spring when easeAllWithSpring, else the named ease):
    # the cue keeps its sync (the legacy spring lands within ~60 ms), acting only adds overlap, wind-up and settle on top.
    if k.rigid: return 1.0
    if k.ease == "spring" or EASE_ALL: return spring(p, k.k, k.c)
    return ease(k.ease, p)

def arrival_p(k):
    """First progress on a 1/128 grid where the shape reaches 98% (1.0 when it never does before 1)."""
    if k.rigid: return 0.0
    for i in range(GRID + 1):
        if shape(k, i / GRID) >= 0.98: return i / GRID
    return 1.0

def shape_over(k):
    """How far the legacy shape itself overshoots 1 (fraction), found on a 1/128 grid."""
    if k.rigid: return 0.0
    return max(0.0, max(shape(k, i / GRID) for i in range(GRID + 1)) - 1.0)

def seg_start(k, L, P): return k.ts if k.rigid else k.ts + L * P["delay"]
def seg_dur(k, L, P): return 0.0 if k.rigid else k.d * (1 + L * P["stretch"])

def seg_value(k, b, L, P, fe, v0, t):
    if k.rigid:
        return k.to[b] if t >= k.ts else fe
    s = seg_start(k, L, P); e = t - s
    if e <= 0: return fe
    dS = seg_dur(k, L, P); m = k.to[b] - fe; sg = 1.0 if m >= 0 else -1.0
    p = min(e / dS, 1.0)
    val = fe + m * shape(k, p)
    if (not k.loco) and k.ease not in OWN_OVERSHOOT:
        tau = t - (s + dS * arrival_p(k) - OS_LEAD)
        if tau > 0:
            O = max(0.0, min(P["osF"] * abs(m), P["osCap"]) - shape_over(k) * abs(m))   # total overshoot stays within the cap
            val += sg * O * _g_raw(tau) / GPEAK
    if v0 != 0.0:
        val += v0 * e * math.exp(-e / CARRY_TAU) * fade(e)
    return val

def seg_settle_time(k, L, P):
    if k.rigid: return k.ts
    s = seg_start(k, L, P); dS = seg_dur(k, L, P)
    return s + max(dS, dS * arrival_p(k) - OS_LEAD + SETTLE, SETTLE)
def seg_settled(k, L, P, t): return t >= seg_settle_time(k, L, P)

def dip_eligible(k, m): return (not k.rigid) and (not k.loco) and abs(m) >= ANT_MIN_MOVE and k.d >= ANT_MIN_DUR

M64 = (1 << 64) - 1
def splitmix(x):
    x = (x + 0x9E3779B97F4A7C15) & M64
    z = x
    z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & M64
    z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & M64
    return z ^ (z >> 31)
def unit(seed, b, k):
    h = splitmix((seed ^ ((b * 0x9E3779B97F4A7C15) & M64) ^ ((k * 0xD1B54A32D192ED03) & M64)) & M64)
    return (h >> 11) / float(1 << 53)

def drift(seed, b, t, amp):
    f1 = 0.21 + 0.19 * unit(seed, b, 0); f2 = 0.37 + 0.31 * unit(seed, b, 1)
    p1 = 2 * math.pi * unit(seed, b, 2); p2 = 2 * math.pi * unit(seed, b, 3)
    return amp * (0.65 * math.sin(2 * math.pi * f1 * t + p1) + 0.35 * math.sin(2 * math.pi * f2 * t + p2))

def acted(kfs, b, t, P, seed=12345, hold=True):
    L = LEVEL[b]; n = len(kfs)
    a = -1
    for i, k in enumerate(kfs):
        if k.ts <= t: a = i
    j = max(a, 0)
    while j > 0 and seg_start(kfs[j], L, P) > t: j -= 1
    if seg_start(kfs[j], L, P) > t:                  # before anything has started on this bone
        return kfs[0].to[b] if kfs[0].rigid else kfs[0].frm[b]
    c, depth = j, 0
    while c > 0 and depth < CHAIN_DEPTH and not seg_settled(kfs[c - 1], L, P, seg_start(kfs[c], L, P)):
        c -= 1; depth += 1
    fe, v0 = kfs[c].frm[b], 0.0
    for i in range(c, j):
        sN = seg_start(kfs[i + 1], L, P)
        nfe = seg_value(kfs[i], b, L, P, fe, v0, sN)
        vel = (nfe - seg_value(kfs[i], b, L, P, fe, v0, sN - VEL_H)) / VEL_H
        v0 = max(-CARRY_MAX, min(CARRY_MAX, vel)) if not kfs[i + 1].rigid else 0.0
        fe = nfe
    k = kfs[j]
    val = seg_value(k, b, L, P, fe, v0, t)
    if j + 1 < n:                                     # anticipation: wind back just before the next move starts on this bone
        nx = kfs[j + 1]; sN = seg_start(nx, L, P); lead = P["lead"]
        if t > sN - lead:
            mN = nx.to[b] - seg_value(k, b, L, P, fe, v0, sN)
            if dip_eligible(nx, mN):
                u = (t - (sN - lead)) / lead
                val -= (1.0 if mN >= 0 else -1.0) * min(P["antF"] * abs(mN), P["antCap"]) * math.sin(math.pi * u) ** 2
    if hold and not k.loco:
        w = smooth((t - seg_settle_time(k, L, P) + SETTLE) / HOLD_RAMP) if not k.rigid else smooth((t - k.ts) / HOLD_RAMP)
        if w > 0: val += w * drift(seed, b, t, P["hold"])
    return val


# ---- reference drawer (PIL) ---------------------------------------------------------------------------------------
def fk(angles):
    """Joint positions in normalised units: pelvis at the origin, y down. Returns [(x0,y0,x1,y1)] per bone and abs angles."""
    ab = [0.0] * 10; seg = [None] * 10
    tip = [None] * 10
    for i in range(10):
        ab[i] = angles[i] + (ab[PARENT[i]] if PARENT[i] >= 0 else 0.0)
        x0, y0 = (0.0, 0.0) if PARENT[i] < 0 else ((tip[PARENT[i]]))
        if i in (2, 4): x0, y0 = tip[0]         # arms hang from the torso tip (shoulders)
        if i == 1: x0, y0 = tip[0]
        x1 = x0 + LENGTH[i] * math.cos(math.radians(ab[i])); y1 = y0 + LENGTH[i] * math.sin(math.radians(ab[i]))
        tip[i] = (x1, y1); seg[i] = (x0, y0, x1, y1)
    return seg

def draw_figure(draw, ox, oy, S, angles, hand=False, bucket=0, seed=7, ink=(25, 25, 30), w=4):
    seg = fk(angles)
    rng = np.random.default_rng(seed * 1000 + bucket)
    def wob(n, amp):                         # smooth 1-D noise for a wobbling stroke
        pts = rng.normal(0, amp, size=n)
        return np.convolve(np.pad(pts, 2, mode="edge"), np.ones(3) / 3, mode="valid")[:n]
    for i, (x0, y0, x1, y1) in enumerate(seg):
        if not hand:
            draw.line([(ox + x0 * S, oy + y0 * S), (ox + x1 * S, oy + y1 * S)], fill=ink, width=w); continue
        N = 9; dx, dy = x1 - x0, y1 - y0
        nx, ny = -dy, dx; ln = math.hypot(nx, ny) or 1.0; nx, ny = nx / ln, ny / ln
        off = wob(N, 0.0035)
        for rep in range(2):                 # two slightly different strokes, like a re-traced pencil line
            pts = []
            for q in range(N):
                u = q / (N - 1); extra = (-0.004 if rep else 0.004) * (1 if q in (0, N - 1) else 0)
                o = off[q] * (1.0 if rep == 0 else 0.7) + extra * 0.5 + 0.002 * (rep * 2 - 1)
                pts.append((ox + (x0 + dx * u + nx * o) * S, oy + (y0 + dy * u + ny * o) * S))
            draw.line(pts, fill=ink if rep == 0 else (70, 70, 80), width=w if rep == 0 else max(1, w - 2), joint="curve")
    hx, hy = seg[1][2], seg[1][3]
    r = 0.048
    if not hand:
        draw.ellipse([ox + (hx - r) * S, oy + (hy - r) * S, ox + (hx + r) * S, oy + (hy + r) * S], outline=ink, width=w)
    else:
        for rep in range(2):
            th0 = rng.uniform(0, 6.28); M = 28; ex = rng.uniform(-0.12, 0.12)
            ptsH = []
            for q in range(M + 4):
                th = th0 + (q / M) * 2 * math.pi * 1.0 + rep * 0.15
                rr = r * (1 + 0.04 * math.sin(3 * th + rep) + ex * 0.05) + rng.normal(0, 0.0012)
                ptsH.append((ox + (hx + rr * math.cos(th)) * S, oy + (hy + rr * math.sin(th)) * S))
            draw.line(ptsH, fill=ink if rep == 0 else (70, 70, 80), width=w if rep == 0 else max(1, w - 2), joint="curve")

def strip(rows, times, fname, labels, cell=(250, 300), hand_rows=(), boil_fps=8):
    W, H = cell; img = Image.new("RGB", (W * len(times), H * len(rows)), (247, 243, 232))
    d = ImageDraw.Draw(img)
    for r, fn in enumerate(rows):
        for c, t in enumerate(times):
            ox, oy = c * W + W * 0.5, r * H + H * 0.60
            ang = [fn(b, t) for b in range(10)]
            draw_figure(d, ox, oy, 330, ang, hand=(r in hand_rows), bucket=int(t * boil_fps))
            d.text((c * W + 6, r * H + 4), f"{labels[r]}  t={t:.2f}s", fill=(90, 90, 90))
        d.line([(0, (r + 1) * H - 1), (W * len(times), (r + 1) * H - 1)], fill=(200, 195, 180))
    img.save(os.path.join(OUT, fname))


# ---- scenarios and checks -----------------------------------------------------------------------------------------
def off(b, t): return 0.0
POSE_REACH = [14, -18, -130, -60, 35, 25, 0, 0, 0, 0]       # torso, head, arm_r, forearm_r, arm_l, forearm_l, legs
POSE_STAND = [0] * 10
SCEN = build([(0.0, 0.4, POSE_STAND, "ease_in_out", "stand_straight"),
              (0.5, 0.6, POSE_REACH, "ease_in_out", "explain"),
              (1.9, 0.6, POSE_STAND, "ease_in_out", "stand_straight")])
OVERLAP = build([(0.0, 0.4, POSE_STAND, "ease_in_out", "stand_straight"),
                 (0.5, 0.6, POSE_REACH, "ease_in_out", "explain"),
                 (0.53, 0.6, [-10, 12, 60, 30, -50, -20, 0, 0, 0, 0], "ease_in_out", "think")])   # next event lands mid-snap

def main():
    P = LEVELS["NORMAL"]; ts = np.arange(0.0, 3.2, 1 / 240.0)
    leg = lambda b: np.array([legacy(SCEN, b, t) for t in ts])
    act = lambda b: np.array([acted(SCEN, b, t, P, hold=False) for t in ts])
    names = {0: "torso (level 0)", 1: "head (level 1)", 3: "forearm_r (level 2)"}

    fig, ax = plt.subplots(3, 1, figsize=(11, 10), sharex=True)
    for b, nm in names.items():
        mv = act(b) - SCEN[0].frm[b]
        ax[0].plot(ts, (mv - mv[0]) / max(abs(mv).max(), 1e-9), label=nm)
    ax[0].set_title("NORMAL acted motion, one reach (0.5 s, 0.6 s ease_in_out): normalised, shows lead/follow, dip and overshoot")
    ax[0].axvline(0.5, c="grey", ls=":"); ax[0].legend()
    b = 3
    ax[1].plot(ts, leg(b), label="OFF (legacy implicit spring)"); ax[1].plot(ts, act(b), label="NORMAL"); ax[1].legend()
    ax[1].set_title("forearm_r angle: legacy snap vs acted"); ax[1].axvline(0.5, c="grey", ls=":")
    for lv, col in (("SUBTLE", "C2"), ("NORMAL", "C1"), ("BOLD", "C3")):
        a = np.array([acted(SCEN, 3, t, LEVELS[lv], hold=False) for t in ts]); ax[2].plot(ts, a, c=col, label=lv)
    ax[2].set_title("forearm_r by intensity"); ax[2].legend(); ax[2].set_xlim(0.3, 2.9)
    plt.tight_layout(); plt.savefig(os.path.join(OUT, "01_chain_and_intensity.png"), dpi=90); plt.close()

    # retarget: interrupted move
    ts2 = np.arange(0.0, 2.4, 1 / 240.0)
    fig, ax = plt.subplots(2, 1, figsize=(11, 7), sharex=True)
    for b, nm in ((0, "torso"), (3, "forearm_r")):
        ax[0].plot(ts2, [legacy(OVERLAP, b, t) for t in ts2], ls="--", label=nm + " OFF")
        ax[0].plot(ts2, [acted(OVERLAP, b, t, P, hold=False) for t in ts2], label=nm + " NORMAL")
        ax[1].plot(ts2[1:], np.diff([acted(OVERLAP, b, t, P, hold=False) for t in ts2]) * 240, label=nm + " NORMAL deg/s")
    ax[0].axvline(0.53, c="grey", ls=":"); ax[0].set_xlim(0.3, 1.8); ax[1].set_xlim(0.3, 1.8); ax[0].legend(); ax[0].set_title("retarget: the event at 0.53 s lands in the middle of the 0.5 s move (OFF pops, NORMAL continues)")
    ax[1].legend(); ax[1].set_title("angular velocity (no step at the retarget)")
    plt.tight_layout(); plt.savefig(os.path.join(OUT, "02_retarget.png"), dpi=90); plt.close()

    # numeric checks
    print("== numeric checks (NORMAL) ==")
    t60 = np.arange(0.0, 3.2, 1 / 60.0)
    for lv in LEVELS:
        Pl = LEVELS[lv]
        for sc, nm in ((SCEN, "single"), (OVERLAP, "overlap")):
            worst_step, worst_acc, nan = 0.0, 0.0, False
            for b in range(10):
                v = np.array([acted(sc, b, t, Pl, hold=True) for t in t60]); nan |= bool(np.isnan(v).any())
                worst_step = max(worst_step, float(np.abs(np.diff(v)).max()))
                worst_acc = max(worst_acc, float(np.abs(np.diff(v, 2)).max()))
            print(f"{lv:6s} {nm:8s} max |step|/frame {worst_step:6.2f} deg, max |2nd diff| {worst_acc:6.2f}, NaN {nan}")
    # legacy step for reference
    lstep = max(float(np.abs(np.diff([legacy(OVERLAP, b, t) for t in t60])).max()) for b in range(10))
    print(f"legacy overlap max |step|/frame {lstep:.2f} deg (the pop)")
    # ordering of arrival (time to reach 90% of the move) and of peak velocity
    for lv in LEVELS:
        Pl = LEVELS[lv]; arr, pk = [], []
        for b in (0, 1, 3):
            v = np.array([acted(SCEN, b, t, Pl, hold=False) for t in ts]); m0 = SCEN[1].to[b] - SCEN[1].frm[b]
            frac = (v - SCEN[1].frm[b]) / m0
            arr.append(float(ts[np.argmax((frac >= 0.9) & (ts > 0.5))]))
            vel = np.abs(np.diff(v)) * 240; pk.append(float(ts[1:][np.argmax(np.where((ts[1:] > 0.5) & (ts[1:] < 1.5), vel, 0))]))
        print(f"{lv:6s} 90% arrival torso/head/forearm {arr}  peak-velocity time {pk}")
    # overshoot cap and exact landing
    for lv in LEVELS:
        Pl = LEVELS[lv]; worst = 0.0; land = 0.0
        for b in range(10):
            for sc in (SCEN,):
                v = np.array([acted(sc, b, t, Pl, hold=False) for t in ts])
                for k in range(1, len(sc)):
                    k0, k1 = sc[k], sc[k - 1]; m = k0.to[b] - k0.frm[b]
                    if m == 0: continue
                    sel = (ts > k0.ts + 0.1) & (ts < ((seg_start(sc[k + 1], LEVEL[b], Pl) - Pl["lead"]) if k + 1 < len(sc) else 3.2))   # the next move's wind-back is anticipation, not overshoot
                    over = np.sign(m) * (v[sel] - k0.to[b]); worst = max(worst, float(over.max()))
                    tend = seg_settle_time(k0, LEVEL[b], Pl)
                    if tend < (sc[k + 1].ts if k + 1 < len(sc) else 3.2):
                        land = max(land, abs(acted(sc, b, tend + 0.01, Pl, hold=False) - k0.to[b]))
        print(f"{lv:6s} worst overshoot {worst:.3f} deg (cap {Pl['osCap']}), worst landing error {land:.2e}")
    # hold drift bound
    mx = max(abs(drift(12345, b, t, 0.3)) for b in range(10) for t in np.arange(0, 60, 0.05)); print(f"hold drift max {mx:.3f} deg (<= 0.3)")
    print("splitmix/unit reference:", [round(unit(12345, b, k), 12) for b, k in ((0, 0), (3, 2), (9, 1))])

    # motion strips: OFF vs NORMAL vs NORMAL + hand-drawn line
    times = [0.40, 0.46, 0.50, 0.54, 0.58, 0.64, 0.74, 0.95]
    rows = [lambda b, t: legacy(SCEN, b, t), lambda b, t: acted(SCEN, b, t, P), lambda b, t: acted(SCEN, b, t, P)]
    strip(rows, times, "03_motion_strip.png", ["OFF (legacy)", "NORMAL", "NORMAL + hand-drawn line"], hand_rows=(2,))
    # a long hold, to see the moving hold and the hand-drawn "boil"
    strip([lambda b, t: acted(SCEN, b, t, P)] * 2, [1.6, 1.7, 1.8, 1.9], "04_hold_boil.png", ["NORMAL clean", "NORMAL hand-drawn"], hand_rows=(1,))

POSES = {  # PoseDef offsets of the built-in poses used by the Kotlin test (ids -> offset)
    "stand_straight": {},
    "explain": {"torso": -5, "upper_arm_r": -75, "lower_arm_r": -30, "upper_arm_l": 75, "lower_arm_l": 30},
    "think": {"torso": 8, "head": -20, "upper_arm_r": -80, "lower_arm_r": -100},
    "wave": {"upper_arm_r": -130, "lower_arm_r": -20},
    "walk_a": {"torso": 7, "upper_leg_r": -28, "lower_leg_r": -22, "upper_leg_l": 32, "lower_leg_l": 12, "upper_arm_r": 38, "upper_arm_l": -38},
    "walk_b": {"torso": 7, "upper_leg_r": 32, "lower_leg_r": 12, "upper_leg_l": -28, "lower_leg_l": -22, "upper_arm_r": -38, "upper_arm_l": 38},
}
# (timeSec, duration, pose, ease): the script ActingMotionTest compiles; Python and Kotlin must agree on it.
REF_EVENTS = [(0.0, 0.4, "stand_straight", "ease_in_out"), (0.5, 0.6, "explain", "ease_in_out"), (0.53, 0.6, "think", "ease_in_out"),
              (1.9, 0.6, "wave", "linear"), (2.4, 0.5, "stand_straight", "rigid"), (3.0, 0.6, "walk_a", "ease_in_out"),
              (3.6, 0.6, "walk_b", "ease_in_out"), (4.5, 0.6, "stand_straight", "ease_in_out")]
def ref_kfs():
    ev = [(ts, d, [POSES[p].get(BONES[i], 0) for i in range(10)], e, p) for ts, d, p, e in REF_EVENTS]
    return build(ev)

def emit_reference():
    kfs = ref_kfs(); seed = 12345
    times = [0.45, 0.507, 0.523, 0.557, 0.6, 0.7, 1.0, 1.907, 2.0, 2.1, 2.407, 2.5, 3.1, 3.7, 4.6, 5.8]   # never exactly on a keyframe start (float32 vs float64) nor on a joint's delayed start (ts + level*delay)
    for lv in ("NORMAL", "BOLD"):
        print("// " + lv + ": (bone, time, value) in degrees, from acting_proto.py (seed 12345, moving hold on)")
        for b in (0, 1, 3, 7):
            for t in times:
                print("        doubleArrayOf(%d.0, %s, %.6f)," % (b, t, acted(kfs, b, t, LEVELS[lv], seed=seed, hold=True)))

if __name__ == "__main__":
    if "--emit-reference" in sys.argv: emit_reference()
    else: main()
