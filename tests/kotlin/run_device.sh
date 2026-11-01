#!/bin/sh
# Compiles the android-free device protocol, lyrics parser and image sender, runs their checks and writes tests/device_vectors.txt for the firmware tests.
set -e
KC=${KOTLINC:-kotlinc}
mkdir -p /tmp/meow-kt
$KC plugins/kotlin/DeviceProtocol.kt plugins/kotlin/LyricsLogic.kt plugins/kotlin/NowPlaying.kt plugins/kotlin/ImageSender.kt tests/kotlin/DeviceProtocolTest.kt tests/kotlin/ExtrasTests.kt -include-runtime -d /tmp/meow-kt/dev.jar 2>&1 | grep -E "error" || true
java -cp /tmp/meow-kt/dev.jar DeviceProtocolTestKt tests/device_vectors.txt
