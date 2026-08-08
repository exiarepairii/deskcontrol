#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 4 ]]; then
    echo "Usage: $0 INPUT OUTPUT SIZE INSET_SCALE" >&2
    exit 2
fi

input="$1"
output="$2"
canvas_size="$3"
inset_scale="$4"
inset_size="$(
    awk -v size="$canvas_size" -v scale="$inset_scale" \
        'BEGIN { printf "%d", size * scale + 0.5 }'
)"
blur_sigma="$(
    awk -v size="$canvas_size" \
        'BEGIN { printf "%.2f", size * 0.055 }'
)"

ffmpeg -loglevel error -y \
    -i "$input" \
    -i "$input" \
    -filter_complex \
    "[0:v]scale=${canvas_size}:${canvas_size}:flags=lanczos,gblur=sigma=${blur_sigma},eq=brightness=-0.12:saturation=0.82[backdrop];[1:v]scale=${inset_size}:${inset_size}:flags=lanczos[inset];[backdrop][inset]overlay=(W-w)/2:(H-h)/2" \
    -frames:v 1 \
    "$output"
