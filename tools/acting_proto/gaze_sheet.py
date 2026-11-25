#!/usr/bin/env python3
"""Confirms the WP2.3 gaze can point in every direction (idle saccades), with the face drawer of face_proto.py.

Writes out/16_gaze_directions.png: row 1 = the face looking centre, left, right, up, down and the four diagonals at the
gaze cap (what a directed look_at would reach once WP2.5 adds targets); row 2 = polar scatter of where 400 seeded idle
saccades actually land, and the quadrant counts. Constants come from face_proto.py (Kotlin: `ActingParams.GAZE_*`).
Run: python3 gaze_sheet.py
"""
import math, os
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from PIL import Image, ImageDraw
import face_proto as fp

OUT = fp.OUT


def main():
    tl = fp.Timeline(fp.NORMAL, [])
    base = fp.resolve(tl, 1, 100.0, "NORMAL", gaze_on=False)
    cap = fp.GAZE_CAP
    dirs = [("centre", 0, 0), ("left", -1, 0), ("right", 1, 0), ("up", 0, 1), ("down", 0, -1),
            ("up-left", -1, 1), ("up-right", 1, 1), ("down-left", -1, -1), ("down-right", 1, -1)]
    cell, r = 130, 50
    img = Image.new("RGB", (cell * len(dirs), cell + 22), (245, 240, 230))
    d = ImageDraw.Draw(img)
    for j, (name, dx, dy) in enumerate(dirs):
        f = dict(base)
        n = math.hypot(dx, dy) or 1.0
        f["gazeX"], f["gazeY"] = cap * dx / n, cap * dy / n
        fp.draw_face(d, j * cell + cell / 2, cell / 2 + 4, r, f)
        d.text((j * cell + 6, cell + 6), name, fill=(0, 0, 0))
    path1 = os.path.join(OUT, "16_gaze_directions.png")
    img.save(path1)

    pts = [fp.gaze_target(seed, k, 1.0) for seed in (1, 2, 3, 4) for k in range(100)]
    quad = {"right-up": 0, "left-up": 0, "left-down": 0, "right-down": 0}
    for x, y in pts:
        quad[("right" if x >= 0 else "left") + "-" + ("up" if y >= 0 else "down")] += 1
    fig, ax = plt.subplots(figsize=(5, 5))
    ax.scatter([p[0] for p in pts], [p[1] for p in pts], s=8)
    ax.add_patch(plt.Circle((0, 0), cap, fill=False, ls=":"))
    ax.set_aspect("equal"); ax.axhline(0, color="gray", lw=0.5); ax.axvline(0, color="gray", lw=0.5)
    ax.set_title("400 idle saccade targets (head radii): " + ", ".join("%s %d" % kv for kv in quad.items()), fontsize=7)
    fig.tight_layout(); path2 = os.path.join(OUT, "17_gaze_targets.png"); fig.savefig(path2, dpi=90); plt.close(fig)
    xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
    print("targets: x %.3f..%.3f, y %.3f..%.3f (cap %.2f), quadrants %s" % (min(xs), max(xs), min(ys), max(ys), cap, quad))
    ok = all(v > 40 for v in quad.values()) and max(abs(v) for v in xs + ys) <= cap + 1e-9
    print("PASS all four quadrants reached, inside the cap" if ok else "FAIL")
    return ok


if __name__ == "__main__":
    raise SystemExit(0 if main() else 1)
