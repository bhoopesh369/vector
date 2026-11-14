#pragma once
#include "driver/i2c_master.h"
#include "esp_timer.h"
#include "esp_rom_sys.h"
#include "high_rate.hpp"
namespace vector_motion {
class QmiFifo {
 i2c_master_dev_handle_t device=nullptr;
 bool read(uint8_t reg,uint8_t* out,size_t n){return i2c_master_transmit_receive(device,&reg,1,out,n,100)==ESP_OK;}
 bool write(uint8_t reg,uint8_t value){uint8_t bytes[]={reg,value};return i2c_master_transmit(device,bytes,2,20)==ESP_OK;}
 bool wait(bool done){int64_t end=esp_timer_get_time()+10000;do{uint8_t value;if(!read(0x2d,&value,1))return false;if(bool(value&0x80)==done)return true;esp_rom_delay_us(30);}while(esp_timer_get_time()<end);return false;}
public:
 bool begin(i2c_master_bus_handle_t bus,uint8_t address){i2c_device_config_t config{};config.dev_addr_length=I2C_ADDR_BIT_LEN_7;config.device_address=address;config.scl_speed_hz=400000;return i2c_master_bus_add_device(bus,&config,&device)==ESP_OK;}
 // Returns 0 before watermark, -1 on bus/protocol errors, -2 on malformed FIFO.
 int drain(uint8_t* out,uint64_t& anchor,bool& overflow,uint64_t& pause){
  uint8_t status;if(!read(0x16,&status,1))return -1;if(!(status&0xc0))return 0;overflow=status&0x20;
  const int64_t start=esp_timer_get_time();
  if(!write(0x0a,5)||!wait(true)){write(0x0a,0);write(0x14,0x0e);return -1;}
  anchor=esp_timer_get_time(); // approximate newest FIFO sample time, not host drain completion
  bool okay=write(0x0a,0)&&wait(false);uint8_t count[2]{};
  okay=okay&&read(0x15,count,2);overflow=overflow||bool(count[1]&0x20);size_t bytes=2*(size_t(count[0])|((size_t(count[1])&3)<<8));
  bool valid=vector_high_rate::valid_bytes(bytes);
  if(okay&&valid)okay=read(0x17,out,bytes);
  bool released=write(0x14,0x0e);pause=esp_timer_get_time()-start;
  if(!okay||!released)return -1;
  return valid?static_cast<int>(bytes/12):-2;
 }
};
}
