#include "high_rate.hpp"
#include <cassert>
#include <iostream>
int main(){
 uint8_t bytes[12]={0,8,0,248,0,16,0,4,0,252,0,8};
 auto a=vector_high_rate::decode(bytes);assert(a[0]==1&&a[1]==-1&&a[2]==2);assert(a[3]==32&&a[4]==-32&&a[5]==64);
 vector_high_rate::BatchClock clock;
 auto first=clock.first(100000,64);assert(first<100000);assert(clock.at(first,63)<=100000);
 auto second=clock.first(160000,64);assert(second>clock.at(first,63)+1000); // preserve real drain pause
 auto third=clock.first(160100,64);assert(third==0);assert(clock.last==clock.at(second,63)); // reject an impossible anchor; never generate future samples
 assert(!vector_high_rate::valid_bytes(1537));assert(!vector_high_rate::valid_bytes(13));assert(vector_high_rate::valid_bytes(1536));
 std::cout<<"FIFO decode, bounds and timestamp-gap preservation PASS\n";
}
