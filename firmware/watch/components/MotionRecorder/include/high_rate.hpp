#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <algorithm>
namespace vector_high_rate {
constexpr double RATE=1793.6;
constexpr size_t MAX_FRAMES=128;
inline bool valid_bytes(size_t bytes){return bytes>0&&bytes<=MAX_FRAMES*12&&bytes%12==0;}
inline std::array<float,6> decode(const uint8_t* p){std::array<float,6> a{};for(size_t i=0;i<6;i++){int16_t raw=static_cast<int16_t>(uint16_t(p[2*i])|(uint16_t(p[2*i+1])<<8));a[i]=raw/(i<3?2048.f:32.f);}return a;}
// FIFO carries no per-frame timestamps. Preserve host-observed inter-batch gaps;
// within each batch only, reconstruct spacing from the configured nominal ODR.
struct BatchClock {
 uint64_t last=0;
 uint64_t at(uint64_t first,size_t i)const{return first+static_cast<uint64_t>(i*1000000.0/RATE+0.5);}
 uint64_t first(uint64_t anchor,size_t count){uint64_t span=at(0,count-1);uint64_t begin=anchor>span?anchor-span:1;if(begin<=last)return 0;last=at(begin,count-1);return begin;}
};
}
