#!/data/data/com.termux/files/usr/bin/bash
#
# Encode panels for every not-yet-encoded CBZ under COMICS_DIR.
#
# Runs as a one-tap Termux:Widget shortcut or as a periodic termux-job-scheduler job -- both use
# this same script. Archives that already carry an .acbf are skipped by the encoder, so a full
# sweep is also the "watch for new downloads" mechanism: re-running only ever costs a scan of each
# archive's central directory, and only new chapters get detected.
#
# See README.md for setup.

set -euo pipefail

# --- configuration -----------------------------------------------------------------------------
# Override any of these by exporting them before calling, or by editing here.
COMICS_DIR="${COMICS_DIR:-$HOME/storage/shared/Comics/downloads}"
KUMIKO_DIR="${KUMIKO_DIR:-$HOME/kumiko}"
ENCODER="${ENCODER:-$HOME/acbf_panel_encoder.py}"
LOG_FILE="${LOG_FILE:-$HOME/acbf-encode.log}"
# Right-to-left panel order within a row. Set RTL=1 for a manga library. A mixed library needs one
# run per root, since this is a whole-run setting and nothing here can tell the two apart.
RTL="${RTL:-0}"
# Detection tuning, passed straight through to the encoder. Empty means the encoder's default.
# See "Tuning detection" in README.md.
MIN_PANEL_SIZE="${MIN_PANEL_SIZE:-}"   # e.g. 0.03 to keep small panels, 0.15 to drop spurious ones
MARGIN="${MARGIN:-}"                   # e.g. 0.03 to grow every panel and stop bubbles clipping
EXPAND="${EXPAND:-1}"                  # 0 to stop Kumiko expanding panels toward their gutters
# Re-encode archives that already have an .acbf. Off by default, which is what makes a repeat sweep
# cheap -- but it also means changing the tuning above has no effect on already-encoded archives
# until you run once with FORCE=1.
FORCE="${FORCE:-0}"
# -----------------------------------------------------------------------------------------------

LOCK_DIR="$HOME/.acbf-encode.lock"

log() {
    printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >>"$LOG_FILE"
}

have() {
    command -v "$1" >/dev/null 2>&1
}

notify() {
    # Termux:API is optional; without it the log is the only output.
    if have termux-notification; then
        termux-notification --id acbf-encode --title "Panel encoding" --content "$1" >/dev/null 2>&1 || true
    fi
}

# mkdir is atomic, so this also rejects a second run started while a long sweep is still going --
# which a widget tap during a scheduled run would otherwise do.
if ! mkdir "$LOCK_DIR" 2>/dev/null; then
    log "another run is in progress ($LOCK_DIR exists); exiting"
    notify "Already running"
    exit 0
fi

woke=0
cleanup() {
    status=$?
    rmdir "$LOCK_DIR" 2>/dev/null || true
    if [ "$woke" -eq 1 ]; then
        termux-wake-unlock >/dev/null 2>&1 || true
    fi
    exit "$status"
}
trap cleanup EXIT INT TERM

for required in "$ENCODER" "$KUMIKO_DIR/kumikolib.py"; do
    if [ ! -e "$required" ]; then
        log "missing $required -- see README.md"
        notify "Setup incomplete: $(basename "$required") not found"
        exit 1
    fi
done

if [ ! -d "$COMICS_DIR" ]; then
    log "COMICS_DIR does not exist: $COMICS_DIR"
    notify "Comics directory not found"
    exit 1
fi

# Detection is CPU-bound and long; without a wake lock Android suspends it when the screen goes off.
if have termux-wake-lock; then
    termux-wake-lock >/dev/null 2>&1 && woke=1
fi

args=("$ENCODER" "$COMICS_DIR" --kumiko "$KUMIKO_DIR")
[ "$RTL" = "1" ] && args+=(--rtl)
[ "$EXPAND" = "0" ] && args+=(--no-expand)
[ "$FORCE" = "1" ] && args+=(--force)
[ -n "$MIN_PANEL_SIZE" ] && args+=(--min-panel-size "$MIN_PANEL_SIZE")
[ -n "$MARGIN" ] && args+=(--margin "$MARGIN")

log "starting sweep of $COMICS_DIR (rtl=$RTL force=$FORCE min=${MIN_PANEL_SIZE:-default} margin=${MARGIN:-0} expand=$EXPAND)"
notify "Encoding started"
started=$(date +%s)

# The encoder exits non-zero when any archive failed, which for a sweep is a partial result rather
# than a reason to lose the ones that worked.
if python "${args[@]}" >>"$LOG_FILE" 2>&1; then
    outcome="finished"
else
    outcome="finished with some failures"
fi

elapsed=$(( $(date +%s) - started ))
encoded=$(grep -c '=> wrote ' "$LOG_FILE" 2>/dev/null || true)
log "$outcome in ${elapsed}s"
notify "$outcome in $((elapsed / 60))m — $encoded archives encoded in total"
