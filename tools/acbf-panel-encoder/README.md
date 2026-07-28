# ACBF panel encoder

Pre-computes comic panel geometry for CBZ archives on a desktop and stores it in the archive as
ACBF, so the reader doesn't have to detect panels on the phone.

Mihon prefers an embedded `.acbf` entry over both its disk cache and its own on-device detection,
so an archive encoded here needs no detection at read time. Guided Panel still has to be enabled in
the app — that preference gates the whole feature, including reading embedded ACBF.

## Detection is Kumiko, not a reimplementation

Panels are detected by [Kumiko](https://github.com/njean42/kumiko), called with exactly the options
ACBF Editor's own "Find Panels" uses (see `find_frames()` in ACBF Editor's `src/frames_editor.py`).
This script extracts pages, hands each to Kumiko, and serialises the rectangles it returns. It
contains no detection logic of its own.

## Licensing

Kumiko is **AGPL-3.0**. It is imported at runtime and never vendored into this repository, and
nothing here is linked into the Mihon app — this is a separate program that emits a data file.
Install it yourself (below); the coordinates it produces are data, not a derived work.

This is deliberately the clean side of the problem described in `core/panels/NOTICE.md`: encoding
on the desktop and shipping only the resulting XML avoids putting copyleft detection code inside an
Apache-2.0 application.

## Setup

Kumiko needs OpenCV 4.x. On OpenCV 5 it fails with `IndexError: invalid index to scalar variable`,
because `HoughLinesP` changed its output shape.

```sh
git clone https://github.com/njean42/kumiko
pip install "opencv-python-headless<5" numpy
```

### On Android

`pip install opencv-python` has no prebuilt aarch64-Android wheels, so pip tries to build OpenCV
from source and that will not finish. Use a packaged build instead.

Termux, via the user repository — note the package is named after the PyPI project, not the Debian
one:

```sh
pkg install tur-repo
pkg install opencv-python python-numpy
python -c "import cv2; print(cv2.__version__)"   # must be 4.x
```

If that reports OpenCV 5, or the package is unavailable, use Debian under proot, whose
`python3-opencv` is prebuilt for arm64 and still on 4.x:

```sh
termux-setup-storage        # in Termux, before logging in, so /sdcard is reachable
pkg install proot-distro
proot-distro install debian
proot-distro login debian
apt update && apt install -y python3 python3-numpy python3-opencv git
```

proot emulates syscalls in userspace, so detection runs slower than native. Time a single chapter
with `--dry-run` before committing to a library-sized batch.

## Usage

```sh
# encode in place
./acbf_panel_encoder.py chapter.cbz --kumiko ./kumiko

# manga (right-to-left panel order within a row)
./acbf_panel_encoder.py chapter.cbz --kumiko ./kumiko --rtl

# check detection quality without touching the archive
./acbf_panel_encoder.py chapter.cbz --kumiko ./kumiko --dry-run --overlay ./overlays

# a whole library
./acbf_panel_encoder.py ~/comics/**/*.cbz --kumiko ./kumiko
```

`--kumiko` can be replaced by a `KUMIKO_PATH` environment variable, or a `kumiko` directory next to
wherever you run it.

`--overlay` writes each page with numbered panel boxes drawn on it. Use it before committing to a
large batch: reading order is only as good as the `--rtl` flag matching the book, and a page whose
boxes look wrong in the overlay will step through wrongly in the reader.

## Output

An entry named `panels.acbf` (override with `--entry-name`), replacing any existing `.acbf`:

```xml
<ACBF xmlns="http://www.acbf.info/xml/acbf/1.1">
  <body>
    <page>
      <image href="001.webp" />
      <frame points="61,83 1904,83 1904,798 61,798" />
    </page>
  </body>
</ACBF>
```

`href` must match the archive entry name exactly — that is how the reader maps frames back to
pages. Coordinates are in the source image's own pixel space.

An archive where nothing is detected is left untouched rather than given an empty document, so it
can be retried later without having to strip a useless entry first.
