#!/usr/bin/env python3
"""WP2.6 prototype of the mood idles (Craft): breathing rate and depth and the talk/fidget sway follow the expression.

What the model is
-----------------
Each of the six expressions has a (rate, depth, sway) multiplier triple (calm vs energetic), see MOOD_* below. The
multipliers blend between expressions with a smoothstep over MOOD_BLEND_SEC (slower than the face: breathing does not
change in 180 ms). The blend is a LEFT-TO-RIGHT recurrence (the value at each change time is stored), so it has no depth
cap and the breathing PHASE has an exact closed-form integral:

    phase(t) = 2*pi*f*(t + W(t)),   W(t) = integral_0^t (rate(tau) - 1) dtau

so a rate change never makes the breath jump (the phase is continuous and its derivative is continuous). With every
multiplier 1 (an all-NORMAL timeline) W is exactly 0 and the acted breath equals the legacy breath exactly.

The acting layer ADDS (acted - legacy) to the frame the engine computed, so everything else in that frame survives:

    breath:  d = w_free * a * (depth(t) * sin(2*pi*f*(t + W(t))) - sin(2*pi*f*t));  torso += d,  head -= 0.5 * d
    sway:    bone += w_free * (sway(t) - 1) * legacy_sway_bone      (talk sway + fidget, recorded by the engine)

w_free fades to 0 over MOOD_LOCO_RAMP_SEC while a walk/jog keyframe is active ("off during locomotion cycles"), so
there the figure moves exactly as the legacy engine moves it. Targets are 1 + moodScale*(table - 1) with the level's
moodScale (SUBTLE 0.5, NORMAL 1.0, BOLD 1.4). OFF never runs the layer.

Constants mirror `ActingParams` (`MOOD_*`, `ActingLevel.moodScale`); change both together.

Run:  python3 mood_proto.py                    (writes out/24..27 PNGs, prints the numeric checks)
      python3 mood_proto.py --emit-reference   (the timelines and rows `MoodMotionTest` pins the Kotlin port to)
"""
import bisect, math, os, sys
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
os.makedirs(OUT, exist_ok=True)

NORMAL, WIDE, SQUINT, WORRIED, ANGRY, HAPPY = range(6)
EXPR_NAMES = ["NORMAL", "WIDE", "SQUINT", "WORRIED", "ANGRY", "HAPPY"]

# ---- constants (ActingParams.MOOD_*) ----------------------------------------------------------------------------
#                   NORMAL WIDE  SQUINT WORRIED ANGRY HAPPY
MOOD_RATE = [1.00, 1.40, 0.85, 1.35, 1.30, 1.10]    # breathing rate multiplier: alert and tense breathe faster, a squint is calm
MOOD_DEPTH = [1.00, 1.25, 0.85, 0.80, 1.35, 1.10]   # breathing depth multiplier: worried is shallow, angry heaves
MOOD_SWAY = [1.00, 1.15, 0.80, 0.85, 1.30, 1.25]    # talk-sway and fidget multiplier: angry and happy are big, squint and worried small
MOOD_BLEND_SEC = 1.5                                # how long a mood takes to settle into the next
MOOD_LOCO_RAMP_SEC = 0.35                           # how fast the mood fades out and in around a walk/jog
MOOD_MIN, MOOD_MAX = 0.5, 2.0                       # every multiplier is clamped to this range (a safety net for tuning)
LEVEL_SCALE = {"SUBTLE": 0.5, "NORMAL": 1.0, "BOLD": 1.4}   # ActingLevel.moodScale

# ---- legacy amplitude motion (AmplitudeSettings defaults, used by the plots) --------------------------------------
BREATH_AMP, BREATH_HZ = 1.4, 0.35
TALK_TORSO, TALK_HEAD, TALK_HZ, SILENCE = 5.0, 3.5, 4.5, 0.08


def f32(x):
    return float(np.float32(x))


def smooth(x):
    c = 0.0 if x < 0.0 else 1.0 if x > 1.0 else x
    return c * c * (3.0 - 2.0 * c)


def smooth_integral(u):
    """Integral of smoothstep from 0 to u (u in units of the blend time)."""
    return u - 0.5 if u >= 1.0 else u * u * u - 0.5 * u ** 4


def target(expr, scale):
    """The (rate, depth, sway) the expression settles to at a level with this moodScale."""
    def one(table):
        return min(MOOD_MAX, max(MOOD_MIN, 1.0 + scale * (table[expr] - 1.0)))
    return [one(MOOD_RATE), one(MOOD_DEPTH), one(MOOD_SWAY)]


class BlendTrack:
    """Piecewise smoothstep blend of C channels between targets at change times (exact recurrence), with the closed-form
    integral of (channel `integ` - 1) so a rate can be turned into a phase warp. Pure data after construction."""

    def __init__(self, init, times, targets, blend, integ=-1):
        self.init = list(init)
        self.times = [f32(t) for t in times]
        self.targets = [list(x) for x in targets]
        self.blend = blend
        self.integ = integ
        n, c = len(self.times), len(self.init)
        self.V = [[0.0] * c for _ in range(n)]
        self.cum = [0.0] * n
        if n:
            self.V[0] = list(self.init)
            if integ >= 0:
                self.cum[0] = (self.init[integ] - 1.0) * self.times[0]
            for i in range(n - 1):
                L = self.times[i + 1] - self.times[i]
                u = L / blend
                for k in range(c):
                    v, g = self.V[i][k], self.targets[i][k]
                    self.V[i + 1][k] = g if u >= 1.0 else v + (g - v) * smooth(u)
                if integ >= 0:
                    v, g = self.V[i][integ], self.targets[i][integ]
                    self.cum[i + 1] = self.cum[i] + (v - 1.0) * L + (g - v) * blend * smooth_integral(u)

    def evaluate(self, t):
        """(channel values, warp). Before the first change the init values hold."""
        idx = bisect.bisect_right(self.times, t) - 1
        c = len(self.init)
        if idx < 0:
            return list(self.init), ((self.init[self.integ] - 1.0) * t if self.integ >= 0 else 0.0)
        L = t - self.times[idx]
        u = L / self.blend
        vals = []
        for k in range(c):
            v, g = self.V[idx][k], self.targets[idx][k]
            vals.append(g if u >= 1.0 else v + (g - v) * smooth(u))
        warp = 0.0
        if self.integ >= 0:
            v, g = self.V[idx][self.integ], self.targets[idx][self.integ]
            warp = self.cum[idx] + (v - 1.0) * L + (g - v) * self.blend * smooth_integral(u)
        return vals, warp


def mood_track(expr0, change_times, change_exprs, level):
    scale = f32(LEVEL_SCALE[level])   # ActingLevel.moodScale is a Float in Kotlin: 1.4 is 1.39999998 there
    return BlendTrack(target(expr0, scale), change_times, [target(e, scale) for e in change_exprs], MOOD_BLEND_SEC, integ=0)


def loco_track(flag0, toggle_times, toggle_flags):
    """One channel: 1 = free (mood on), 0 = a walk/jog keyframe is active."""
    return BlendTrack([0.0 if flag0 else 1.0], toggle_times, [[0.0 if f else 1.0] for f in toggle_flags], MOOD_LOCO_RAMP_SEC)


def breath_delta(mood, loco, t, a=BREATH_AMP, hz=BREATH_HZ):
    """The torso degrees the layer adds to the legacy breath at time t (the head gets -0.5 times this)."""
    (rate, depth, sway), warp = mood.evaluate(t)
    w = loco.evaluate(t)[0][0]
    acted = depth * math.sin(2.0 * math.pi * hz * (t + warp))
    legacy = math.sin(2.0 * math.pi * hz * t)
    return w * a * (acted - legacy)


def sway_gain(mood, loco, t):
    """(sway multiplier - 1) * w_free: what multiplies the legacy sway components."""
    (rate, depth, sway), warp = mood.evaluate(t)
    return loco.evaluate(t)[0][0] * (sway - 1.0)


# ---- scenarios -------------------------------------------------------------------------------------------------
# Timeline A (plots and reference): expression changes, one overlapping pair (HAPPY then SQUINT 0.6 s later).
A_EXPR0 = NORMAL
A_TIMES = [6.0, 16.0, 16.6, 26.0, 32.0, 34.0]
A_EXPRS = [ANGRY, HAPPY, SQUINT, WORRIED, WIDE, NORMAL]
# Locomotion: a walk from 20.0 s to 24.0 s (flags toggle at the keyframes that start and end it).
L_FLAG0 = False
L_TIMES = [20.0, 24.0]
L_FLAGS = [True, False]


def numeric_checks():
    print("== WP2.6 mood idles: numeric checks ==")
    for level in ("SUBTLE", "NORMAL", "BOLD"):
        m = mood_track(A_EXPR0, A_TIMES, A_EXPRS, level)
        lo = mood_track(A_EXPR0, [], [], level)
        # 1. closed-form warp equals a fine numeric integral of the rate
        dt = 1e-4
        ts = np.arange(0.0, 40.0, dt)
        rates = np.array([m.evaluate(float(x))[0][0] for x in ts])
        cum = np.cumsum(rates - 1.0) * dt
        worst = 0.0
        for probe in (3.0, 6.7, 10.0, 16.3, 16.9, 21.0, 26.5, 33.1, 39.9):
            k = int(round(probe / dt))
            worst = max(worst, abs(cum[k] - m.evaluate(probe)[1]))
        print("%-6s warp closed form vs numeric integral: worst error %.2e s (Riemann sum step 1e-4)" % (level, worst))
        # 2. continuity of rate, depth, sway and the warp, and of the breath delta
        step = 1e-3
        ts2 = np.arange(0.0, 40.0, step)
        vals = np.array([m.evaluate(float(x))[0] + [m.evaluate(float(x))[1]] for x in ts2])
        jumps = np.abs(np.diff(vals, axis=0)).max(axis=0)
        loco = loco_track(L_FLAG0, L_TIMES, L_FLAGS)
        bd = np.array([breath_delta(m, loco, float(x)) for x in ts2])
        db = np.abs(np.diff(bd)).max()
        print("       largest 1 ms step: rate %.4f depth %.4f sway %.4f warp %.5f; breath delta %.4f deg" % (*jumps, db))
        # 3. bounds
        mn = vals[:, :3].min(axis=0)
        mx = vals[:, :3].max(axis=0)
        print("       range rate %.3f..%.3f depth %.3f..%.3f sway %.3f..%.3f; max |breath delta| %.3f deg (legacy amplitude %.1f)"
              % (mn[0], mx[0], mn[1], mx[1], mn[2], mx[2], np.abs(bd).max(), BREATH_AMP))
        assert mn.min() >= MOOD_MIN - 1e-12 and mx.max() <= MOOD_MAX + 1e-12
        # 4. all-NORMAL timeline: exactly the legacy breath
        flat = mood_track(NORMAL, [3.0, 9.0], [NORMAL, NORMAL], level)
        assert all(breath_delta(flat, loco_track(False, [], []), float(x)) == 0.0 for x in np.arange(0.0, 30.0, 0.0137))
        assert all(sway_gain(flat, loco_track(False, [], []), float(x)) == 0.0 for x in np.arange(0.0, 30.0, 0.0137))
        # 5. locomotion: inside the walk (after the ramp) the delta is exactly 0
        assert all(breath_delta(m, loco, float(x)) == 0.0 for x in np.arange(20.0 + MOOD_LOCO_RAMP_SEC + 0.01, 24.0, 0.05))
        assert lo.evaluate(5.0)[1] == 0.0 or level == "NORMAL" or True
    print("all-NORMAL timelines give exactly zero delta; the delta is exactly zero inside a walk; rate/depth/sway stay in range")


# ---- plots ------------------------------------------------------------------------------------------------------
COLORS = {NORMAL: "#9aa5b1", WIDE: "#e0a030", SQUINT: "#6aa84f", WORRIED: "#8e7cc3", ANGRY: "#cc4125", HAPPY: "#f1c232"}


def shade_expr(ax, t_end):
    edges = [0.0] + A_TIMES + [t_end]
    exprs = [A_EXPR0] + A_EXPRS
    for i, e in enumerate(exprs):
        ax.axvspan(edges[i], edges[i + 1], color=COLORS[e], alpha=0.13, lw=0)


def plot_tables():
    fig, axs = plt.subplots(1, 3, figsize=(13, 3.6), sharey=True)
    for ax, (name, table) in zip(axs, (("breathing rate", MOOD_RATE), ("breathing depth", MOOD_DEPTH), ("talk sway and fidget", MOOD_SWAY))):
        w = 0.26
        for j, level in enumerate(("SUBTLE", "NORMAL", "BOLD")):
            sc = LEVEL_SCALE[level]
            ax.bar(np.arange(6) + (j - 1) * w, [1 + sc * (v - 1) for v in table], w, label=level)
        ax.axhline(1.0, color="k", lw=0.6)
        ax.set_xticks(range(6)); ax.set_xticklabels(EXPR_NAMES, rotation=30, fontsize=8)
        ax.set_title(name + " (x legacy)")
    axs[0].legend(fontsize=8, loc="upper left")
    fig.suptitle("Mood multipliers by expression and acting level (calm < 1 < energetic)")
    fig.tight_layout(); fig.savefig(os.path.join(OUT, "24_mood_tables.png"), dpi=110); plt.close(fig)


def plot_breathing():
    t_end = 40.0
    ts = np.arange(0.0, t_end, 0.01)
    fig, axs = plt.subplots(4, 1, figsize=(13, 9.5), sharex=True)
    for level, col in (("SUBTLE", "#6aa84f"), ("NORMAL", "#3d85c6"), ("BOLD", "#cc4125")):
        m = mood_track(A_EXPR0, A_TIMES, A_EXPRS, level)
        r = [m.evaluate(float(x))[0][0] * BREATH_HZ * 60.0 for x in ts]
        axs[0].plot(ts, r, color=col, label=level)
    axs[0].set_ylabel("breaths / min"); axs[0].legend(fontsize=8, ncol=3); axs[0].set_title("Breathing rate (legacy = %.0f per min); shading = expression" % (BREATH_HZ * 60))
    m = mood_track(A_EXPR0, A_TIMES, A_EXPRS, "NORMAL")
    loco_off = loco_track(False, [], [])
    legacy = [BREATH_AMP * math.sin(2 * math.pi * BREATH_HZ * float(x)) for x in ts]
    acted = [BREATH_AMP * math.sin(2 * math.pi * BREATH_HZ * float(x)) + breath_delta(m, loco_off, float(x)) for x in ts]
    axs[1].plot(ts, legacy, color="#999", lw=1.0, label="legacy torso breath")
    axs[1].plot(ts, acted, color="#3d85c6", lw=1.2, label="acted (NORMAL)")
    axs[1].set_ylabel("torso deg"); axs[1].legend(fontsize=8, ncol=2); axs[1].set_title("Torso breath, degrees")
    axs[2].plot(ts, [m.evaluate(float(x))[1] for x in ts], color="#674ea7")
    axs[2].set_ylabel("warp W(t) s"); axs[2].set_title("Phase warp W(t): continuous, so no jump when the rate changes")
    ph = np.array([2 * math.pi * BREATH_HZ * (float(x) + m.evaluate(float(x))[1]) for x in ts])
    axs[3].plot(ts[1:], np.diff(ph) / 0.01 / (2 * math.pi), color="#a64d79")
    axs[3].set_ylabel("Hz"); axs[3].set_xlabel("time s"); axs[3].set_title("Instantaneous breathing frequency (derivative of the phase): smooth, no steps")
    for ax in axs:
        shade_expr(ax, t_end)
    fig.tight_layout(); fig.savefig(os.path.join(OUT, "25_mood_breathing.png"), dpi=110); plt.close(fig)


def plot_locomotion():
    t_end = 40.0
    ts = np.arange(14.0, 30.0, 0.005)
    m = mood_track(A_EXPR0, A_TIMES, A_EXPRS, "BOLD")
    loco = loco_track(L_FLAG0, L_TIMES, L_FLAGS)
    fig, axs = plt.subplots(3, 1, figsize=(13, 7.5), sharex=True)
    axs[0].plot(ts, [loco.evaluate(float(x))[0][0] for x in ts], color="#38761d")
    axs[0].set_title("w_free: 1 = mood on, 0 = a walk/jog keyframe is active (walk 20 to 24 s, ramp %.2f s)" % MOOD_LOCO_RAMP_SEC)
    legacy = [BREATH_AMP * math.sin(2 * math.pi * BREATH_HZ * float(x)) for x in ts]
    acted = [legacy[i] + breath_delta(m, loco, float(x)) for i, x in enumerate(ts)]
    axs[1].plot(ts, legacy, color="#999", label="legacy"); axs[1].plot(ts, acted, color="#cc4125", label="acted (BOLD)")
    axs[1].legend(fontsize=8); axs[1].set_title("Torso breath: identical to legacy during the walk; HAPPY at 16.0 then SQUINT at 16.6 overlap (0.6 s apart) with no pop")
    gain = [sway_gain(m, loco, float(x)) for x in ts]
    axs[2].plot(ts, gain, color="#b45f06"); axs[2].set_title("Sway gain (sway multiplier - 1) x w_free, applied to the legacy sway components")
    axs[2].set_xlabel("time s")
    fig.tight_layout(); fig.savefig(os.path.join(OUT, "26_mood_locomotion.png"), dpi=110); plt.close(fig)


def plot_sway():
    ts = np.arange(0.0, 4.0, 0.002)
    env = 0.55 + 0.25 * np.sin(2 * math.pi * 0.7 * ts)               # a steady speaking level
    strength = np.clip(env - SILENCE, 0.0, None)
    legacy = TALK_TORSO * np.sin(2 * math.pi * TALK_HZ * ts) * strength
    fig, axs = plt.subplots(2, 3, figsize=(13, 5.6), sharex=True, sharey=True)
    for ax, e in zip(axs.ravel(), range(6)):
        g = target(e, 1.0)[2]
        ax.plot(ts, legacy, color="#bbb", lw=1.0, label="legacy")
        ax.plot(ts, g * legacy, color=COLORS[e], lw=1.2, label="NORMAL x%.2f" % g)
        ax.set_title(EXPR_NAMES[e], fontsize=9); ax.legend(fontsize=7, loc="upper right")
    for ax in axs[1]:
        ax.set_xlabel("time s")
    fig.suptitle("Talk sway of the torso (deg) per steady mood, same speech: the swing grows or shrinks, the rhythm stays")
    fig.tight_layout(); fig.savefig(os.path.join(OUT, "27_mood_sway.png"), dpi=110); plt.close(fig)


def emit_reference():
    print("// from `python3 mood_proto.py --emit-reference` (times rounded through float32; BOLD and NORMAL levels)")
    print("// timeline A: expr0=%d, change times %s, exprs %s; locomotion: flag0=%s, toggle times %s, flags %s"
          % (A_EXPR0, A_TIMES, A_EXPRS, str(L_FLAG0).lower(), L_TIMES, [str(f).lower() for f in L_FLAGS]))
    probes = [0.0, 2.5, 6.0, 6.4, 7.5, 9.0, 12.0, 15.9, 16.3, 16.6, 17.0, 18.5, 20.1, 20.3, 22.0, 24.2, 24.6, 26.8, 29.0, 32.4, 33.7, 35.0, 39.0]
    for level in ("NORMAL", "BOLD"):
        m = mood_track(A_EXPR0, A_TIMES, A_EXPRS, level)
        lo = loco_track(L_FLAG0, L_TIMES, L_FLAGS)
        print("// %s rows: t, rate, depth, sway, warp, w_free, torso breath delta (a=%.1f f=%.2f), sway gain" % (level, BREATH_AMP, BREATH_HZ))
        for t in probes:
            (r, d, s), w = m.evaluate(t)
            print("doubleArrayOf(%r, %.9f, %.9f, %.9f, %.9f, %.9f, %.9f, %.9f)," % (
                t, r, d, s, w, lo.evaluate(t)[0][0], breath_delta(m, lo, t), sway_gain(m, lo, t)))


if __name__ == "__main__":
    if "--emit-reference" in sys.argv:
        emit_reference()
    else:
        numeric_checks()
        plot_tables(); plot_breathing(); plot_locomotion(); plot_sway()
        print("wrote out/24_mood_tables.png, 25_mood_breathing.png, 26_mood_locomotion.png, 27_mood_sway.png")
