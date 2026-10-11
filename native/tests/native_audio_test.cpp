#include "audio_packetizer.h"
#include "thread_safe.h"
#include <cassert>
#include <future>
#include <iostream>
#include <thread>

int main() {
    using namespace std::chrono_literals;
    safe::queue_t<int> bounded(3);
    for (int i = 0; i < 100; ++i) bounded.raise_latest(i);
    assert(bounded.unsafe().size() == 3);
    assert(*bounded.pop() == 97 && *bounded.pop() == 98 && *bounded.pop() == 99);

    // A silent capture source must not keep a disconnected encoder blocked in pop().
    auto stopped = std::async(std::launch::async, [&] { return !bounded.pop(); });
    bounded.stop();
    assert(stopped.wait_for(1s) == std::future_status::ready && stopped.get());
    bounded.raise_latest(100);
    assert(!bounded.pop());

    using Packet = std::vector<float>;
    safe::queue_t<Packet> first(4), second(4);
    sunshine_android::AudioPacketizer a(240, 2), b(480, 2);
    Packet pcm(960, .25f);
    a.append(pcm.data(), pcm.size(), [&](Packet p) { first.raise_latest(std::move(p)); });
    b.append(pcm.data(), pcm.size(), [&](Packet p) { second.raise_latest(std::move(p)); });
    assert(first.unsafe().size() == 2 && second.unsafe().size() == 1);
    assert(first.pop()->front() == .25f && second.pop()->front() == .25f);
    first.stop();
    // Disconnecting one client does not stop the other client's queue or packetizer.
    b.append(pcm.data(), pcm.size(), [&](Packet p) { second.raise_latest(std::move(p)); });
    assert(second.pop()->size() == 960);
    second.stop();
    std::cout << "bounded backlog, silent shutdown, independent fanout and remaining client passed\n";
}
