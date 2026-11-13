# This component is staged under .cache/watch-project/components/MotionRecorder.
get_filename_component(VECTOR_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../.." ABSOLUTE)
idf_component_register(SRCS "MotionRecorder.cpp" "motion_service.cpp" "motion_ble.cpp"
 INCLUDE_DIRS "include" "${VECTOR_ROOT}/.vendor/waveshare/examples/arduino/libraries/SensorLib/src"
 REQUIRES brookesia_core esp32_s3_touch_amoled_2_06 esp_timer esp_driver_i2c fatfs wear_levelling esp_partition esp_system bt nvs_flash cjson
)
target_compile_options(${COMPONENT_LIB} PRIVATE -Wno-missing-field-initializers)

target_compile_definitions(${COMPONENT_LIB} PRIVATE CONFIG_SENSORLIB_ESP_IDF_NEW_API=1)
