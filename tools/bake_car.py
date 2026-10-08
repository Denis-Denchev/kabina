"""Blender (headless) script: glTF car -> decimated, centred mesh for the tablet's GLES2 renderer.

Usage:
  Blender -b -P tools/bake_car.py -- <input.gltf|glb> brands/<name>/assets/car.bin [target_triangles]

Output (little-endian): int32 vertex_count, then per vertex 9 float32:
  position xyz (metres, x = right, y = up, z = forward), normal xyz, base colour rgb.
Non-indexed triangles. The car is centred on the floor (y = 0) and faces +z.
"""
import struct
import sys

import bpy
import bmesh

argv = sys.argv[sys.argv.index("--") + 1:]
src, dst = argv[0], argv[1]
target = int(argv[2]) if len(argv) > 2 else 40000

bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=src)

meshes = [o for o in bpy.context.scene.objects if o.type == "MESH"]
print("meshes:", len(meshes))


def base_color(mat):
    """Material class encoded in the colour (the shader decodes it):
    r < 0 glass · r > 1 emissive (colour + 1) · grey 0.6 paint · grey 0.3 metal · grey 0.05 dark."""
    name = (mat.name if mat else "").lower()
    if "redglass" in name:
        return (1.9, 1.05, 1.05)
    if "orangeglass" in name:
        return (1.95, 1.45, 1.0)
    if "glass" in name or "window" in name or "lens" in name:
        return (-1.0, -1.0, -1.0)
    if "paint" in name or "body" in name:
        return (0.6, 0.6, 0.6)
    if any(k in name for k in ("chrome", "disc", "rim", "mirror", "brake", "metal")):
        return (0.3, 0.3, 0.3)
    return (0.05, 0.05, 0.05)


# apply transforms and collect per-face colours via a vertex-colour attribute
total = sum(len(o.data.polygons) for o in meshes)
print("input faces:", total)
ratio = min(1.0, target / max(1, total * 1.15))

for o in meshes:
    bpy.context.view_layer.objects.active = o
    o.select_set(True)
bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)

tris = []
for o in meshes:
    if ratio < 1.0:
        mod = o.modifiers.new("dec", "DECIMATE")
        mod.ratio = ratio
        mod.use_collapse_triangulate = True
    dg = bpy.context.evaluated_depsgraph_get()
    eo = o.evaluated_get(dg)
    me = eo.to_mesh()
    bm = bmesh.new()
    bm.from_mesh(me)
    bmesh.ops.triangulate(bm, faces=bm.faces[:])
    mats = [s.material for s in o.material_slots]
    cols = [base_color(m) for m in mats] or [(0.5, 0.5, 0.5)]
    for f in bm.faces:
        c = cols[f.material_index] if f.material_index < len(cols) else cols[0]
        n = f.normal
        for v in f.verts:
            vn = v.normal if f.smooth else n
            tris.append((v.co.x, v.co.y, v.co.z, vn.x, vn.y, vn.z, c[0], c[1], c[2]))
    bm.free()
    eo.to_mesh_clear()

print("output triangles:", len(tris) // 3)

# Blender is Z-up; convert to Y-up and centre on the floor
xs = [t[0] for t in tris]; ys = [t[1] for t in tris]; zs = [t[2] for t in tris]
cx, cy, zmin = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2, min(zs)
length_axis_is_y = (max(ys) - min(ys)) > (max(xs) - min(xs))
out = []
for (x, y, z, nx, ny, nz, r, g, b) in tris:
    x, y, z = x - cx, y - cy, z - zmin
    if length_axis_is_y:      # length along Blender Y -> our z (forward)
        out.append([x, z, -y, nx, nz, -ny, r, g, b])
    else:                     # length along Blender X -> our z
        out.append([-y, z, -x, -ny, nz, -nx, r, g, b])

# real size: the reference van (a compact MPV) is 4.38 m long
zs2 = [v[2] for v in out]
length = max(zs2) - min(zs2)
k = 4.38 / length
for v in out:
    v[0] *= k; v[1] *= k; v[2] *= k

# the bonnet end is low, the tailgate end is tall: make sure the bonnet faces +z
zmin2, zmax2 = min(v[2] for v in out), max(v[2] for v in out)
def end_height(lo, hi):
    hs = [v[1] for v in out if lo <= v[2] <= hi]
    return sorted(hs)[int(len(hs) * 0.98)] if hs else 0
front_h = end_height(zmax2 - 0.45, zmax2)
back_h = end_height(zmin2, zmin2 + 0.45)
print("end heights +z/-z:", round(front_h, 2), round(back_h, 2))
if front_h > back_h:
    for v in out:
        v[0], v[2], v[3], v[5] = -v[0], -v[2], -v[3], -v[5]
    print("flipped so the bonnet faces +z")

with open(dst, "wb") as f:
    f.write(struct.pack("<i", len(out)))
    for v in out:
        f.write(struct.pack("<9f", *v))
xs3 = [v[0] for v in out]; ys3 = [v[1] for v in out]; zs3 = [v[2] for v in out]
print("final size m (w,h,l):", round(max(xs3) - min(xs3), 2), round(max(ys3) - min(ys3), 2), round(max(zs3) - min(zs3), 2))
print("size m:", max(xs) - min(xs), max(ys) - min(ys), max(zs) - min(zs))
print("written", dst)
