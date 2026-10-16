#include <cassert>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <vector>
extern "C" {
#include "rs.h"
void obl_axpy(uint8_t*,uint8_t*,uint8_t,unsigned);
}
static uint8_t multiply(uint8_t a,uint8_t b){
    uint8_t value=0;
    for(int bit=0;bit<8;++bit){if(b&1)value^=a;bool high=a&128;a<<=1;if(high)a^=0x1d;b>>=1;}
    return value;
}
int main(){
    // Every coefficient, vector tail, and unaligned source/destination matches
    // an independent GF(256) scalar implementation.
    for(unsigned length:{1u,15u,16u,17u,31u,1408u,1411u})for(int coefficient=0;coefficient<256;++coefficient){
        std::vector<uint8_t> source(length+3),actual(length+5),expected(length+5);
        for(unsigned n=0;n<length;++n){source[n+1]=uint8_t(n*17+coefficient);actual[n+3]=expected[n+3]=uint8_t(n*31);}
        for(unsigned n=0;n<length;++n)expected[n+3]^=multiply(source[n+1],uint8_t(coefficient));
        obl_axpy(actual.data()+3,source.data()+1,uint8_t(coefficient),length);
        assert(actual==expected);
    }
    for(int data:{1,17,90,180,212}){
        int parity=(data+4)/5,total=data+parity;constexpr int bytes=1408;
        auto* rs=reed_solomon_new(data,parity);assert(rs);
        std::vector<std::vector<uint8_t>> storage(total,std::vector<uint8_t>(bytes));
        std::vector<uint8_t*> pointers;for(auto& shard:storage)pointers.push_back(shard.data());
        for(int n=0;n<data;++n)for(int b=0;b<bytes;++b)storage[n][b]=uint8_t(n*37+b*13);
        auto before=std::chrono::steady_clock::now();
        assert(reed_solomon_encode(rs,pointers.data(),total,bytes)==0);
        auto elapsed=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-before).count();
        for(int p=0;p<parity;++p)for(int b=0;b<bytes;++b){
            uint8_t expected=0;for(int n=0;n<data;++n)expected^=multiply(rs->p[p*data+n],storage[n][b]);
            assert(storage[data+p][b]==expected);
        }
        auto original=storage;std::vector<uint8_t> missing(total);
        for(int n=0;n<parity;++n){missing[n]=1;std::memset(pointers[n],0,bytes);}
        assert(reed_solomon_decode(rs,pointers.data(),missing.data(),total,bytes)==0);
        for(int n=0;n<data;++n)assert(storage[n]==original[n]);
        std::printf("FEC %d+%d x %d: %.3f ms, parity and recovery match scalar\n",data,parity,bytes,elapsed);
        reed_solomon_release(rs);
    }
}
