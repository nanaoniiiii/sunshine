#include "latest_queue.h"
#include "audio_packetizer.h"
#include <cassert>
#include <future>
#include <iostream>

int main() {
    using namespace std::chrono_literals;
    sunshine_android::LatestQueue<int> queue(3);
    for(int i=0;i<100;++i)queue.raise(i);
    assert(queue.pop()==97&&queue.pop()==98&&queue.pop()==99);
    auto blocked=std::async(std::launch::async,[&]{return queue.pop();});
    queue.stop();
    assert(blocked.wait_for(1s)==std::future_status::ready&&!blocked.get());
    queue.raise(100);assert(!queue.pop());
    using Packet=std::vector<float>;
    sunshine_android::LatestQueue<Packet> first(4),second(4);
    sunshine_android::AudioPacketizer a(240,2),b(480,2);
    Packet pcm(960,.25f);
    a.append(pcm.data(),pcm.size(),[&](Packet p){first.raise(std::move(p));});
    b.append(pcm.data(),pcm.size(),[&](Packet p){second.raise(std::move(p));});
    assert(first.pop()->size()==480&&first.pop()->front()==.25f);
    assert(second.pop()->size()==960);
    first.stop();
    b.append(pcm.data(),pcm.size(),[&](Packet p){second.raise(std::move(p));});
    assert(second.pop()->front()==.25f);second.stop();
    std::cout<<"bounded queue, silent shutdown, mixed packet sizes and independent clients passed\n";
}
