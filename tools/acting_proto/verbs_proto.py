#!/usr/bin/env python3
"""WP2.5 prototype of the `act` verbs (Direction): additive offsets over the base pose and face, pure functions of time.

Every verb is  offset(u) = amount * a * shape(u)  with u = t - t0 and a = actIntensity (0..1). Shapes start and end at
exactly 0 with zero slope, so a verb returns to the base pose exactly (no pop) and several verbs may overlap by simple
addition. look_at / point_at turn an `actTarget` into a direction (gaze) or a two-bone IK solution (arm).
Constants mirror `VerbParams` (Kotlin); change both together.

Run:  python3 verbs_proto.py                 prints the numeric checks, writes out/18..21
      python3 verbs_proto.py --emit-reference   prints the rows that `ActVerbTest` pins the Kotlin port to
"""
import math, os, sys
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from PIL import Image, ImageDraw
import acting_proto as ap
import face_proto as fp

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")

# ---- constants (VerbParams) ---------------------------------------------------------------------------------------
LOOK_CAP = 0.30          # head radii: the gaze offset of a directed look
HEAD_LOOK_DEG = 7.0      # head turn (tilt) that goes with a full-cap look
LOOK_IN, LOOK_OUT = 0.18, 0.25
LOOK_MAX_HOLD = 2.0      # s: a look_at that no later gaze verb replaces lasts this long
POINT_LOOK_SHARE = 0.6   # point_at also looks at its target, this fraction of a look
REACH_MARGIN = 0.02      # of the arm length kept off the full stretch (no snap to a locked elbow)
DURATION = {"nod": 0.45, "shake_head": 0.6, "emphasize": 0.35, "react_surprise": 0.7, "react_think": 0.8,
            "react_laugh": 1.0, "react_sad": 1.8, "look_at": 2.0, "look_away": 0.8, "point_at": 1.8}
VERBS = list(DURATION)
BONES = ap.BONES
IDX = {b: i for i, b in enumerate(BONES)}
GAZE_VERBS = ("look_at", "look_away")


def smooth(x):
    c = 0.0 if x < 0 else 1.0 if x > 1 else x
    return c * c * (3 - 2 * c)


def bump(u, d):
    """sin^2 lobe: 0 at both ends with zero slope, 1 in the middle."""
    if u <= 0 or u >= d: return 0.0
    return math.sin(math.pi * u / d) ** 2


def plateau(u, rise, hold_end, fall_end):
    """0 -> 1 over [0, rise], 1 until hold_end, back to 0 at fall_end (smoothsteps)."""
    if u <= 0 or u >= fall_end: return 0.0
    return smooth(u / rise) * (1 - smooth((u - hold_end) / (fall_end - hold_end)))


# ---- one verb's offsets --------------------------------------------------------------------------------------------
class Off:
    """Bone offsets (degrees, relative angles) and face deltas of one verb at one time."""
    def __init__(self):
        self.b = [0.0] * 10
        self.eyeH = 0.0       # eye height multiplier delta (squint < 0, take > 0)
        self.eyeR = 0.0       # eye radius fraction delta
        self.gx = 0.0; self.gy = 0.0      # gaze delta (head radii, +x right, +y up)
        self.gw = 0.0         # weight with which the directed gaze replaces the idle gaze (0..1)
        self.browH = 0.0; self.browL = 0.0
        self.mouthW = 0.0     # mouth width multiplier delta
        self.mouthB = 0.0     # mouth vertical bias delta (fraction of head radius)
        self.laugh = 0.0      # mouth opening of a laugh (0..1), 0 = not laughing

    def add(self, o):
        for i in range(10): self.b[i] += o.b[i]
        self.eyeH += o.eyeH; self.eyeR += o.eyeR; self.gx += o.gx; self.gy += o.gy
        self.gw = min(1.0, self.gw + o.gw)
        self.browH += o.browH; self.browL += o.browL; self.mouthW += o.mouthW; self.mouthB += o.mouthB
        self.laugh = max(self.laugh, o.laugh)


def verb_offsets(kind, a, u, dirx=0.0, diry=0.0, window=None):
    """Offsets of verb `kind` at local time u with intensity a. (dirx, diry) = unit direction for look/point (+y up).
    window = (end, fall) of a gaze verb whose hold was cut by a later gaze verb."""
    o = Off()
    D = DURATION[kind]
    if kind == "nod":
        w = bump(u, D)
        o.b[IDX["head"]] = 16.0 * a * w
        o.b[IDX["torso"]] = 3.0 * a * w
        o.gy = -0.55 * LOOK_CAP * a * w; o.gw = 0.6 * a * w
        o.eyeH = -0.20 * a * w
    elif kind == "shake_head":
        env = bump(u, D)
        s = math.sin(4 * math.pi * u / D)
        o.b[IDX["head"]] = 18.0 * a * s * env
        o.gx = 0.6 * LOOK_CAP * a * s * env; o.gw = 0.6 * a * env
        o.b[IDX["torso"]] = -2.0 * a * s * env
    elif kind == "emphasize":
        w = bump(u, D)
        o.b[IDX["torso"]] = 6.0 * a * w
        o.b[IDX["head"]] = -7.0 * a * w
        o.b[IDX["upper_arm_r"]] = -16.0 * a * w
        o.b[IDX["upper_arm_l"]] = 16.0 * a * w
        o.b[IDX["lower_arm_r"]] = -10.0 * a * w
        o.b[IDX["lower_arm_l"]] = 10.0 * a * w
        o.browH = 0.25 * a * w
    elif kind == "react_surprise":
        sq = bump(u, 0.12) if u < 0.12 else 0.0                    # squint 0 .. 0.12 s
        tk = 0.0 if u < 0.10 else smooth((u - 0.10) / 0.10) * (1 - smooth((u - 0.28) / 0.42))   # take, then settle by 0.70
        lean = plateau(u, 0.16, 0.30, 0.70)
        o.eyeH = -0.35 * a * sq + 0.50 * a * tk
        o.eyeR = 0.045 * a * tk
        o.browH = 0.55 * a * tk
        o.b[IDX["torso"]] = -7.0 * a * lean
        o.b[IDX["head"]] = -5.0 * a * lean
        o.b[IDX["upper_arm_r"]] = -18.0 * a * lean
        o.b[IDX["upper_arm_l"]] = 18.0 * a * lean
        o.b[IDX["lower_arm_r"]] = -8.0 * a * lean
        o.b[IDX["lower_arm_l"]] = 8.0 * a * lean
        o.mouthW = -0.10 * a * lean; o.laugh = 0.0
    elif kind == "react_think":
        w = plateau(u, 0.22, 0.52, 0.80)
        o.b[IDX["head"]] = 15.0 * a * w
        o.b[IDX["torso"]] = 2.0 * a * w
        o.gx = 0.75 * LOOK_CAP * a * w; o.gy = 0.8 * LOOK_CAP * a * w; o.gw = a * w
        o.browL = 0.30 * a * w; o.browH = 0.10 * a * w
        o.eyeH = -0.10 * a * w
    elif kind == "react_laugh":
        env = smooth(u / 0.15) * (1 - smooth((u - 0.8) / 0.2)) if 0 < u < D else 0.0
        s = math.sin(2 * math.pi * 3.5 * u / 1.0)
        o.b[IDX["torso"]] = 3.5 * a * s * env
        o.b[IDX["head"]] = -2.5 * a * s * env
        o.b[IDX["upper_arm_r"]] = -4.0 * a * s * env
        o.b[IDX["upper_arm_l"]] = 4.0 * a * s * env
        o.eyeH = -0.30 * a * env
        o.mouthW = 0.30 * a * env; o.mouthB = -0.03 * a * env
        o.laugh = (0.55 + 0.35 * abs(s)) * a * env
    elif kind == "react_sad":
        w = plateau(u, 0.40, 1.30, 1.80)
        o.b[IDX["torso"]] = 5.0 * a * w
        o.b[IDX["head"]] = 16.0 * a * w
        o.b[IDX["upper_arm_r"]] = 7.0 * a * w
        o.b[IDX["upper_arm_l"]] = -7.0 * a * w
        o.gy = -0.8 * LOOK_CAP * a * w; o.gw = a * w
        o.browL = 0.50 * a * w; o.browH = -0.10 * a * w
        o.eyeH = -0.20 * a * w
        o.mouthB = 0.05 * a * w; o.mouthW = -0.10 * a * w
    elif kind in ("look_at", "look_away"):
        if kind == "look_at":
            end, fall = window if window else (LOOK_MAX_HOLD, LOOK_MAX_HOLD + LOOK_OUT)
            w = plateau(u, LOOK_IN, end, fall)
        else:
            w = plateau(u, 0.15, 0.50, 0.80)
        o.gx = dirx * LOOK_CAP * w; o.gy = diry * LOOK_CAP * w
        o.gw = w
        o.b[IDX["head"]] = HEAD_LOOK_DEG * dirx * w
    return o


# ---- two-bone IK (point_at) ---------------------------------------------------------------------------------------
L1, L2 = 0.12, 0.10    # upper arm and forearm, in rig units (normalizedLength)


def ik_two_bone(sx, sy, tx, ty, parent_abs_deg, side=1, l1=L1, l2=L2):
    """Relative angles (upper, fore) in degrees so the wrist reaches (tx, ty) from shoulder (sx, sy) (y down); side +1 = right arm, -1 = left arm.
    Out-of-reach targets clamp to the reach (just short of a locked elbow). Returns (upper_rel, fore_rel, reached, dist)."""
    dx, dy = tx - sx, ty - sy
    d = math.hypot(dx, dy)
    lo, hi = abs(l1 - l2) + 1e-4, (l1 + l2) * (1 - REACH_MARGIN)
    dc = min(max(d, lo), hi)
    reached = (lo <= d <= hi)
    ang = math.atan2(dy, dx)
    c2 = (dc * dc - l1 * l1 - l2 * l2) / (2 * l1 * l2)
    th2 = math.acos(max(-1.0, min(1.0, c2)))               # interior bend of the elbow relative to straight
    th2 = -side * th2          # elbow DOWN: the right arm (side +1, screen right) bends its forearm counter-clockwise, the left arm clockwise
    al = math.atan2(l2 * math.sin(th2), l1 + l2 * math.cos(th2))
    up_abs = ang - al
    fore_rel = math.degrees(th2)
    up_rel = math.degrees(up_abs) - parent_abs_deg
    return wrap(up_rel), wrap(fore_rel), reached, d


def wrap(a):
    a = (a + 180.0) % 360.0 - 180.0
    return a


def fk_wrist(sx, sy, up_abs_deg, fore_rel_deg, l1=L1, l2=L2):
    ex, ey = sx + l1 * math.cos(math.radians(up_abs_deg)), sy + l1 * math.sin(math.radians(up_abs_deg))
    fa = up_abs_deg + fore_rel_deg
    return ex + l2 * math.cos(math.radians(fa)), ey + l2 * math.sin(math.radians(fa))


# ---- stage geometry: rig units with y down, pelvis position from the appearance --------------------------------------
def stage_scale(aspect, character_scale=1.0):
    """The figure scale in units of the canvas HEIGHT (rig lengths are multiplied by it): portrait scales with the width."""
    return (aspect if aspect < 1.0 else 0.5625) * character_scale


def norm_to_rig(tx, ty, aspect, root_x, root_y, character_scale=1.0):
    """Normalised screen (0..1, y down) -> rig units relative to the pelvis."""
    sc = stage_scale(aspect, character_scale)
    return ((tx - root_x) * aspect) / sc, (ty - root_y) / sc


def torso_tip(torso_abs_deg):
    return 0.22 * math.cos(math.radians(torso_abs_deg)), 0.22 * math.sin(math.radians(torso_abs_deg))


def look_dir(head_x, head_y, tx, ty, aspect):
    """Unit gaze direction (+y up) from the head to a normalised screen point; the distance does not matter."""
    dx = (tx - head_x) * aspect; dy = -(ty - head_y)
    n = math.hypot(dx, dy)
    return (0.0, 0.0) if n < 1e-9 else (dx / n, dy / n)


def point_weight(u):
    """Rise with a small wind-back, hold, fall. 0 at both ends with zero slope; wind-back is negative for u in (0, 0.1)."""
    D = DURATION["point_at"]
    main = plateau(u, 0.35, 1.55, D)
    ant = -0.08 * bump(u, 0.12)
    return main + ant * (1 - smooth(u / 0.2))


def point_offsets(u, a, base_up, base_fore, ik_up, ik_fore):
    """Arm offsets toward the IK solution: weight(u) * (target - base), the amount scaled by a."""
    w = point_weight(u) * a
    return wrap(ik_up - base_up) * w, wrap(ik_fore - base_fore) * w


# ---- sampling a whole verb as bone/face curves ----------------------------------------------------------------------------
def curves(kind, a=0.7, dirx=1.0, diry=0.0, n=401):
    D = DURATION[kind] + (LOOK_OUT if kind == "look_at" else 0.0)
    ts = np.linspace(0, D + 0.1, n)
    rows = [verb_offsets(kind, a, float(t), dirx, diry) for t in ts]
    return ts, rows


# ---- plots and strips -------------------------------------------------------------------------------------------------
def plot_curves():
    fig, axs = plt.subplots(5, 2, figsize=(13, 14))
    for ax, kind in zip(axs.flat, VERBS):
        ts, rows = curves(kind, a=1.0, dirx=1.0, diry=0.7)
        shown = ["torso", "head", "upper_arm_r", "upper_arm_l", "lower_arm_r"]
        for b in shown:
            y = [r.b[IDX[b]] for r in rows]
            if max(abs(v) for v in y) > 1e-9: ax.plot(ts, y, label=b)
        ax2 = ax.twinx()
        ax2.plot(ts, [r.eyeH for r in rows], "k--", lw=0.9, label="eyeH")
        ax2.plot(ts, [r.gy for r in rows], "m:", lw=0.9, label="gazeY")
        ax2.plot(ts, [r.gx for r in rows], "c:", lw=0.9, label="gazeX")
        ax.set_title("%s (a=1)" % kind, fontsize=9); ax.axhline(0, color="gray", lw=0.4)
        ax.legend(fontsize=6, loc="upper left") if ax.lines else None; ax2.legend(fontsize=6, loc="upper right")
    fig.tight_layout(); fig.savefig(os.path.join(OUT, "18_verb_curves.png"), dpi=80); plt.close(fig)


BASE_STAND = [ap.DEFAULT[i] for i in range(10)]


def figure_at(kind, t, a=0.7, dirx=1.0, diry=0.0):
    o = verb_offsets(kind, a, t, dirx, diry)
    return [BASE_STAND[i] + o.b[i] for i in range(10)] , o


def draw_strip(kinds, fname, a=0.9, cell=(190, 250)):
    W, H = cell
    cols = 8
    img = Image.new("RGB", (W * cols, H * len(kinds)), (247, 243, 232))
    d = ImageDraw.Draw(img)
    for r, kind in enumerate(kinds):
        D = DURATION[kind]
        for c in range(cols):
            t = D * (c + 0.5) / cols
            ang_rel, o = figure_at(kind, t, a, dirx=0.8, diry=0.6)
            # draw_figure takes relative angles (the drawer adds parents); head drawn with its circle at the bone tip
            ox, oy = c * W + W * 0.5, r * H + H * 0.62
            ap.draw_figure(d, ox, oy, 250, [ang_rel[i] - (0) for i in range(10)])
            seg = ap.fk(ang_rel); hx, hy = seg[1][2], seg[1][3]
            f = {"eyeRad": 0.15 + o.eyeR, "openness": 1.0, "heightMul": max(0.2, 1.0 + o.eyeH), "gazeX": o.gx * 1.0, "gazeY": o.gy,
                 "browH": 1.8 + o.browH, "browL": o.browL, "mouthW": 1.0 + o.mouthW, "mouthB": o.mouthB}
            fp.draw_face(d, ox + hx * 250, oy + hy * 250, 0.048 * 250, f, mouth_open=o.laugh, tilt=sum(ang_rel[:2]) + 90 if False else (ang_rel[1]))
            d.text((c * W + 4, r * H + 3), "%s t=%.2f" % (kind, t), fill=(90, 90, 90))
        d.line([(0, (r + 1) * H - 1), (W * cols, (r + 1) * H - 1)], fill=(200, 195, 180))
    img.save(os.path.join(OUT, fname))


def face_strip(kinds, fname, a=1.0, cols=7, r=46):
    """Close-up of the face (and the head tilt it gets from torso + head offsets) through each verb."""
    W, H = 130, 130
    img = Image.new("RGB", (W * cols, H * len(kinds)), (247, 243, 232)); d = ImageDraw.Draw(img)
    base = {"eyeRad": 0.15, "openness": 1.0, "heightMul": 1.0, "gazeX": 0.0, "gazeY": 0.0, "browH": 1.8, "browL": 0.0, "mouthW": 1.0, "mouthB": 0.0}
    for r_i, kind in enumerate(kinds):
        D = DURATION[kind]
        for c in range(cols):
            t = D * (c + 0.5) / cols
            o = verb_offsets(kind, a, t, 0.8, 0.6)
            f = dict(base)
            f["eyeRad"] += o.eyeR; f["heightMul"] = max(0.2, 1.0 + o.eyeH); f["gazeX"] = o.gx; f["gazeY"] = o.gy
            f["browH"] += o.browH; f["browL"] = o.browL; f["mouthW"] = 1.0 + o.mouthW; f["mouthB"] = o.mouthB
            tilt = o.b[IDX["torso"]] + o.b[IDX["head"]]
            fp.draw_face(d, c * W + W / 2, r_i * H + H / 2 + 6, r, f, mouth_open=o.laugh, tilt=tilt)
            d.text((c * W + 3, r_i * H + 2), "%s %.2f" % (kind, t), fill=(90, 90, 90))
    img.save(os.path.join(OUT, fname))


def plot_ik():
    """point_at: IK accuracy over a grid of targets, and the reach clamp."""
    errs = []; xs = []; ys = []; reached = []
    for ty in np.linspace(-0.25, 0.35, 25):
        for tx in np.linspace(-0.40, 0.40, 33):
            up, fo, ok, d = ik_two_bone(0.0, -0.22, tx, ty - 0.0, -90.0, side=1 if tx >= 0 else -1)
            up_abs = up - 90.0
            wx, wy = fk_wrist(0.0, -0.22, up_abs, fo)
            tgt_d = math.hypot(tx, ty + 0.22)
            lo, hi = abs(L1 - L2) + 1e-4, (L1 + L2) * (1 - REACH_MARGIN)
            clamped = min(max(math.hypot(tx, ty + 0.22), lo), hi)
            err = math.hypot(wx - tx, wy - ty) if ok else abs(math.hypot(wx - 0.0, wy + 0.22) - clamped)
            errs.append(err); xs.append(tx); ys.append(ty); reached.append(ok)
    fig, ax = plt.subplots(figsize=(6, 6))
    ax.scatter([x for x, r in zip(xs, reached) if r], [y for y, r in zip(ys, reached) if r], s=6, c="tab:green", label="reachable")
    ax.scatter([x for x, r in zip(xs, reached) if not r], [y for y, r in zip(ys, reached) if not r], s=6, c="tab:red", label="clamped")
    ax.invert_yaxis(); ax.set_aspect("equal"); ax.legend(fontsize=7)
    ax.set_title("point_at IK from the shoulder (0,-0.22): max error %.2e" % max(errs), fontsize=8)
    fig.tight_layout(); fig.savefig(os.path.join(OUT, "19_ik_reach.png"), dpi=80); plt.close(fig)
    return max(errs)


def point_strip(fname):
    """Figure pointing at six targets (left, right, up-left, up-right, down-right, out of reach) at the hold."""
    targets = [("left", -0.17, -0.12), ("right", 0.17, -0.12), ("up-left", -0.12, -0.36), ("up-right", 0.12, -0.38),
               ("low-right", 0.14, -0.02), ("far right", 0.60, -0.22)]
    W, H = 220, 280
    img = Image.new("RGB", (W * len(targets), H), (247, 243, 232)); d = ImageDraw.Draw(img)
    for c, (name, tx, ty) in enumerate(targets):
        base = list(BASE_STAND)
        right = tx >= 0
        ub, fb = (IDX["upper_arm_r"], IDX["lower_arm_r"]) if right else (IDX["upper_arm_l"], IDX["lower_arm_l"])
        sx, sy = torso_tip(-90.0)
        # target is given relative to the pelvis in rig units; the elbow hangs down
        up, fo, ok, dist = ik_two_bone(sx, sy, tx, ty, -90.0, side=1 if right else -1)
        ang = list(base)
        ang[ub] = up; ang[fb] = fo
        ox, oy = c * W + W * 0.5, H * 0.62
        ap.draw_figure(d, ox, oy, 250, ang)
        d.ellipse([ox + tx * 250 - 4, oy + ty * 250 - 4, ox + tx * 250 + 4, oy + ty * 250 + 4], outline=(200, 30, 30), width=2)
        d.text((c * W + 4, 3), "%s %s" % (name, "ok" if ok else "clamped"), fill=(90, 90, 90))
    img.save(os.path.join(OUT, fname))


# ---- checks -------------------------------------------------------------------------------------------------------------
def checks():
    ok = True
    for kind in VERBS:
        D = DURATION[kind] + (LOOK_OUT if kind == "look_at" else 0.0)
        for a in (0.2, 0.7, 1.0):
            o0 = verb_offsets(kind, a, 0.0, 0.6, 0.8); o1 = verb_offsets(kind, a, D, 0.6, 0.8); o2 = verb_offsets(kind, a, D + 0.5, 0.6, 0.8)
            for o in (o0, o1, o2):
                tot = sum(abs(v) for v in o.b) + abs(o.eyeH) + abs(o.eyeR) + abs(o.gx) + abs(o.gy) + abs(o.browH) + abs(o.browL) + abs(o.mouthW) + abs(o.mouthB) + o.laugh
                if tot > 1e-9: print("FAIL %s a=%.1f does not return to zero (%.3g)" % (kind, a, tot)); ok = False
        # slope continuity: the largest per-step change of any bone, at 240 fps
        prev = None; worst = 0.0
        for i in range(int((D + 0.2) * 240)):
            o = verb_offsets(kind, 1.0, i / 240.0, 0.6, 0.8)
            v = o.b + [o.eyeH * 20, o.gx * 100, o.gy * 100]
            if prev is not None: worst = max(worst, max(abs(x - y) for x, y in zip(v, prev)))
            prev = v
        mx = max(max(abs(x) for x in verb_offsets(kind, 1.0, i / 240.0, 0.6, 0.8).b) for i in range(int(D * 240)))
        print("%-15s duration %.2f  max bone offset %5.1f deg  max step@240fps %.2f deg" % (kind, DURATION[kind], mx, worst))
        if mx > 25.0: print("FAIL offset above 25 deg"); ok = False
        if worst > 3.5: print("FAIL discontinuity"); ok = False
    err = plot_ik()
    print("IK max error %.2e" % err)
    if err > 1e-6: ok = False
    # the intensity scales linearly
    o_a = verb_offsets("emphasize", 0.35, 0.17); o_b = verb_offsets("emphasize", 0.70, 0.17)
    if abs(o_b.b[0] - 2 * o_a.b[0]) > 1e-9: print("FAIL linear in a"); ok = False
    return ok


REF_TIMES = [0.0, 0.05, 0.1, 0.2, 0.3, 0.45, 0.6, 0.9, 1.4, 2.1]
REF_DIR = (0.6, 0.8)


def emit_reference():
    print("// kind, a, u, then 10 bone offsets, eyeH, eyeR, gx, gy, gw, browH, browL, mouthW, mouthB, laugh")
    for kind in VERBS:
        for u in REF_TIMES:
            o = verb_offsets(kind, 0.7, u, REF_DIR[0], REF_DIR[1])
            vals = o.b + [o.eyeH, o.eyeR, o.gx, o.gy, o.gw, o.browH, o.browL, o.mouthW, o.mouthB, o.laugh]
            print('"%s,0.7,%s,%s",' % (kind, repr(u), ",".join("%.6f" % v for v in vals)))
    # IK rows: shoulder, target, parent abs -> up, fore
    print("// ik: sx,sy,tx,ty,parentAbs -> up,fore (elbow down)")
    for (sx, sy, tx, ty, pa) in [(0.0, -0.22, 0.15, -0.20, -90.0), (0.0, -0.22, -0.12, -0.05, -90.0), (0.0, -0.22, 0.05, -0.40, -75.0), (0.0, -0.22, 0.60, -0.22, -90.0)]:
        up, fo, ok, d = ik_two_bone(sx, sy, tx, ty, pa, side=1 if tx >= sx else -1)
        print('"%s,%s,%s,%s,%s,%.6f,%.6f",' % (sx, sy, tx, ty, pa, up, fo))
    print("// point weight: u -> weight")
    for u in (0.0, 0.03, 0.06, 0.1, 0.2, 0.35, 0.8, 1.55, 1.7, 1.8, 2.0):
        print('"%s,%.9f",' % (u, point_weight(u)))


def main():
    os.makedirs(OUT, exist_ok=True)
    ok = checks()
    plot_curves()
    draw_strip(["nod", "shake_head", "emphasize", "react_surprise", "react_think"], "20_verb_strip_a.png")
    draw_strip(["react_laugh", "react_sad", "look_at", "look_away", "point_at"], "21_verb_strip_b.png")
    point_strip("22_point_targets.png")
    face_strip(["nod", "shake_head", "react_surprise", "react_think", "react_laugh", "react_sad", "look_at", "look_away"], "23_verb_faces.png")
    print("PASS" if ok else "FAIL")
    return ok


if __name__ == "__main__":
    if "--emit-reference" in sys.argv: emit_reference()
    else: raise SystemExit(0 if main() else 1)
