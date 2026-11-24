#!/usr/bin/env python3
"""WP2.3 step 2 prototype of the face model: expression blend with anticipation and take, brows, gaze.

Pure functions of (expression timeline, seed, t): no state, so seek == sequential (I2). Constants mirror
`ActingParams` / `FaceMotion` (Kotlin); change both together. The PIL drawer repeats `FaceGeometry` (eyes, brows,
mouth) so the plots show what the renderers would draw; it validates MOTION SHAPE only, not app pixels.
Run:  python3 face_proto.py            (writes PNGs to ./out, prints the numeric checks)
      python3 face_proto.py --emit-reference   (rows that `FaceMotionTest` pins the Kotlin port to)
"""
import math, os, sys
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
os.makedirs(OUT, exist_ok=True)

NORMAL, WIDE, SQUINT, WORRIED, ANGRY, HAPPY = range(6)
NAMES = ["normal", "wide", "squint", "worried", "angry", "happy"]

# ---- per-expression parameter sets ---------------------------------------------------------------------------------
# eyeRad (x radius / head radius), mouth widthMul / cyBias (fraction of r), brow height (eye radii), brow inner lift
# (eye radii; + = inner end up), brow visible in the LEGACY path. The legacy values are the ones in FaceGeometry.
EYE_RAD = [0.15, 0.20, 0.11, 0.15, 0.15, 0.20]
MOUTH_W = [1.0, 1.0, 1.0, 0.90, 0.90, 1.12]
MOUTH_B = [0.0, 0.0, 0.0, 0.05, 0.05, -0.06]
BROW_H = [1.8, 2.3, 1.4, 1.8, 1.8, 1.95]       # acting only, except worried/angry which are the legacy 1.8
BROW_L = [0.0, 0.20, 0.0, 0.55, -0.55, 0.12]   # worried/angry are the legacy +-0.55
BROW_LEGACY = [False, False, False, True, True, False]

LEVELS = {  # blend time (ActingLevel.expressionBlendSec), anticipation lead, take scale, gaze scale
    "SUBTLE": dict(blend=0.12, ant=0.040, take=0.5, gaze=0.6),
    "NORMAL": dict(blend=0.18, ant=0.060, take=1.0, gaze=1.0),
    "BOLD":   dict(blend=0.24, ant=0.080, take=1.4, gaze=1.0),
}
# anticipation squint depth (eye height multiplier dips by this much just before the change), by TARGET expression
ANT_DEPTH = [0.0, 0.35, 0.0, 0.0, 0.25, 0.0]
# take: eye-height overshoot (fraction) after the change, by TARGET expression
TAKE_AMP = [0.03, 0.10, 0.0, 0.05, 0.04, 0.06]
ZETA = 0.45
SETTLE = 0.6          # s after the change: every face term is exactly neutral again
CHAIN_DEPTH = 4

# gaze
GAZE_CAP = 0.18       # of head radius
SLOT = 2.1            # s; one saccade per slot at a seeded offset in [0, SLOT_SPREAD]  => intervals 1.0 .. 3.2 s
SLOT_SPREAD = 1.1
SACCADE_SEC = 0.06
EYE_LEAD_SEC = 0.09   # the head follows the eyes by this long
HEAD_TILT_MAX = 3.0   # degrees
P_CENTRE = 0.45       # return-to-centre bias
HEAD_RAMP_SEC = 0.25  # the head turns slowly: its follow motion is a 0.25 s ramp, not a saccade
BLINK_SHIFT = 0.12    # of head radius: a shift larger than this may carry a blink
P_BLINK = 0.35
BLINK_DUR = 0.15      # AmplitudeSettings.blinkDurationSec default

M64 = (1 << 64) - 1


def smooth(x):
    x = min(max(x, 0.0), 1.0)
    return x * x * (3 - 2 * x)


def fade(u):
    return 1.0 - smooth((u - 0.5 * SETTLE) / (0.5 * SETTLE))


def mix64(x):
    z = (x + 0x9E3779B97F4A7C15) & M64
    z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & M64
    z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & M64
    return z ^ (z >> 31)


def unit(seed, k, salt):
    """Deterministic uniform [0,1) from (seed, k, salt): the same on every device and run."""
    h = mix64((seed & M64) ^ mix64((k & M64) * 0x100000001B3 ^ (salt * 0x9E3779B97F4A7C15 & M64)))
    return (h >> 11) / float(1 << 53)


# ---- expression timeline ------------------------------------------------------------------------------------------
class Timeline:
    """changes[i] = (time, expression); expr0 holds before the first change (the first keyframe's expression)."""
    def __init__(self, expr0, changes):
        self.expr0 = expr0
        self.t = [c[0] for c in changes]
        self.e = [c[1] for c in changes]


def target_params(e):
    return [EYE_RAD[e], MOUTH_W[e], MOUTH_B[e], BROW_H[e], BROW_L[e]]


def blended(tl, i, t, lv, depth=0):
    """Parameter vector of the blend that starts at change i (i = -1: the initial expression), evaluated at t >= its start.
    A retarget starts from the value of the previous blend at the change time (no pop); older ones count as settled."""
    tgt = target_params(tl.expr0 if i < 0 else tl.e[i])
    if i < 0:
        return tgt
    tc = tl.t[i]
    if depth >= CHAIN_DEPTH:
        frm = target_params(tl.expr0 if i - 1 < 0 else tl.e[i - 1])
    else:
        frm = blended(tl, i - 1, tc, lv, depth + 1)
    s = smooth((t - tc) / lv["blend"])
    return [f + (g - f) * s for f, g in zip(frm, tgt)]


def active_index(tl, t):
    idx = -1
    for i, tc in enumerate(tl.t):
        if tc <= t:
            idx = i
        else:
            break
    return idx


def eye_height_term(e, u, lv):
    """One change's contribution to the eye-height multiplier (u = seconds since the change; negative = anticipation).
    Anticipation: a squint of -depth that ramps in over lv["ant"] and ends exactly at u = 0 (value -depth).
    After the change: the step response of an underdamped system from -depth back to 0, plus a sine kick whose first
    peak is `amp` (the take); everything is exactly 0 after SETTLE (fade)."""
    depth = ANT_DEPTH[e] * lv["take"]
    amp = TAKE_AMP[e] * lv["take"]
    a = lv["ant"]
    if u < -a or u >= SETTLE:
        return 0.0
    if u < 0:
        return -depth * smooth((u + a) / a)
    om = math.pi / lv["blend"] / math.sqrt(1 - ZETA * ZETA)   # first peak near the end of the blend
    od = om * math.sqrt(1 - ZETA * ZETA)
    env = math.exp(-ZETA * om * u)
    peak_t = math.atan2(math.sqrt(1 - ZETA * ZETA), ZETA) / od
    kick = amp / (math.exp(-ZETA * om * peak_t) * math.sin(od * peak_t))
    term = -depth * (math.cos(od * u) + ZETA * om / od * math.sin(od * u)) + kick * math.sin(od * u)
    return fade(u) * env * term


def eye_height_mul(tl, t, lv):
    """Anticipation squint and take as a multiplier of the eye height (1 = nothing): the SUM of the terms of every
    change whose window contains t (the system is linear, so overlapping changes superpose and nothing pops)."""
    m = 1.0
    for tc, e in zip(tl.t, tl.e):
        m += eye_height_term(e, t - tc, lv)
    return max(0.2, m)


# ---- gaze ---------------------------------------------------------------------------------------------------------
def saccade_time(seed, k):
    return k * SLOT + SLOT_SPREAD * unit(seed, k, 1)


def gaze_target(seed, k, scale):
    """Target of saccade k (head-radius units, +x = screen right, +y = up). Memoryless per k: centre-biased."""
    if k < 0:
        return (0.0, 0.0)
    cap = GAZE_CAP * scale
    if unit(seed, k, 2) < P_CENTRE:
        return (cap * 0.25 * (unit(seed, k, 3) * 2 - 1), cap * 0.20 * (unit(seed, k, 4) * 2 - 1))
    ang = 2 * math.pi * unit(seed, k, 5)
    mag = cap * (0.45 + 0.55 * unit(seed, k, 6))
    return (mag * math.cos(ang), mag * 0.7 * math.sin(ang))


def latest_saccade(seed, t):
    k = int(math.floor(t / SLOT))
    for kk in (k, k - 1, k - 2):
        if kk >= 0 and saccade_time(seed, kk) <= t:
            return kk
    return -1


def gaze_at(seed, t, scale, ramp=SACCADE_SEC):
    k = latest_saccade(seed, t)
    if k < 0:
        return (0.0, 0.0)
    prev = gaze_target(seed, k - 1, scale)
    cur = gaze_target(seed, k, scale)
    s = smooth((t - saccade_time(seed, k)) / ramp)
    return (prev[0] + (cur[0] - prev[0]) * s, prev[1] + (cur[1] - prev[1]) * s)


def head_tilt(seed, t, scale):
    """The head follows the eyes EYE_LEAD_SEC later, by at most HEAD_TILT_MAX degrees (clockwise positive)."""
    gx, _ = gaze_at(seed, t - EYE_LEAD_SEC, scale, HEAD_RAMP_SEC)
    return max(-HEAD_TILT_MAX, min(HEAD_TILT_MAX, HEAD_TILT_MAX * gx / (GAZE_CAP * scale) if scale > 0 else 0.0))


def gaze_blink(seed, t, scale):
    """Openness (0..1) of a blink coupled to a large shift, seeded; 1 when there is none."""
    k = latest_saccade(seed, t)
    if k < 0:
        return 1.0
    prev, cur = gaze_target(seed, k - 1, scale), gaze_target(seed, k, scale)
    shift = math.hypot(cur[0] - prev[0], cur[1] - prev[1])
    if shift <= BLINK_SHIFT * scale or unit(seed, k, 7) >= P_BLINK:
        return 1.0
    el = t - saccade_time(seed, k)
    if el >= BLINK_DUR:
        return 1.0
    half = BLINK_DUR / 2
    return 1.0 - el / half if el < half else (el - half) / half


# ---- resolved face --------------------------------------------------------------------------------------------------
def resolve(tl, seed, t, level, legacy_open=1.0, gaze_on=True):
    lv = LEVELS[level]
    i = active_index(tl, t)
    p = blended(tl, i, t, lv)
    gscale = lv["gaze"] if gaze_on else 0.0
    g = gaze_at(seed, t, gscale) if gscale > 0 else (0.0, 0.0)
    return dict(eyeRad=p[0], mouthW=p[1], mouthB=p[2], browH=p[3], browL=p[4],
                heightMul=eye_height_mul(tl, t, lv),
                openness=min(legacy_open, gaze_blink(seed, t, gscale) if gscale > 0 else 1.0),
                gazeX=g[0], gazeY=g[1], tilt=head_tilt(seed, t, gscale) if gscale > 0 else 0.0)


# ---- FaceGeometry port (upright head, canvas pixels) ---------------------------------------------------------------
EYE_SPACING, EYE_VOFF, EYE_ASPECT = 0.34, -0.03, 1.2


def draw_face(d, cx, cy, r, f, mouth_open=0.2, tilt=0.0):
    """Head circle plus eyes, brows and mouth from a resolved face dict, head upright (tilt rotates the whole face)."""
    nx, ny = cx, cy + 1.04 * r
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=(40, 40, 40), width=3)

    def rot(x, y):
        a = math.radians(tilt)
        dx, dy = x - cx, y - cy
        return (cx + dx * math.cos(a) - dy * math.sin(a), cy + dx * math.sin(a) + dy * math.cos(a))
    # eyes: perp = (-1, 0) when upright, up = (0, -1) on screen
    ex, ey = cx, cy + (ny - cy) * EYE_VOFF
    spacing = EYE_SPACING * r
    rx = f["eyeRad"] * r
    ry = rx * EYE_ASPECT * (0.12 + 0.88 * max(0.0, min(1.0, f["openness"]))) * f["heightMul"]
    gx, gy = f["gazeX"] * r, -f["gazeY"] * r        # screen-right positive, up positive -> y down
    for sign in (-1, 1):
        x0 = ex + sign * spacing
        px, py = rot(x0 + gx, ey + gy)
        d.ellipse([px - rx, py - max(ry, 0.5), px + rx, py + max(ry, 0.5)], fill=(20, 20, 20))
        # brows anchor to the eye WITHOUT gaze (they belong to the face)
        bh = f["browH"] * f["eyeRad"] * r
        hl = 1.0 * f["eyeRad"] * r
        lift = f["browL"] * f["eyeRad"] * r
        # outer end on the eye's outer side, inner end (toward the face centre) raised by `lift`
        outer = rot(x0 + sign * hl, ey - bh)
        innerp = rot(x0 - sign * hl, ey - bh - lift)
        d.line([outer, innerp], fill=(90, 50, 20), width=max(1, int(rx * 0.35)))
    # mouth
    mx = cx
    my = cy + (ny - cy) * 0.24 + f["mouthB"] * r
    hw = 0.42 * r * f["mouthW"]
    hh = 0.14 * r * (0.5 + 0.5 * mouth_open)
    mp = rot(mx, my)
    d.ellipse([mp[0] - hw, mp[1] - hh, mp[0] + hw, mp[1] + hh], fill=(160, 40, 40))


def strip(tl, seed, level, times, title, path, gaze_on=True, cell=150):
    W = cell * len(times)
    img = Image.new("RGB", (W, cell + 24), (245, 240, 230))
    d = ImageDraw.Draw(img)
    for j, t in enumerate(times):
        f = resolve(tl, seed, t, level, gaze_on=gaze_on)
        draw_face(d, j * cell + cell / 2, cell / 2 + 6, 46, f, tilt=f["tilt"])
        d.text((j * cell + 6, cell + 8), "%.3fs" % t, fill=(0, 0, 0))
    d.text((6, 4), title, fill=(0, 0, 0))
    img.save(path)


# ---- plots and checks ----------------------------------------------------------------------------------------------
def plots():
    seed = 123456789
    # 1) parameter and eye-height curves over a few changes
    tl = Timeline(NORMAL, [(1.0, WIDE), (2.5, HAPPY), (3.6, ANGRY), (3.75, SQUINT), (5.5, NORMAL)])
    ts = [i / 240.0 for i in range(0, 240 * 7)]
    fig, ax = plt.subplots(4, 1, figsize=(11, 9), sharex=True)
    for lvn, col in (("SUBTLE", "tab:green"), ("NORMAL", "tab:blue"), ("BOLD", "tab:red")):
        lv = LEVELS[lvn]
        ax[0].plot(ts, [blended(tl, active_index(tl, t), t, lv)[0] for t in ts], color=col, label=lvn)
        ax[1].plot(ts, [eye_height_mul(tl, t, lv) for t in ts], color=col)
        ax[2].plot(ts, [blended(tl, active_index(tl, t), t, lv)[3] for t in ts], color=col)
        ax[3].plot(ts, [blended(tl, active_index(tl, t), t, lv)[4] for t in ts], color=col)
    for a, n in zip(ax, ["eye x-radius / r", "eye height multiplier (anticipation squint, take)", "brow height (eye radii)", "brow inner lift (eye radii)"]):
        a.set_ylabel(n, fontsize=8)
        for tc in tl.t:
            a.axvline(tc, color="k", lw=0.5, ls=":")
    ax[0].legend()
    plt.tight_layout(); plt.savefig(os.path.join(OUT, "05_blend_curves.png"), dpi=90); plt.close()

    # 2) gaze: offsets, head tilt and coupled blinks over 20 s, plus the saccade interval histogram
    ts = [i / 120.0 for i in range(0, 120 * 20)]
    gaz = [gaze_at(seed, t, 1.0) for t in ts]
    fig, ax = plt.subplots(4, 1, figsize=(11, 9), sharex=True)
    ax[0].plot(ts, [g[0] for g in gaz], label="gaze x"); ax[0].plot(ts, [g[1] for g in gaz], label="gaze y")
    ax[0].axhline(GAZE_CAP, color="k", lw=0.5, ls=":"); ax[0].axhline(-GAZE_CAP, color="k", lw=0.5, ls=":"); ax[0].legend()
    ax[1].plot(ts, [head_tilt(seed, t, 1.0) for t in ts]); ax[1].set_ylabel("head tilt deg (follows eyes by 90 ms)")
    ax[2].plot(ts, [gaze_blink(seed, t, 1.0) for t in ts]); ax[2].set_ylabel("coupled blink openness")
    sac = [saccade_time(seed, k) for k in range(0, 200)]
    gaps = [b - a for a, b in zip(sac, sac[1:])]
    ax[3].hist(gaps, bins=30); ax[3].set_ylabel("interval histogram (s), 200 saccades")
    ax[0].set_ylabel("gaze (head radii)")
    plt.tight_layout(); plt.savefig(os.path.join(OUT, "06_gaze.png"), dpi=90); plt.close()

    # 3) face strips
    strip(Timeline(NORMAL, [(0.5, WIDE)]), seed, "NORMAL", [0.40, 0.44, 0.48, 0.50, 0.53, 0.57, 0.62, 0.68, 0.75, 0.85, 1.0, 1.3], "normal -> wide at 0.5 s (NORMAL): squint, take, settle", os.path.join(OUT, "07_strip_wide.png"), gaze_on=False)
    strip(Timeline(NORMAL, [(0.5, ANGRY)]), seed, "NORMAL", [0.40, 0.44, 0.48, 0.50, 0.55, 0.60, 0.65, 0.70, 0.80, 1.0, 1.3], "normal -> angry at 0.5 s (NORMAL)", os.path.join(OUT, "08_strip_angry.png"), gaze_on=False)
    strip(Timeline(WIDE, [(0.5, HAPPY)]), seed, "NORMAL", [0.45, 0.5, 0.55, 0.6, 0.65, 0.7, 0.8, 0.9, 1.0, 1.3], "wide -> happy at 0.5 s (NORMAL)", os.path.join(OUT, "09_strip_happy.png"), gaze_on=False)
    all6 = Image.new("RGB", (150 * 6, 170), (245, 240, 230)); d = ImageDraw.Draw(all6)
    for e in range(6):
        f = resolve(Timeline(e, []), seed, 10.0, "NORMAL", gaze_on=False)
        draw_face(d, e * 150 + 75, 80, 46, f); d.text((e * 150 + 40, 150), NAMES[e], fill=(0, 0, 0))
    all6.save(os.path.join(OUT, "10_steady_states.png"))
    # gaze strip: 12 frames over a few saccades
    ts = [0.9 + 0.35 * j for j in range(12)]
    strip(Timeline(NORMAL, []), seed, "NORMAL", ts, "idle gaze, NORMAL, every 0.35 s", os.path.join(OUT, "11_strip_gaze.png"))


def checks():
    seed = 987654321
    ok = True
    # steady state: after the settle time the face equals the target set exactly
    tl = Timeline(NORMAL, [(1.0, WIDE)])
    for lvn in LEVELS:
        f = resolve(tl, seed, 1.0 + SETTLE + 0.01, lvn, gaze_on=False)
        ok &= abs(f["eyeRad"] - EYE_RAD[WIDE]) < 1e-12 and abs(f["heightMul"] - 1.0) < 1e-12
    # peak take
    for lvn in LEVELS:
        lv = LEVELS[lvn]
        pk = max(eye_height_mul(tl, 1.0 + i / 2000.0, lv) for i in range(0, 1200))
        lo = min(eye_height_mul(tl, 1.0 - lv["ant"] + i / 2000.0, lv) for i in range(0, 1200))
        print("wide take %-6s peak mul %.4f  squint min %.4f" % (lvn, pk, lo))
    # saccade interval bounds
    sac = [saccade_time(seed, k) for k in range(0, 5000)]
    gaps = [b - a for a, b in zip(sac, sac[1:])]
    print("saccade interval min %.3f max %.3f (brief: 1.0 .. 3.2)" % (min(gaps), max(gaps)))
    ok &= min(gaps) >= 1.0 - 1e-9 and max(gaps) <= 3.2 + 1e-9
    # gaze bound
    mx = max(math.hypot(*gaze_at(seed, i / 60.0, 1.0)) for i in range(0, 60 * 300))
    print("max gaze magnitude %.4f (cap %.2f)" % (mx, GAZE_CAP))
    ok &= mx <= GAZE_CAP + 1e-9
    mt = max(abs(head_tilt(seed, i / 60.0, 1.0)) for i in range(0, 60 * 300))
    print("max head tilt %.3f deg (cap %.1f)" % (mt, HEAD_TILT_MAX))
    ok &= mt <= HEAD_TILT_MAX + 1e-9
    # blink coupling rate among shifts above the threshold
    big = sum(1 for k in range(1, 3000) if math.hypot(*[a - b for a, b in zip(gaze_target(seed, k, 1.0), gaze_target(seed, k - 1, 1.0))]) > BLINK_SHIFT)
    bl = sum(1 for k in range(1, 3000) if math.hypot(*[a - b for a, b in zip(gaze_target(seed, k, 1.0), gaze_target(seed, k - 1, 1.0))]) > BLINK_SHIFT and unit(seed, k, 7) < P_BLINK)
    print("shifts above %.2f: %d of 2999, with a blink: %d (%.2f)" % (BLINK_SHIFT, big, bl, bl / max(1, big)))
    # continuity: max step of the eye height multiplier and of the blended params at 240 Hz
    tl = Timeline(NORMAL, [(1.0, WIDE), (1.2, ANGRY), (1.25, HAPPY)])
    lv = LEVELS["NORMAL"]
    ms = max(abs(eye_height_mul(tl, (i + 1) / 240.0, lv) - eye_height_mul(tl, i / 240.0, lv)) for i in range(0, 240 * 3))
    print("max eye-height step at 240 Hz: %.4f" % ms)
    ok &= ms < 0.08
    print("checks:", "OK" if ok else "FAILED")
    return ok


def emit_reference():
    seed = 424242
    tl = Timeline(NORMAL, [(1.0, WIDE), (2.1, ANGRY), (2.2, HAPPY), (4.0, SQUINT)])
    print("// seed", seed, "timeline NORMAL; WIDE@1.0 ANGRY@2.1 HAPPY@2.2 SQUINT@4.0")
    print("// t, level, eyeRad, mouthW, mouthB, browH, browL, heightMul, openness, gazeX, gazeY, tilt")
    for lvn in ("SUBTLE", "NORMAL", "BOLD"):
        for t in (0.3, 0.97, 1.013, 1.07, 1.19, 1.4, 2.03, 2.137, 2.173, 2.31, 3.0, 4.013, 4.2, 5.05, 7.3, 9.71):
            f = resolve(tl, seed, t, lvn)
            print("%s, %.3f, %s" % (lvn, t, ", ".join("%.9f" % f[k] for k in ("eyeRad", "mouthW", "mouthB", "browH", "browL", "heightMul", "openness", "gazeX", "gazeY", "tilt"))))


if __name__ == "__main__":
    if "--emit-reference" in sys.argv:
        emit_reference()
    else:
        checks()
        plots()
        print("wrote", OUT)
