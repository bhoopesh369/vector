#pragma once
#include <array>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <string>
namespace vector_format {
using Header=std::array<uint8_t,64>;using Packet=std::array<uint8_t,26>;
inline void put(uint8_t *p,uint64_t value,int bytes){for(int i=0;i<bytes;i++)p[i]=(value>>(8*i))&255;}
inline uint16_t u16(const uint8_t*p){return uint16_t(p[0])|(uint16_t(p[1])<<8);}
inline uint64_t u64(const uint8_t*p){uint64_t v=0;for(int i=0;i<8;i++)v|=uint64_t(p[i])<<(8*i);return v;}
inline bool valid_id(const std::string&id){if(id.size()!=16)return false;for(char c:id)if(!((c>='0'&&c<='9')||(c>='a'&&c<='f')))return false;return true;}
inline Header header(const std::string&id,uint8_t sport,uint8_t wrist,uint64_t unix_seconds,uint64_t started_us){
    Header h{};std::memcpy(h.data(),"VECTORM1",8);put(h.data()+8,1,2);put(h.data()+10,64,2);h[12]=sport;h[13]=wrist;
    put(h.data()+16,unix_seconds,8);put(h.data()+24,started_us,8);std::memcpy(h.data()+32,id.data(),std::min(size_t(16),id.size()));
    float rate=112.1f;uint32_t bits;std::memcpy(&bits,&rate,4);put(h.data()+48,bits,4);put(h.data()+52,16,2);put(h.data()+54,1024,2);put(h.data()+56,26,2);return h;
}
inline bool valid_header(const uint8_t *p,size_t bytes){return bytes>=64&&!std::memcmp(p,"VECTORM1",8)&&u16(p+8)==1&&u16(p+10)==64&&p[12]<4&&p[13]<2&&u16(p+56)==26&&valid_id(std::string(reinterpret_cast<const char*>(p+32),16));}
inline Packet packet(uint32_t seq,uint64_t us,const std::array<float,6>&axes,bool clipped){Packet p{};p[0]=1;p[1]=clipped;put(p.data()+2,seq,4);put(p.data()+6,us,8);
    for(int i=0;i<6;i++){long value=std::lround(axes[i]*(i<3?1000:16));int16_t sample=static_cast<int16_t>(std::max(-32768L,std::min(32767L,value)));put(p.data()+14+i*2,static_cast<uint16_t>(sample),2);}return p;}
inline size_t complete_size(size_t bytes){return bytes<64?0:64+(bytes-64)/26*26;}
}
