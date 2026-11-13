#pragma once
#include "systems/phone/esp_brookesia_phone_app.hpp"
#include "lvgl.h"
namespace esp_brookesia::apps {
class MotionRecorder:public systems::phone::App {
public:static MotionRecorder* requestInstance();
protected:MotionRecorder();bool init() override;bool run() override;bool back() override;bool close() override;bool pause() override;bool resume() override;bool deinit() override;
private:bool library=false;uint8_t selectedSport=0,selectedWrist=0;lv_obj_t *root=nullptr,*stateLabel=nullptr,*count=nullptr;lv_obj_t *info=nullptr,*timer=nullptr,*button=nullptr,*sport=nullptr,*wrist=nullptr,*dialog=nullptr;lv_timer_t *refresh=nullptr;void update();void destroy();void render();void confirmDelete();
};
}
