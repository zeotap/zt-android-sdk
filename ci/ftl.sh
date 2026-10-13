#!/usr/bin/env bash
# Runs the Android sample app's instrumentation suite on REAL devices via
# Firebase Test Lab (increment 246).
#
# What this adds over `.github/workflows/sdk-android-instrumented.yml`, which
# already runs the same suite on emulators: real hardware, and specifically the
# things an emulator cannot be trusted about —
#
#   - a real Doze/background policy, which is what the flush-on-background and
#     durable-queue assertions actually depend on;
#   - a vendor OEM image with its own SharedPreferences and SQLite build, where
#     the IAB TCF `IABTCF_*` reads and the queue's WAL behaviour are not the AOSP
#     ones the emulator provides;
#   - minSdk 23 on hardware rather than on an x86 system image.
#
# Cost is why this is nightly/labelled rather than per-PR: each device-minute is
# billed, so the matrix is deliberately two devices — a current Pixel and an
# older one at the API floor — not a sweep.
#
#   sdk/android/ci/ftl.sh [--dry-run]
#
# Expects: gcloud authenticated (the workflow uses Workload Identity
# Federation), and both APKs already built:
#   ./gradlew :sample-app:assembleDebug :sample-app:assembleDebugAndroidTest
set -euo pipefail

cd "$(dirname "$0")/.."
ANDROID_DIR="$PWD"

APP_APK="$ANDROID_DIR/sample-app/build/outputs/apk/debug/sample-app-debug.apk"
TEST_APK="$ANDROID_DIR/sample-app/build/outputs/apk/androidTest/debug/sample-app-debug-androidTest.apk"

DRY_RUN="false"
[[ "${1:-}" == "--dry-run" ]] && DRY_RUN="true"

# The GCS bucket the results land in. Per-run prefix so a failed run's artifacts
# are not overwritten by the next one — the videos and logcat are the whole
# point of paying for real devices.
RESULTS_BUCKET="${FTL_RESULTS_BUCKET:-signalsmith-ftl-results}"
RESULTS_DIR="android-$(date -u +%Y%m%d-%H%M%S)-${GITHUB_RUN_ID:-local}"

# --- The device matrix -------------------------------------------------------
# `oriole` (Pixel 6) on API 33: current-generation hardware, and the OEM image
# whose Doze policy differs most from the emulator's.
# `redfin` (Pixel 5) on API 30: an older device still in wide use, and far
# enough below the current API to catch a behaviour we only tested on 34.
#
# NOT the minSdk-23 floor: Test Lab retires old physical devices, so pinning a
# model at API 23 breaks the job when it is retired. The emulator workflow covers
# API 23 (`sdk-android-instrumented.yml` runs it), which is the right split —
# an emulator is a fine place to prove an API level, a poor place to prove Doze.
DEVICES=(
  "model=oriole,version=33,locale=en,orientation=portrait"
  "model=redfin,version=30,locale=en,orientation=portrait"
)

for apk in "$APP_APK" "$TEST_APK"; do
  if [[ ! -f "$apk" ]]; then
    echo "missing APK: $apk" >&2
    echo "build it first: ./gradlew :sample-app:assembleDebug :sample-app:assembleDebugAndroidTest" >&2
    exit 1
  fi
done

ARGS=(
  firebase test android run
  --type instrumentation
  --app "$APP_APK"
  --test "$TEST_APK"
  --results-bucket "$RESULTS_BUCKET"
  --results-dir "$RESULTS_DIR"
  # The suite asserts on a MockWebServer the app starts in-process, so it needs
  # no network egress — but the SDK's own retry policy will still try to reach
  # 127.0.0.1, and a device with no network at all changes the timing the
  # durable-queue test depends on.
  --network-profile LTE
  # 20 min: the persistence test deliberately waits out a retry backoff
  # (500 + 1000 + 2000 ms) and a background flush, and Test Lab's default 15 min
  # has been enough to truncate a slow device.
  --timeout 20m
  --use-orchestrator
  # Without this a single flaky device fails the whole run; with it the report
  # still records the flake but the job's verdict comes from the retry.
  --num-flaky-test-attempts 1
)
for device in "${DEVICES[@]}"; do
  ARGS+=(--device "$device")
done

echo "[ftl] app:     $APP_APK"
echo "[ftl] test:    $TEST_APK"
echo "[ftl] results: gs://$RESULTS_BUCKET/$RESULTS_DIR"
echo "[ftl] devices: ${DEVICES[*]}"

if [[ "$DRY_RUN" == "true" ]]; then
  echo
  echo "[ftl] DRY RUN — would run:"
  printf '  gcloud %s\n' "${ARGS[*]}"
  exit 0
fi

# `gcloud firebase test` exits non-zero on a test failure AND on an
# infrastructure failure, and the two need different responses (fix the SDK vs
# re-run the job). Exit 10 is Test Lab's "one or more test cases failed"; the
# distinction is surfaced so the workflow can say which happened.
set +e
gcloud "${ARGS[@]}"
RC=$?
set -e

case "$RC" in
  0)  echo "[ftl] all tests passed on every device" ;;
  10) echo "[ftl] TEST FAILURES — see gs://$RESULTS_BUCKET/$RESULTS_DIR (video + logcat per device)" >&2 ;;
  *)  echo "[ftl] INFRASTRUCTURE failure (rc=$RC) — this is not an SDK failure; re-run the job" >&2 ;;
esac
exit "$RC"
