#include "../datagram_retry.h"
#include <cassert>
using namespace sunshine_android;
int main(){
    int64_t clock=0;int calls=0,waits=0;
    auto now=[&]{return clock;};auto wait=[&]{++waits;clock+=100000;};
    auto success=send_datagram(1408,[&]{++calls;return DatagramAttempt{1408,0};},wait,now);
    assert(success.sent&&calls==1&&waits==0&&success.retries==0);
    calls=0;auto recovered=send_datagram(1408,[&]{return ++calls<4?DatagramAttempt{-1,ENOBUFS}:DatagramAttempt{1408,0};},wait,now);
    assert(recovered.sent&&calls==4&&recovered.retries==3);
    calls=0;auto full=send_datagram(1408,[&]{++calls;return DatagramAttempt{-1,EAGAIN};},wait,now);
    assert(!full.sent&&clock<9000000&&full.error==EAGAIN);
    calls=0;auto permanent=send_datagram(1408,[&]{++calls;return DatagramAttempt{-1,EMSGSIZE};},wait,now);
    assert(!permanent.sent&&calls==1&&permanent.retries==0);
    calls=0;auto partial=send_datagram(1408,[&]{++calls;return DatagramAttempt{100,0};},wait,now);
    assert(!partial.sent&&calls==1&&partial.error==EIO);
    calls=0;auto interrupted=send_datagram(1408,[&]{return ++calls==1?DatagramAttempt{-1,EINTR}:DatagramAttempt{1408,0};},wait,now);
    assert(interrupted.sent&&calls==2&&interrupted.retries==1);
}
