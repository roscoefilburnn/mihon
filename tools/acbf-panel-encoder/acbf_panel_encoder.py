#!/usr/bin/env python3
"""Encode comic panel geometry for CBZ/CBR archives as ACBF.

Detection is done by Kumiko (https://github.com/njean42/kumiko), invoked exactly the way ACBF
Editor's own "Find Panels" invokes it -- see find_frames() in ACBF Editor's src/frames_editor.py.
This is not a reimplementation: the detection algorithm here *is* Kumiko.

The result is written as an .acbf entry inside the archive, which Mihon prefers over its own
on-device detection and over its cache, so a pre-encoded archive needs no detection on the phone.

Kumiko is AGPL-3.0. It is imported, never vendored, and this script only passes images to it and
serialises the coordinates it returns; see README.md.
"""
from __future__ import annotations

import argparse
import os
import shutil
import sys
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path
from xml.etree import ElementTree as ET

ACBF_NS = "http://www.acbf.info/xml/acbf/1.1"
IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".avif", ".jxl"}


def load_kumiko(kumiko_path: str | None):
    """Imports Kumiko, whose repo layout requires its root on sys.path."""
    for candidate in filter(None, [kumiko_path, os.environ.get("KUMIKO_PATH"), "kumiko"]):
        lib = Path(candidate).expanduser() / "kumikolib.py"
        if lib.is_file():
            sys.path.insert(0, str(lib.parent))
            from kumikolib import Kumiko  # noqa: PLC0415

            return Kumiko
    raise SystemExit(
        "Could not find kumikolib.py. Clone it and pass --kumiko:\n"
        "  git clone https://github.com/njean42/kumiko\n"
        "  acbf_panel_encoder.py chapter.cbz --kumiko ./kumiko",
    )


class LiteRTDetector:
    """Runs a .tflite panel detector without PyTorch.

    Ultralytics pulls in torch, which has no aarch64-Android wheels, so fusion is unusable on
    Termux through it. LiteRT is a pure inference runtime that installs there, and the decode this
    needs turns out to be trivial: the model is NMS-free (YOLO26), so its output is already a fixed
    set of finished detections rather than raw anchors needing suppression.
    """

    def __init__(self, path: str) -> None:
        try:
            from ai_edge_litert.interpreter import Interpreter  # noqa: PLC0415
        except ImportError:
            raise SystemExit("--model with a .tflite needs:  pip install ai-edge-litert") from None
        self.interpreter = Interpreter(model_path=path)
        self.interpreter.allocate_tensors()
        self.input = self.interpreter.get_input_details()[0]
        self.output = self.interpreter.get_output_details()[0]
        self.size = int(self.input["shape"][1])
        self.names = self._names(path)

    @staticmethod
    def _names(path: str) -> dict[int, str]:
        """Class names from the metadata.json Ultralytics embeds in the .tflite container."""
        try:
            import json  # noqa: PLC0415

            with zipfile.ZipFile(path) as container:
                meta = json.loads(container.read("metadata.json"))
            return {int(k): v for k, v in meta.get("names", {}).items()}
        except Exception:  # noqa: BLE001 - names are a convenience, not required
            return {}

    def detect(self, image_path: Path, conf: float) -> list[tuple[int, int, int, int]]:
        import cv2  # noqa: PLC0415
        import numpy as np  # noqa: PLC0415

        image = cv2.imread(str(image_path))
        if image is None:
            return []
        height, width = image.shape[:2]
        size = self.size

        # Letterbox: scale to fit, pad the remainder with YOLO's standard grey.
        scale = min(size / width, size / height)
        new_w, new_h = int(width * scale), int(height * scale)
        canvas = np.full((size, size, 3), 114, np.uint8)
        pad_x, pad_y = (size - new_w) // 2, (size - new_h) // 2
        canvas[pad_y : pad_y + new_h, pad_x : pad_x + new_w] = cv2.resize(image, (new_w, new_h))

        pixels = cv2.cvtColor(canvas, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
        self.interpreter.set_tensor(self.input["index"], pixels[None])
        self.interpreter.invoke()

        boxes = []
        for x1, y1, x2, y2, score, cls in self.interpreter.get_tensor(self.output["index"])[0]:
            if score < conf:
                continue
            # Coordinates are normalised to the padded canvas: undo the padding, then the scale.
            left = (x1 * size - pad_x) / scale
            top = (y1 * size - pad_y) / scale
            right = (x2 * size - pad_x) / scale
            bottom = (y2 * size - pad_y) / scale
            left, top = max(0.0, left), max(0.0, top)
            right, bottom = min(float(width), right), min(float(height), bottom)
            if right > left and bottom > top:
                boxes.append((int(cls), int(left), int(top), int(right - left), int(bottom - top)))
        return [b[1:] for b in boxes], [b[0] for b in boxes]


class UltralyticsDetector:
    """Wraps an Ultralytics model behind the same interface, for .pt weights LiteRT cannot read."""

    def __init__(self, path: str) -> None:
        try:
            from ultralytics import YOLO  # noqa: PLC0415
        except ImportError:
            raise SystemExit("--model with a .pt needs:  pip install ultralytics") from None
        self.model = YOLO(path, task="detect")
        self.names = getattr(self.model, "names", {}) or {}
        self.imgsz = 640

    def detect(self, image_path: Path, conf: float):
        result = self.model.predict(str(image_path), imgsz=self.imgsz, conf=conf, verbose=False)[0]
        boxes, classes = [], []
        for (x1, y1, x2, y2), cls in zip(
            result.boxes.xyxy.cpu().numpy(), result.boxes.cls.cpu().numpy().astype(int),
        ):
            boxes.append((int(x1), int(y1), int(x2 - x1), int(y2 - y1)))
            classes.append(int(cls))
        return boxes, classes


def load_model(path: str, imgsz: int = 640):
    """Picks a runtime: LiteRT for .tflite (no torch, works on Termux), Ultralytics for .pt."""
    if Path(path).suffix.lower() == ".tflite":
        return LiteRTDetector(path)
    model = UltralyticsDetector(path)
    model.imgsz = imgsz
    return model


def frame_class_ids(model) -> set[int]:
    """The class ids that mean "panel".

    Panel models are not consistently labelled -- chika's has {0: frame, 1: text}, others a single
    "Comic Panel" class. Anything that looks like a frame is taken as a panel, and if nothing
    matches, every class is (the model only detects one thing).
    """
    names = getattr(model, "names", None) or {}
    wanted = {i for i, n in names.items() if any(k in str(n).lower() for k in ("frame", "panel"))}
    return wanted or set(names)


def detect_with_model(model, image_path: Path, conf: float, imgsz: int) -> list[tuple[int, int, int, int]]:
    """Runs the model over one page, returning only its panel-class boxes as (x, y, w, h)."""
    boxes, classes = model.detect(image_path, conf)
    keep = frame_class_ids(model)
    return [b for b, c in zip(boxes, classes) if c in keep]


def area(b: tuple[int, int, int, int]) -> int:
    return b[2] * b[3]


def intersection(a: tuple[int, int, int, int], b: tuple[int, int, int, int]) -> int:
    iw = max(0, min(a[0] + a[2], b[0] + b[2]) - max(a[0], b[0]))
    ih = max(0, min(a[1] + a[3], b[1] + b[3]) - max(a[1], b[1]))
    return iw * ih


def is_redundant(
    candidate: tuple[int, int, int, int],
    existing: list,
    iou_threshold: float,
    contain: float,
    inset_max: float,
) -> bool:
    """Whether a model box duplicates, straddles or swallows what Kumiko already found.

    IoU alone is too weak here. A model box covering two adjacent panels scores below the IoU
    threshold against each of them individually, so it survives as a third box lying across both --
    the main source of noise when fusing. Containment in either direction catches that:

    * mostly inside an existing panel and not much smaller -> a near-duplicate, drop it;
    * mostly inside but *much* smaller -> a genuine inset panel drawn within a larger one, keep it,
      since recovering those is the reason for fusing at all;
    * swallowing an existing panel -> a merge spanning several real panels, drop it.
    """
    for other in existing:
        overlap = intersection(candidate, other)
        if not overlap:
            continue
        union = area(candidate) + area(other) - overlap
        if union and overlap / union >= iou_threshold:
            return True
        if area(candidate) and overlap / area(candidate) >= contain:
            # Contained: only survives if small enough relative to its parent to be an inset.
            if area(candidate) / max(area(other), 1) > inset_max:
                return True
        if area(other) and overlap / area(other) >= contain:
            return True
    return False


def fuse(kumiko: list, model_boxes: list, threshold: float, contain: float = 0.8, inset_max: float = 0.5) -> list:
    """Union of both detectors' boxes, keeping Kumiko's geometry where they agree.

    The two fail on disjoint pages -- Kumiko traces gutters, so it is exact on ruled borders but
    blind to panels that have none; the model recognises a panel semantically but bounds it loosely
    and merges dense rows. So agreement keeps Kumiko's coordinates, and a model box matching nothing
    is a panel Kumiko missed.
    """
    fused = list(kumiko)
    for box in model_boxes:
        if not is_redundant(box, fused, threshold, contain, inset_max):
            fused.append(box)
    return fused


def reading_order(boxes: list, rtl: bool) -> list:
    """Sorts boxes into reading order by banding them into rows, then ordering within each row.

    Fusing loses Kumiko's own ordering, and sorting purely by y interleaves panels of differing
    heights. Boxes are grouped into a row while they overlap the band vertically, which keeps a
    tall panel from splitting the row beside it.
    """
    if not boxes:
        return boxes
    rows: list[list] = []
    for box in sorted(boxes, key=lambda b: b[1]):
        for row in rows:
            top = min(b[1] for b in row)
            bottom = max(b[1] + b[3] for b in row)
            centre = box[1] + box[3] / 2
            if top < centre < bottom:
                row.append(box)
                break
        else:
            rows.append([box])
    ordered = []
    for row in rows:
        ordered.extend(sorted(row, key=lambda b: -b[0] if rtl else b[0]))
    return ordered


def image_size(path: Path) -> tuple[int, int]:
    """Page dimensions, needed when Kumiko failed but the model still produced boxes."""
    import cv2  # noqa: PLC0415

    im = cv2.imread(str(path))
    return (0, 0) if im is None else (im.shape[1], im.shape[0])


def grow(panel: tuple[int, int, int, int], margin: float, width: int, height: int):
    """Grows a panel by [margin] of its own size on each side, clamped to the page.

    Independent of Kumiko's own panel expansion, which stops at neighbouring panels. This is for
    deliberate breathing room around the detected box so art and bubbles that overflow a frame stay
    on screen.
    """
    x, y, w, h = panel
    dx, dy = int(w * margin), int(h * margin)
    left, top = max(0, x - dx), max(0, y - dy)
    right, bottom = min(width, x + w + dx), min(height, y + h + dy)
    return (left, top, right - left, bottom - top)


@dataclass
class Page:
    """One archive entry and the panels detected on it, in source-image pixels."""

    href: str
    width: int
    height: int
    panels: list[tuple[int, int, int, int]]  # (x, y, w, h)


def image_entries(archive: zipfile.ZipFile) -> list[str]:
    """Archive image entries in the same natural-sorted order Mihon pages through them."""
    names = [
        i.filename
        for i in archive.infolist()
        if not i.is_dir() and Path(i.filename).suffix.lower() in IMAGE_SUFFIXES
    ]
    return sorted(names, key=lambda n: n.lower())


def detect(
    archive_path: Path,
    kumiko_cls,
    rtl: bool,
    overlay_dir: Path | None,
    min_panel_size: float | None,
    expand: bool,
    margin: float,
    model=None,
    model_conf: float = 0.25,
    model_imgsz: int = 640,
    fuse_iou: float = 0.5,
    fuse_contain: float = 0.8,
    inset_max: float = 0.5,
    model_only: bool = False,
) -> list[Page]:
    pages: list[Page] = []
    with zipfile.ZipFile(archive_path) as archive, tempfile.TemporaryDirectory() as tmp:
        entries = image_entries(archive)
        if not entries:
            raise SystemExit(f"{archive_path.name}: no image entries found")

        # Kumiko reads from disk, and flattening keeps nested archive paths from colliding.
        for index, href in enumerate(entries):
            extracted = Path(tmp) / f"{index:04d}{Path(href).suffix.lower()}"
            with archive.open(href) as src, open(extracted, "wb") as dst:
                shutil.copyfileobj(src, dst)

            # Kumiko reads min_panel_size_ratio as `value or DEFAULT_MIN_PANEL_SIZE_RATIO`, so
            # ACBF Editor passing False silently selects 1/10 rather than "no minimum". That is
            # worth exposing, hence --min-panel-size.
            #
            # Expansion stays off, matching ACBF Editor. Kumiko defaults it on, and turning it on
            # here made real pages worse: with the tight gutters actual comics use, expanded panels
            # overrun their neighbours. Use --margin instead for breathing room, since that grows a
            # panel symmetrically rather than pushing it outward until it hits something.
            kumiko = kumiko_cls(
                {
                    "debug": False,
                    "progress": False,
                    "rtl": rtl,
                    "min_panel_size_ratio": min_panel_size,
                    "panel_expansion": expand,
                },
            )
            # Each detector gets its own try: Kumiko has internal failures on some pages (e.g.
            # "list.remove(x): x not in list"), and sharing a block meant one crashing discarded the
            # other's results for that page -- the exact pages fusion exists to rescue.
            panels: list[tuple[int, int, int, int]] = []
            width = height = 0
            try:
                kumiko.parse_image(str(extracted))
                info = kumiko.get_infos()[0]
                panels = [tuple(int(v) for v in p) for p in info["panels"]]
                width, height = (int(v) for v in info["size"])
            except Exception as exc:  # noqa: BLE001 - one bad page must not lose the chapter
                print(f"  ! {href}: kumiko failed ({type(exc).__name__}: {exc})", file=sys.stderr)

            if model is not None:
                try:
                    detected = detect_with_model(model, extracted, model_conf, model_imgsz)
                    if not width:
                        width, height = image_size(extracted)
                    panels = detected if model_only else fuse(panels, detected, fuse_iou, fuse_contain, inset_max)
                    panels = reading_order(panels, rtl)
                except Exception as exc:  # noqa: BLE001 - keep whatever Kumiko produced
                    print(f"  ! {href}: model failed ({type(exc).__name__}: {exc})", file=sys.stderr)

            if margin and width:
                panels = [grow(p, margin, width, height) for p in panels]

            page = Page(href=href, width=width, height=height, panels=panels)
            pages.append(page)
            print(f"  {href}: {len(panels)} panels ({width}x{height})")

            if overlay_dir is not None and panels:
                write_overlay(extracted, page, overlay_dir, index)

    return pages


def write_overlay(image_path: Path, page: Page, overlay_dir: Path, index: int) -> None:
    """Writes the page with numbered panel boxes drawn on it, to eyeball detection quality."""
    import cv2  # noqa: PLC0415 - only needed for --overlay

    image = cv2.imread(str(image_path))
    if image is None:
        return
    for order, (x, y, w, h) in enumerate(page.panels, start=1):
        cv2.rectangle(image, (x, y), (x + w, y + h), (0, 0, 255), 6)
        cv2.putText(image, str(order), (x + 20, y + 90), cv2.FONT_HERSHEY_SIMPLEX, 3, (0, 0, 255), 8)
    overlay_dir.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(overlay_dir / f"{index:04d}_{Path(page.href).stem}.png"), image)


def build_acbf(pages: list[Page]) -> bytes:
    """Serialises [pages] as an ACBF 1.1 document body.

    Frames are axis-aligned quads written as ACBF's native space-delimited "x,y" point list, in the
    background image's own pixel space -- the format Mihon's AcbfDocument.Frame.points parses.
    """
    ET.register_namespace("", ACBF_NS)
    root = ET.Element(f"{{{ACBF_NS}}}ACBF")
    body = ET.SubElement(root, f"{{{ACBF_NS}}}body")
    for page in pages:
        page_el = ET.SubElement(body, f"{{{ACBF_NS}}}page")
        ET.SubElement(page_el, f"{{{ACBF_NS}}}image", {"href": page.href})
        for x, y, w, h in page.panels:
            points = f"{x},{y} {x + w},{y} {x + w},{y + h} {x},{y + h}"
            ET.SubElement(page_el, f"{{{ACBF_NS}}}frame", {"points": points})
    ET.indent(root, space="  ")
    return ET.tostring(root, encoding="utf-8", xml_declaration=True)


def write_into_archive(archive_path: Path, acbf: bytes, entry_name: str, output: Path | None) -> Path:
    """Rewrites the archive with [acbf] added, replacing any existing .acbf entry."""
    destination = output or archive_path
    handle, staging_name = tempfile.mkstemp(suffix=".cbz", dir=str(destination.parent))
    os.close(handle)
    staging = Path(staging_name)
    try:
        with zipfile.ZipFile(archive_path) as src, zipfile.ZipFile(
            staging, "w", zipfile.ZIP_DEFLATED,
        ) as dst:
            for item in src.infolist():
                if Path(item.filename).suffix.lower() == ".acbf":
                    continue
                dst.writestr(item, src.read(item.filename))
            dst.writestr(entry_name, acbf)
        staging.replace(destination)
    except BaseException:
        staging.unlink(missing_ok=True)
        raise
    return destination


def collect_archives(paths: list[Path]) -> list[Path]:
    """Expands directories into the .cbz files under them, so a whole library can be handed over."""
    found: list[Path] = []
    for path in paths:
        if not path.exists():
            # Worth being explicit: on Termux the shared-storage symlink lives at ~/storage/shared,
            # so an absolute /storage/shared/... silently does not resolve.
            print(f"{path}: no such file or directory", file=sys.stderr)
        elif path.is_dir():
            found.extend(sorted(p for p in path.rglob("*") if p.suffix.lower() == ".cbz"))
            unsupported = sorted(p for p in path.rglob("*") if p.suffix.lower() == ".cbr")
            for archive in unsupported:
                print(f"{archive}: .cbr (RAR) is not supported, skipping", file=sys.stderr)
        else:
            found.append(path)
    return found


def already_encoded(archive_path: Path) -> bool:
    try:
        with zipfile.ZipFile(archive_path) as archive:
            return any(Path(n).suffix.lower() == ".acbf" for n in archive.namelist())
    except zipfile.BadZipFile:
        return False


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("archives", nargs="+", type=Path, help="CBZ file(s), or directories to search")
    parser.add_argument("--kumiko", help="path to a kumiko checkout (else $KUMIKO_PATH, else ./kumiko)")
    parser.add_argument("--rtl", action="store_true", help="right-to-left reading order (manga)")
    parser.add_argument(
        "--min-panel-size",
        type=float,
        metavar="RATIO",
        help="drop panels smaller than this fraction of the page's shorter side "
        "(Kumiko default 0.1; lower to keep small panels, raise to drop spurious boxes)",
    )
    parser.add_argument(
        "--expand",
        action="store_true",
        help="let Kumiko expand panels toward their gutters (off by default: on real pages with "
        "tight gutters it tends to overrun neighbouring panels)",
    )
    parser.add_argument(
        "--model",
        metavar="PATH",
        help="YOLO panel detector (.tflite/.pt) to fuse with Kumiko; needs ultralytics",
    )
    parser.add_argument("--model-conf", type=float, default=0.25, help="model confidence (default 0.25)")
    parser.add_argument("--model-imgsz", type=int, default=640, help="model input size (default 640)")
    parser.add_argument(
        "--fuse-iou",
        type=float,
        default=0.5,
        metavar="IOU",
        help="a model box overlapping a Kumiko box by more than this is the same panel (default 0.5)",
    )
    parser.add_argument(
        "--fuse-contain",
        type=float,
        default=0.8,
        metavar="RATIO",
        help="a model box this much inside (or containing) an existing one is redundant (default 0.8)",
    )
    parser.add_argument(
        "--inset-max",
        type=float,
        default=0.5,
        metavar="RATIO",
        help="a contained box smaller than this fraction of its parent is kept as an inset (default 0.5)",
    )
    parser.add_argument("--model-only", action="store_true", help="use the model alone, skipping Kumiko")
    parser.add_argument(
        "--margin",
        type=float,
        default=0.0,
        metavar="RATIO",
        help="grow every panel by this fraction of its own size per side, e.g. 0.03 (default: 0)",
    )
    parser.add_argument("-o", "--output", type=Path, help="write here instead of updating in place")
    parser.add_argument("--overlay", type=Path, help="also write annotated PNGs here to check quality")
    parser.add_argument("--dry-run", action="store_true", help="detect and report, don't modify anything")
    parser.add_argument("--entry-name", default="panels.acbf", help="name of the .acbf entry (default: panels.acbf)")
    parser.add_argument("--force", action="store_true", help="re-encode archives that already have an .acbf")
    args = parser.parse_args()

    archives = collect_archives(args.archives)
    if not archives:
        parser.error("no .cbz files found")
    if args.output and len(archives) > 1:
        parser.error("--output only makes sense with a single archive")

    kumiko_cls = load_kumiko(args.kumiko)
    model = load_model(args.model, args.model_imgsz) if args.model else None
    if args.model_only and model is None:
        parser.error("--model-only needs --model")
    failures = 0

    for position, archive_path in enumerate(archives, start=1):
        if not archive_path.is_file():
            print(f"{archive_path}: not found", file=sys.stderr)
            failures += 1
            continue
        if not args.force and already_encoded(archive_path):
            print(f"[{position}/{len(archives)}] {archive_path.name}: already encoded, skipping")
            continue

        print(f"[{position}/{len(archives)}] {archive_path.name}:")
        # Overlays go in a per-archive subdirectory; page numbering repeats across chapters, so a
        # flat directory would have them overwrite each other.
        overlay_dir = args.overlay / archive_path.stem if args.overlay else None
        pages = detect(
            archive_path,
            kumiko_cls,
            args.rtl,
            overlay_dir,
            args.min_panel_size,
            args.expand,
            args.margin,
            model,
            args.model_conf,
            args.model_imgsz,
            args.fuse_iou,
            args.fuse_contain,
            args.inset_max,
            args.model_only,
        )
        total = sum(len(p.panels) for p in pages)
        detected_pages = sum(1 for p in pages if p.panels)
        print(f"  => {total} panels across {detected_pages}/{len(pages)} pages")

        if total == 0:
            print("  !! no panels detected; leaving archive untouched", file=sys.stderr)
            failures += 1
            continue
        if args.dry_run:
            continue

        written = write_into_archive(archive_path, build_acbf(pages), args.entry_name, args.output)
        print(f"  => wrote {args.entry_name} into {written}")

    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
