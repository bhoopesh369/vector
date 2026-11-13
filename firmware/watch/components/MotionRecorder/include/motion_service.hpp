#pragma once
#include <cstdint>
#include <string>
#include <vector>
#include "esp_err.h"
namespace vector_motion {
struct Status {uint8_t sport=0,wrist=0;bool recording=false;bool storage_ready=false;uint64_t samples=0,free_bytes=0,total_bytes=0;uint32_t dropped=0;double seconds=0;std::string message;};
struct Saved {std::string id;uint32_t bytes=0,crc=0,samples=0;uint64_t started=0;uint8_t sport=0,wrist=0;std::string outcome;};
esp_err_t start();bool latest(float axes[6]);Status status();
bool begin(uint8_t sport,uint8_t wrist);void stop();std::vector<Saved> inventory();bool clear_saved();
std::string control(const std::string& command,std::vector<uint8_t>& chunk,size_t capacity);
void ble_start();void ble_live(const uint8_t *packet,size_t size);bool transfer_active();
}
