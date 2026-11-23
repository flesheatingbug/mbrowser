#!/usr/bin/env bash
# Turns a screen recording into a README-ready GIF (docs/demo/<name>.gif) and a small MP4.
# Usage: tools/make-demo.sh recording.mp4 speed-dial [start-seconds] [length-seconds]
# Record on Wayland with:  wf-recorder -g "$(slurp)" -f recording.mp4   (Ctrl+C to stop)
set -euo pipefail
in="${1:?recording file}"; name="${2:?short name, e.g. speed-dial}"; ss="${3:-0}"; t="${4:-12}"
out="$(dirname "$0")/../docs/demo"; mkdir -p "$out"
pal="$(mktemp --suffix=.png)"
vf="fps=15,scale=900:-1:flags=lanczos"
ffmpeg -y -v error -ss "$ss" -t "$t" -i "$in" -vf "$vf,palettegen=stats_mode=diff" "$pal"
ffmpeg -y -v error -ss "$ss" -t "$t" -i "$in" -i "$pal" -lavfi "$vf [x]; [x][1:v] paletteuse=dither=bayer:bayer_scale=4" "$out/$name.gif"
ffmpeg -y -v error -ss "$ss" -t "$t" -i "$in" -vf "scale=1280:-2" -c:v libx264 -crf 26 -preset veryfast -pix_fmt yuv420p -an "$out/$name.mp4"
rm -f "$pal"; ls -lh "$out/$name".*
