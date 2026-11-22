#!/usr/bin/env bash
# Build CarrierOverrides inside Docker; the host needs only Docker.
# Usage: ./docker-build.sh [gradle tasks...]   (default: assembleDebug)
set -euo pipefail

cd "$(dirname "$0")"
IMAGE=carrieroverrides-builder

docker build -t "$IMAGE" docker/

mkdir -p .docker-cache/gradle .docker-cache/home

[ $# -eq 0 ] && set -- assembleDebug

exec docker run --rm \
  --user "$(id -u):$(id -g)" \
  -v "$PWD":/project \
  -w /project \
  -e HOME=/project/.docker-cache/home \
  -e GRADLE_USER_HOME=/project/.docker-cache/gradle \
  -e ANDROID_USER_HOME=/project/.docker-cache/home/.android \
  "$IMAGE" \
  gradle --no-daemon "$@"
