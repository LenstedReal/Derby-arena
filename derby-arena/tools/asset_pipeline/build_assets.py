"""Derby Arena asset pipeline.

Bakes every runtime GLB (vehicles, arena, props, weapons, crowd, vfx) with procedural PBR
textures (baseColor / metallicRoughness / normal / emissive) and exports the arena collision
layout consumed by ArenaSystem.

Run:  python3 tools/asset_pipeline/build_assets.py
Validation (material / scale / pivot / bounds / node names) runs automatically afterwards.
"""
import json
import math
import os
import random
import struct
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(__file__))
from glb_writer import GlbDocument, MeshBuilder, fbm, normal_from_height, pack_mr, quat_from_euler, rot_x, rot_y, rot_z  # noqa

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets", "models")
TEX = 512
WORLD_SCALE = 0.04  # game units -> meters (must match GpuStateSynchronizer.WORLD_SCALE)
PLAYABLE_RADIUS_M = 700 * WORLD_SCALE  # 28 m
random.seed(7)


def out(sub, name):
    d = os.path.join(ASSETS, sub)
    os.makedirs(d, exist_ok=True)
    return os.path.join(d, name)


# ----------------------------------------------------------------------------- texture sets
def paint_textures(doc, tag, paint_rgb, rust_amount=0.45, seed=1):
    """Rusty / chipped / dusty vehicle paint. Returns (base, mr, normal) texture indices."""
    n1 = fbm(TEX, 6, seed, 0.55, 4)
    n2 = fbm(TEX, 4, seed + 11, 0.5, 16)
    scratches = (fbm(TEX, 3, seed + 23, 0.6, 64) > 0.66).astype(np.float32)
    rust_mask = np.clip((n1 - (1.0 - rust_amount)) * 4.0, 0, 1)
    rust_mask = np.maximum(rust_mask, scratches * 0.6)
    dust = fbm(TEX, 3, seed + 5, 0.5, 8) * 0.25
    paint = np.array(paint_rgb, np.float32)[None, None, :] * (0.85 + n2[..., None] * 0.3)
    rust = np.array([0.36, 0.17, 0.08], np.float32)[None, None, :] * (0.7 + n2[..., None] * 0.6)
    base = paint * (1 - rust_mask[..., None]) + rust * rust_mask[..., None]
    base = base * (1 - dust[..., None]) + np.array([0.45, 0.40, 0.33])[None, None, :] * dust[..., None]
    base_u8 = np.clip(base * 255, 0, 255).astype(np.uint8)
    rough = 0.42 + n2 * 0.18 + rust_mask * 0.45 + dust * 0.8
    metal = 0.75 * (1 - rust_mask) + 0.15 * rust_mask
    mr = pack_mr(np.clip(metal, 0, 1), np.clip(rough, 0, 1))
    height = n1 * 0.6 + rust_mask * 0.4 + scratches * 0.3
    nrm = normal_from_height(height, 3.0)
    return (doc.add_texture(base_u8, f"{tag}_base"), doc.add_texture(mr, f"{tag}_mr"), doc.add_texture(nrm, f"{tag}_nrm"))


def stone_textures(doc, tag, tint=(0.62, 0.58, 0.50), seed=3):
    n1 = fbm(TEX, 6, seed, 0.55, 6)
    cracks = (np.abs(fbm(TEX, 4, seed + 9, 0.6, 12) - 0.5) < 0.012).astype(np.float32)
    blocks = (((np.arange(TEX)[:, None] // 64) + (np.arange(TEX)[None, :] // 128)) % 2) * 0.06
    mortar = ((np.arange(TEX)[:, None] % 64 < 3) | (np.arange(TEX)[None, :] % 128 < 3)).astype(np.float32)
    shade = 0.75 + n1 * 0.45 + blocks - mortar * 0.35 - cracks * 0.5
    base = np.array(tint, np.float32)[None, None, :] * shade[..., None]
    base_u8 = np.clip(base * 255, 0, 255).astype(np.uint8)
    mr = pack_mr(np.zeros((TEX, TEX), np.float32), np.clip(0.82 + n1 * 0.15, 0, 1))
    nrm = normal_from_height(np.clip(n1 - mortar * 0.5 - cracks * 0.6, 0, 1), 2.5)
    return (doc.add_texture(base_u8, f"{tag}_base"), doc.add_texture(mr, f"{tag}_mr"), doc.add_texture(nrm, f"{tag}_nrm"))


def sand_textures(doc, tag, seed=4):
    n1 = fbm(TEX, 6, seed, 0.5, 5)
    n2 = fbm(TEX, 5, seed + 3, 0.5, 40)
    yy = np.linspace(0, 2 * math.pi, TEX, endpoint=False)
    streaks = np.clip(np.sin(yy * 9)[None, :] * np.sin(yy * 3)[:, None] * fbm(TEX, 3, seed + 7, 0.5, 8) * 3 - 1.2, 0, 1)
    tint = np.array([0.56, 0.47, 0.34], np.float32)
    dirt = np.array([0.30, 0.25, 0.19], np.float32)
    base = tint[None, None, :] * (0.7 + n1[..., None] * 0.5 + n2[..., None] * 0.15)
    base = base * (1 - streaks[..., None] * 0.7) + dirt[None, None, :] * streaks[..., None] * 0.7
    base_u8 = np.clip(base * 255, 0, 255).astype(np.uint8)
    mr = pack_mr(np.zeros((TEX, TEX), np.float32), np.clip(0.88 + n2 * 0.1, 0, 1))
    nrm = normal_from_height(n1 * 0.7 + n2 * 0.3, 2.2)
    return (doc.add_texture(base_u8, f"{tag}_base"), doc.add_texture(mr, f"{tag}_mr"), doc.add_texture(nrm, f"{tag}_nrm"))


def metal_hazard_textures(doc, tag, seed=5):
    n1 = fbm(TEX, 5, seed, 0.55, 8)
    rust = np.clip((fbm(TEX, 5, seed + 1, 0.5, 5) - 0.55) * 4, 0, 1)
    diag = (((np.arange(TEX)[:, None] + np.arange(TEX)[None, :]) // 48) % 2).astype(np.float32)
    band = (np.arange(TEX)[:, None] > TEX * 0.62).astype(np.float32)
    grey = np.array([0.32, 0.33, 0.34], np.float32)[None, None, :] * (0.8 + n1[..., None] * 0.4)
    yellow = np.array([0.85, 0.65, 0.10], np.float32)[None, None, :] * (0.8 + n1[..., None] * 0.3)
    black = np.array([0.06, 0.06, 0.06], np.float32)[None, None, :]
    stripes = yellow * diag[..., None] + black * (1 - diag[..., None])
    base = grey * (1 - band[..., None]) + stripes * band[..., None]
    rust_c = np.array([0.35, 0.16, 0.07], np.float32)[None, None, :]
    base = base * (1 - rust[..., None]) + rust_c * rust[..., None]
    base_u8 = np.clip(base * 255, 0, 255).astype(np.uint8)
    mr = pack_mr(np.clip(0.85 - rust * 0.7, 0, 1), np.clip(0.4 + n1 * 0.2 + rust * 0.5, 0, 1))
    nrm = normal_from_height(n1 * 0.5 + rust * 0.5, 2.0)
    return (doc.add_texture(base_u8, f"{tag}_base"), doc.add_texture(mr, f"{tag}_mr"), doc.add_texture(nrm, f"{tag}_nrm"))


def cloth_textures(doc, tag, rgb=(0.55, 0.06, 0.05), seed=6):
    n1 = fbm(TEX, 5, seed, 0.5, 6)
    weave = ((np.arange(TEX)[:, None] % 4 < 2) ^ (np.arange(TEX)[None, :] % 4 < 2)).astype(np.float32) * 0.06
    emblem = ((np.abs(np.arange(TEX)[None, :] - TEX / 2) < TEX * 0.16) & (np.abs(np.arange(TEX)[:, None] - TEX / 2) < TEX * 0.16)).astype(np.float32)
    ring = emblem * ((np.hypot(np.arange(TEX)[None, :] - TEX / 2, np.arange(TEX)[:, None] - TEX / 2) > TEX * 0.11)).astype(np.float32)
    base = np.array(rgb, np.float32)[None, None, :] * (0.7 + n1[..., None] * 0.5 + weave[..., None])
    gold = np.array([0.7, 0.55, 0.2], np.float32)[None, None, :]
    base = base * (1 - ring[..., None]) + gold * ring[..., None]
    tear = (fbm(TEX, 3, seed + 2, 0.5, 10) > 0.7).astype(np.float32) * (np.arange(TEX)[:, None] > TEX * 0.8)
    base = base * (1 - tear[..., None] * 0.8)
    base_u8 = np.clip(base * 255, 0, 255).astype(np.uint8)
    mr = pack_mr(np.zeros((TEX, TEX), np.float32), np.full((TEX, TEX), 0.92, np.float32))
    return (doc.add_texture(base_u8, f"{tag}_base"), doc.add_texture(mr, f"{tag}_mr"), None)


def rubber_textures(doc, tag, seed=8):
    n = fbm(TEX, 5, seed, 0.5, 20)
    tread = ((np.arange(TEX)[None, :] // 16) % 2).astype(np.float32) * 0.08
    base = np.array([0.05, 0.05, 0.055], np.float32)[None, None, :] * (0.7 + n[..., None] * 0.6 + tread[..., None])
    base_u8 = np.clip(base * 255, 0, 255).astype(np.uint8)
    mr = pack_mr(np.zeros((TEX, TEX), np.float32), np.clip(0.9 + n * 0.1, 0, 1))
    nrm = normal_from_height(n * 0.4 + tread * 3, 2.0)
    return (doc.add_texture(base_u8, f"{tag}_base"), doc.add_texture(mr, f"{tag}_mr"), doc.add_texture(nrm, f"{tag}_nrm"))


def textured(doc, name, texset, **kw):
    base, mr, nrm = texset
    return doc.add_material(name, base_tex=base, mr_tex=mr, normal_tex=nrm, metallic=1.0, roughness=1.0, **kw)


# ----------------------------------------------------------------------------- vehicle
def build_vehicle(doc, prefix, paint_rgb, rust=0.45, boss=False, wreck=False, seed=1, glass_alpha=0.55):
    """Detailed heavy muscle car. Returns dict(node indices) with named nodes:
    Body(root child), Hood, Wheel_FL/FR/RL/RR, Turret, Barrel."""
    tag = f"{prefix}_paint"
    if wreck:
        paint = textured(doc, "WreckBurntPaint", paint_textures(doc, "wreck", (0.12, 0.10, 0.09), 0.85, 77)) if "WreckBurntPaint" not in doc.material_index else doc.material_index["WreckBurntPaint"]
    else:
        paint = textured(doc, tag, paint_textures(doc, tag, paint_rgb, rust, seed))
    dark = doc.add_material(f"{prefix}_dark_steel", base_color=(0.10, 0.10, 0.11, 1), metallic=0.85, roughness=0.55)
    chrome = doc.add_material(f"{prefix}_chrome", base_color=(0.75, 0.74, 0.70, 1), metallic=1.0, roughness=0.22)
    rubber = textured(doc, "VehicleRubber", rubber_textures(doc, "vehicle_rubber", 9)) if "VehicleRubber" not in doc.material_index else doc.material_index["VehicleRubber"]
    if wreck:
        glass = doc.add_material(f"{prefix}_glass", base_color=(0.05, 0.05, 0.05, 1), metallic=0.2, roughness=0.9)
        head = doc.add_material(f"{prefix}_head", base_color=(0.1, 0.1, 0.1, 1), metallic=0.3, roughness=0.8)
        tail = head
    else:
        glass = doc.add_material(f"{prefix}_glass", base_color=(0.10, 0.14, 0.15, glass_alpha), metallic=0.0, roughness=0.08, alpha_mode="BLEND")
        head = doc.add_material(f"{prefix}_headlight", base_color=(0.9, 0.9, 0.85, 1), metallic=0.0, roughness=0.2, emissive=(1.0, 0.92, 0.75), emissive_strength=6.0)
        tail = doc.add_material(f"{prefix}_taillight", base_color=(0.6, 0.05, 0.03, 1), metallic=0.0, roughness=0.3, emissive=(1.0, 0.08, 0.04), emissive_strength=4.0)
    s = 1.22 if boss else 1.0

    body = MeshBuilder()
    # main hull
    body.box(paint, (0, 0.56 * s, 0), (1.95 * s, 0.44 * s, 4.6 * s), uv_scale=0.4)
    body.box(dark, (0, 0.36 * s, 0), (2.0 * s, 0.14 * s, 4.15 * s), uv_scale=0.4)  # rocker/sills
    # cabin with sloped pillars approximated by a slightly narrower upper shell
    body.box(paint, (0, 1.06 * s, 0.18 * s), (1.62 * s, 0.52 * s, 1.95 * s), uv_scale=0.4)
    body.box(paint, (0, 1.30 * s, 0.18 * s), (1.5 * s, 0.06 * s, 1.75 * s), uv_scale=0.4)  # roof skin
    # trunk deck + spoiler
    body.box(paint, (0, 0.84 * s, 1.72 * s), (1.82 * s, 0.14 * s, 1.05 * s), uv_scale=0.4)
    body.box(dark, (0, 1.02 * s, 2.22 * s), (1.75 * s, 0.05 * s, 0.38 * s))
    for x in (-0.7, 0.7):
        body.box(dark, (x * s, 0.94 * s, 2.22 * s), (0.08 * s, 0.14 * s, 0.25 * s))
    # fenders (top half cylinders) over wheels
    for x in (-0.97, 0.97):
        for z in (-1.45, 1.45):
            body.cylinder(paint, (x * s, 0.44 * s, z * s), 0.52 * s, 0.52 * s, 0.36 * s, 14, axis="x",
                          caps=False, theta_start=0, theta_len=math.pi, uv_scale=0.4, open_inside=True)
    # glass
    body.box(glass, (0, 1.05 * s, -0.86 * s), (1.5 * s, 0.5 * s, 0.05 * s), rot=rot_x(-26))
    body.box(glass, (0, 1.05 * s, 1.18 * s), (1.45 * s, 0.46 * s, 0.05 * s), rot=rot_x(24))
    for x in (-0.82, 0.82):
        body.box(glass, (x * s, 1.09 * s, 0.18 * s), (0.04 * s, 0.36 * s, 1.5 * s))
    # front: bumper, ram bars, grille, lights
    body.box(dark, (0, 0.42 * s, -2.42 * s), (2.02 * s, 0.24 * s, 0.18 * s))
    for x in (-0.55, 0, 0.55):
        body.box(dark, (x * s, 0.66 * s, -2.46 * s), (0.08 * s, 0.56 * s, 0.08 * s))
    body.box(dark, (0, 0.9 * s, -2.46 * s), (1.5 * s, 0.08 * s, 0.08 * s))
    body.box(dark, (0, 0.64 * s, -2.32 * s), (1.25 * s, 0.3 * s, 0.05 * s))
    for i in range(4):
        body.box(chrome, (0, (0.53 + i * 0.07) * s, -2.345 * s), (1.2 * s, 0.02 * s, 0.02 * s))
    for x in (-0.72, 0.72):
        body.box(head, (x * s, 0.66 * s, -2.335 * s), (0.3 * s, 0.17 * s, 0.05 * s))
        body.box(tail, (x * s, 0.72 * s, 2.325 * s), (0.5 * s, 0.12 * s, 0.05 * s))
    # rear bumper, exhausts
    body.box(dark, (0, 0.42 * s, 2.42 * s), (2.02 * s, 0.22 * s, 0.16 * s))
    for x in (-0.55, 0.55):
        body.cylinder(chrome, (x * s, 0.3 * s, 2.5 * s), 0.065 * s, 0.065 * s, 0.34 * s, 10, axis="z")
    # roof rack / cage bars, door seams, mirrors
    for x in (-0.72, 0.72):
        body.box(dark, (x * s, 1.34 * s, 0.18 * s), (0.06 * s, 0.06 * s, 1.7 * s))
        body.box(dark, (x * s * 1.42, 1.0 * s, -0.7 * s), (0.18 * s, 0.1 * s, 0.14 * s))  # mirrors
        body.box(dark, (x * s * 1.36, 0.62 * s, -0.25 * s), (0.02 * s, 0.4 * s, 0.03 * s))  # door seam
        body.box(dark, (x * s * 1.36, 0.62 * s, 0.95 * s), (0.02 * s, 0.4 * s, 0.03 * s))
    # suspension: axles, shocks, diff
    for z in (-1.45, 1.45):
        body.box(dark, (0, 0.42 * s, z * s), (1.75 * s, 0.09 * s, 0.09 * s))
        for x in (-0.7, 0.7):
            body.cylinder(chrome, (x * s, 0.52 * s, z * s), 0.035 * s, 0.035 * s, 0.26 * s, 8, axis="y")
    body.cylinder(dark, (0, 0.4 * s, 0.9 * s), 0.14 * s, 0.14 * s, 0.4 * s, 10, axis="z")
    if boss:
        for x in (-1.02, 1.02):
            body.box(dark, (x * s, 0.7 * s, 0), (0.12 * s, 0.5 * s, 3.2 * s))  # armor plates
        for z in (-1.0, 0.0, 1.0):
            for x in (-1.1, 1.1):
                body.cylinder(chrome, (x * s, 0.7 * s, z * s), 0.0, 0.08 * s, 0.4 * s, 6, axis="x")  # spikes
    body_mesh = doc.add_mesh(body, f"{prefix}_Body")

    # hood (pivot at rear edge for damage-state popping)
    hood = MeshBuilder()
    hood.box(paint, (0, 0.03 * s, -0.8 * s), (1.72 * s, 0.07 * s, 1.58 * s), uv_scale=0.4)
    hood.box(paint, (0, 0.09 * s, -0.75 * s), (0.62 * s, 0.07 * s, 1.1 * s), uv_scale=0.4)  # power bulge
    hood.box(dark, (0, 0.14 * s, -0.55 * s), (0.36 * s, 0.05 * s, 0.4 * s))  # air scoop
    hood_mesh = doc.add_mesh(hood, f"{prefix}_Hood")

    # wheel
    wheel = MeshBuilder()
    wheel.cylinder(rubber, (0, 0, 0), 0.43 * s, 0.43 * s, 0.32 * s, 22, axis="x", uv_scale=0.6)
    wheel.cylinder(chrome, (0, 0, 0), 0.27 * s, 0.27 * s, 0.34 * s, 16, axis="x")
    wheel.cylinder(dark, (0, 0, 0), 0.08 * s, 0.08 * s, 0.38 * s, 8, axis="x")
    for k in range(5):
        wheel.box(dark, (0, 0, 0), (0.36 * s, 0.05 * s, 0.22 * s), rot=rot_x(k * 72))
    wheel_mesh = doc.add_mesh(wheel, f"{prefix}_Wheel")

    # turret + barrel
    tur = MeshBuilder()
    tur.cylinder(dark, (0, 0.05 * s, 0), 0.34 * s, 0.34 * s, 0.1 * s, 18, axis="y")
    tur.box(dark, (0, 0.24 * s, 0), (0.52 * s, 0.3 * s, 0.62 * s))
    tur.box(paint, (0.33 * s, 0.24 * s, 0.1 * s), (0.14 * s, 0.24 * s, 0.32 * s), uv_scale=0.6)  # ammo box
    tur.box(dark, (0, 0.42 * s, -0.1 * s), (0.2 * s, 0.06 * s, 0.3 * s))  # sight
    turret_mesh = doc.add_mesh(tur, f"{prefix}_Turret")
    bar = MeshBuilder()
    barrels = ((0.0,),) if not boss else ((-0.12,), (0.12,))
    for (bx,) in barrels:
        bar.cylinder(dark, (bx * s, 0, -0.6 * s), 0.055 * s, 0.06 * s, 1.05 * s, 12, axis="z")
        bar.box(dark, (bx * s, 0, -1.16 * s), (0.13 * s, 0.13 * s, 0.16 * s))
    barrel_mesh = doc.add_mesh(bar, f"{prefix}_Barrel")

    # nodes
    n_body = doc.add_node(f"{prefix}_Body", body_mesh, root=False)
    n_hood = doc.add_node("Hood", hood_mesh, translation=(0, 0.78 * s, -0.5 * s), root=False)
    wheels = []
    for name, x, z in (("Wheel_FL", -0.98, -1.45), ("Wheel_FR", 0.98, -1.45), ("Wheel_RL", -0.98, 1.45), ("Wheel_RR", 0.98, 1.45)):
        wheels.append(doc.add_node(name, wheel_mesh, translation=(x * s, 0.43 * s, z * s), root=False))
    n_barrel = doc.add_node("Barrel", barrel_mesh, translation=(0, 0.24 * s, -0.3 * s), root=False)
    n_turret = doc.add_node("Turret", turret_mesh, translation=(0, 1.33 * s, 0.05 * s), children=[n_barrel], root=False)
    root = doc.add_node(f"{prefix}_Root", children=[n_body, n_hood, *wheels, n_turret])
    return {"root": root, "hood": n_hood, "turret": n_turret, "barrel": n_barrel}


def export_vehicle(filename, prefix, paint, rust=0.45, boss=False, seed=1):
    doc = GlbDocument()
    build_vehicle(doc, prefix, paint, rust, boss, seed=seed)
    size = doc.write(out("vehicles", filename))
    print(f"  vehicles/{filename}: {size/1024:.1f} KB, nodes={len(doc.nodes)}, materials={len(doc.materials)}")


# ----------------------------------------------------------------------------- arena
def build_arena():
    doc = GlbDocument()
    sand = textured(doc, "ArenaSandFloor", sand_textures(doc, "sand"))
    stone = textured(doc, "RomanLimestone", stone_textures(doc, "stone"))
    dark_stone = textured(doc, "WeatheredBasalt", stone_textures(doc, "basalt", (0.30, 0.29, 0.27), 13))
    metal = textured(doc, "HazardBarrierSteel", metal_hazard_textures(doc, "metal"))
    cloth = textured(doc, "ImperialCrimsonBanner", cloth_textures(doc, "cloth"), double_sided=True)
    concrete = textured(doc, "CrackedConcrete", stone_textures(doc, "concrete", (0.48, 0.47, 0.44), 21))
    opening = doc.add_material("ArchShadowVoid", base_color=(0.02, 0.02, 0.02, 1), metallic=0.0, roughness=1.0)
    ruin = doc.add_material("DistantRuinConcrete", base_color=(0.22, 0.22, 0.24, 1), metallic=0.0, roughness=1.0)
    fire = doc.add_material("FireEmissive", base_color=(1.0, 0.45, 0.1, 1), metallic=0, roughness=1, emissive=(1.0, 0.35, 0.05), emissive_strength=8.0)
    beacon = doc.add_material("BeaconEmissive", base_color=(1.0, 0.2, 0.05, 1), metallic=0, roughness=1, emissive=(1.0, 0.1, 0.02), emissive_strength=10.0)
    rubber = textured(doc, "TireStackRubber", rubber_textures(doc, "arena_rubber", 31))

    layout = []  # collision circles in GAME units

    def add_collider(kind, xm, zm, radius_m, height_m=1.0):
        layout.append({"type": kind, "x": round(xm / WORLD_SCALE, 1), "y": round(zm / WORLD_SCALE, 1),
                       "radius": round(radius_m / WORLD_SCALE, 1), "height": round(height_m, 2)})

    # --- floor and inner ring
    ground = MeshBuilder()
    ground.disk(sand, (0, 0, 0), 31.5, 96, uv_scale=0.16)
    ground.disk(dark_stone, (0, 0.02, 0), 31.6, 96, uv_scale=0.25, inner=28.4)  # worn stone apron under barriers
    for i in range(14):  # dark oil / scorch patches
        r = random.uniform(4, 24)
        a = random.uniform(0, 2 * math.pi)
        ground.disk(dark_stone, (math.cos(a) * r, 0.015, math.sin(a) * r), random.uniform(1.2, 3.0), 18, uv_scale=0.5)
    doc.add_node("Ground", doc.add_mesh(ground, "Ground"))

    barrier = MeshBuilder()
    segs = 56
    for i in range(segs):
        if i % 9 == 4:
            continue  # breach in the barrier ring -> debris instead
        a = 2 * math.pi * (i + 0.5) / segs
        r = 29.0
        barrier.box(metal, (math.cos(a) * r, 0.5, math.sin(a) * r), (3.15, 1.0, 0.36), rot=rot_y(-math.degrees(a) + 90), uv_scale=0.5)
        barrier.box(dark_stone, (math.cos(a) * r, 0.12, math.sin(a) * r), (3.3, 0.24, 0.9), rot=rot_y(-math.degrees(a) + 90), uv_scale=0.5)
    doc.add_node("BarrierRing", doc.add_mesh(barrier, "BarrierRing"))

    # --- inner wall + 5 stepped tribune tiers
    wall = MeshBuilder()
    for i in range(64):
        a = 2 * math.pi * (i + 0.5) / 64
        wall.box(stone, (math.cos(a) * 30.6, 1.5, math.sin(a) * 30.6), (3.02, 3.0, 1.2), rot=rot_y(-math.degrees(a) + 90), uv_scale=0.35)
    for tier in range(5):
        r = 31.2 + 1.8 * tier + 0.9
        h = 3.0 + 1.8 * (tier + 1)
        for i in range(72):
            a = 2 * math.pi * (i + 0.5) / 72
            mat = stone if (i + tier) % 7 else dark_stone
            wall.box(mat, (math.cos(a) * r, h / 2, math.sin(a) * r), (2 * math.pi * r / 72 + 0.03, h, 1.82), rot=rot_y(-math.degrees(a) + 90), uv_scale=0.3)
    # stairways cut through tiers (visual only)
    for k in range(8):
        a = 2 * math.pi * k / 8
        for tier in range(5):
            r = 31.2 + 1.8 * tier + 0.9
            wall.box(dark_stone, (math.cos(a) * r, 3.0 + 1.8 * (tier + 1) + 0.05, math.sin(a) * r), (1.6, 0.1, 1.9), rot=rot_y(-math.degrees(a) + 90))
    doc.add_node("Tribunes", doc.add_mesh(wall, "Tribunes"))

    # --- colonnade on top of the tiers (with broken columns / missing lintels)
    cols = MeshBuilder()
    top_y = 3.0 + 1.8 * 5
    ncol = 36
    broken = {3, 9, 14, 22, 27, 33}
    for i in range(ncol):
        a = 2 * math.pi * i / ncol
        x, z = math.cos(a) * 42.0, math.sin(a) * 42.0
        if i in broken:
            hgt = random.uniform(1.5, 3.5)
            cols.cylinder(stone, (x, top_y + hgt / 2, z), 0.55, 0.62, hgt, 14, axis="y", uv_scale=0.4)
            for _ in range(4):  # rubble around the broken stump
                cols.box(dark_stone, (x + random.uniform(-1.5, 1.5), top_y + 0.3, z + random.uniform(-1.5, 1.5)), (0.8, 0.6, 0.7), rot=rot_y(random.uniform(0, 90)))
        else:
            cols.cylinder(stone, (x, top_y + 3.2, z), 0.55, 0.62, 6.4, 16, axis="y", uv_scale=0.4)
            cols.box(stone, (x, top_y + 6.6, z), (1.5, 0.45, 1.5), rot=rot_y(-math.degrees(a)), uv_scale=0.4)
            cols.box(stone, (x, top_y + 0.2, z), (1.4, 0.4, 1.4), rot=rot_y(-math.degrees(a)), uv_scale=0.4)
        nxt = (i + 1) % ncol
        if i not in broken and nxt not in broken and i % 5 != 2:
            am = 2 * math.pi * (i + 0.5) / ncol
            cols.box(stone, (math.cos(am) * 42.0, top_y + 7.3, math.sin(am) * 42.0), (2 * math.pi * 42 / ncol, 1.0, 1.3), rot=rot_y(-math.degrees(am) + 90), uv_scale=0.35)
            cols.cylinder(stone, (math.cos(am) * 42.0, top_y + 6.8, math.sin(am) * 42.0), 2.6, 2.6, 1.1, 12, axis="z",
                          rot=rot_y(-math.degrees(am) + 90), caps=False, theta_start=math.pi, theta_len=math.pi, open_inside=True)  # arch
    doc.add_node("Colonnade", doc.add_mesh(cols, "Colonnade"))

    # --- outer wall with arch openings, crenellations, banners and towers
    outer = MeshBuilder()
    for i in range(72):
        a = 2 * math.pi * (i + 0.5) / 72
        x, z = math.cos(a) * 45.0, math.sin(a) * 45.0
        outer.box(stone, (x, 10.5, z), (2 * math.pi * 45 / 72 + 0.05, 21.0, 2.2), rot=rot_y(-math.degrees(a) + 90), uv_scale=0.25)
        if i % 2 == 0:
            for yy in (6.0, 13.5):
                outer.box(opening, (math.cos(a) * 43.85, yy, math.sin(a) * 43.85), (2.2, 4.2, 0.2), rot=rot_y(-math.degrees(a) + 90))
        if i % 3 != 1:
            outer.box(stone, (x, 21.6, z), (2.0, 1.2, 2.2), rot=rot_y(-math.degrees(a) + 90), uv_scale=0.25)
    for i in range(24):
        a = 2 * math.pi * (i + 0.3) / 24
        outer.quad(cloth, (math.cos(a) * 43.6, 15.5, math.sin(a) * 43.6), 1.8, 5.5, rot=rot_y(-math.degrees(a) - 90), double=True)
    for k in range(4):
        a = 2 * math.pi * k / 4 + math.pi / 4
        x, z = math.cos(a) * 47.5, math.sin(a) * 47.5
        outer.box(dark_stone, (x, 13.0, z), (6.5, 26.0, 6.5), rot=rot_y(-math.degrees(a)), uv_scale=0.25)
        outer.box(stone, (x, 26.6, z), (7.6, 1.4, 7.6), rot=rot_y(-math.degrees(a)), uv_scale=0.25)
        outer.box(metal, (x, 28.2, z), (1.2, 1.6, 1.2), rot=rot_y(-math.degrees(a)))
        outer.cylinder(beacon, (x, 29.4, z), 0.35, 0.35, 0.8, 10, axis="y")
    doc.add_node("OuterWall", doc.add_mesh(outer, "OuterWall"))

    # --- props inside the arena (all exported as colliders)
    props = MeshBuilder()
    # rubble piles
    for _ in range(9):
        r = random.uniform(6, 25)
        a = random.uniform(0, 2 * math.pi)
        x, z = math.cos(a) * r, math.sin(a) * r
        for _ in range(random.randint(6, 10)):
            props.box(dark_stone, (x + random.uniform(-1.1, 1.1), random.uniform(0.2, 0.8), z + random.uniform(-1.1, 1.1)),
                      (random.uniform(0.5, 1.3), random.uniform(0.4, 1.0), random.uniform(0.5, 1.3)), rot=rot_y(random.uniform(0, 90)), uv_scale=0.6)
        add_collider("rubble", x, z, 1.6, 1.0)
    # concrete blocks
    for _ in range(6):
        r = random.uniform(8, 24)
        a = random.uniform(0, 2 * math.pi)
        x, z = math.cos(a) * r, math.sin(a) * r
        rot = random.uniform(0, 90)
        props.box(concrete, (x, 0.65, z), (1.9, 1.3, 1.9), rot=rot_y(rot), uv_scale=0.5)
        props.box(metal, (x, 1.36, z), (0.3, 0.12, 1.4), rot=rot_y(rot))
        add_collider("concrete", x, z, 1.3, 1.3)
    # fallen column pieces
    for _ in range(4):
        r = random.uniform(10, 24)
        a = random.uniform(0, 2 * math.pi)
        x, z = math.cos(a) * r, math.sin(a) * r
        yaw = random.uniform(0, 180)
        props.cylinder(stone, (x, 0.7, z), 0.7, 0.7, 5.0, 14, axis="z", rot=rot_y(yaw), uv_scale=0.4)
        add_collider("column", x, z, 2.2, 1.4)
    # tire stacks
    for _ in range(10):
        r = random.uniform(5, 26)
        a = random.uniform(0, 2 * math.pi)
        x, z = math.cos(a) * r, math.sin(a) * r
        for k in range(3):
            props.cylinder(rubber, (x, 0.18 + k * 0.36, z), 0.55, 0.55, 0.34, 14, axis="y", uv_scale=0.6)
        add_collider("tires", x, z, 0.75, 1.1)
    # burning barrels
    for _ in range(4):
        r = random.uniform(9, 22)
        a = random.uniform(0, 2 * math.pi)
        x, z = math.cos(a) * r, math.sin(a) * r
        props.cylinder(metal, (x, 0.5, z), 0.36, 0.36, 1.0, 14, axis="y", uv_scale=0.8)
        props.cylinder(fire, (x, 1.15, z), 0.05, 0.28, 0.5, 8, axis="y")
        add_collider("barrel", x, z, 0.5, 1.0)
    doc.add_node("Props", doc.add_mesh(props, "Props"))

    # wreck cars (detailed car geometry, burnt paint, tilted)
    for k in range(6):
        r = random.uniform(9, 25)
        a = 2 * math.pi * k / 6 + random.uniform(-0.4, 0.4)
        x, z = math.cos(a) * r, math.sin(a) * r
        nodes = build_vehicle(doc, f"wreck{k}", (0.2, 0.1, 0.05), 0.9, wreck=True, seed=40 + k)
        doc.nodes[nodes["root"]]["translation"] = [x, -0.12, z]
        doc.nodes[nodes["root"]]["rotation"] = quat_from_euler(random.uniform(0, 360), random.uniform(-6, 6), random.uniform(-14, 14))
        doc.nodes[nodes["hood"]]["rotation"] = quat_from_euler(0, -random.uniform(20, 55), 0)
        add_collider("wreck", x, z, 2.6, 1.2)

    # --- distant world: ruined city silhouette & mountains
    far = MeshBuilder()
    for _ in range(90):
        r = random.uniform(70, 150)
        a = random.uniform(0, 2 * math.pi)
        h = random.uniform(8, 48)
        far.box(ruin, (math.cos(a) * r, h / 2, math.sin(a) * r), (random.uniform(5, 14), h, random.uniform(5, 14)), rot=rot_y(random.uniform(0, 90)))
    for k in range(12):
        a = 2 * math.pi * k / 12 + random.uniform(-0.1, 0.1)
        r = 230
        far.cylinder(ruin, (math.cos(a) * r, 0, math.sin(a) * r), 0.0, random.uniform(70, 110), random.uniform(60, 120), 7, axis="y", caps=False)
    far.disk(dark_stone, (0, -0.05, 0), 400, 48, uv_scale=0.02, inner=31.0)
    doc.add_node("DistantWorld", doc.add_mesh(far, "DistantWorld"))

    size = doc.write(out("arena", "arena01_colosseum.glb"))
    with open(out("arena", "arena01_layout.json"), "w") as f:
        json.dump({"playableRadius": 700, "barrierRadius": 720, "obstacles": layout}, f, indent=1)
    print(f"  arena/arena01_colosseum.glb: {size/1024:.1f} KB, meshes={len(doc.meshes)}, materials={len(doc.materials)}, colliders={len(layout)}")


# ----------------------------------------------------------------------------- crowd
def build_crowd():
    doc = GlbDocument()
    robot = doc.add_material("RobotSpectator", base_color=(1, 1, 1, 1), metallic=0.7, roughness=0.45)
    eyes = doc.add_material("RobotEyes", base_color=(0.2, 1.0, 1.0, 1), metallic=0, roughness=0.5, emissive=(0.1, 0.9, 1.0), emissive_strength=5.0)
    palettes = [(0.55, 0.56, 0.6, 1), (0.35, 0.36, 0.4, 1), (0.6, 0.25, 0.12, 1), (0.25, 0.4, 0.3, 1), (0.7, 0.55, 0.2, 1), (0.45, 0.12, 0.12, 1)]
    groups = 24
    total = 0
    for g in range(groups):
        mb = MeshBuilder()
        a0, a1 = 2 * math.pi * g / groups, 2 * math.pi * (g + 1) / groups
        for tier in range(5):
            r = 31.2 + 1.8 * tier + 0.9
            y = 3.0 + 1.8 * (tier + 1)
            count = int((a1 - a0) * r / 1.05)
            for i in range(count):
                if random.random() < 0.12:
                    continue  # empty seats
                a = a0 + (a1 - a0) * (i + 0.5) / count + random.uniform(-0.01, 0.01)
                x, z = math.cos(a) * (r + random.uniform(-0.4, 0.4)), math.sin(a) * (r + random.uniform(-0.4, 0.4))
                sc = random.uniform(0.85, 1.2)
                col = palettes[random.randrange(len(palettes))]
                yaw = -math.degrees(a) + 90 + random.uniform(-25, 25)  # face the arena
                rot = rot_y(yaw)
                body_h = 0.7 * sc
                mb.box(robot, (x, y + body_h / 2 + 0.05, z), (0.5 * sc, body_h, 0.34 * sc), rot=rot, col=col)
                mb.box(robot, (x, y + body_h + 0.25 * sc, z), (0.32 * sc, 0.3 * sc, 0.32 * sc), rot=rot, col=col)
                eye = rot @ np.array([0, y + body_h + 0.27 * sc, -0.17 * sc], np.float32)
                mb.box(eyes, (x + eye[0] - 0, eye[1], z + eye[2]), (0.2 * sc, 0.06 * sc, 0.02), rot=rot)
                raised = random.random() < 0.45
                for sx in (-0.32, 0.32):
                    if raised:
                        arm = rot @ np.array([sx * sc, y + body_h + 0.15 * sc, 0], np.float32)
                        mb.box(robot, (x + arm[0], arm[1], z + arm[2]), (0.12 * sc, 0.5 * sc, 0.12 * sc), rot=rot, col=col)
                    else:
                        arm = rot @ np.array([sx * sc, y + body_h * 0.45, 0], np.float32)
                        mb.box(robot, (x + arm[0], arm[1], z + arm[2]), (0.12 * sc, 0.5 * sc, 0.12 * sc), rot=rot, col=col)
                total += 1
        doc.add_node(f"CrowdGroup_{g:02d}", doc.add_mesh(mb, f"CrowdGroup_{g:02d}"))
    size = doc.write(out("crowd", "crowd_tiers.glb"))
    print(f"  crowd/crowd_tiers.glb: {size/1024:.1f} KB, robots={total}, groups={groups}")


# ----------------------------------------------------------------------------- props / weapons / vfx
def build_loot():
    kinds = {"ammo": (1.0, 0.8, 0.1), "health": (0.2, 1.0, 0.3), "armor": (0.2, 0.9, 1.0), "nitro": (1.0, 0.45, 0.05),
             "weapon": (1.0, 0.1, 0.3), "repair": (0.9, 0.9, 0.9)}
    for kind, col in kinds.items():
        doc = GlbDocument()
        metal = textured(doc, "LootCrateSteel", metal_hazard_textures(doc, "crate", 51))
        glow = doc.add_material("LootGlow", base_color=(*col, 1), metallic=0, roughness=0.4, emissive=col, emissive_strength=6.0)
        mb = MeshBuilder()
        mb.box(metal, (0, 0.45, 0), (0.9, 0.9, 0.9), uv_scale=1.0)
        mb.box(glow, (0, 0.45, 0), (0.95, 0.12, 0.95))
        mb.box(glow, (0, 0.92, 0), (0.3, 0.06, 0.3))
        mb.cylinder(glow, (0, 1.9, 0), 0.03, 0.03, 2.0, 6, axis="y")  # light beacon column
        doc.add_node("Loot", doc.add_mesh(mb, "Loot"))
        size = doc.write(out("props", f"loot_{kind}.glb"))
        print(f"  props/loot_{kind}.glb: {size/1024:.1f} KB")


def build_vfx():
    specs = {
        "spark": dict(shape="box", size=(0.07, 0.07, 0.45), color=(1.0, 0.72, 0.25, 1), unlit=True, emissive=(1.0, 0.7, 0.2), strength=8.0),
        "muzzle": dict(shape="box", size=(0.35, 0.35, 0.9), color=(1.0, 0.85, 0.4, 1), unlit=True, emissive=(1.0, 0.85, 0.4), strength=10.0),
        "tracer": dict(shape="box", size=(0.1, 0.1, 1.6), color=(1.0, 0.9, 0.5, 1), unlit=True, emissive=(1.0, 0.9, 0.5), strength=8.0),
        "enemy_tracer": dict(shape="box", size=(0.1, 0.1, 1.4), color=(1.0, 0.3, 0.2, 1), unlit=True, emissive=(1.0, 0.25, 0.15), strength=8.0),
        "rocket": dict(shape="box", size=(0.22, 0.22, 0.9), color=(0.15, 0.15, 0.15, 1), unlit=False, emissive=(1.0, 0.4, 0.1), strength=4.0),
        "smoke": dict(shape="blob", size=0.5, color=(0.16, 0.15, 0.14, 0.55), unlit=True, blend=True),
        "dust": dict(shape="blob", size=0.5, color=(0.62, 0.52, 0.38, 0.42), unlit=True, blend=True),
        "fire": dict(shape="blob", size=0.4, color=(1.0, 0.4, 0.08, 0.85), unlit=True, blend=True, emissive=(1.0, 0.35, 0.05), strength=6.0),
        "debris": dict(shape="box", size=(0.25, 0.12, 0.2), color=(0.1, 0.1, 0.1, 1), unlit=False),
        "tire_smoke": dict(shape="blob", size=0.5, color=(0.8, 0.8, 0.8, 0.35), unlit=True, blend=True),
        "zone_ring": dict(shape="ring", color=(1.0, 0.1, 0.05, 0.28), unlit=True, blend=True, emissive=(1.0, 0.1, 0.05), strength=3.0),
    }
    for name, sp in specs.items():
        doc = GlbDocument()
        mat = doc.add_material(f"Vfx_{name}", base_color=sp["color"], metallic=0.0 if sp.get("unlit") else 0.6, roughness=0.6,
                               emissive=sp.get("emissive"), emissive_strength=sp.get("strength"),
                               alpha_mode="BLEND" if sp.get("blend") else None, double_sided=True, unlit=sp.get("unlit", False))
        mb = MeshBuilder()
        if sp["shape"] == "box":
            mb.box(mat, (0, 0, 0), sp["size"])
        elif sp["shape"] == "blob":
            s = sp["size"]
            mb.cylinder(mat, (0, 0, 0), s * 0.55, s * 0.55, s * 0.9, 8, axis="y")
            mb.cylinder(mat, (0, 0, 0), s * 0.9, s * 0.9, s * 0.35, 8, axis="y", rot=rot_z(35))
            mb.cylinder(mat, (0, 0, 0), s * 0.75, s * 0.75, s * 0.5, 8, axis="x", rot=rot_y(20))
        else:  # zone ring: unit radius, 8 m tall, open ended, double sided
            mb.cylinder(mat, (0, 4.0, 0), 1.0, 1.0, 8.0, 96, axis="y", caps=False, open_inside=True)
        doc.add_node(f"Vfx_{name}", doc.add_mesh(mb, f"Vfx_{name}"))
        size = doc.write(out("vfx", f"{name}.glb"))
        print(f"  vfx/{name}.glb: {size/1024:.1f} KB")


# ----------------------------------------------------------------------------- validation
def validate():
    """Post-import validation: parse every GLB, check materials, scale, pivots, bounds, node names."""
    problems = 0
    required = {
        "vehicles/player_muscle.glb": ["Hood", "Wheel_FL", "Wheel_FR", "Wheel_RL", "Wheel_RR", "Turret", "Barrel"],
        "vehicles/enemy_raider_a.glb": ["Hood", "Wheel_FL", "Turret", "Barrel"],
        "vehicles/enemy_raider_b.glb": ["Hood", "Wheel_FL", "Turret"],
        "vehicles/enemy_raider_c.glb": ["Hood", "Wheel_FL", "Turret"],
        "vehicles/enemy_boss_warrig.glb": ["Hood", "Wheel_FL", "Turret"],
        "arena/arena01_colosseum.glb": ["Ground", "BarrierRing", "Tribunes", "Colonnade", "OuterWall", "Props", "DistantWorld"],
        "crowd/crowd_tiers.glb": [f"CrowdGroup_{g:02d}" for g in range(24)],
    }
    for rel, names in required.items():
        path = os.path.join(ASSETS, rel)
        with open(path, "rb") as f:
            data = f.read()
        magic, version, length = struct.unpack("<III", data[:12])
        assert magic == 0x46546C67 and version == 2 and length == len(data), f"bad GLB header {rel}"
        jlen = struct.unpack("<I", data[12:16])[0]
        js = json.loads(data[20:20 + jlen])
        node_names = {n.get("name") for n in js["nodes"]}
        for n in names:
            if n not in node_names:
                print(f"  !! {rel}: missing node {n}")
                problems += 1
        for m in js["materials"]:
            pbr = m.get("pbrMetallicRoughness", {})
            if "baseColorTexture" not in pbr and "baseColorFactor" not in pbr:
                print(f"  !! {rel}: material {m['name']} has no base color")
                problems += 1
        # world-space bounds: apply node translation to each mesh's accessor bounds
        pts = []
        for n in js["nodes"]:
            if "mesh" not in n:
                continue
            t = np.array(n.get("translation", [0, 0, 0]))
            for prim in js["meshes"][n["mesh"]]["primitives"]:
                acc = js["accessors"][prim["attributes"]["POSITION"]]
                pts.append(np.array(acc["min"]) + t)
                pts.append(np.array(acc["max"]) + t)
        mins = np.array(pts).min(axis=0)
        maxs = np.array(pts).max(axis=0)
        ext = maxs - mins
        if rel.startswith("vehicles"):
            if not (4.0 < ext[2] < 6.5) or not (1.5 < ext[0] < 3.2):
                print(f"  !! {rel}: vehicle scale out of range ext={ext}")
                problems += 1
            if mins[1] < -0.15:
                print(f"  !! {rel}: pivot below ground min y={mins[1]}")
                problems += 1
        verts = sum(a["count"] for a in js["accessors"] if a["type"] == "VEC3" and "min" in a)
        print(f"  ok {rel}: bounds=({ext[0]:.1f} x {ext[1]:.1f} x {ext[2]:.1f}) m, verts={verts}, mats={len(js['materials'])}, tex={len(js.get('textures', []))}")
    lay = json.load(open(os.path.join(ASSETS, "arena/arena01_layout.json")))
    for o in lay["obstacles"]:
        if math.hypot(o["x"], o["y"]) + o["radius"] > 690:
            print(f"  !! collider outside playable radius: {o}")
            problems += 1
    print(f"validation finished with {problems} problem(s)")
    return problems


if __name__ == "__main__":
    print("Baking Derby Arena GLB assets ...")
    export_vehicle("player_muscle.glb", "player", (0.55, 0.16, 0.08), 0.42, seed=1)
    export_vehicle("enemy_raider_a.glb", "raiderA", (0.55, 0.55, 0.12), 0.55, seed=2)
    export_vehicle("enemy_raider_b.glb", "raiderB", (0.12, 0.45, 0.25), 0.55, seed=3)
    export_vehicle("enemy_raider_c.glb", "raiderC", (0.22, 0.24, 0.30), 0.6, seed=4)
    export_vehicle("enemy_boss_warrig.glb", "boss", (0.32, 0.05, 0.05), 0.35, boss=True, seed=5)
    build_arena()
    build_crowd()
    build_loot()
    build_vfx()
    for legacy in ("vehicles/enemy_raider.glb", "arena/arena01_base.glb"):
        p = os.path.join(ASSETS, legacy)
        if os.path.exists(p):
            os.remove(p)
    sys.exit(1 if validate() else 0)
