"""Render an APK's launcher icon to a PNG without an Android device.

Handles adaptive icons (colour, raster and VectorDrawable layers, gradients,
groups, clip paths) by translating them to SVG and rasterising the SVG, and
falls back to the legacy raster icon when a layer cannot be translated.
"""
from __future__ import annotations

import base64
import io
import re
import shutil
import struct
import subprocess
import zipfile

ANDROID_NS = "http://schemas.android.com/apk/res/android"
DENSITIES = {"ldpi": 120, "mdpi": 160, "tvdpi": 213, "hdpi": 240, "xhdpi": 320,
             "xxhdpi": 480, "xxxhdpi": 640, "nodpi": 500, "anydpi": 100000}
RASTER_EXT = (".png", ".webp", ".jpg", ".jpeg")


class IconError(Exception):
    pass


class Node:
    def __init__(self, name):
        self.name = name
        self.attrs = {}
        self.children = []

    def get(self, key, default=None):
        return self.attrs.get(key, default)


class Apk:
    def __init__(self, path, aapt2):
        self.path = path
        self.aapt2 = aapt2
        self.zip = zipfile.ZipFile(path)
        self._resources = None
        self._badging = None

    # ---- aapt2 wrappers -------------------------------------------------
    def _run(self, *args):
        r = subprocess.run([self.aapt2, *args], capture_output=True, text=True, errors="replace")
        if r.returncode != 0 and not r.stdout:
            raise IconError(f"aapt2 {' '.join(args[:2])} failed: {r.stderr.strip()[:300]}")
        return r.stdout

    def badging(self):
        if self._badging is None:
            self._badging = self._run("dump", "badging", self.path)
        return self._badging

    def resources(self):
        """resource id -> list of (config, kind, value); kind is file|color|ref|other."""
        if self._resources is None:
            table = {}
            current = None
            for line in self._run("dump", "resources", self.path).splitlines():
                m = re.match(r"\s+resource (0x[0-9a-f]{8}) (\S+)", line)
                if m:
                    current = table.setdefault(int(m.group(1), 16), [])
                    continue
                m = re.match(r"\s+\((.*?)\) (.*)$", line)
                if not m or current is None:
                    continue
                config, rest = m.group(1), m.group(2).strip()
                fm = re.match(r"\(file\) (\S+)", rest)
                if fm:
                    current.append((config, "file", fm.group(1)))
                elif re.match(r"#[0-9a-fA-F]{3,8}$", rest):
                    current.append((config, "color", rest))
                elif re.match(r"@0x[0-9a-f]{8}$", rest):
                    current.append((config, "ref", int(rest[1:], 16)))
                else:
                    current.append((config, "other", rest))
            self._resources = table
        return self._resources

    def xmltree(self, file):
        out = self._run("dump", "xmltree", self.path, "--file", file)
        root = None
        stack = []  # (indent, node)
        for line in out.splitlines():
            stripped = line.lstrip(" ")
            indent = len(line) - len(stripped)
            if stripped.startswith("E: "):
                name = stripped[3:].split(" (line=")[0].strip()
                name = name.split(":")[-1]
                node = Node(name)
                while stack and stack[-1][0] >= indent:
                    stack.pop()
                if stack:
                    stack[-1][1].children.append(node)
                elif root is None:
                    root = node
                stack.append((indent, node))
            elif stripped.startswith("A: ") and stack:
                body = stripped[3:]
                m = re.match(r"(?:(\S+?):)?([A-Za-z_][\w.]*)(?:\(0x[0-9a-f]+\))?=(.*)$", body)
                if not m:
                    continue
                key, value = m.group(2), m.group(3)
                sm = re.match(r'"(.*)" \(Raw: ".*"\)$', value, re.S)
                if sm:
                    value = sm.group(1)
                stack[-1][1].attrs[key] = value
        if root is None:
            raise IconError(f"cannot parse {file}")
        return root

    def read(self, file):
        return self.zip.read(file)


# ---- value helpers ----------------------------------------------------------
def num(value, default=0.0):
    if value is None:
        return default
    value = value.strip()
    m = re.match(r"\(type (0x[0-9a-f]+)\)(0x[0-9a-f]+)$", value)
    if m:
        kind, raw = int(m.group(1), 16), int(m.group(2), 16)
        if kind == 0x04:
            return struct.unpack(">f", struct.pack(">I", raw))[0]
        return float(raw if raw < 0x80000000 else raw - 0x100000000)
    m = re.match(r"-?[0-9.]+(?:e[-+]?[0-9]+)?", value)
    if m:
        try:
            return float(m.group(0))
        except ValueError:
            return default
    if value == "true":
        return 1.0
    return default


def parse_color(text):
    """'#aarrggbb' -> ('#rrggbb', alpha 0..1)."""
    h = text.lstrip("#")
    if len(h) == 3:
        h = "ff" + "".join(c * 2 for c in h)
    elif len(h) == 4:
        h = "".join(c * 2 for c in h)
    elif len(h) == 6:
        h = "ff" + h
    if len(h) != 8:
        raise IconError(f"bad colour {text}")
    return "#" + h[2:], int(h[:2], 16) / 255.0


def fmt(x):
    return ("%.4f" % x).rstrip("0").rstrip(".")


def config_score(config, want_xml):
    """Rank a resource configuration; None means unusable (night, land, ...)."""
    score = 0
    for q in [q for q in config.split("-") if q]:
        if q in DENSITIES:
            score = max(score, DENSITIES[q])
        elif re.match(r"v\d+$", q):
            continue
        else:
            return None
    return score


class Renderer:
    def __init__(self, apk: Apk):
        self.apk = apk
        self.defs = []
        self.uid = 0

    def new_id(self, prefix):
        self.uid += 1
        return f"{prefix}{self.uid}"

    # ---- resource resolution ---------------------------------------------
    def resolve(self, value, depth=0):
        """-> ('color', '#aarrggbb') | ('file', path) | None"""
        if value is None or depth > 8:
            return None
        value = value.strip()
        if value.startswith("#"):
            return ("color", value)
        m = re.match(r"@(0x[0-9a-f]{8})$", value)
        if not m:
            return None
        entries = self.apk.resources().get(int(m.group(1), 16))
        if not entries:
            return None
        best = None
        for config, kind, val in entries:
            score = config_score(config, True)
            if score is None:
                continue
            if kind == "file":
                if val.lower().endswith(".xml"):
                    score = max(score, 200000)  # vectors scale, prefer them
                elif not val.lower().endswith(RASTER_EXT):
                    continue
            if best is None or score > best[0]:
                best = (score, kind, val)
        if best is None:
            return None
        _, kind, val = best
        if kind == "color":
            return ("color", val)
        if kind == "file":
            return ("file", val)
        if kind == "ref":
            return self.resolve("@0x%08x" % val, depth + 1)
        return None

    # ---- paints -------------------------------------------------------------
    def paint(self, value):
        """-> (svg paint string, opacity) or None when it cannot be painted."""
        res = self.resolve(value)
        if res is None:
            return None
        kind, val = res
        if kind == "color":
            return parse_color(val)
        if not val.lower().endswith(".xml"):
            return None
        node = self.apk.xmltree(val)
        if node.name == "gradient":
            return self.gradient(node), 1.0
        if node.name == "selector":
            for item in reversed(node.children):
                if item.get("color"):
                    return self.paint(item.get("color"))
        return None

    def stop(self, offset, colour_value):
        p = self.paint(colour_value)
        if p is None:
            p = ("#000000", 0.0)
        colour, alpha = p
        return f'<stop offset="{fmt(offset)}" stop-color="{colour}" stop-opacity="{fmt(alpha)}"/>'

    def gradient(self, node):
        gid = self.new_id("g")
        stops = []
        items = [c for c in node.children if c.name == "item"]
        if items:
            for item in items:
                stops.append(self.stop(num(item.get("offset")), item.get("color")))
        else:
            if node.get("centerColor"):
                stops = [self.stop(0, node.get("startColor")), self.stop(0.5, node.get("centerColor")),
                         self.stop(1, node.get("endColor"))]
            else:
                stops = [self.stop(0, node.get("startColor")), self.stop(1, node.get("endColor"))]
        gtype = int(num(node.get("type"), 0))
        if gtype == 1:
            self.defs.append(
                f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" cx="{fmt(num(node.get("centerX")))}" '
                f'cy="{fmt(num(node.get("centerY")))}" r="{fmt(max(num(node.get("gradientRadius")), 0.01))}">'
                + "".join(stops) + "</radialGradient>")
        else:
            self.defs.append(
                f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{fmt(num(node.get("startX")))}" '
                f'y1="{fmt(num(node.get("startY")))}" x2="{fmt(num(node.get("endX")))}" '
                f'y2="{fmt(num(node.get("endY")))}">' + "".join(stops) + "</linearGradient>")
        return f"url(#{gid})"

    # ---- vector drawables --------------------------------------------------
    def vector(self, node, box):
        x, y, w, h = box
        vw, vh = num(node.get("viewportWidth"), 24), num(node.get("viewportHeight"), 24)
        alpha = num(node.get("alpha"), 1.0)
        body = self.vector_children(node)
        opacity = f' opacity="{fmt(alpha)}"' if alpha < 1 else ""
        return (f'<svg x="{fmt(x)}" y="{fmt(y)}" width="{fmt(w)}" height="{fmt(h)}" viewBox="0 0 {fmt(vw)} {fmt(vh)}" '
                f'preserveAspectRatio="none" overflow="hidden"{opacity}>{body}</svg>')

    def vector_children(self, node):
        out, closers = [], 0
        for child in node.children:
            if child.name == "group":
                px, py = num(child.get("pivotX")), num(child.get("pivotY"))
                sx, sy = num(child.get("scaleX"), 1), num(child.get("scaleY"), 1)
                tx, ty = num(child.get("translateX")), num(child.get("translateY"))
                rot = num(child.get("rotation"))
                transform = (f"translate({fmt(tx + px)} {fmt(ty + py)}) rotate({fmt(rot)}) "
                             f"scale({fmt(sx)} {fmt(sy)}) translate({fmt(-px)} {fmt(-py)})")
                out.append(f'<g transform="{transform}">{self.vector_children(child)}</g>')
            elif child.name == "clip-path":
                cid = self.new_id("c")
                self.defs.append(f'<clipPath id="{cid}"><path d="{child.get("pathData", "")}"/></clipPath>')
                out.append(f'<g clip-path="url(#{cid})">')
                closers += 1
            elif child.name == "path":
                out.append(self.path(child))
        return "".join(out) + "</g>" * closers

    def path(self, node):
        data = node.get("pathData")
        if not data:
            return ""
        attrs = [f'd="{data}"']
        fill = self.paint(node.get("fillColor")) if node.get("fillColor") else None
        if fill:
            attrs.append(f'fill="{fill[0]}"')
            fa = fill[1] * num(node.get("fillAlpha"), 1.0)
            if fa < 1:
                attrs.append(f'fill-opacity="{fmt(fa)}"')
            if int(num(node.get("fillType"), 0)) == 1:
                attrs.append('fill-rule="evenodd"')
        else:
            attrs.append('fill="none"')
        stroke = self.paint(node.get("strokeColor")) if node.get("strokeColor") else None
        width = num(node.get("strokeWidth"), 0)
        if stroke and width > 0:
            attrs.append(f'stroke="{stroke[0]}" stroke-width="{fmt(width)}"')
            sa = stroke[1] * num(node.get("strokeAlpha"), 1.0)
            if sa < 1:
                attrs.append(f'stroke-opacity="{fmt(sa)}"')
            cap = {1: "round", 2: "square"}.get(int(num(node.get("strokeLineCap"), 0)))
            join = {1: "round", 2: "bevel"}.get(int(num(node.get("strokeLineJoin"), 0)))
            if cap:
                attrs.append(f'stroke-linecap="{cap}"')
            if join:
                attrs.append(f'stroke-linejoin="{join}"')
            attrs.append(f'stroke-miterlimit="{fmt(num(node.get("strokeMiterLimit"), 4))}"')
        return f'<path {" ".join(attrs)}/>'

    # ---- generic drawables -------------------------------------------------
    def raster(self, file, box):
        from PIL import Image
        img = Image.open(io.BytesIO(self.apk.read(file))).convert("RGBA")
        buf = io.BytesIO()
        img.save(buf, "PNG")
        x, y, w, h = box
        data = base64.b64encode(buf.getvalue()).decode()
        return (f'<image x="{fmt(x)}" y="{fmt(y)}" width="{fmt(w)}" height="{fmt(h)}" preserveAspectRatio="none" '
                f'href="data:image/png;base64,{data}"/>')

    def drawable(self, value, box, depth=0):
        if depth > 6:
            raise IconError("drawable nesting too deep")
        res = self.resolve(value)
        if res is None:
            raise IconError(f"unresolved drawable {value}")
        kind, val = res
        x, y, w, h = box
        if kind == "color":
            colour, alpha = parse_color(val)
            return f'<rect x="{fmt(x)}" y="{fmt(y)}" width="{fmt(w)}" height="{fmt(h)}" fill="{colour}" fill-opacity="{fmt(alpha)}"/>'
        if val.lower().endswith(RASTER_EXT):
            return self.raster(val, box)
        return self.node(self.apk.xmltree(val), box, depth + 1)

    def inset_box(self, node, box):
        x, y, w, h = box

        def side(name, extent):
            v = node.get(name) or node.get("inset")
            if not v:
                return 0.0
            if "%" in v:
                return extent * num(v) / 100.0
            return num(v)  # dp, and adaptive layers are 108dp wide
        l, t, r, b = side("insetLeft", w), side("insetTop", h), side("insetRight", w), side("insetBottom", h)
        return (x + l, y + t, max(w - l - r, 0.01), max(h - t - b, 0.01))

    def node(self, node, box, depth=0):
        x, y, w, h = box
        if node.name == "vector":
            return self.vector(node, box)
        if node.name == "inset":
            inner = self.inset_box(node, box)
            if node.get("drawable"):
                return self.drawable(node.get("drawable"), inner, depth + 1)
            if node.children:
                return self.node(node.children[0], inner, depth + 1)
            return ""
        if node.name == "layer-list":
            parts = []
            for item in node.children:
                if item.get("drawable"):
                    parts.append(self.drawable(item.get("drawable"), box, depth + 1))
                elif item.children:
                    parts.append(self.node(item.children[0], box, depth + 1))
            return "".join(parts)
        if node.name == "shape":
            for child in node.children:
                if child.name == "solid" and child.get("color"):
                    p = self.paint(child.get("color"))
                    if p:
                        return f'<rect x="{fmt(x)}" y="{fmt(y)}" width="{fmt(w)}" height="{fmt(h)}" fill="{p[0]}" fill-opacity="{fmt(p[1])}"/>'
                if child.name == "gradient":
                    import math
                    angle = math.radians(num(child.get("angle"), 0))
                    cx, cy = x + w / 2, y + h / 2
                    dx, dy = math.cos(angle) * w / 2, -math.sin(angle) * h / 2
                    fake = Node("gradient")
                    fake.attrs = dict(child.attrs)
                    fake.attrs.update({"startX": str(cx - dx), "startY": str(cy - dy), "endX": str(cx + dx),
                                       "endY": str(cy + dy), "type": child.get("type", "0")})
                    if int(num(child.get("type"), 0)) == 1:
                        fake.attrs.update({"centerX": str(cx), "centerY": str(cy),
                                           "gradientRadius": str(num(child.get("gradientRadius"), w / 2))})
                    return f'<rect x="{fmt(x)}" y="{fmt(y)}" width="{fmt(w)}" height="{fmt(h)}" fill="{self.gradient(fake)}"/>'
            return ""
        if node.name == "bitmap" and node.get("src"):
            return self.drawable(node.get("src"), box, depth + 1)
        if node.name == "color" and node.get("color"):
            p = self.paint(node.get("color"))
            if p:
                return f'<rect x="{fmt(x)}" y="{fmt(y)}" width="{fmt(w)}" height="{fmt(h)}" fill="{p[0]}" fill-opacity="{fmt(p[1])}"/>'
        if node.name == "selector":
            for item in reversed(node.children):
                if item.get("drawable"):
                    return self.drawable(item.get("drawable"), box, depth + 1)
        raise IconError(f"unsupported drawable <{node.name}>")

    # ---- entry points --------------------------------------------------------
    def adaptive_svg(self, node, size):
        layers = []
        for name in ("background", "foreground"):
            layer = next((c for c in node.children if c.name == name), None)
            if layer is None:
                continue
            box = (0.0, 0.0, 108.0, 108.0)
            if layer.get("drawable"):
                layers.append(self.drawable(layer.get("drawable"), box))
            elif layer.children:
                layers.append(self.node(layer.children[0], box))
        if not layers:
            raise IconError("adaptive icon without layers")
        # Launchers show the centre 72dp of the 108dp layers.
        return (f'<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" '
                f'width="{size}" height="{size}" viewBox="18 18 72 72"><defs>{"".join(self.defs)}</defs>'
                f'{"".join(layers)}</svg>')


def rasterise(svg, size, dest):
    if shutil.which("rsvg-convert"):
        r = subprocess.run(["rsvg-convert", "--unlimited", "-w", str(size), "-h", str(size), "-o", dest],
                           input=svg.encode(), capture_output=True)
        if r.returncode != 0:
            r = subprocess.run(["rsvg-convert", "-w", str(size), "-h", str(size), "-o", dest],
                               input=svg.encode(), capture_output=True)
        if r.returncode != 0:
            raise IconError(f"rsvg-convert: {r.stderr.decode(errors='replace')[:300]}")
        return
    try:
        import cairosvg
    except ImportError as e:
        raise IconError("need rsvg-convert or cairosvg to render vector icons") from e
    cairosvg.svg2png(bytestring=svg.encode(), write_to=dest, output_width=size, output_height=size)


def icon_candidates(badging):
    found = {}
    for m in re.finditer(r"^application-icon-(\d+):'([^']*)'", badging, re.M):
        found[int(m.group(1))] = m.group(2)
    m = re.search(r"^application: .*?icon='([^']*)'", badging, re.M)
    if m and m.group(1):
        found.setdefault(0, m.group(1))
    return found


def extract_icon(apk_path, aapt2, dest, size=384):
    """Writes dest (PNG). Returns 'adaptive' or 'legacy'. Raises IconError."""
    from PIL import Image
    apk = Apk(apk_path, aapt2)
    candidates = icon_candidates(apk.badging())
    if not candidates:
        raise IconError("APK declares no icon")
    errors = []
    for path in dict.fromkeys(p for _, p in sorted(candidates.items(), reverse=True) if p.lower().endswith(".xml")):
        try:
            node = apk.xmltree(path)
            renderer = Renderer(apk)
            if node.name == "adaptive-icon":
                svg = renderer.adaptive_svg(node, size)
            else:
                body = renderer.node(node, (0.0, 0.0, 108.0, 108.0))
                svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 108 108">'
                       f'<defs>{"".join(renderer.defs)}</defs>{body}</svg>')
            rasterise(svg, size, dest)
            with Image.open(dest) as img:
                if img.convert("RGBA").getextrema()[3][1] == 0:
                    raise IconError("rendered icon is empty")
            return "adaptive" if node.name == "adaptive-icon" else "legacy"
        except (IconError, KeyError, ValueError, OSError) as e:
            errors.append(f"{path}: {e}")
    rasters = [(d, p) for d, p in candidates.items() if p.lower().endswith(RASTER_EXT) and d < 65000]
    if rasters:
        _, path = max(rasters)
        img = Image.open(io.BytesIO(apk.read(path))).convert("RGBA")
        img = img.resize((size, size), Image.LANCZOS)
        img.save(dest, "PNG", optimize=True)
        return "legacy"
    raise IconError("; ".join(errors) or "no usable icon")


if __name__ == "__main__":
    import sys
    print(extract_icon(sys.argv[1], sys.argv[2], sys.argv[3]))
