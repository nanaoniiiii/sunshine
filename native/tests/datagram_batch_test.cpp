#include "../udp_batch_io.h"
#include <cassert>
#include <cstring>
#include <netinet/in.h>
#include <vector>
#include <unistd.h>
#include <future>
#include <atomic>
using namespace sunshine_android;
static void logic(){
    int64_t clock=0;std::vector<size_t> offsets;
    auto wait=[&]{clock+=100000;};auto now=[&]{return clock;};
    auto result=send_datagram_batch(16,[&](size_t offset,size_t count){
        offsets.push_back(offset);
        if(offset==0)return BatchAttempt{4,0};
        if(offsets.size()==2)return BatchAttempt{-1,EAGAIN};
        return BatchAttempt{int(count),0};
    },wait,now);
    assert(result.accepted==16&&result.syscalls==3&&result.retries==1);
    assert((offsets==std::vector<size_t>{0,4,4}));
    int64_t delayed_clock=0;int delayed_calls=0;
    auto delayed=send_datagram_batch(16,[&](size_t offset,size_t count){
        if(++delayed_calls==1){delayed_clock+=video_batch_retry_ns*2;return BatchAttempt{3,0};}
        assert(offset==3);return BatchAttempt{int(count),0};
    },[]{assert(false);},[&]{return delayed_clock;});
    // A scheduling gap after a successful prefix is not a socket failure.
    assert(delayed.accepted==16&&delayed.error==0&&delayed.syscalls==2&&delayed.retries==0);
    auto full=send_datagram_batch(16,[](size_t,size_t){return BatchAttempt{-1,ENOBUFS};},wait,now);
    assert(full.accepted==0&&full.error==ENOBUFS&&full.retries>0&&clock<=video_batch_retry_ns+200000);
    int64_t recovered_clock=0;int recovery_calls=0;
    auto recovered=send_datagram_batch(16,[&](size_t,size_t count){
        ++recovery_calls;return recovered_clock<10000000?BatchAttempt{-1,ENOBUFS}:BatchAttempt{int(count),0};
    },[&]{recovered_clock+=100000;},[&]{return recovered_clock;});
    assert(recovered.accepted==16&&recovered.error==0&&recovery_calls>80); // Driver stall longer than 8ms recovers.
    int calls=0;auto partial=send_datagram_batch(16,[&](size_t,size_t){return ++calls==1?BatchAttempt{3,0}:BatchAttempt{-1,EMSGSIZE};},wait,now);
    assert(partial.accepted==3&&partial.syscalls==2&&partial.error==EMSGSIZE);
    auto zero=send_datagram_batch(1,[](size_t,size_t){return BatchAttempt{0,0};},wait,now);
    assert(zero.accepted==0&&zero.syscalls==1&&zero.error==EIO);
}
static void loopback(int family){
    DatagramBatchGate gate;
    int receiver=socket(family,SOCK_DGRAM,0),sender=socket(family,SOCK_DGRAM,0);
    assert(receiver>=0&&sender>=0);
    int previous=0;socklen_t option_size=sizeof(previous);
    assert(getsockopt(sender,SOL_SOCKET,SO_SNDBUF,&previous,&option_size)==0);
    const int configured=ensure_video_send_buffer(sender);
    assert(configured>=previous&&configured>0);
    assert(ensure_video_send_buffer(sender)==configured);
    int buffer=2*1024*1024;assert(setsockopt(receiver,SOL_SOCKET,SO_RCVBUF,&buffer,sizeof(buffer))==0);
    timeval timeout{2,0};assert(setsockopt(receiver,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout))==0);
    sockaddr_storage target{};socklen_t length;
    if(family==AF_INET){auto& a=(sockaddr_in&)target;a.sin_family=AF_INET;a.sin_addr.s_addr=htonl(INADDR_LOOPBACK);length=sizeof(a);}
    else {auto& a=(sockaddr_in6&)target;a.sin6_family=AF_INET6;a.sin6_addr=in6addr_loopback;length=sizeof(a);}
    assert(bind(receiver,(sockaddr*)&target,length)==0);assert(getsockname(receiver,(sockaddr*)&target,&length)==0);
    constexpr size_t stride=1408,total=64;
    std::vector<char> bytes(stride*total);
    for(size_t n=0;n<total;++n){std::memset(bytes.data()+n*stride,int(n),stride);std::memcpy(bytes.data()+n*stride,&n,sizeof(n));}
    uint32_t syscalls=0;
    for(size_t n=0;n<total;n+=16){auto result=send_udp_batch(sender,(sockaddr*)&target,length,bytes.data()+n*stride,stride,16,&gate);assert(result.accepted==16&&result.error==0);syscalls+=result.syscalls;}
    assert(syscalls==4);std::vector<bool> received(total);
    for(size_t n=0;n<total;++n){std::array<char,stride> packet{};assert(recv(receiver,packet.data(),packet.size(),0)==stride);size_t index;std::memcpy(&index,packet.data(),sizeof(index));assert(index<total&&!received[index]);received[index]=true;assert(!std::memcmp(packet.data(),bytes.data()+index*stride,stride));}
    close(receiver);close(sender);
}
static void shared_socket(){
    DatagramBatchGate gate;
    std::promise<void> entered,release;
    auto released=release.get_future();
    auto first=std::async(std::launch::async,[&]{return gate.send([&]{entered.set_value();released.wait();return 7;});});
    entered.get_future().wait();
    std::atomic<bool> overlap{false};
    auto second=std::async(std::launch::async,[&]{return gate.send([&]{overlap=true;return 8;});});
    assert(second.wait_for(std::chrono::milliseconds(20))==std::future_status::timeout&&!overlap);
    release.set_value();assert(first.get()==7&&second.get()==8);
    try{gate.send([]()->int{throw 1;});}catch(int){}
    assert(gate.send([]{return 9;})==9); // Exceptions cannot strand the next peer.
}
static void retry_releases_shared_gate(){
    DatagramBatchGate gate;
    std::promise<void> waiting,resume;
    auto resumed=resume.get_future();int calls=0;int64_t clock=0;
    auto slow=std::async(std::launch::async,[&]{return gate.send_batch(16,[&](size_t offset,size_t remaining){
        if(++calls==1){assert(offset==0);return BatchAttempt{4,0};}
        assert(offset==4);
        return calls==2?BatchAttempt{-1,EAGAIN}:BatchAttempt{int(remaining),0};
    },[&]{waiting.set_value();resumed.wait();clock+=100000;},[&]{return clock;});});
    waiting.get_future().wait();
    auto other=std::async(std::launch::async,[&]{return gate.send_batch(1,[](size_t,size_t){return BatchAttempt{1,0};},[]{assert(false);},[]{return int64_t(0);});});
    // The first peer is still in retry backoff. Another peer must be able to
    // use the shared socket before that wait ends, without duplicating a prefix.
    assert(other.wait_for(std::chrono::seconds(1))==std::future_status::ready);
    assert(other.get().accepted==1);
    resume.set_value();auto result=slow.get();
    assert(result.accepted==16&&result.retries==1&&result.syscalls==3);
}
int main(){logic();shared_socket();retry_releases_shared_gate();loopback(AF_INET);loopback(AF_INET6);}
