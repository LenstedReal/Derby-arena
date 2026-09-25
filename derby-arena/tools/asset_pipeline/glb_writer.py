"""Minimal, dependency-light GLB 2.0 writer with procedural PBR texture support.

Used by build_assets.py to bake Derby Arena vehicles / arena / props / vfx / crowd
into textured glTF binaries consumed at runtime by Filament gltfio (UbershaderProvider).
"""
import io
import json
import math
import struct

import numpy as np
from PIL import Image


# --------------------------------------------------------------------------- math
def rot_y(deg):
    r = math.radians(deg)
    c, s = math.cos(r), math.sin(r)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]], dtype=np.float32)


def rot_x(deg):
    r = math.radians(deg)
    c, s = math.cos(r), math.sin(r)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]], dtype=np.float32)


def rot_z(deg):
    r = math.radians(deg)
    c, s = math.cos(r), math.sin(r)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]], dtype=np.float32)


def quat_from_euler(yaw=0.0, pitch=0.0, roll=0.0):
    """Returns glTF quaternion [x,y,z,w] for Y(yaw) * X(pitch) * Z(roll) in degrees."""
    cy, sy = math.cos(math.radians(yaw) / 2), math.sin(math.radians(yaw) / 2)
    cp, sp = math.cos(math.radians(pitch) / 2), math.sin(math.radians(pitch) / 2)
    cr, sr = math.cos(math.radians(roll) / 2), math.sin(math.radians(roll) / 2)
    # q = qy * qx * qz
    qy = (0, sy, 0, cy)
    qx = (sp, 0, 0, cp)
    qz = (0, 0, sr, cr)

    def mul(a, b):
        ax, ay, az, aw = a
        bx, by, bz, bw = b
        return (
            aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw,
            aw * bw - ax * bx - ay * by - az * bz,
        )

    q = mul(mul(qy, qx), qz)
    return [float(v) for v in q]


# --------------------------------------------------------------------------- textures
def fbm(size, octaves=5, seed=0, persistence=0.5, base_freq=4):
    """Tileable value-noise fBm in [0,1]."""
    rng = np.random.default_rng(seed)
    out = np.zeros((size, size), dtype=np.float32)
    amp, total = 1.0, 0.0
    freq = base_freq
    for _ in range(octaves):
        grid = rng.random((freq, freq)).astype(np.float32)
        # bilinear upsample with wrap for tileability
        ys = np.linspace(0, freq, size, endpoint=False)
        xs = np.linspace(0, freq, size, endpoint=False)
        y0 = np.floor(ys).astype(int)
        x0 = np.floor(xs).astype(int)
        fy = (ys - y0)[:, None]
        fx = (xs - x0)[None, :]
        fy = fy * fy * (3 - 2 * fy)
        fx = fx * fx * (3 - 2 * fx)
        y1 = (y0 + 1) % freq
        x1 = (x0 + 1) % freq
        a = grid[y0][:, x0]
        b = grid[y0][:, x1]
        c = grid[y1][:, x0]
        d = grid[y1][:, x1]
        layer = (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
        out += layer * amp
        total += amp
        amp *= persistence
        freq *= 2
    return out / total


def normal_from_height(height, strength=2.0):
    """Tangent-space normal map (RGB uint8) from a height field in [0,1]."""
    dx = np.roll(height, -1, axis=1) - np.roll(height, 1, axis=1)
    dy = np.roll(height, -1, axis=0) - np.roll(height, 1, axis=0)
    nx = -dx * strength
    ny = -dy * strength
    nz = np.ones_like(height)
    length = np.sqrt(nx * nx + ny * ny + nz * nz)
    n = np.stack([nx / length, ny / length, nz / length], axis=-1)
    return ((n * 0.5 + 0.5) * 255).astype(np.uint8)


def to_png_bytes(rgb_uint8):
    img = Image.fromarray(rgb_uint8, mode="RGB" if rgb_uint8.shape[-1] == 3 else "RGBA")
    buf = io.BytesIO()
    img.save(buf, format="PNG", optimize=True)
    return buf.getvalue()


def pack_mr(metallic, roughness):
    """glTF metallicRoughness texture: G=roughness, B=metallic."""
    size = roughness.shape[0]
    out = np.zeros((size, size, 3), dtype=np.uint8)
    out[..., 1] = np.clip(roughness * 255, 0, 255).astype(np.uint8)
    out[..., 2] = np.clip(metallic * 255, 0, 255).astype(np.uint8)
    return out


# --------------------------------------------------------------------------- geometry
class MeshBuilder:
    """Accumulates triangles per material; produces one glTF mesh with N primitives."""

    def __init__(self):
        self.prims = {}  # material -> dict(pos, nrm, uv, col, idx)

    def _prim(self, material):
        if material not in self.prims:
            self.prims[material] = {"pos": [], "nrm": [], "uv": [], "col": [], "idx": []}
        return self.prims[material]

    def add_tris(self, material, pos, nrm, uv, idx, col=None):
        p = self._prim(material)
        base = sum(len(x) for x in p["pos"])
        p["pos"].append(np.asarray(pos, np.float32))
        p["nrm"].append(np.asarray(nrm, np.float32))
        p["uv"].append(np.asarray(uv, np.float32))
        p["idx"].append(np.asarray(idx, np.uint32) + base)
        if col is not None:
            p["col"].append(np.asarray(col, np.float32))

    def box(self, material, center, size, rot=None, uv_scale=1.0, col=None):
        cx, cy, cz = center
        sx, sy, sz = [s / 2 for s in size]
        faces = [
            # normal, corners (ccw seen from outside), (u-dim, v-dim)
            ((0, 0, 1), [(-sx, -sy, sz), (sx, -sy, sz), (sx, sy, sz), (-sx, sy, sz)], (size[0], size[1])),
            ((0, 0, -1), [(sx, -sy, -sz), (-sx, -sy, -sz), (-sx, sy, -sz), (sx, sy, -sz)], (size[0], size[1])),
            ((1, 0, 0), [(sx, -sy, sz), (sx, -sy, -sz), (sx, sy, -sz), (sx, sy, sz)], (size[2], size[1])),
            ((-1, 0, 0), [(-sx, -sy, -sz), (-sx, -sy, sz), (-sx, sy, sz), (-sx, sy, -sz)], (size[2], size[1])),
            ((0, 1, 0), [(-sx, sy, sz), (sx, sy, sz), (sx, sy, -sz), (-sx, sy, -sz)], (size[0], size[2])),
            ((0, -1, 0), [(-sx, -sy, -sz), (sx, -sy, -sz), (sx, -sy, sz), (-sx, -sy, sz)], (size[0], size[2])),
        ]
        pos, nrm, uv, idx, cols = [], [], [], [], []
        for n, corners, dims in faces:
            b = len(pos)
            for i, c in enumerate(corners):
                v = np.array(c, np.float32)
                nn = np.array(n, np.float32)
                if rot is not None:
                    v = rot @ v
                    nn = rot @ nn
                pos.append(v + np.array([cx, cy, cz], np.float32))
                nrm.append(nn)
                u = [0, 1, 1, 0][i] * dims[0] * uv_scale
                vv = [0, 0, 1, 1][i] * dims[1] * uv_scale
                uv.append((u, vv))
                if col is not None:
                    cols.append(col)
            idx += [b, b + 1, b + 2, b, b + 2, b + 3]
        self.add_tris(material, pos, nrm, uv, idx, cols if col is not None else None)

    def cylinder(self, material, center, radius_top, radius_bottom, height, segments=24, axis="y",
                 rot=None, caps=True, uv_scale=1.0, theta_start=0.0, theta_len=2 * math.pi, col=None,
                 open_inside=False):
        """Cylinder along `axis` ('x','y','z') centered at center. Supports partial arcs."""
        pos, nrm, uv, idx, cols = [], [], [], [], []
        half = height / 2
        rings = []
        for j, (r, y) in enumerate(((radius_bottom, -half), (radius_top, half))):
            ring = []
            for i in range(segments + 1):
                t = theta_start + theta_len * i / segments
                x, z = math.cos(t) * r, math.sin(t) * r
                ring.append((x, y, z, math.cos(t), math.sin(t), i / segments))
            rings.append(ring)
        slope = (radius_bottom - radius_top) / max(height, 1e-6)
        for i in range(segments):
            b = len(pos)
            for j in (0, 1):
                for k in (i, i + 1):
                    x, y, z, nx, nz, u = rings[j][k]
                    pos.append((x, y, z))
                    ny = slope
                    ln = math.sqrt(nx * nx + ny * ny + nz * nz)
                    nrm.append((nx / ln, ny / ln, nz / ln))
                    uv.append((u * theta_len * (radius_top + radius_bottom) / 2 * uv_scale, (j * height) * uv_scale))
            idx += [b, b + 2, b + 3, b, b + 3, b + 1]
            if open_inside:
                idx += [b, b + 3, b + 2, b, b + 1, b + 3]
        if caps:
            for j, y, sign in ((1, half, 1), (0, -half, -1)):
                r = radius_top if j == 1 else radius_bottom
                if r <= 0:
                    continue
                b = len(pos)
                pos.append((0, y, 0))
                nrm.append((0, sign, 0))
                uv.append((0.5 * r * uv_scale, 0.5 * r * uv_scale))
                for i in range(segments + 1):
                    x, yy, z, _, _, _ = rings[j][i]
                    pos.append((x, yy, z))
                    nrm.append((0, sign, 0))
                    uv.append(((x + r) * 0.5 * uv_scale, (z + r) * 0.5 * uv_scale))
                for i in range(segments):
                    if sign > 0:
                        idx += [b, b + 2 + i, b + 1 + i]
                    else:
                        idx += [b, b + 1 + i, b + 2 + i]
        pos = np.array(pos, np.float32)
        nrm = np.array(nrm, np.float32)
        if axis == "x":
            m = rot_z(-90)
        elif axis == "z":
            m = rot_x(90)
        else:
            m = np.eye(3, dtype=np.float32)
        if rot is not None:
            m = rot @ m
        pos = pos @ m.T
        nrm = nrm @ m.T
        pos += np.array(center, np.float32)
        if col is not None:
            cols = [col] * len(pos)
        self.add_tris(material, pos, nrm, uv, idx, cols if col is not None else None)

    def disk(self, material, center, radius, segments=64, uv_scale=1.0, inner=0.0, y_normal=1):
        pos, nrm, uv, idx = [], [], [], []
        cx, cy, cz = center
        for i in range(segments + 1):
            t = 2 * math.pi * i / segments
            for r in (inner, radius):
                pos.append((cx + math.cos(t) * r, cy, cz + math.sin(t) * r))
                nrm.append((0, y_normal, 0))
                uv.append(((math.cos(t) * r + radius) * uv_scale, (math.sin(t) * r + radius) * uv_scale))
        for i in range(segments):
            b = i * 2
            if y_normal > 0:
                idx += [b, b + 3, b + 1, b, b + 2, b + 3]
            else:
                idx += [b, b + 1, b + 3, b, b + 3, b + 2]
        self.add_tris(material, pos, nrm, uv, idx)

    def quad(self, material, center, w, h, rot=None, double=False):
        hw, hh = w / 2, h / 2
        corners = [(-hw, -hh, 0), (hw, -hh, 0), (hw, hh, 0), (-hw, hh, 0)]
        pos, nrm, uv = [], [], []
        for i, c in enumerate(corners):
            v = np.array(c, np.float32)
            n = np.array((0, 0, 1), np.float32)
            if rot is not None:
                v = rot @ v
                n = rot @ n
            pos.append(v + np.array(center, np.float32))
            nrm.append(n)
            uv.append(([0, 1, 1, 0][i], [1, 1, 0, 0][i]))
        idx = [0, 1, 2, 0, 2, 3]
        if double:
            idx += [0, 2, 1, 0, 3, 2]
        self.add_tris(material, pos, nrm, uv, idx)

    def is_empty(self):
        return not self.prims


# --------------------------------------------------------------------------- document
class GlbDocument:
    def __init__(self, generator="DerbyArena-AssetPipeline-v8"):
        self.buffer = bytearray()
        self.buffer_views = []
        self.accessors = []
        self.images = []
        self.textures = []
        self.samplers = [{"magFilter": 9729, "minFilter": 9987, "wrapS": 10497, "wrapT": 10497}]
        self.materials = []
        self.material_index = {}
        self.meshes = []
        self.nodes = []
        self.scene_nodes = []
        self.generator = generator
        self.extensions_used = set()

    # -- buffers
    def _push(self, data: bytes, target=None):
        while len(self.buffer) % 4:
            self.buffer.append(0)
        off = len(self.buffer)
        self.buffer.extend(data)
        bv = {"buffer": 0, "byteOffset": off, "byteLength": len(data)}
        if target:
            bv["target"] = target
        self.buffer_views.append(bv)
        return len(self.buffer_views) - 1

    def _accessor(self, arr, comp_type, acc_type, target, minmax=False, normalized=False):
        arr = np.ascontiguousarray(arr)
        bv = self._push(arr.tobytes(), target)
        acc = {"bufferView": bv, "componentType": comp_type, "count": int(arr.shape[0]), "type": acc_type}
        if minmax:
            acc["min"] = [float(v) for v in arr.min(axis=0)]
            acc["max"] = [float(v) for v in arr.max(axis=0)]
        if normalized:
            acc["normalized"] = True
        self.accessors.append(acc)
        return len(self.accessors) - 1

    # -- textures
    def add_texture(self, rgb_uint8, name):
        png = to_png_bytes(rgb_uint8)
        bv = self._push(png)
        self.images.append({"bufferView": bv, "mimeType": "image/png", "name": name})
        self.textures.append({"sampler": 0, "source": len(self.images) - 1})
        return len(self.textures) - 1

    # -- materials
    def add_material(self, name, base_color=(1, 1, 1, 1), metallic=0.0, roughness=0.8,
                     base_tex=None, mr_tex=None, normal_tex=None, emissive=None, emissive_strength=None,
                     alpha_mode=None, double_sided=False, unlit=False, normal_scale=1.0):
        if name in self.material_index:
            return self.material_index[name]
        pbr = {"baseColorFactor": list(base_color), "metallicFactor": metallic, "roughnessFactor": roughness}
        if base_tex is not None:
            pbr["baseColorTexture"] = {"index": base_tex}
        if mr_tex is not None:
            pbr["metallicRoughnessTexture"] = {"index": mr_tex}
        mat = {"name": name, "pbrMetallicRoughness": pbr, "doubleSided": double_sided}
        if normal_tex is not None:
            mat["normalTexture"] = {"index": normal_tex, "scale": normal_scale}
        if emissive is not None:
            mat["emissiveFactor"] = list(emissive)
            if emissive_strength is not None and emissive_strength != 1.0:
                mat.setdefault("extensions", {})["KHR_materials_emissive_strength"] = {"emissiveStrength": emissive_strength}
                self.extensions_used.add("KHR_materials_emissive_strength")
        if alpha_mode:
            mat["alphaMode"] = alpha_mode
        if unlit:
            mat.setdefault("extensions", {})["KHR_materials_unlit"] = {}
            self.extensions_used.add("KHR_materials_unlit")
        self.materials.append(mat)
        self.material_index[name] = len(self.materials) - 1
        return self.material_index[name]

    # -- meshes / nodes
    def add_mesh(self, builder: MeshBuilder, name):
        prims = []
        for mat, p in builder.prims.items():
            pos = np.concatenate(p["pos"])
            nrm = np.concatenate(p["nrm"])
            uv = np.concatenate(p["uv"])
            idx = np.concatenate(p["idx"])
            attrs = {
                "POSITION": self._accessor(pos, 5126, "VEC3", 34962, minmax=True),
                "NORMAL": self._accessor(nrm, 5126, "VEC3", 34962),
                "TEXCOORD_0": self._accessor(uv, 5126, "VEC2", 34962),
            }
            if p["col"] and sum(len(c) for c in p["col"]) == len(pos):
                col = np.concatenate([np.asarray(c, np.float32).reshape(-1, len(p["col"][0][0])) for c in p["col"]])
                attrs["COLOR_0"] = self._accessor(col, 5126, "VEC4" if col.shape[1] == 4 else "VEC3", 34962)
            if len(pos) < 65535:
                idx_acc = self._accessor(idx.astype(np.uint16), 5123, "SCALAR", 34963)
            else:
                idx_acc = self._accessor(idx.astype(np.uint32), 5125, "SCALAR", 34963)
            prims.append({"attributes": attrs, "indices": idx_acc, "material": int(mat), "mode": 4})
        self.meshes.append({"name": name, "primitives": prims})
        return len(self.meshes) - 1

    def add_node(self, name, mesh=None, translation=None, rotation=None, scale=None, children=None, root=True):
        node = {"name": name}
        if mesh is not None:
            node["mesh"] = mesh
        if translation is not None:
            node["translation"] = [float(v) for v in translation]
        if rotation is not None:
            node["rotation"] = [float(v) for v in rotation]
        if scale is not None:
            node["scale"] = [float(v) for v in scale]
        if children:
            node["children"] = list(children)
        self.nodes.append(node)
        i = len(self.nodes) - 1
        if root:
            self.scene_nodes.append(i)
        return i

    def write(self, path):
        gltf = {
            "asset": {"version": "2.0", "generator": self.generator},
            "scene": 0,
            "scenes": [{"nodes": self.scene_nodes}],
            "nodes": self.nodes,
            "meshes": self.meshes,
            "materials": self.materials,
            "accessors": self.accessors,
            "bufferViews": self.buffer_views,
            "buffers": [{"byteLength": len(self.buffer)}],
        }
        if self.images:
            gltf["images"] = self.images
            gltf["textures"] = self.textures
            gltf["samplers"] = self.samplers
        if self.extensions_used:
            gltf["extensionsUsed"] = sorted(self.extensions_used)
        js = json.dumps(gltf, separators=(",", ":")).encode("utf-8")
        while len(js) % 4:
            js += b" "
        bin_data = bytes(self.buffer)
        while len(bin_data) % 4:
            bin_data += b"\x00"
        total = 12 + 8 + len(js) + 8 + len(bin_data)
        with open(path, "wb") as f:
            f.write(struct.pack("<III", 0x46546C67, 2, total))
            f.write(struct.pack("<II", len(js), 0x4E4F534A))
            f.write(js)
            f.write(struct.pack("<II", len(bin_data), 0x004E4942))
            f.write(bin_data)
        return total
