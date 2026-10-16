#include "../video_policy.h"
#include "../video_controls.h"
#include <cassert>
#include <limits>
#include <vector>
#include <thread>

int main() {
    using namespace sunshine_android;
    VideoPolicy policy{5000, 15000, 90};
    assert(policy.bitrate(30000) == 15000);
    assert(policy.bitrate(3000) == 3000); // Never exceed the client's negotiated budget.
    assert(policy.bitrate(10000, 7000) == 7000);
    assert(policy.bitrate(10000, 30000) == 10000);
    policy.maximum = 4000;
    assert(policy.bitrate(10000, 7000) == 4000);
    assert(policy.fps(30) == 30 && policy.fps(120) == 90);
    assert(policy.fps(120,60)==60&&policy.fps(30,60)==30);
    assert(policy.fps(120,120)==90&&policy.fps(120,0)==90);
    assert(policy.fps(120,-1)==90&&policy.fps(120,1)==1);
    assert(valid_output_range(3, 5, 8));
    assert(!valid_output_range(-1, 1, 8));
    assert(!valid_output_range(4, 5, 8));
    assert(!valid_output_range(1, -1, 8));
    assert(valid_output_range(8, 0, 8));
    OutputDelayWindow window;
    assert(!window.add(-1) && !window.add(std::numeric_limits<double>::infinity()));
    assert(!window.add(std::numeric_limits<double>::quiet_NaN()));
    for (int i = 1; i <= 256; ++i) window.add(i);
    assert(window.count() == 256 && window.p95() == 244);
    for (int i = 0; i < 256; ++i) window.add(7);
    assert(window.count() == 256 && window.p95() == 7);
    assert(json_quote("a\n\"\\") == "\"a\\u000a\\\"\\\\\"");
    struct Batch { std::size_t block_offset, block_count; } batch{10, 7};
    std::vector<std::size_t> delivered;
    for (int i = 0; i < 3; ++i) delivered.push_back(batch.block_offset + i);
    advance_batch(batch, 3);
    assert(batch.block_offset == 13 && batch.block_count == 4);
    // Simulate batched-send failure followed by the individual-send fallback.
    for (std::size_t i = 0; i < batch.block_count; ++i) delivered.push_back(batch.block_offset + i);
    assert((delivered == std::vector<std::size_t>{10,11,12,13,14,15,16}));
    advance_batch(batch, 4);
    assert(batch.block_count == 0 && batch.block_offset == 17);
    advance_batch(batch, 0);
    assert(batch.block_count == 0);
    configure_video(5000, 50000, 240);
    auto session = std::make_shared<VideoSession>();
    session->negotiated = session->applied = 10000;
    bool disconnected = false;
    session->disconnect = [&] { disconnected = true; configure_video(5000, 50000, 240); };
    register_video(session);
    std::thread writer([&]{for(int n=0;n<500;++n)record_video_output(session,100,false,0);});
    for(int n=0;n<100;++n){auto value=video_snapshot();assert(value.front()=='['&&value.back()==']');assert(value.find("\"session_id\":"+std::to_string(session->id))!=std::string::npos);}
    writer.join();assert(session->frames==500);
    auto first_id = session->id;
    assert(!control_video(first_id, 1, 11000));
    assert(!control_video(first_id, 1, 4000));
    assert(control_video(first_id, 1, 7000));
    assert(control_video(first_id, 2, 0) && control_video(first_id, 2, 0));
    auto command = take_video_command(session);
    assert(command.bitrate == 7000 && command.idr);
    assert(take_video_command(session).bitrate == 0 && !take_video_command(session).idr);
    configure_video(1000, 4000, 30);
    assert(take_video_command(session).bitrate == 4000);
    assert(session->applied == 10000); // Queued is not the same as driver-applied.
    assert(control_video(first_id, 3, 0) && disconnected); // Callback runs outside registry lock.
    unregister_video(first_id);
    assert(!control_video(first_id, 2, 0) && video_snapshot() == "[]");
    register_video(session);
    assert(first_id != session->id && !control_video(first_id, 2, 0));
    unregister_video(session->id);
}
