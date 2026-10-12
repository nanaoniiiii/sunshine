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
    q.remove(a); assert(q.push(std::make_unique<int>(18),a,1,false)); assert(*q.pop()==18);
    q.invalidate(a); assert(!q.push(std::make_unique<int>(19),a,1,false));
    assert(q.push(std::make_unique<int>(20),a,1,true)); assert(*q.pop()==20);
    q.push(std::make_unique<int>(21),a,1,false); now+=81;
    auto consumer=std::async(std::launch::async,[&] { return q.pop(); });
    assert(consumer.wait_for(std::chrono::milliseconds(30))==std::future_status::timeout); // Expired frame cannot escape.
    q.stop(); assert(!consumer.get()); assert(!q.push(std::make_unique<int>(22),a,1,true));
    q.reset(); q.push(std::make_unique<int>(23),a,1,true); assert(*q.pop()==23); q.stop();
}
