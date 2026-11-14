#include "stream_batch.hpp"
#include <cassert>
#include <iostream>
int main(){using namespace vector_stream;assert(capacity(23)==0&&capacity(37)==1&&capacity(185)==6&&capacity(517)==18);vector_format::Packet frames[2]={vector_format::packet(7,1000,{1,2,3,4,5,6},false),vector_format::packet(8,1558,{1,2,3,4,5,6},true)};uint8_t bytes[60];assert(encode(frames,2,0,bytes,60)==60&&bytes[0]==0xa3&&bytes[2]==2&&bytes[3]==0);assert(encode(frames,2,65537,bytes,60)==60&&bytes[3]==1&&bytes[4]==1&&bytes[6]==1);assert(vector_format::u64(bytes+8+26+6)==1558);assert(encode(frames,2,0,bytes,59)==0);frames[0][0]=2;assert(encode(frames,2,0,bytes,60)==0);std::cout<<"BLE MTU bounds, batching, loss signaling and timestamp preservation PASS\n";}
