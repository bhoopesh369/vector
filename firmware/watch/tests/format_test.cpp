#include "record_format.hpp"
#include <cassert>
#include <cstring>
#include <iostream>
int main(){
    auto header=vector_format::header("0123456789abcdef",1,0,1700000000,123456);
    assert(std::memcmp(header.data(),"VECTORM1",8)==0&&header[8]==1&&header[10]==64);
    assert(vector_format::valid_header(header.data(),header.size()));
    header[8]=2;assert(!vector_format::valid_header(header.data(),header.size()));
    auto packet=vector_format::packet(0xffffffffU,5000000000ULL,{1,-2,3,1,-1,1024},true);
    assert(packet[0]==1&&packet[1]==1&&packet[2]==255&&packet[5]==255);
    assert(vector_format::u64(packet.data()+6)==5000000000ULL);
    assert(int16_t(vector_format::u16(packet.data()+14))==1000);
    assert(int16_t(vector_format::u16(packet.data()+24))==16384);
    assert(vector_format::complete_size(64+26*10+13)==64+26*10);
    assert(vector_format::complete_size(8)==0);
    assert(vector_format::valid_id("0123456789abcdef"));assert(!vector_format::valid_id("../bad"));
    std::cout<<"Watch format: version, LE fields, scaling, recovery boundary and ID validation PASS\n";
}
