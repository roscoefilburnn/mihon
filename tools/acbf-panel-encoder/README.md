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

Termux's build links Qt for OpenCV's GUI module, which this script never uses. If the import fails
with `library "libdbus-1.so" not found`, install its missing dependency:

```sh
pkg install dbus
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

# a whole library -- directories are searched recursively for .cbz
./acbf_panel_encoder.py /sdcard/Comics/downloads --kumiko ./kumiko
```

Archives that already contain an `.acbf` are skipped, so a batch over a library can be interrupted
and re-run without redoing finished chapters. Pass `--force` to re-encode them anyway. `.cbr` (RAR)
is not supported and is reported and skipped.

With `--overlay`, each archive gets its own subdirectory — page numbering repeats across chapters,
so a flat directory would have them overwrite each other.

`--kumiko` can be replaced by a `KUMIKO_PATH` environment variable, or a `kumiko` directory next to
wherever you run it.

`--overlay` writes each page with numbered panel boxes drawn on it. Use it before committing to a
large batch: reading order is only as good as the `--rtl` flag matching the book, and a page whose
boxes look wrong in the overlay will step through wrongly in the reader.

## Unattended encoding on Android

`encode-comics.sh` sweeps a library and encodes whatever isn't done yet. Because the encoder skips
archives that already contain an `.acbf`, a repeat sweep costs only a read of each archive's central
directory — a re-run over an already-encoded library takes well under a second. That makes one
script serve as both the manual "do everything" button and the periodic new-download watcher; there
is no separate monitor to keep running.

Configure by exporting before the call, or by editing the block at the top:

| Variable | Default |
| --- | --- |
| `COMICS_DIR` | `~/storage/shared/Comics/downloads` |
| `KUMIKO_DIR` | `~/kumiko` |
| `ENCODER` | `~/acbf_panel_encoder.py` |
| `LOG_FILE` | `~/acbf-encode.log` |
| `RTL` | `0` — set `1` for a manga library |
| `MIN_PANEL_SIZE` | encoder default (Kumiko's `0.1`) |
| `MARGIN` | `0` |
| `EXPAND` | `0` — set `1` to let Kumiko expand toward gutters |
| `FORCE` | `0` — set `1` to re-encode archives that already have an `.acbf` |

**Changing the tuning does not affect archives already encoded.** The skip that makes repeat sweeps
cheap also means a retuned sweep silently passes over everything it did before. Run once with
`FORCE=1` after changing `MIN_PANEL_SIZE`, `MARGIN` or `EXPAND`, then drop it again:

```sh
FORCE=1 MIN_PANEL_SIZE=0.03 MARGIN=0.03 ~/.shortcuts/encode-comics.sh
```

It takes a wake lock so Android doesn't suspend detection when the screen goes off, holds a lock
directory so a widget tap during a scheduled run doesn't start a second sweep, appends to the log,
and posts a notification on start and finish if Termux:API is installed. `RTL` is per run, so a
library mixing manga and left-to-right comics needs one invocation per root.

### One tap: Termux:Widget

Install the Termux:Widget app, then:

```sh
mkdir -p ~/.shortcuts
cp encode-comics.sh ~/.shortcuts/
chmod +x ~/.shortcuts/encode-comics.sh
```

Add the Termux:Widget widget to your home screen; it lists what's in `~/.shortcuts`. Tapping it
runs a sweep.

### Periodic: termux-job-scheduler

Needs Termux:API. Runs every six hours, only while charging, surviving reboot:

```sh
termux-job-scheduler \
    --script ~/.shortcuts/encode-comics.sh \
    --period-ms 21600000 \
    --persisted true \
    --charging true
```

`--charging true` is deliberate — detection is sustained CPU work and will noticeably drain a
battery otherwise. Inspect or cancel with `termux-job-scheduler --pending` and
`--cancel-all`. Android treats the period as a floor, not a guarantee, and may defer a job well past
it depending on doze state.

Check on a run with `tail -f ~/acbf-encode.log`.

## Tuning detection

Defaults match ACBF Editor. Kumiko turns `panel_expansion` on by default; enabling it here made
real pages worse, because with the tight gutters actual comics use, expanded panels overrun their
neighbours. `--margin` is the better tool for breathing room — it grows a panel symmetrically
rather than pushing it outward until it collides.

| Symptom | Option |
| --- | --- |
| Small panels missing | `--min-panel-size 0.03` |
| Spurious extra boxes | `--min-panel-size 0.15` |
| Boxes clip bubbles or SFX | `--margin 0.03` |
| Panels look cramped, gutters unused | `--expand` (off by default) |
| Wrong order within a row | add or drop `--rtl` |

`--min-panel-size` is the fraction of the page's **shorter side** below which a detection is
discarded. Kumiko's default is `0.1`, so on a 1988px-wide page anything under ~198px is dropped —
enough to lose a genuine narrow panel. Note that Kumiko reads this as
`value or DEFAULT_MIN_PANEL_SIZE_RATIO`, so passing `0` means the default, not "no minimum"; use a
small positive number like `0.01` instead.

It is a single trade-off dial: lowering it recovers small panels *and* admits spurious boxes.
There is no value that fixes both, so tune it per-series against overlays rather than hunting for
one global setting.

`--margin` grows every panel by a fraction of its own size on each side, clamped to the page. This
is independent of Kumiko's expansion, which stops at neighbouring panels — use it when art or
bubbles overflow their frame and get cut off on screen.

Measured on a synthetic page with six drawn panels, one deliberately 150px tall:

| Settings | Panels found | Total area |
| --- | --- | --- |
| defaults | 5 of 6 | — |
| `--min-panel-size 0.03` | 6 of 6 | 5,282,566 px |
| `--min-panel-size 0.03 --no-expand` | 6 of 6 | 4,945,263 px |
| `--min-panel-size 0.03 --margin 0.03` | 6 of 6 | 5,884,327 px |

Always check with `--dry-run --overlay` before writing a batch.

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
