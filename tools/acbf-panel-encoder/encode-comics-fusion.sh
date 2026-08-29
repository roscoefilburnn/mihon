#!/data/data/com.termux/files/usr/bin/bash
#
# Termux:Widget shortcut -- fused sweep (Kumiko + YOLO model) over new chapters only.
#
# Thin wrapper: all the work, locking, wake lock and logging live in encode-comics.sh, which this
# expects at $HOME. Keeping that script out of ~/.shortcuts stops it appearing as its own widget.

export MODEL="${MODEL:-$HOME/panel_detector.tflite}"
exec "$HOME/encode-comics.sh" "$@"
