#!/usr/bin/env python3
"""Stage the pinned factory project and the tracked Vector overlay locally."""
from pathlib import Path
import shutil
ROOT=Path(__file__).resolve().parents[1]
source=ROOT/'.vendor/waveshare/examples/factory'
target=ROOT/'.cache/watch-project'
shutil.copytree(source,target,dirs_exist_ok=True)
lock=ROOT/'firmware/watch/dependencies.lock'
if lock.exists():shutil.copy2(lock,target/'dependencies.lock')
shutil.copytree(ROOT/'firmware/watch/components/MotionRecorder',target/'components/MotionRecorder',dirs_exist_ok=True)
def replace(path,old,new):
    text=path.read_text()
    if old not in text: raise SystemExit(f'Pinned factory source changed: missing anchor in {path}')
    path.write_text(text.replace(old,new,1))
replace(target/'main/main.cpp','#include "Recorder.hpp"','#include "Recorder.hpp"\n#include "MotionRecorder.hpp"\n#include "motion_service.hpp"')
replace(target/'main/main.cpp','    bsp_display_cfg_t display_config = {','    ESP_ERROR_CHECK(init_nvs());\n    vector_motion::ble_start();\n\n    bsp_display_cfg_t display_config = {')
replace(target/'main/main.cpp','    ESP_UTILS_CHECK_ERROR_EXIT(settings->initWifi(), "Initialize Wi-Fi failed");','    // Start Wi-Fi only when WLAN is opened; retain internal RAM for BLE recording.')
replace(target/'components/Settings/Settings.cpp','        return initWifi() == ESP_OK;','        return true; // Installing Settings must not allocate the Wi-Fi driver.')
replace(target/'components/Settings/Settings.cpp','            active_page = ActivePage::Wlan;','            if (initWifi() != ESP_OK) { showRootPage(); return; }\n            active_page = ActivePage::Wlan;')
replace(target/'components/XiaozhiApp/XiaozhiApp.hpp','    bool run() override;','    bool initializeOnDemand();\n    bool run() override;')
replace(target/'components/XiaozhiApp/XiaozhiApp.cpp','bool XiaozhiApp::init()\n{','bool XiaozhiApp::init() { return true; }\n\nbool XiaozhiApp::initializeOnDemand()\n{')
replace(target/'components/XiaozhiApp/XiaozhiApp.cpp','bool XiaozhiApp::run()\n{','bool XiaozhiApp::run()\n{\n    if (!initializeOnDemand()) return false;')
# Battery/status monitoring must not implicitly re-enable the radio at boot.
replace(target/'components/system_status/system_status.cpp','        esp_err_t result = ensureWifiStackInitialized();','        esp_err_t result = esp_netif_init();\n        if (result == ESP_OK || result == ESP_ERR_INVALID_STATE) {\n            result = esp_event_loop_create_default();\n            if (result == ESP_ERR_INVALID_STATE) result = ESP_OK;\n        }')
replace(target/'components/system_status/system_status.cpp','        result = loadWifiEnabled(wifi_enabled);','        // Keep saved credentials, but let the user enable WLAN explicitly.\n        result = ESP_OK;')
replace(target/'components/system_status/system_status.cpp','        if (enabled) {\n            if (!_wifi_started.load()) {','        if (enabled) {\n            esp_err_t initialized = ensureWifiStackInitialized();\n            if (initialized != ESP_OK) return initialized;\n            if (!_wifi_started.load()) {')
replace(target/'main/main.cpp','    update_startup_screen("Loading apps...", 65);','    const esp_err_t motion_result=vector_motion::start();\n    if(motion_result!=ESP_OK) ESP_LOGE("VectorMotion", "Recorder initialization failed: %s", esp_err_to_name(motion_result));\n    ESP_UTILS_CHECK_FALSE_EXIT(install_app(phone, apps::MotionRecorder::requestInstance(), "Motion Recorder"), "Install Motion Recorder failed");\n    update_startup_screen("Loading apps...", 65);')
replace(target/'main/CMakeLists.txt','        Recorder\n','        Recorder\n        MotionRecorder\n')
replace(target/'main/CMakeLists.txt','set(STORAGE_SOURCE_DIR', 'target_compile_definitions(${COMPONENT_LIB} PRIVATE VECTOR_WATCH_BUILD=1)\n\nset(STORAGE_SOURCE_DIR')
p=target/'components/Gravitysphere/Gravitysphere.cpp'
s=p.read_text().replace('#include "Gravitysphere.hpp"','#include "Gravitysphere.hpp"\n#include "motion_service.hpp"\n\nstatic esp_err_t vector_gravity_read(qmi8658_dev_t *, qmi8658_data_t *out) { float a[6]; if(!vector_motion::latest(a)) return ESP_ERR_INVALID_STATE; *out={}; out->accelX=a[0]*9.80665f; out->accelY=a[1]*9.80665f; out->accelZ=a[2]*9.80665f; return ESP_OK; }\nstatic esp_err_t vector_gravity_ready(qmi8658_dev_t *, bool *ready) { float a[6]; *ready=vector_motion::latest(a); return ESP_OK; }')
a=s.index('    const i2c_master_bus_handle_t bus = bsp_i2c_get_handle();')
b=s.index('    const gpio_config_t button_config',a)
s=s[:a]+'    // Motion Recorder owns hardware configuration; this app uses snapshots.\n    esp_err_t result=ESP_OK;\n\n'+s[b:]
s=s.replace('qmi8658_read_sensor_data(', 'vector_gravity_read(').replace('qmi8658_is_data_ready(', 'vector_gravity_ready(')
p.write_text(s)
replace(target/'components/Gravitysphere/CMakeLists.txt','esp_driver_gpio qmi8658','esp_driver_gpio qmi8658 MotionRecorder')
p=target/'partitions.csv';p.write_text(p.read_text()+'\n# Dedicated archive in verified-erased 16–22 MiB; original older data above22 MiB is retained.\nrecords, data, fat, 0x01000000, 0x00600000,\n')
p=target/'sdkconfig.defaults';s=p.read_text().replace('CONFIG_ESPTOOLPY_FLASHSIZE_16MB=y','CONFIG_ESPTOOLPY_FLASHSIZE_32MB=y').replace('CONFIG_ESP_CONSOLE_UART_CUSTOM=y','CONFIG_ESP_CONSOLE_USB_SERIAL_JTAG=y');s+='''
CONFIG_BT_ENABLED=y
CONFIG_BT_NIMBLE_ENABLED=y
CONFIG_BT_NIMBLE_ROLE_PERIPHERAL=y
CONFIG_BT_NIMBLE_MAX_CONNECTIONS=1
CONFIG_BT_NIMBLE_ATT_PREFERRED_MTU=247
CONFIG_BT_NIMBLE_MEM_ALLOC_MODE_EXTERNAL=y
CONFIG_FATFS_VOLUMES=2
''';p.write_text(s)
print(target)
