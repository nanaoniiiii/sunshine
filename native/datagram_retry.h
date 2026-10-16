#pragma once
#include <cerrno>
#include <cstddef>
#include <cstdint>

namespace sunshine_android {
struct DatagramAttempt { int64_t bytes; int error; };
struct DatagramResult { bool sent; uint32_t retries; int error; };
// UDP sends are atomic: retry only an entirely rejected datagram. Never resend
// a successful datagram or an unexpected short write. Bound socket pressure
// recovery so a slow peer cannot indefinitely block the shared video sender.
template<class Send,class Wait,class Now>
DatagramResult send_datagram(size_t bytes,Send send,Wait wait,Now now) {
    const auto deadline=now()+8000000;
    uint32_t retries=0;
    for(;;) {
        auto result=send();
        if(result.bytes==int64_t(bytes))return {true,retries,0};
        if(result.bytes>=0)return {false,retries,EIO};
        if((result.error!=EINTR&&result.error!=EAGAIN&&result.error!=EWOULDBLOCK&&result.error!=ENOBUFS)||now()>=deadline)
            return {false,retries,result.error};
        ++retries;
        if(result.error!=EINTR)wait();
    }
}
}
