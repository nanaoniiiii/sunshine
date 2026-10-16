#pragma once
#include "datagram_batch.h"
#include <array>
#include <chrono>
#include <sys/socket.h>
#include <sys/uio.h>
#include <thread>
#include <mutex>
#include <condition_variable>

namespace sunshine_android {
// Android/Linux reports twice the requested SO_SNDBUF for bookkeeping. Size
// the one shared video socket for concurrent bounded bursts without changing
// system-wide limits or reducing a socket already configured above this cap.
inline int ensure_video_send_buffer(int socket) {
    constexpr int desired=1024*1024;
    int current=0;socklen_t size=sizeof(current);
    if(getsockopt(socket,SOL_SOCKET,SO_SNDBUF,&current,&size)!=0)return 0;
    if(current<desired) {
        int requested=desired/2;
        setsockopt(socket,SOL_SOCKET,SO_SNDBUF,&requested,sizeof(requested));
        size=sizeof(current);
        if(getsockopt(socket,SOL_SOCKET,SO_SNDBUF,&current,&size)!=0)return 0;
    }
    return current;
}
// Peers share one UDP socket. Arbitrate only a non-blocking syscall (at most
// 16 datagrams), never the retry backoff, entire frame or FEC block. FIFO tickets keep a busy
// peer from repeatedly taking newly freed socket space ahead of another peer.
class DatagramBatchGate {
    std::mutex mutex;
    std::condition_variable ready;
    uint64_t issued=0,serving=0;
public:
    template<class Send> auto send(Send operation) {
        std::unique_lock lock(mutex);
        const auto ticket=issued++;
        ready.wait(lock,[&]{return ticket==serving;});
        lock.unlock();
        struct Release {
            DatagramBatchGate& gate;
            ~Release(){ {std::lock_guard lock(gate.mutex);++gate.serving;} gate.ready.notify_all(); }
        } release{*this};
        return operation();
    }
    template<class Send,class Wait,class Now>
    BatchResult send_batch(size_t count,Send operation,Wait wait,Now now) {
        return send_datagram_batch(count,[&](size_t offset,size_t remaining) {
            return send([&]{return operation(offset,remaining);});
        },wait,now);
    }
};

inline BatchResult send_udp_batch(int socket,const sockaddr* target,socklen_t target_size,
                                 const char* bytes,size_t stride,size_t count,DatagramBatchGate* gate=nullptr) {
    if(count>16||!stride)return {0,0,0,EINVAL};
    std::array<iovec,16> vectors{};
    std::array<mmsghdr,16> messages{};
    for(size_t n=0;n<count;++n) {
        vectors[n].iov_base=const_cast<char*>(bytes+n*stride);vectors[n].iov_len=stride;
        messages[n].msg_hdr.msg_name=const_cast<sockaddr*>(target);
        messages[n].msg_hdr.msg_namelen=target_size;
        messages[n].msg_hdr.msg_iov=&vectors[n];messages[n].msg_hdr.msg_iovlen=1;
    }
    auto send=[&](size_t offset,size_t remaining) {
        int sent=sendmmsg(socket,messages.data()+offset,unsigned(remaining),MSG_DONTWAIT);
        return BatchAttempt{sent,sent<0?errno:0};
    };
    auto wait=[] {std::this_thread::sleep_for(std::chrono::microseconds(100));};
    auto now=[] {
        return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
    };
    return gate?gate->send_batch(count,send,wait,now):send_datagram_batch(count,send,wait,now);
}
}
