#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/analytics-tests
javac -d build/analytics-tests android/src/com/vector/motion/MotionPacket.java android/src/com/vector/motion/MotionAnalytics.java android/src/com/vector/motion/WatchRecording.java android/tests/MotionAnalyticsTest.java android/tests/MotionPacketTest.java android/tests/WatchRecordingTest.java
java -cp build/analytics-tests MotionAnalyticsTest
java -cp build/analytics-tests MotionPacketTest

java -cp build/analytics-tests WatchRecordingTest
