#!/usr/bin/env python3
"""Deterministic generator for app/src/test/resources/fixtures/*.json (WP0.3).

    python3 tools/fixtures/gen_fixtures.py

These files are the legacy-script corpus: they use only fields that exist at commit
eb6b0b3 (invariant I1). Never add V2 fields to them; add new fixtures for new fields.
Colors are decimal Longs because kotlinx.serialization reads and writes Long as a number.
The generator checks every key against the schema, because the app's parser ignores
unknown keys silently and a typo would quietly weaken a fixture.
"""
import json
import os
import random

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
OUT = os.path.join(ROOT, "app", "src", "test", "resources", "fixtures")

POSES = ["stand_straight", "wave", "think", "explain", "walk_a", "walk_b", "jog_a", "jog_b",
         "jump", "tired", "lazy", "sleepy", "confused", "excited", "shrug", "point_right",
         "point_left", "point_up", "celebrate", "sit", "present", "point_self", "open_hands"]
EXPRESSIONS = ["normal", "wide", "squint", "worried", "angry", "happy"]
EASES = ["linear", "ease_in", "ease_out", "ease_in_out", "bounce", "elastic_out", "spring", "rigid"]
SCENE_SHAPES = ["none", "mountains", "city", "trees", "clouds", "room", "beach"]
ATMOSPHERES = ["none", "rain", "snow", "fog", "stars"]
SFX = ["click", "confirm", "error", "glitch", "drop", "tick", "toggle", "bong", "switch",
       "pluck", "question", "select", "open", "close"]
BONES = ["torso", "head", "upper_arm_r", "lower_arm_r", "upper_arm_l", "lower_arm_l",
         "upper_leg_r", "lower_leg_r", "upper_leg_l", "lower_leg_l"]
STYLES = ["fade", "pop", "zoom", "slideup", "slidedown", "none"]
PALETTE = [0xFFFFFFFF, 0xFFFFEB3B, 0xFF4FC3F7, 0xFFFF7043, 0xFF69F0AE, 0xFFFF8A65, 0xFF80D8FF, 0xFFE1BEE7]
SKY = [0xFF87CEEB, 0xFF16213E, 0xFFF3E5D0, 0xFF1A2744, 0xFF9BB7D4]
GROUND = [0xFFE8F4F8, 0xFF0F3443, 0xFFC9A876, 0xFF0D1B2A, 0xFFDCE8F0]

EVENT_KEYS = {
    "timeSec", "pose", "duration", "ease", "springStiffness", "springDamping", "expression",
    "cameraZoom", "cameraPanX", "cameraPanY", "cameraShake", "caption", "captionDurationSec",
    "skyColor", "groundColor", "horizonY", "sceneShape", "sceneAtmosphere", "soundEffect",
    "soundEffectVolume", "figureX", "figureY", "figureScale", "headScale", "figureOpacity",
    "boneColor", "headColor", "jointColor", "bgColor", "backgroundGradientColor",
    "backgroundStyle", "groundLineColor", "showGroundLine", "groundLineYFraction",
    "mouthColor", "eyeColor", "eyebrowColor"}
EVENT_LONG = {"skyColor", "groundColor", "boneColor", "headColor", "jointColor", "bgColor",
              "backgroundGradientColor", "groundLineColor", "mouthColor", "eyeColor", "eyebrowColor"}
LAYER_KEYS = {
    "id", "type", "shape", "startSec", "endSec", "x", "y", "slot", "width", "height", "radius",
    "rotationDeg", "scale", "text", "fontSize", "bold", "align", "color", "gradientColor", "glow",
    "glowColor", "glowRadius", "enterStyle", "enterDuration", "enterEase", "exitStyle",
    "exitDuration", "exitEase", "opacity", "parentBone", "parentLayer", "inFrontOfFigure",
    "screenSpace", "anim", "physics", "physicsVx", "physicsVy", "physicsGravity", "physicsFloorY",
    "physicsBounceDamping", "trail", "trailLengthSec", "particleCount", "particleShape",
    "particleSpeed", "particleGravity", "particleLifetimeSec", "particleSizeMin",
    "particleSizeMax", "pose", "expression"}
LAYER_LONG = {"color", "gradientColor", "glowColor"}
ANIM_KEYS = {"t", "x", "y", "scale", "opacity", "rotationDeg", "ease"}
TOP_KEYS = {"version", "events", "blinkEvents", "overlayLayers"}


def r(v):
    return round(v, 3)


def script(events, blinks=None, layers=None, extra=None):
    doc = {"version": "1.0", "events": events}
    if blinks:
        doc["blinkEvents"] = blinks
    if layers:
        doc["overlayLayers"] = layers
    if extra:
        doc.update(extra)
    return doc


def check(name, doc, allowed_unknown=()):
    """Schema-key and type check; also requires sorted event times unless allowed_unknown is set."""
    allowed = set(allowed_unknown)
    assert set(doc) - allowed <= TOP_KEYS, (name, "top-level keys", set(doc) - allowed - TOP_KEYS)
    for ev in doc["events"]:
        assert set(ev) - allowed <= EVENT_KEYS, (name, "event keys", set(ev) - allowed - EVENT_KEYS)
        assert {"timeSec", "pose"} <= set(ev), (name, "event without required keys", ev)
        for k in EVENT_LONG & set(ev):
            assert type(ev[k]) is int and 0 <= ev[k] <= 0xFFFFFFFF, (name, k, ev[k])
    for layer in doc.get("overlayLayers", []):
        assert set(layer) - allowed <= LAYER_KEYS, (name, "layer keys", set(layer) - allowed - LAYER_KEYS)
        assert {"type", "startSec", "endSec"} <= set(layer), (name, "layer without required keys", layer)
        for k in LAYER_LONG & set(layer):
            assert type(layer[k]) is int and 0 <= layer[k] <= 0xFFFFFFFF, (name, k, layer[k])
        for kf in layer.get("anim") or []:
            assert set(kf) <= ANIM_KEYS and "t" in kf, (name, "anim keys", kf)
    if not allowed:
        times = [e["timeSec"] for e in doc["events"]]
        assert times == sorted(times), (name, "event times must be sorted")
        ids = [l["id"] for l in doc.get("overlayLayers", []) if l.get("id")]
        assert len(ids) == len(set(ids)), (name, "duplicate layer ids")


def dumps(doc):
    """One record per line for events and layers: readable and diff-friendly."""
    parts = []
    for key, value in doc.items():
        if isinstance(value, list) and value and isinstance(value[0], dict):
            body = ",\n".join("  " + json.dumps(v, separators=(",", ":"), ensure_ascii=False) for v in value)
            parts.append('"%s":[\n%s\n]' % (key, body))
        else:
            parts.append('"%s":%s' % (key, json.dumps(value, separators=(",", ":"), ensure_ascii=False)))
    return "{\n" + ",\n".join(parts) + "\n}\n"


def f01_minimal():
    return script([{"timeSec": 0.0, "pose": "stand_straight"}, {"timeSec": 2.0, "pose": "wave"}])


def f02_poses_expressions():
    springs = [(280, 28), (120, 12), (400, 40)]
    events, spring_n = [], 0
    for i, pose in enumerate(POSES):
        ev = {"timeSec": r(1.5 * i), "pose": pose, "duration": 0.6, "ease": EASES[i % len(EASES)]}
        if ev["ease"] == "spring":
            ev["springStiffness"], ev["springDamping"] = springs[spring_n % len(springs)]
            spring_n += 1
        if i > 0:
            ev["expression"] = EXPRESSIONS[i % len(EXPRESSIONS)]
        events.append(ev)
    return script(events, blinks=[r(2.0 + 3.7 * k) for k in range(9)])


def f03_camera_scene_captions():
    blue, dark = 0xFF0000FF, 0xFF0D0D14
    events = [
        {"timeSec": 0.0, "pose": "stand_straight", "duration": 0.4, "ease": "ease_out",
         "sceneShape": "mountains", "sceneAtmosphere": "none", "skyColor": 0xFF87CEEB,
         "groundColor": 0xFFE8F4F8, "horizonY": 0.68, "showGroundLine": True,
         "groundLineColor": 0xFF4FC3F7, "groundLineYFraction": 0.8, "cameraZoom": 1.0,
         "caption": "Camera, scene and caption tour", "captionDurationSec": 2.0},
        {"timeSec": 2.5, "pose": "explain", "duration": 0.6, "sceneShape": "city",
         "sceneAtmosphere": "rain", "skyColor": 0xFF16213E, "groundColor": 0xFF0F3443,
         "horizonY": 0.72, "cameraZoom": 1.2, "cameraPanX": -0.06},
        {"timeSec": 5.0, "pose": "present", "duration": 0.6, "ease": "ease_out",
         "sceneShape": "trees", "sceneAtmosphere": "snow", "skyColor": 0xFF9BB7D4,
         "groundColor": 0xFFDCE8F0, "horizonY": 0.7, "cameraZoom": 1.0, "cameraPanX": 0.0,
         "cameraPanY": 0.04, "cameraShake": 0.35,
         "caption": "A longer caption that has to wrap over several lines so the caption fitting "
                    "code is exercised on both renderers",
         "captionDurationSec": 3.5},
        {"timeSec": 7.5, "pose": "think", "duration": 0.8, "sceneShape": "clouds",
         "sceneAtmosphere": "fog", "backgroundStyle": "gradient",
         "backgroundGradientColor": 0xFF283593, "cameraPanY": 0.0, "cameraShake": 0.0},
        {"timeSec": 10.0, "pose": "wave", "duration": 0.6, "ease": "spring", "sceneShape": "room",
         "sceneAtmosphere": "stars", "skyColor": 0xFFF3E5D0, "groundColor": 0xFFC9A876,
         "horizonY": 0.68, "backgroundStyle": "solid", "cameraZoom": 1.8, "cameraPanX": 0.1,
         "cameraPanY": -0.05, "caption": "Caf\u00e9 \u2014 d\u00e9j\u00e0 vu (non-ASCII caption)",
         "captionDurationSec": 2.0},
        {"timeSec": 12.5, "pose": "celebrate", "duration": 0.6, "ease": "elastic_out",
         "expression": "happy", "sceneShape": "beach", "sceneAtmosphere": "none",
         "skyColor": 0xFF1A2744, "groundColor": 0xFF0D1B2A, "horizonY": 0.62, "cameraZoom": 0.8,
         "cameraShake": 0.4, "bgColor": 0xFF3E2723, "caption": "Zoomed out and shaking",
         "captionDurationSec": 1.5},
        {"timeSec": 15.0, "pose": "stand_straight", "duration": 0.8, "sceneShape": "none",
         "sceneAtmosphere": "none", "showGroundLine": False, "cameraZoom": 1.0, "cameraPanX": 0.0,
         "cameraPanY": 0.0, "cameraShake": 0.0},
        {"timeSec": 17.0, "pose": "point_right", "figureX": 0.3, "figureY": 0.55,
         "figureScale": 0.8, "headScale": 1.2, "figureOpacity": 0.6},
        {"timeSec": 19.5, "pose": "point_left", "figureX": 0.7, "figureScale": 1.3,
         "headScale": 0.9, "figureOpacity": 1.0},
        {"timeSec": 22.0, "pose": "stand_straight", "figureX": 0.5, "figureY": 0.52,
         "figureScale": 1.0, "headScale": 1.0},
        {"timeSec": 24.0, "pose": "excited", "boneColor": 0xFFFF7043, "headColor": 0xFFFFCC80,
         "jointColor": 0xFFFFFFFF, "mouthColor": 0xFF3E2723, "eyeColor": 0xFF3E2723,
         "eyebrowColor": 0xFF3E2723, "bgColor": 0xFF1B5E20, "backgroundStyle": "gradient",
         "backgroundGradientColor": 0xFF004D40},
        {"timeSec": 27.0, "pose": "stand_straight", "boneColor": blue, "headColor": blue,
         "jointColor": blue, "mouthColor": dark, "eyeColor": dark, "eyebrowColor": dark,
         "bgColor": 0xFF1A1A2E, "backgroundStyle": "solid", "caption": "Line one\nLine two",
         "captionDurationSec": 2.0},
    ]
    return script(events, blinks=[1.2, 6.4, 11.1, 18.3])


def f04_overlays():
    events = [
        {"timeSec": 0.0, "pose": "stand_straight", "duration": 0.3, "ease": "ease_out", "cameraZoom": 1.0},
        {"timeSec": 6.0, "pose": "explain", "cameraZoom": 1.3, "cameraPanX": 0.05},
        {"timeSec": 12.0, "pose": "present", "cameraZoom": 1.0, "cameraPanX": 0.0, "figureX": 0.4},
        {"timeSec": 16.0, "pose": "wave", "cameraShake": 0.3},
        {"timeSec": 20.0, "pose": "point_self", "cameraShake": 0.0, "figureX": 0.5, "cameraZoom": 1.15},
        {"timeSec": 26.0, "pose": "celebrate", "ease": "elastic_out", "expression": "happy"},
        {"timeSec": 33.0, "pose": "stand_straight"},
    ]
    L = []

    def add(**kw):
        L.append(kw)

    # text
    add(id="t_upper", type="text", text="UPPER SLOT", slot="upper", startSec=0.5, endSec=3.5,
        fontSize=0.09, enterStyle="pop", enterEase="back", enterDuration=0.4, exitStyle="fade")
    add(id="t_center_multi", type="text", text="Line one\nLine two\nLine three", slot="center",
        x=0.15, align="left", fontSize=0.06, startSec=4.0, endSec=8.0, enterStyle="zoom",
        enterEase="elastic_out", exitStyle="slidedown", exitEase="ease_in")
    add(id="t_lower_right", type="text", text="LOWER RIGHT", slot="lower", x=0.85, align="right",
        startSec=8.5, endSec=11.5, enterStyle="slideup", exitStyle="slidedown")
    add(id="t_gradient_glow", type="text", text="GLOW", slot="center", color=0xFFFFEB3B,
        gradientColor=0xFF4FC3F7, glow=True, glowColor=0xFFFFFFFF, glowRadius=0.03,
        startSec=12.0, endSec=15.0, exitStyle="none")
    add(id="t_no_styles", type="text", text="NO STYLES", x=0.5, y=0.2, bold=False, fontSize=0.05,
        startSec=15.5, endSec=17.0, enterStyle="none", exitStyle="none")
    add(id="t_tilted", type="text", text="TILTED", x=0.3, y=0.35, rotationDeg=12.0, scale=1.3,
        opacity=0.8, startSec=17.5, endSec=20.5, enterStyle="slidedown", exitStyle="zoom")
    add(id="t_hud_screen_space", type="text", text="HUD", x=0.12, y=0.08, fontSize=0.04,
        screenSpace=True, startSec=0.0, endSec=34.0, enterStyle="none", exitStyle="none")
    add(id="t_world_space", type="text", text="WORLD", x=0.88, y=0.08, fontSize=0.04,
        startSec=0.0, endSec=34.0, enterStyle="none", exitStyle="none")
    add(id="t_anim", type="text", text="MOVE", fontSize=0.07, startSec=21.0, endSec=25.0, anim=[
        {"t": 0.0, "x": 0.1, "y": 0.3, "scale": 0.5, "opacity": 0.0, "ease": "ease_out"},
        {"t": 1.0, "x": 0.5, "y": 0.3, "scale": 1.0, "opacity": 1.0, "ease": "back"},
        {"t": 2.0, "rotationDeg": 15.0, "ease": "rigid"},
        {"t": 3.0, "x": 0.9, "y": 0.6, "scale": 0.7, "opacity": 0.5, "rotationDeg": 0.0, "ease": "spring"}])
    add(id="t_behind_figure", type="text", text="BEHIND", x=0.5, y=0.5, fontSize=0.2,
        color=0x55FFFFFF, inFrontOfFigure=False, startSec=26.0, endSec=30.0)
    # shapes
    add(id="s_rect", type="shape", shape="rect", slot="lower", width=0.35, height=0.03,
        color=0xFFFF7043, gradientColor=0xFFFFEE58, glow=True, glowRadius=0.015,
        startSec=1.0, endSec=4.0, enterStyle="slideup")
    add(id="s_circle", type="shape", shape="circle", x=0.2, y=0.2, radius=0.04, color=0xFF4FC3F7,
        startSec=4.0, endSec=7.0, exitStyle="pop")
    add(id="s_line", type="shape", shape="line", x=0.5, y=0.15, width=0.4, height=0.006,
        rotationDeg=20.0, startSec=7.0, endSec=10.0, exitStyle="slideup")
    add(id="s_arrow", type="shape", shape="arrow", x=0.7, y=0.7, width=0.2, height=0.08,
        rotationDeg=-30.0, color=0xFFFFD54F, startSec=10.0, endSec=13.0)
    add(id="s_cross", type="shape", shape="cross", x=0.3, y=0.7, width=0.08, height=0.08,
        radius=0.04, color=0xFFFF5252, startSec=13.0, endSec=16.0)
    add(id="s_on_head", type="shape", shape="circle", parentBone="head", x=0.0, y=-0.1,
        radius=0.02, color=0xFFFFF59D, glow=True, startSec=9.0, endSec=14.0)
    add(id="s_on_hand", type="shape", shape="circle", parentBone="lower_arm_r", x=0.0, y=0.0,
        radius=0.018, color=0xFFFFF59D, startSec=1.5, endSec=3.4)
    add(id="s_on_torso", type="shape", shape="rect", parentBone="torso", x=0.0, y=0.0,
        width=0.05, height=0.05, color=0xFF26C6DA, startSec=14.0, endSec=18.0)
    add(id="s_on_foot", type="shape", shape="circle", parentBone="lower_leg_l", x=0.0, y=0.0,
        radius=0.015, color=0xFFFFAB91, startSec=18.0, endSec=22.0)
    add(id="pl_root", type="shape", shape="rect", x=0.6, y=0.4, width=0.1, height=0.1,
        color=0xFF7E57C2, startSec=22.0, endSec=28.0, anim=[
            {"t": 0.0, "x": 0.6, "y": 0.4, "ease": "ease_in_out"}, {"t": 5.0, "x": 0.3, "y": 0.45}])
    add(id="pl_child", type="shape", shape="circle", parentLayer="pl_root", x=0.0, y=-0.08,
        radius=0.02, color=0xFFFFF176, startSec=22.5, endSec=27.5)
    add(id="s_projectile", type="shape", shape="circle", x=0.1, y=0.7, radius=0.02,
        physics="projectile", physicsVx=0.3, physicsVy=-0.5, physicsGravity=1.2,
        color=0xFF69F0AE, startSec=14.0, endSec=17.0)
    add(id="s_bounce_trail", type="shape", shape="circle", x=0.15, y=0.4, radius=0.02,
        physics="bounce", physicsVx=0.35, physicsVy=-0.4, physicsGravity=1.4, physicsFloorY=0.82,
        physicsBounceDamping=0.55, trail=True, trailLengthSec=0.5, color=0xFF4FC3F7,
        startSec=18.0, endSec=22.0, enterStyle="none")
    add(id="s_anim_trail", type="shape", shape="circle", radius=0.02, trail=True, trailLengthSec=0.3,
        color=0xFFFF8A65, startSec=23.0, endSec=27.0, anim=[
            {"t": 0.0, "x": 0.1, "y": 0.2, "ease": "linear"}, {"t": 2.0, "x": 0.9, "y": 0.3, "ease": "ease_out"},
            {"t": 4.0, "x": 0.1, "y": 0.5, "ease": "bounce"}])
    add(id="s_behind_figure", type="shape", shape="rect", x=0.5, y=0.5, width=0.6, height=0.7,
        color=0x33FFFFFF, inFrontOfFigure=False, startSec=28.0, endSec=33.0)
    add(id="s_faded_rotated", type="shape", shape="rect", x=0.8, y=0.3, width=0.15, height=0.15,
        opacity=0.4, rotationDeg=45.0, startSec=29.0, endSec=32.0)
    # particles
    add(id="p_circles", type="particles", particleShape="circle", x=0.5, y=0.35, particleCount=24,
        particleSpeed=0.4, particleGravity=0.9, particleLifetimeSec=1.1, particleSizeMin=0.008,
        particleSizeMax=0.018, color=0xFFFF7043, gradientColor=0xFFFFEE58, startSec=5.0, endSec=6.5)
    add(id="p_rects", type="particles", particleShape="rect", x=0.3, y=0.5, particleCount=40,
        particleSpeed=0.25, particleGravity=0.0, particleLifetimeSec=2.0, color=0xFF4FC3F7,
        startSec=15.0, endSec=18.0)
    add(id="p_few_big", type="particles", particleShape="circle", x=0.7, y=0.6, particleCount=6,
        particleSpeed=0.15, particleGravity=0.3, particleLifetimeSec=1.5, particleSizeMin=0.02,
        particleSizeMax=0.04, color=0xFFE1BEE7, startSec=24.0, endSec=26.0)
    # figures
    add(id="f_wave", type="figure", x=0.8, y=0.62, scale=0.8, pose="wave", expression="happy",
        color=0xFFFFA726, startSec=3.6, endSec=7.5)
    add(id="f_think", type="figure", x=0.2, y=0.62, scale=0.5, pose="think", expression="worried",
        color=0xFF80CBC4, startSec=10.0, endSec=14.0, enterStyle="pop", exitStyle="zoom")
    add(id="f_moving", type="figure", x=0.5, y=0.7, scale=0.6, rotationDeg=20.0, opacity=0.7,
        pose="jump", color=0xFFCE93D8, startSec=20.0, endSec=26.0, anim=[
            {"t": 0.0, "x": 0.2, "ease": "ease_in_out"}, {"t": 5.0, "x": 0.8}])
    return script(events, layers=L)


def f05_sound_effects():
    cues = [
        (0.0, "stand_straight", "select", 1.0), (1.0, "wave", "click", 1.0),
        (2.0, "think", "confirm", 0.8), (3.0, "explain", "error", 0.4),
        (4.0, "present", "glitch", 1.0), (5.0, "point_self", "drop", 0.6),
        (6.0, "open_hands", "tick", 1.0), (7.0, "shrug", "toggle", 0.9),
        (8.0, "excited", "bong", 1.0),
        (9.0, "confused", "custom_boom", 0.9),    # id from the project's own library
        (10.0, "tired", "unknown_sfx_id", 1.0),   # in no library: dropped silently at export (B1)
        (11.0, "celebrate", "pluck", 1.5),        # volume above 1.0: preview clamps, export does not (B1)
        (12.0, "sit", "switch", 0.0),             # a silent cue
    ]
    events = [{"timeSec": t, "pose": p, "duration": 0.5, "soundEffect": s, "soundEffectVolume": v}
              for t, p, s, v in cues]
    events += [
        {"timeSec": 13.5, "pose": "jump", "soundEffect": "drop"},   # two cues at the same timeSec
        {"timeSec": 13.5, "pose": "celebrate", "soundEffect": "open", "soundEffectVolume": 0.7},
        {"timeSec": 15.0, "pose": "point_up", "soundEffect": "question"},
        {"timeSec": 16.5, "pose": "stand_straight", "soundEffect": "close", "soundEffectVolume": 0.5},
        {"timeSec": 18.0, "pose": "stand_straight"},
    ]
    return script(events, blinks=[2.2, 7.9])


def f06_long_dense():
    rng = random.Random(20261010)
    captions = ["Here is the first thing to know.", "Watch what happens next.", "That changes everything.",
                "Three quick points.", "And this is the surprising part.", "Remember this one.",
                "Let us look closer.", "Now the other side.", "Numbers do not lie.", "Here is the catch.",
                "So what do we do?", "That is the whole idea."]
    words = ["FIRST", "NEXT", "WHY?", "BIG IDEA", "KEY POINT", "REMEMBER", "NOW", "LOOK", "THE CATCH",
             "RESULT", "TIP", "BEFORE", "AFTER", "FACT", "MYTH", "DONE"]
    events, t = [], 0.0
    while t < 596.0:
        ev = {"timeSec": r(t), "pose": rng.choice(POSES), "duration": rng.choice([0.3, 0.4, 0.5, 0.6, 0.8, 1.0]),
              "ease": rng.choice(EASES)}
        if ev["ease"] == "spring" and rng.random() < 0.5:
            ev["springStiffness"] = rng.choice([120, 200, 280, 400])
            ev["springDamping"] = rng.choice([10, 16, 28, 40])
        if rng.random() < 0.45:
            ev["expression"] = rng.choice(EXPRESSIONS)
        if rng.random() < 0.12:
            ev["cameraZoom"] = r(rng.uniform(0.9, 1.4))
            if rng.random() < 0.5:
                ev["cameraPanX"] = r(rng.uniform(-0.1, 0.1))
                ev["cameraPanY"] = r(rng.uniform(-0.06, 0.06))
        if rng.random() < 0.05:
            ev["cameraShake"] = r(rng.uniform(0.1, 0.5))
        if rng.random() < 0.15:
            ev["caption"] = rng.choice(captions)
            ev["captionDurationSec"] = rng.choice([1.5, 2.0, 2.5, 3.0])
        if rng.random() < 0.05:
            ev["sceneShape"] = rng.choice(SCENE_SHAPES)
            ev["sceneAtmosphere"] = rng.choice(ATMOSPHERES)
            ev["skyColor"] = rng.choice(SKY)
            ev["groundColor"] = rng.choice(GROUND)
            ev["horizonY"] = r(rng.uniform(0.6, 0.75))
        if rng.random() < 0.04:
            ev["figureX"] = r(rng.uniform(0.35, 0.65))
            ev["figureScale"] = r(rng.uniform(0.85, 1.2))
        if rng.random() < 0.06:
            ev["soundEffect"] = rng.choice(SFX)
            ev["soundEffectVolume"] = rng.choice([0.5, 0.8, 1.0])
        events.append(ev)
        t += rng.choice([1.2, 1.8, 2.2, 2.6, 3.0, 3.4])
    blinks, b = [], 1.5
    while b < 596.0:
        blinks.append(r(b))
        b += rng.choice([2.6, 3.4, 4.2, 5.0])
    layers = []
    for i in range(58):
        s = r(4.0 + i * 10.1)
        layer = {"id": "txt_%03d" % i, "type": "text", "text": rng.choice(words), "startSec": s,
                 "endSec": r(s + rng.choice([1.8, 2.4, 3.0])), "slot": ["upper", "center", "lower"][i % 3],
                 "fontSize": rng.choice([0.05, 0.07, 0.09, 0.11]), "color": rng.choice(PALETTE),
                 "enterStyle": rng.choice(STYLES), "exitStyle": rng.choice(STYLES)}
        if rng.random() < 0.3:
            layer["glow"] = True
            layer["glowRadius"] = 0.02
        if rng.random() < 0.3:
            layer["gradientColor"] = rng.choice(PALETTE)
        if i % 4 == 1:
            layer["align"] = "left"
            layer["x"] = 0.12
        layers.append(layer)
    for i in range(40):
        s = r(8.0 + i * 14.6)
        kind = rng.choice(["rect", "circle", "line", "arrow", "cross"])
        layer = {"id": "shp_%03d" % i, "type": "shape", "shape": kind, "startSec": s,
                 "endSec": r(s + rng.choice([2.0, 3.0, 4.0])), "x": r(rng.uniform(0.15, 0.85)),
                 "y": r(rng.uniform(0.15, 0.4)), "color": rng.choice(PALETTE)}
        if kind == "circle":
            layer["radius"] = r(rng.uniform(0.015, 0.05))
        else:
            layer["width"] = r(rng.uniform(0.05, 0.3))
            layer["height"] = r(rng.uniform(0.01, 0.1))
        if rng.random() < 0.3:
            layer["rotationDeg"] = r(rng.uniform(-45, 45))
        if rng.random() < 0.25:
            layer["glow"] = True
        if i % 5 == 2:
            layer["parentBone"] = rng.choice(BONES)
            layer["x"] = 0.0
            layer["y"] = 0.0
        elif i % 7 == 3:
            layer["anim"] = [{"t": 0.0, "scale": 0.6, "ease": "ease_out"},
                             {"t": r(layer["endSec"] - s), "scale": 1.0, "ease": "back"}]
        layers.append(layer)
    for i in range(14):
        s = r(12.0 + i * 41.3)
        layers.append({"id": "prt_%03d" % i, "type": "particles", "particleShape": rng.choice(["circle", "rect"]),
                       "startSec": s, "endSec": r(s + 1.5), "x": r(rng.uniform(0.3, 0.7)), "y": 0.4,
                       "particleCount": rng.choice([12, 24, 40]), "particleSpeed": 0.3, "particleGravity": 0.6,
                       "particleLifetimeSec": 1.2, "color": rng.choice(PALETTE)})
    for i in range(11):
        s = r(20.0 + i * 52.5)
        layers.append({"id": "fig_%03d" % i, "type": "figure", "startSec": s, "endSec": r(s + 3.0),
                       "x": rng.choice([0.2, 0.8]), "y": 0.62, "scale": 0.6, "pose": rng.choice(POSES),
                       "expression": rng.choice(EXPRESSIONS), "color": rng.choice(PALETTE)})
    for i in range(8):
        s = r(30.0 + i * 70.3)
        layers.append({"id": "phy_%03d" % i, "type": "shape", "shape": "circle", "radius": 0.02, "startSec": s,
                       "endSec": r(s + 3.0), "x": 0.15, "y": 0.4, "physics": "bounce", "physicsVx": 0.35,
                       "physicsVy": -0.4, "physicsGravity": 1.4, "physicsFloorY": 0.82, "trail": True,
                       "trailLengthSec": 0.3, "color": 0xFF4FC3F7})
    return script(events, blinks=blinks, layers=layers)


def f07_edge_cases():
    """Everything the validator warns about, plus unknown JSON keys (ignoreUnknownKeys), in one script."""
    events = [
        {"timeSec": 0.0, "pose": "stand_straight", "duration": 0.3, "ease": "ease_out", "futureEventField": 7},
        {"timeSec": 1.0, "pose": "moonwalk", "expression": "angry", "cameraZoom": 1.3,
         "caption": "event with an unknown pose"},
        {"timeSec": 2.0, "pose": "wave", "duration": 0.6, "ease": "wobble"},
        {"timeSec": 3.0, "pose": "explain", "sceneShape": "castle", "sceneAtmosphere": "meteors",
         "backgroundStyle": "plaid"},
        {"timeSec": 4.0, "pose": "wave", "figureX": 1.2},
        {"timeSec": 5.0, "pose": "think", "duration": 2.0},
        {"timeSec": 5.5, "pose": "present", "duration": 2.0},
        {"timeSec": 8.0, "pose": "wave"},
        {"timeSec": 8.0, "pose": "think", "soundEffect": "nowhere_to_be_found"},
        {"timeSec": 11.0, "pose": "stand_straight"},
        {"timeSec": 10.5, "pose": "celebrate"},
    ]
    layers = [
        {"id": "e_type", "type": "hologram", "startSec": 0.0, "endSec": 2.0},
        {"id": "e_shape", "type": "shape", "shape": "star", "startSec": 0.0, "endSec": 2.0, "x": 0.2, "y": 0.2,
         "width": 0.1, "height": 0.1},
        {"id": "e_styles", "type": "text", "text": "styles", "startSec": 1.0, "endSec": 3.0, "x": 0.8, "y": 0.8,
         "enterStyle": "spiral", "exitStyle": "implode", "enterEase": "wobble", "exitEase": "wobble2"},
        {"id": "e_slot", "type": "text", "text": "slot", "startSec": 4.0, "endSec": 6.0, "slot": "middle",
         "x": 0.1, "y": 0.9},
        {"id": "e_kf", "type": "shape", "shape": "circle", "radius": 0.03, "startSec": 3.0, "endSec": 6.0,
         "x": 0.9, "y": 0.1, "anim": [{"t": 0.0, "x": 0.9, "ease": "zigzag"}, {"t": 2.0, "x": 0.1}]},
        {"id": "e_particles", "type": "particles", "particleShape": "star", "startSec": 6.5, "endSec": 8.0,
         "x": 0.5, "y": 0.5},
        {"id": "e_physics", "type": "shape", "shape": "circle", "radius": 0.02, "physics": "orbit",
         "startSec": 7.0, "endSec": 9.0, "x": 0.4, "y": 0.4},
        {"id": "e_bone", "type": "shape", "shape": "circle", "radius": 0.02, "parentBone": "tail",
         "startSec": 9.0, "endSec": 10.0},
        {"id": "e_both", "type": "shape", "shape": "circle", "radius": 0.02, "parentBone": "head",
         "parentLayer": "e_bone", "startSec": 9.0, "endSec": 10.0},
        {"id": "e_ghost", "type": "shape", "shape": "circle", "radius": 0.02, "parentLayer": "ghost",
         "startSec": 10.0, "endSec": 11.0, "x": 0.6, "y": 0.2},
        {"id": "e_cycle_a", "type": "shape", "shape": "rect", "width": 0.05, "height": 0.05,
         "parentLayer": "e_cycle_b", "startSec": 11.0, "endSec": 12.0, "x": 0.3, "y": 0.3},
        {"id": "e_cycle_b", "type": "shape", "shape": "rect", "width": 0.05, "height": 0.05,
         "parentLayer": "e_cycle_a", "startSec": 11.0, "endSec": 12.0, "x": 0.7, "y": 0.7},
        {"id": "e_never", "type": "text", "text": "never visible", "startSec": 5.0, "endSec": 5.0,
         "x": 0.5, "y": 0.95},
        {"id": "e_fig", "type": "figure", "pose": "custom_pose", "startSec": 2.0, "endSec": 4.0, "x": 0.8,
         "y": 0.6, "scale": 0.5, "futureLayerField": "ignored"},
        {"id": "e_text_null", "type": "text", "startSec": 6.0, "endSec": 7.0},
    ]
    return script(events, blinks=[3.0, 1.0, 1.0], layers=layers, extra={"futureTopLevel": {"a": 1}})


FIXTURES = [
    ("01_minimal", f01_minimal, ()),
    ("02_poses_expressions", f02_poses_expressions, ()),
    ("03_camera_scene_captions", f03_camera_scene_captions, ()),
    ("04_overlays", f04_overlays, ()),
    ("05_sound_effects", f05_sound_effects, ()),
    ("06_long_dense", f06_long_dense, ()),
    ("07_edge_cases", f07_edge_cases, ("futureEventField", "futureLayerField", "futureTopLevel")),
]


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, build, allowed in FIXTURES:
        doc = build()
        check(name, doc, allowed)
        text = dumps(doc)
        json.loads(text)  # must be valid JSON
        with open(os.path.join(OUT, name + ".json"), "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        print("%-28s %7d bytes  %3d events  %3d layers  %3d blinks" % (
            name + ".json", len(text.encode("utf-8")), len(doc["events"]),
            len(doc.get("overlayLayers", [])), len(doc.get("blinkEvents", []))))


if __name__ == "__main__":
    main()
