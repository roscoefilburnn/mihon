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
            try:
                kumiko.parse_image(str(extracted))
                info = kumiko.get_infos()[0]
                panels = [tuple(int(v) for v in p) for p in info["panels"]]
                width, height = (int(v) for v in info["size"])
                if margin:
                    panels = [grow(p, margin, width, height) for p in panels]
            except Exception as exc:  # noqa: BLE001 - one bad page must not lose the chapter
                print(f"  ! {href}: detection failed ({type(exc).__name__}: {exc})", file=sys.stderr)
                panels, width, height = [], 0, 0

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
