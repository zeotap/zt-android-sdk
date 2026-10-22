# Android build image for the Zeotap Android SDK (increment 242).
#
# Used by the `sdk-android` step of cloudbuild-sdk-pr.yaml and for local
# builds on machines without an Android SDK (`make sdk-android-test` runs
# Gradle inside this image). Kept minimal on purpose: JDK 17 + cmdline-tools +
# exactly the platform and build-tools the SDK compiles against. No emulator —
# instrumented tests run on GitHub Actions / Firebase Test Lab.
#
# Build (amd64 — Google publishes no arm64 Linux build-tools; Apple-silicon
# hosts run it under Rosetta):
#   docker build --platform linux/amd64 -t zeotap-android-sdk-build:local sdk/android/ci
FROM --platform=linux/amd64 eclipse-temurin:17-jdk

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/root/.gradle \
    GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.jvmargs=-Xmx3g"
ENV PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"

ARG CMDLINE_TOOLS_VERSION=11076708
ARG ANDROID_PLATFORM=android-35
ARG BUILD_TOOLS=35.0.0

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl unzip git ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && mkdir -p "${ANDROID_HOME}/cmdline-tools" \
 && curl -fsSL -o /tmp/cmdline-tools.zip \
      "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" \
 && unzip -q /tmp/cmdline-tools.zip -d "${ANDROID_HOME}/cmdline-tools" \
 && mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest" \
 && rm /tmp/cmdline-tools.zip \
 && yes | sdkmanager --licenses > /dev/null \
 && sdkmanager --install "platform-tools" "platforms;${ANDROID_PLATFORM}" "build-tools;${BUILD_TOOLS}" "build-tools;34.0.0" > /dev/null \
 && rm -rf "${ANDROID_HOME}/.temp"

WORKDIR /work
CMD ["bash"]
