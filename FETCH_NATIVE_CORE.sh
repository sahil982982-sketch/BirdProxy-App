#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
DEST="$ROOT/app/src/main/jni/hev"
TAG="2.16.0"
REPO="https://github.com/heiher/hev-socks5-tunnel.git"

if [[ -f "$DEST/Android.mk" ]]; then
  echo "Hev native core already present: $DEST"
  exit 0
fi
command -v git >/dev/null 2>&1 || { echo "Git is required. Install Git, then rerun this script." >&2; exit 1; }
rm -rf "$DEST"
git clone --depth 1 --branch "$TAG" --recursive --shallow-submodules "$REPO" "$DEST"
[[ -f "$DEST/Android.mk" ]] || { echo "Hev source download is incomplete." >&2; exit 1; }
echo "Fetched official HevSocks5Tunnel $TAG"
