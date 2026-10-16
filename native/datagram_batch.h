#pragma once
#include <cerrno>
#include <cstddef>
#include <cstdint>

namespace sunshine_android {
struct BatchAttempt { int packets; int error; };
struct BatchResult { size_t accepted=0; uint32_t retries=0,syscalls=0; int error=0; };
// One 60Hz frame period. This covers short Wi-Fi driver TX stalls without
// dropping an encoded reference frame after the former 8ms batch cutoff.
constexpr int64_t video_batch_retry_ns=16000000;
// sendmmsg may accept only a prefix. Resume at the first unsent datagram;
// retrying the original batch would silently duplicate successful packets.
template<class Send,class Wait,class Now>
BatchResult send_datagram_batch(size_t count,Send send,Wait wait,Now now) {
    BatchResult result;
    const auto deadline=now()+video_batch_retry_ns;
    while(result.accepted<count) {
        // Successful progress needs at most count calls. Do not discard its
        // unsent suffix just because scheduling elapsed between two syscalls;
        // the deadline bounds recovery from an actual transient socket error.
        auto attempt=send(result.accepted,count-result.accepted);++result.syscalls;
        if(attempt.packets>0&&size_t(attempt.packets)<=count-result.accepted) {
            result.accepted+=attempt.packets;continue;
        }
        if(attempt.packets>=0){result.error=EIO;break;}
        result.error=attempt.error;
        if((attempt.error!=EINTR&&attempt.error!=EAGAIN&&attempt.error!=EWOULDBLOCK&&attempt.error!=ENOBUFS)||now()>=deadline) {
            result.error=attempt.error;break;
        }
        ++result.retries;
        if(attempt.error!=EINTR)wait();
    }
    if(result.accepted==count)result.error=0;
    return result;
}
}
