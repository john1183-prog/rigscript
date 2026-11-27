"""WP3.1 prototype: kinetic text (per-unit staggered enter). Mirrors engine/KineticText.kt and the unit part of
OverlayResolver.resolveUnits; change both together.

python3 kinetic_proto.py            numeric checks + plot out/28_kinetic.png
python3 kinetic_proto.py --emit-reference   rows that KineticTextTest pins the Kotlin port to
"""
import math, sys, os

MAX_UNITS = 40

def ease(kind, t):
    if kind == "linear": return t
    if kind == "ease_in": return t * t * t
    if kind == "ease_out":
        inv = 1 - t
        return 1 - inv * inv * inv
    if kind == "back":
        c1 = 1.70158; c3 = c1 + 1; x = t - 1
        return 1 + c3 * x * x * x + c1 * x * x
    raise ValueError(kind)

def animate(style, p):
    if style == "fade": return (p, 1.0, 0.0, 0.0)
    if style == "pop": return (p, p, 0.0, 0.0)
    if style == "zoom": return (p, 0.4 + 0.6 * p, 0.0, 0.0)
    if style == "slideup": return (p, 1.0, 0.0, (1 - p) * 0.15)
    if style == "slidedown": return (p, 1.0, 0.0, -(1 - p) * 0.15)
    if style == "none": return (1.0, 1.0, 0.0, 0.0)
    return (p, 1.0, 0.0, 0.0)

def words(text):
    out = []; i = 0; n = len(text)
    while i < n:
        while i < n and text[i].isspace(): i += 1
        if i >= n: break
        s = i
        while i < n and not text[i].isspace(): i += 1
        out.append((s, i))
    return out

def chars(text):
    return [(i, i + 1) for i, c in enumerate(text) if not c.isspace()]

def unit_ranges(text, mode):
    if mode == "char":
        r = chars(text)
        if len(r) <= MAX_UNITS and len(r) >= 2: return r
        mode = "word"
    if mode == "word":
        r = words(text)
        if 2 <= len(r) <= MAX_UNITS: return r
    return []

def effective_stagger(stagger, n, enter, exit_, window):
    if n < 2: return 0.0
    room = max(0.0, window - exit_ - enter)
    return max(0.0, min(stagger, room / (n - 1)))

def unit_states(n, style, ease_name, stagger, enter, exit_, window, t):
    eff = effective_stagger(stagger, n, enter, exit_, window)
    out = []
    for i in range(n):
        delay = i * eff
        if t < delay:
            out.append((0.0, 1.0, 0.0, 0.0)); continue
        p = min(1.0, max(0.0, (t - delay) / enter))
        a, s, ox, oy = animate(style, ease(ease_name, p))
        out.append((min(1.0, max(0.0, a)), s, ox, oy))
    return out

CASES = [
    ("fade", "ease_out", 0.12, 0.35, 0.35, 4.0, 6),
    ("pop", "back", 0.10, 0.30, 0.30, 3.0, 5),
    ("zoom", "ease_out", 0.20, 0.40, 0.40, 2.0, 7),
    ("slideup", "linear", 0.15, 0.30, 0.30, 5.0, 4),
    ("none", "linear", 0.25, 0.30, 0.30, 4.0, 5),
]
TIMES = [0.0, 0.05, 0.2, 0.41, 0.77, 1.3, 2.9]

def checks():
    assert unit_ranges("a bb  ccc\nd", "word") == [(0, 1), (2, 4), (6, 9), (10, 11)]
    assert unit_ranges("solo", "word") == []
    assert len(unit_ranges("ab cd", "char")) == 4
    # stagger is squeezed so the last unit lands before the exit starts
    eff = effective_stagger(1.0, 6, 0.35, 0.35, 2.0)
    assert abs(eff - (2.0 - 0.7) / 5) < 1e-12
    assert abs(5 * eff + 0.35 - (2.0 - 0.35)) < 1e-12
    for st, ea, sg, en, ex, win, n in CASES:
        end = (n - 1) * effective_stagger(sg, n, en, ex, win) + en
        for a, s, ox, oy in unit_states(n, st, ea, sg, en, ex, win, end + 0.01):
            assert abs(a - 1) < 1e-9 and abs(s - 1) < 1e-9 and abs(oy) < 1e-9, (st, a, s, oy)
        first = unit_states(n, st, ea, sg, en, ex, win, 0.0)
        assert first[-1][0] == 0.0
    print("checks ok")

def plot():
    import matplotlib; matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig, axes = plt.subplots(len(CASES), 2, figsize=(11, 10), sharex=True)
    for row, (st, ea, sg, en, ex, win, n) in enumerate(CASES):
        ts = [k * 0.02 for k in range(int(win / 0.02))]
        for i in range(n):
            axes[row][0].plot(ts, [unit_states(n, st, ea, sg, en, ex, win, t)[i][0] for t in ts], lw=1)
            axes[row][1].plot(ts, [unit_states(n, st, ea, sg, en, ex, win, t)[i][1] for t in ts], lw=1)
        axes[row][0].set_ylabel(f"{st}/{ea}")
    axes[0][0].set_title("opacity per word"); axes[0][1].set_title("scale per word")
    os.makedirs("out", exist_ok=True)
    fig.tight_layout(); fig.savefig("out/28_kinetic.png", dpi=80)

def emit():
    print("// style,ease,stagger,enter,exit,window,n,t,i,opacity,scale,offsetY")
    for st, ea, sg, en, ex, win, n in CASES:
        for t in TIMES:
            if t >= win: continue
            states = unit_states(n, st, ea, sg, en, ex, win, t)
            for i in (0, n // 2, n - 1):
                a, s, ox, oy = states[i]
                print(f'"{st},{ea},{sg},{en},{ex},{win},{n},{t},{i},{a:.7f},{s:.7f},{oy:.7f}",')

if __name__ == "__main__":
    if "--emit-reference" in sys.argv: emit()
    else: checks(); plot()
