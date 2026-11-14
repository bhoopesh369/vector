#pragma once
#include "record_format.hpp"
namespace vector_stream {
constexpr size_t HEADER=8,MAX_FRAMES=18;
inline size_t capacity(size_t mtu){return mtu<37?0:std::min(MAX_FRAMES,(mtu-3-HEADER)/26);}
inline size_t encode(const vector_format::Packet* frames,size_t count,uint32_t dropped,uint8_t* out,size_t bytes){
 if(count<1||count>MAX_FRAMES||bytes<HEADER+count*26)return 0;
 for(size_t i=0;i<count;i++)if(frames[i][0]!=1||(frames[i][1]&~1))return 0;
 out[0]=0xa3;out[1]=1;out[2]=count;out[3]=dropped?1:0;vector_format::put(out+4,dropped,4);
 for(size_t i=0;i<count;i++)std::memcpy(out+HEADER+i*26,frames[i].data(),26);
 return HEADER+26*count;
}
}
