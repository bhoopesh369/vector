#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
exec .tools/arduino-cli --config-file .cache/arduino-cli.yaml compile \
  --fqbn 'esp32:esp32:esp32s3:USBMode=hwcdc,CDCOnBoot=default,FlashSize=32M,PSRAM=opi,PartitionScheme=default,UploadSpeed=115200' \
  --library .vendor/waveshare/examples/arduino/libraries/SensorLib \
  --library .vendor/waveshare/examples/arduino/libraries/Arduino_GFX \
  --build-path build/vector_ble firmware/vector_ble
