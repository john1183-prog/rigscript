#!/usr/bin/env python3
"""WP2.4 prototype of the speech mouth: continuous (open, width, roundness) from the amplitude envelope.

The model is BAKED once from (envelope, mouth classes, silence threshold) into a track sampled at 120 Hz, and the
mouth at time t is a linear interpolation into that track. So the result is a pure function of the envelope and t:
seek == sequential (I2) and it does not depend on the export frame rate. Constants mirror `ActingParams` (`MOUTH_*`)
and `ActingLevel.mouthOpenScale` (Kotlin); change both together. The PIL drawer repeats `FaceGeometry.mouth`
for the acting path, so the strips show what the renderers would draw; it validates MOTION SHAPE only.

Run:  python3 mouth_proto.py                    (writes out/12..15 PNGs, prints the numeric checks)
      python3 mouth_proto.py --emit-reference   (the envelope literal and the rows `MouthMotionTest` pins the port to)
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

CLOSED, MID, WIDE, NARROW = 0, 1, 2, 3
CLASS_NAMES = ["closed", "mid", "wide", "narrow"]

# ---- constants (ActingParams.MOUTH_*) ---------------------------------------------------------------------------
ENV_FPS = 30            # the envelope rate (AmplitudeAnalyzer.AMPLITUDE_ANALYSIS_FPS)
SUB = 4                 # track samples per envelope frame -> 120 Hz
TAU_ATTACK = 0.035      # s, open rises toward its target
TAU_RELEASE = 0.085     # s, open falls toward its target
TAU_WIDTH = 0.060       # s, co-articulation: width moves between parameter sets
TAU_ROUND = 0.090       # s
HYSTERESIS = 0.08       # fraction of the silence threshold: the gate opens above th*(1+h), closes below th*(1-h)
SILENCE_HOLD_SEC = 0.10  # the gate closes only after this much SUSTAINED silence
CLASS_DWELL = 2         # a class change must last this many envelope frames, so one-frame blips are ignored
SNAP_CLOSED = 0.004     # a closed mouth snaps to exactly 0 below this
W_MIN, W_MAX = 0.32, 0.46   # half-width / head radius at width 0 and 1
H0, HMAX = 0.025, 0.28      # half-height / head radius at open 0 and 1 (H0 = legacy closed 0.05*0.5)
CLOSED_W = 0.44             # legacy closed half-width
CLASS_W = {MID: 0.42, WIDE: 0.44, NARROW: 0.32}     # legacy class half-widths
CLASS_H = {MID: 0.14, WIDE: 0.28, NARROW: 0.10}     # legacy class half-heights at full amplitude
ROUND_MAX = 0.5          # target roundness of a loud WIDE vowel
ROUND_W_CUT = 0.12       # roundness narrows the mouth by up to this fraction ...
ROUND_H_GAIN = 0.25      # ... and opens it by up to this fraction
OPEN_SCALE = {"SUBTLE": 0.85, "NORMAL": 1.0, "BOLD": 1.15}
SILENCE_THRESHOLD = 0.08  # AmplitudeSettings.silenceThreshold default


def clamp(x, lo, hi):
    return lo if x < lo else hi if x > hi else x


def targets(cls, a, gate):
    """(open, width, round) target for the effective class and amplitude. Steady state equals the legacy class mouth."""
    if not gate:
        return 0.0, (CLOSED_W - W_MIN) / (W_MAX - W_MIN), 0.0
    o = clamp((CLASS_H[cls] * (0.5 + 0.5 * a) - H0) / (HMAX - H0), 0.0, 1.0)
    w = (CLASS_W[cls] - W_MIN) / (W_MAX - W_MIN)
    r = ROUND_MAX * clamp((a - 0.5) / 0.4, 0.0, 1.0) if cls == WIDE else 0.0
    return o, w, r


def bake(env, shapes=None, th=SILENCE_THRESHOLD):
    """Track of (open, width, round) at ENV_FPS*SUB Hz; sample j is the state at t = j / (ENV_FPS*SUB)."""
    n = len(env)
    dt = 1.0 / (ENV_FPS * SUB)
    a_att = 1.0 - math.exp(-dt / TAU_ATTACK)
    a_rel = 1.0 - math.exp(-dt / TAU_RELEASE)
    a_w = 1.0 - math.exp(-dt / TAU_WIDTH)
    a_r = 1.0 - math.exp(-dt / TAU_ROUND)
    tr = np.zeros((n * SUB + 1, 3))
    o, w, r = 0.0, (CLOSED_W - W_MIN) / (W_MAX - W_MIN), 0.0
    tr[0] = (o, w, r)
    gate, silent = False, 0.0
    stable, cand, cnt = MID, MID, 0
    on, off = th * (1 + HYSTERESIS), th * (1 - HYSTERESIS)
    for k in range(n):
        a = float(env[k])
        c = int(shapes[min(k, len(shapes) - 1)]) if shapes is not None and len(shapes) else MID
        if c == CLOSED:
            c = MID               # quiet but voiced speech opens a little; the legacy classes call it closed
        if c == stable:
            cnt = 0
        elif c == cand:
            cnt += 1
            if cnt >= CLASS_DWELL:
                stable, cnt = cand, 0
        else:
            cand, cnt = c, 1
            if CLASS_DWELL <= 1:
                stable, cnt = cand, 0
        if not gate:
            if a > on:
                gate, silent = True, 0.0
        else:
            if a < off:
                silent += 1.0 / ENV_FPS
                if silent >= SILENCE_HOLD_SEC - 1e-9:
                    gate = False
            else:
                silent = 0.0
        to, tw, tr_ = targets(stable, a, gate)
        for s in range(SUB):
            o += (to - o) * (a_att if to > o else a_rel)
            w += (tw - w) * a_w
            r += (tr_ - r) * a_r
            if not gate and to == 0.0 and o < SNAP_CLOSED:
                o = 0.0
            tr[k * SUB + s + 1] = (o, w, r)
    return tr


def evaluate(track, t):
    u = clamp(t * ENV_FPS * SUB, 0.0, len(track) - 1.0)
    j = int(math.floor(u))
    if j >= len(track) - 1:
        return tuple(track[-1])
    f = u - j
    return tuple(track[j] * (1 - f) + track[j + 1] * f)


def geometry(open_, width, round_):
    """(halfWidth, halfHeight) as fractions of the head radius: what FaceGeometry.mouth draws on the acting path."""
    w = (W_MIN + (W_MAX - W_MIN) * clamp(width, 0, 1)) * (1 - ROUND_W_CUT * clamp(round_, 0, 1))
    h = (H0 + (HMAX - H0) * clamp(open_, 0, 1)) * (1 + ROUND_H_GAIN * clamp(round_, 0, 1))
    return w, h


# ---- legacy model (what OFF draws) -------------------------------------------------------------------------------
def legacy_series(env, shapes, smoothing=0.22):
    """Per-envelope-frame (halfWidth, halfHeight) fractions with the legacy class table and one-pole amplitude."""
    sm, out = 0.0, []
    table = {CLOSED: (0.44, 0.05), MID: (0.42, 0.14), WIDE: (0.44, 0.28), NARROW: (0.32, 0.10)}
    for a, c in zip(env, shapes):
        sm += (a - sm) * (1 - smoothing)
        wf, hf = table[int(c)]
        out.append((wf, hf * (0.5 + 0.5 * sm)))
    return np.array(out)


# ---- synthetic speech -------------------------------------------------------------------------------------------
def speech(seed=3, seconds=12.0, noisy=False):
    """Speech-like envelope (words of syllable bursts, pauses) and mouth classes, deterministic."""
    rng = np.random.default_rng(seed)
    n = int(seconds * ENV_FPS)
    env = np.zeros(n)
    cls = np.zeros(n, dtype=int)
    k = 0
    while k < n:
        word = rng.integers(2, 5)
        for _ in range(word):
            syl = int(rng.integers(4, 8))            # 130..260 ms per syllable
            peak = rng.uniform(0.45, 0.95)
            kind = rng.choice([WIDE, MID, MID, NARROW])
            for i in range(syl):
                if k >= n:
                    break
                x = math.sin(math.pi * (i + 0.5) / syl)
                env[k] = peak * x ** 0.7
                cls[k] = kind if env[k] > 0.12 else CLOSED
                k += 1
        pause = int(rng.integers(5, 16))             # 170..530 ms between words
        for _ in range(pause):
            if k >= n:
                break
            env[k] = rng.uniform(0.0, 0.05)
            cls[k] = CLOSED
            k += 1
    if noisy:   # worst case: the amplitude jitters around the threshold and the class changes almost every frame
        env = np.clip(env * (1 + rng.normal(0, 0.25, n)) + rng.normal(0, 0.04, n), 0, 1)
        flip = rng.random(n) < 0.45
        cls = np.where(flip & (env > 0.1), rng.integers(1, 4, n), cls)
        cls = np.where(env <= 0.1, CLOSED, cls)
    return env, cls


def reversals(x, swing):
    """Direction reversals of x whose swing is at least `swing` (a hysteretic extremum count)."""
    cnt, direction, ext = 0, 0, x[0]
    for v in x[1:]:
        if direction >= 0:
            if v > ext:
                ext, direction = v, 1
            elif ext - v >= swing:
                cnt += (1 if direction == 1 else 0); direction, ext = -1, v
        if direction <= 0:
            if v < ext:
                ext, direction = v, -1
            elif v - ext >= swing:
                cnt += (1 if direction == -1 else 0); direction, ext = 1, v
    return cnt


def toggles(cls):
    return int(np.sum(cls[1:] != cls[:-1]))


# ---- drawing ----------------------------------------------------------------------------------------------------
def draw_mouth_cell(d, cx, cy, r, w, h, color=(160, 40, 40)):
    d.ellipse([cx - w * r, cy - h * r, cx + w * r, cy + h * r], fill=color)


def plots():
    env, cls = speech(3, 12.0)
    tr = bake(env, cls)
    fps = 60
    ts = np.arange(0, 12.0, 1.0 / fps)
    acted = np.array([evaluate(tr, t) for t in ts])
    g = np.array([geometry(*p) for p in acted])
    leg = legacy_series(env, cls)
    tl = np.arange(len(env)) / ENV_FPS

    # 12: step responses verify the time constants
    step = np.concatenate([np.zeros(15), np.full(30, 0.7), np.zeros(30)])
    trs = bake(step, np.full(len(step), MID))
    tt = np.arange(0, len(step) / ENV_FPS, 1 / 240.0)
    o = np.array([evaluate(trs, t)[0] for t in tt])
    fig, ax = plt.subplots(1, 1, figsize=(10, 3.6))
    ax.plot(tt, o, label="open (acted)")
    ax.step(np.arange(len(step)) / ENV_FPS, [targets(MID, a, a > 0)[0] for a in step], where="post", color="gray", lw=0.8, label="target")
    rise = next(t for t, v in zip(tt, o) if v >= 0.632 * o.max()) - 0.5
    ax.axvline(0.5 + TAU_ATTACK, color="C2", ls=":"); ax.axvline(1.5 + TAU_RELEASE, color="C3", ls=":")
    ax.set_title("step response: attack tau 35 ms (green), release tau 85 ms (red); 63%% rise took %.0f ms" % (rise * 1000))
    ax.legend(); ax.set_xlabel("s")
    fig.tight_layout(); fig.savefig(os.path.join(OUT, "12_mouth_step.png"), dpi=90); plt.close(fig)

    # 13: speech: envelope + gate + acted parameters + half-height vs legacy
    fig, axs = plt.subplots(4, 1, figsize=(12, 9), sharex=True)
    axs[0].plot(tl, env, lw=1); axs[0].axhline(SILENCE_THRESHOLD, color="gray", ls=":")
    axs[0].set_ylabel("envelope")
    axs[1].step(tl, cls, where="post", lw=1); axs[1].set_yticks(range(4)); axs[1].set_yticklabels(CLASS_NAMES); axs[1].set_ylabel("raw class")
    axs[2].plot(ts, acted[:, 0], label="open"); axs[2].plot(ts, acted[:, 1], label="width"); axs[2].plot(ts, acted[:, 2], label="round")
    axs[2].legend(loc="upper right", ncol=3); axs[2].set_ylabel("acted params")
    axs[3].step(tl, leg[:, 1], where="post", label="legacy half-height (OFF)", color="C3", lw=1)
    axs[3].plot(ts, g[:, 1], label="acted half-height", color="C0", lw=1.2); axs[3].legend(loc="upper right"); axs[3].set_ylabel("half-height / r")
    axs[3].set_xlabel("s")
    for a_ in axs: a_.set_xlim(0, 6)
    fig.tight_layout(); fig.savefig(os.path.join(OUT, "13_mouth_speech.png"), dpi=90); plt.close(fig)

    # 14: flicker on the noisy worst case
    envn, clsn = speech(5, 12.0, noisy=True)
    trn = bake(envn, clsn)
    tn = np.arange(0, 12.0, 1 / 60.0)
    an = np.array([evaluate(trn, t) for t in tn]); gn = np.array([geometry(*p) for p in an])
    legn = legacy_series(envn, clsn)
    fig, axs = plt.subplots(3, 1, figsize=(12, 7.5), sharex=True)
    tln = np.arange(len(envn)) / ENV_FPS
    axs[0].plot(tln, envn, lw=0.8); axs[0].set_ylabel("noisy envelope")
    axs[1].step(tln, legn[:, 1], where="post", color="C3", lw=0.9); axs[1].set_ylabel("legacy half-height")
    axs[2].plot(tn, gn[:, 1], lw=1.0); axs[2].set_ylabel("acted half-height")
    for a_ in axs: a_.set_xlim(0, 5)
    axs[2].set_xlabel("s")
    fig.tight_layout(); fig.savefig(os.path.join(OUT, "14_mouth_flicker.png"), dpi=90); plt.close(fig)

    # 15: strips, same word, legacy vs acted, 1/15 s apart
    cell, r = 96, 34
    t0 = 0.0
    nfr = 24
    img = Image.new("RGB", (cell * nfr // 2, cell * 2 + 22), (245, 245, 240))
    d = ImageDraw.Draw(img)
    for i in range(nfr // 2):
        t = t0 + i / 15.0
        wl, hl = legacy_series(env, cls)[min(int(t * ENV_FPS), len(env) - 1)]
        wa, ha = geometry(*evaluate(tr, t))
        for row, (w_, h_) in enumerate([(wl, hl), (wa, ha)]):
            cx, cy = cell * i + cell // 2, 22 + cell * row + cell // 2
            d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=(40, 40, 40), width=2)
            draw_mouth_cell(d, cx, cy + 0.24 * r * 1.04 * 0 + 0.25 * r, r, w_, h_)
    d.text((4, 4), "top: legacy (OFF)   bottom: acted   (1/15 s per cell)", fill=(0, 0, 0))
    img.save(os.path.join(OUT, "15_mouth_strip.png"))


def checks():
    ok = True

    def check(name, cond, detail=""):
        nonlocal ok
        print(("PASS " if cond else "FAIL ") + name + ((" " + detail) if detail else ""))
        ok &= bool(cond)

    # time constants
    step = np.concatenate([np.zeros(15), np.full(30, 0.7), np.zeros(30)])
    trs = bake(step, np.full(len(step), MID))
    o_t = 0.0 + targets(MID, 0.7, True)[0]
    tt = np.arange(0, 2.5, 1 / 480.0)
    o = np.array([evaluate(trs, t)[0] for t in tt])
    t_rise = next(t for t, v in zip(tt, o) if v >= 0.632 * o_t) - 0.5
    peak = o.max()
    t_fall = next(t for t, v in zip(tt, o) if t > 1.5 and v <= (1 - 0.632) * peak) - 1.5
    check("attack 63% rise near 35 ms (frame-quantised input)", 0.025 < t_rise < 0.085, "%.0f ms" % (t_rise * 1000))
    check("release 63% fall near 85 ms", 0.06 < t_fall < 0.14, "%.0f ms" % (t_fall * 1000))
    # steady state equals the legacy class geometry (MID, NARROW, CLOSED exactly; WIDE up to roundness)
    for cls_, a in [(MID, 0.5), (NARROW, 0.6), (WIDE, 0.4), (MID, 1.0), (WIDE, 1.0)]:
        e = np.full(90, a); c = np.full(90, cls_)
        w, h = geometry(*evaluate(bake(e, c), 2.9))
        lw, lh = CLASS_W[cls_], CLASS_H[cls_] * (0.5 + 0.5 * a)
        rnd = ROUND_MAX * clamp((a - 0.5) / 0.4, 0, 1) if cls_ == WIDE else 0.0
        elw, elh = lw * (1 - ROUND_W_CUT * rnd), lh * (1 + ROUND_H_GAIN * rnd)
        check("steady state %s a=%.1f" % (CLASS_NAMES[cls_], a), abs(w - elw) < 2e-3 and abs(h - elh) < 2e-3, "w %.4f/%.4f h %.4f/%.4f" % (w, elw, h, elh))
    # full closure after sustained silence, exactly zero
    e = np.concatenate([np.full(30, 0.7), np.zeros(60)]); c = np.concatenate([np.full(30, MID), np.zeros(60, dtype=int)])
    trc = bake(e, c)
    check("closes to exactly 0 after sustained silence", evaluate(trc, 2.9)[0] == 0.0)
    # a dip shorter than the hold does not close the gate
    e = np.concatenate([np.full(20, 0.7), np.zeros(2), np.full(20, 0.7)]); c = np.full(42, MID)
    trd = bake(e, c)
    mid_dip = evaluate(trd, 22 / ENV_FPS)[0]
    check("a 66 ms dip does not close the mouth fully", mid_dip > 0.02, "open %.3f" % mid_dip)
    # bounds on the noisy worst case
    envn, clsn = speech(5, 12.0, noisy=True)
    trn = bake(envn, clsn)
    check("track bounded", np.all(np.isfinite(trn)) and trn[:, 0].min() >= 0 and trn[:, 0].max() <= 1 and trn[:, 1].min() >= 0 and trn[:, 1].max() <= 1 and trn[:, 2].min() >= 0 and trn[:, 2].max() <= 1)
    # flicker: count visible JUMPS (a half-height step above 0.04 of the head radius between two frames at 60 fps).
    # Direction reversals are not used: they also count the real syllable rhythm.
    tn = np.arange(0, 12.0, 1 / 60.0)
    an = np.array([geometry(*evaluate(trn, t))[1] for t in tn])
    legn_frames = legacy_series(envn, clsn)[:, 1]
    legn = np.array([legn_frames[min(int(t * ENV_FPS), len(legn_frames) - 1)] for t in tn])
    jump_a = int(np.sum(np.abs(np.diff(an)) > 0.04)) / 12.0
    jump_l = int(np.sum(np.abs(np.diff(legn)) > 0.04)) / 12.0
    tog = toggles(clsn) / 12.0
    print("INFO noisy worst case: raw class toggles %.1f /s, legacy jumps %.1f /s, acted jumps %.1f /s, max acted step %.3f" % (tog, jump_l, jump_a, np.abs(np.diff(an)).max()))
    check("acted jumps are rare (onsets only) and at most a tenth of legacy", jump_a < 1.0 and jump_a <= 0.1 * jump_l)
    envc, clsc = speech(3, 12.0)
    rev_c = reversals(np.array([evaluate(bake(envc, clsc), t)[0] for t in tn]), 0.04) / 12.0
    print("INFO clean speech: acted open reversals %.1f /s (about 2 per syllable is natural)" % rev_c)
    # determinism and frame-rate independence
    t1 = bake(envc, clsc); t2 = bake(envc, clsc)
    check("deterministic", np.array_equal(t1, t2))
    a60 = [evaluate(t1, k / 60.0) for k in range(0, 600, 2)]
    a30 = [evaluate(t1, k / 30.0) for k in range(0, 300)]
    check("frame-rate independent (30 fps sample equals 60 fps sample at the same time)", all(np.allclose(x, y) for x, y in zip(a60, a30)))
    print("ALL PASS" if ok else "SOME FAILED")
    return ok


def emit_reference():
    """Envelope literal (3 decimals: float32 holds the same value on both sides) and rows at multiples of 1/64 s."""
    env, cls = speech(11, 4.0)
    env = np.round(env, 3)
    env = env.astype(np.float32).astype(np.float64)
    tr = bake(env, cls)
    print("ENV", ",".join("%.3ff" % v for v in env))
    print("CLS", ",".join(str(int(c)) for c in cls))
    for i in range(0, 4 * 64, 5):
        t = i / 64.0
        o, w, r = evaluate(tr, t)
        print("ROW %d %.6f %.6f %.6f" % (i, o, w, r))


if __name__ == "__main__":
    if "--emit-reference" in sys.argv:
        emit_reference()
    else:
        plots()
        sys.exit(0 if checks() else 1)
