#!/data/data/com.termux/files/usr/bin/bash
#
# Termux:Widget shortcut -- fused sweep that RE-ENCODES everything, including chapters that already
# have an .acbf. Use after changing detection settings, since the ordinary sweep skips finished
# archives and would otherwise leave them on their old encoding.
#
# Slow: it redoes the whole library rather than just new chapters. Run it on charge.

export MODEL="${MODEL:-$HOME/panel_detector.tflite}"
export FORCE=1
exec "$HOME/encode-comics.sh" "$@"
