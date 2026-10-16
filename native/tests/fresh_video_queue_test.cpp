#include "../fresh_video_queue.h"
#include <cassert>
#include <future>
#include <memory>
#include <string>
using namespace sunshine_android;
int main() {
    int64_t now=0; void* a=(void*)1; void* b=(void*)2;
    std::map<void*,VideoQueueStats> stats; int requests=0;
    FreshVideoQueue<std::unique_ptr<int>> q([&](void* key,auto value,bool idr,double) { stats[key]=value; requests+=idr; },[&] { return now; });
    q.reset();
    assert(q.push(std::make_unique<int>(1),a,100,false)); assert(*q.pop()==1);
    for(int i=0;i<8;++i) assert(q.push(std::make_unique<int>(i),a,100,false));
    assert(q.push(std::make_unique<int>(99),b,100,true));
    assert(!q.push(std::make_unique<int>(9),a,100,false));
    assert(stats[a].dropped==9 && stats[a].waiting_idr && stats[a].frames==0 && requests==1);
    assert(*q.pop()==99); // Another client's chain survives.
    assert(!q.push(std::make_unique<int>(10),a,100,false) && requests==1);
    assert(q.push(std::make_unique<int>(11),a,100,true)); assert(*q.pop()==11);
    assert(q.push(std::make_unique<int>(12),a,100,false)); now=81;
    assert(!q.push(std::make_unique<int>(13),a,100,false));
    assert(stats[a].waiting_idr && stats[a].frames==0);
    assert(q.push(std::make_unique<int>(14),a,100,true)); assert(*q.pop()==14);
    assert(q.push(std::make_unique<int>(15),a,FreshVideoQueue<std::unique_ptr<int>>::max_bytes,true));
    assert(!q.push(std::make_unique<int>(16),a,1,false));
    assert(!q.push(std::make_unique<int>(17),a,FreshVideoQueue<std::unique_ptr<int>>::max_bytes+1,true));
    q.remove(a); assert(!q.push(std::make_unique<int>(18),a,1,false));q.open(a);assert(q.push(std::make_unique<int>(18),a,1,false)); assert(*q.pop()==18);
    q.invalidate(a); assert(!q.push(std::make_unique<int>(19),a,1,false));
    assert(q.push(std::make_unique<int>(20),a,1,true)); assert(*q.pop()==20);
    q.push(std::make_unique<int>(21),a,1,false); now+=81;
    auto consumer=std::async(std::launch::async,[&] { return q.pop(); });
    assert(consumer.wait_for(std::chrono::milliseconds(30))==std::future_status::timeout); // Expired frame cannot escape.
    q.stop(); assert(!consumer.get()); assert(!q.push(std::make_unique<int>(22),a,1,true));
    q.reset(); q.push(std::make_unique<int>(23),a,1,true); assert(*q.pop()==23); q.stop();

    struct OwnedPacket {std::shared_ptr<int> owner;};
    std::weak_ptr<int> weak;
    FreshVideoQueue<std::unique_ptr<OwnedPacket>> owned([](auto,auto,bool,double){});
    owned.reset();auto owner=std::make_shared<int>(42);weak=owner;
    owned.push(std::make_unique<OwnedPacket>(OwnedPacket{owner}),a,1,true);
    auto in_flight=owned.pop();owner.reset();owned.remove(a);
    assert(!weak.expired()); // Disconnect cannot destroy an in-flight packet's session.
    in_flight.reset();assert(weak.expired());owned.stop();

    FreshVideoQueue<std::unique_ptr<int>> separate([](auto,auto,bool,double){});
    separate.reset();separate.open(a);separate.open(b);
    auto a_wait=std::async(std::launch::async,[&]{return separate.pop_for(a);});
    auto b_wait=std::async(std::launch::async,[&]{return separate.pop_for(b);});
    separate.push(std::make_unique<int>(77),b,1,true);
    assert(b_wait.wait_for(std::chrono::seconds(1))==std::future_status::ready&&*b_wait.get()==77);
    assert(a_wait.wait_for(std::chrono::milliseconds(20))==std::future_status::timeout);
    separate.remove(a);assert(!a_wait.get());
    separate.push(std::make_unique<int>(88),b,1,false);assert(*separate.pop_for(b)==88);
    separate.stop();

    FreshVideoQueue<std::unique_ptr<int>> recovery([](auto,auto,bool,double){},[&]{return now;});
    recovery.reset();now=0;
    recovery.push(std::make_unique<int>(1),a,1,true);assert(*recovery.pop_for(a)==1);
    recovery.push(std::make_unique<int>(2),a,1,false);
    now=20;recovery.protect_recovery(a,160);
    now=120;assert(recovery.push(std::make_unique<int>(3),a,1,false));
    assert(*recovery.pop_for(a)==2&&*recovery.pop_for(a)==3);
    recovery.push(std::make_unique<int>(4),b,1,true);
    now=201;assert(!recovery.push(std::make_unique<int>(5),b,1,false)); // Other peer retains 80ms.
    recovery.push(std::make_unique<int>(6),a,1,false);
    now=282;assert(!recovery.push(std::make_unique<int>(7),a,1,false)); // Grace expires.
    recovery.push(std::make_unique<int>(8),a,1,true);assert(*recovery.pop_for(a)==8);
    recovery.push(std::make_unique<int>(9),a,1,false);
    now=350;recovery.protect_recovery(a,1000);
    now=533;assert(!recovery.push(std::make_unique<int>(10),a,1,false)); // Absolute 250ms cap.
    recovery.stop();
}
