#pragma once
#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <functional>
#include <map>
#include <mutex>
#include <set>
#include <vector>

namespace sunshine_android {
struct VideoQueueStats {
    size_t frames=0, bytes=0, peak_frames=0, peak_bytes=0;
    uint64_t dropped=0, revision=0;
    double age_ms=0;
    bool waiting_idr=false;
    int64_t last_request=-1000;
};
// Encoded reference frames are not independent images: after discarding a chain,
// reject dependent frames until a new IDR. Each client's chain is isolated.
template<class Packet> class FreshVideoQueue {
    struct Entry { Packet packet; void* channel; size_t bytes; int64_t at,expires; };
    std::deque<Entry> entries;
    std::map<void*,VideoQueueStats> states;
    std::set<void*> closed;
    std::map<void*,int64_t> recovery_until;
    std::mutex mutex;
    std::condition_variable cv;
    bool running=false;
    std::function<int64_t()> clock;
    std::function<void(void*,VideoQueueStats,bool,double)> observe;
    void refresh(void* channel,VideoQueueStats& s,int64_t now) {
        s.age_ms=0;
        for(auto& e:entries) if(e.channel==channel) { s.age_ms=std::max<int64_t>(0,now-e.at); break; }
        ++s.revision;
    }
    void discard(void* channel,VideoQueueStats& s,std::deque<Entry>& retired) {
        for(auto it=entries.begin();it!=entries.end();) {
            if(it->channel!=channel) { ++it; continue; }
            retired.push_back(std::move(*it)); it=entries.erase(it); ++s.dropped;
        }
        s.frames=s.bytes=0; s.waiting_idr=true;
    }
    static bool request(VideoQueueStats& s,int64_t now) {
        if(!s.waiting_idr || now-s.last_request<1000) return false;
        s.last_request=now; return true;
    }
public:
    static constexpr int max_age_ms=80;
    static constexpr int max_recovery_ms=250;
    static constexpr size_t max_frames=8, max_bytes=8*1024*1024;
    explicit FreshVideoQueue(std::function<void(void*,VideoQueueStats,bool,double)> callback,
        std::function<int64_t()> now=[] { return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count(); })
        :clock(std::move(now)),observe(std::move(callback)) {}
    void reset() {
        std::deque<Entry> retired;
        {std::lock_guard lock(mutex);retired.swap(entries);states.clear();closed.clear();recovery_until.clear();running=true;}
    }
    void open(void* channel) {std::lock_guard lock(mutex);closed.erase(channel);states.erase(channel);recovery_until.erase(channel);}
    // A large IDR already on the wire cannot be replaced. Preserve its bounded
    // dependent chain for its estimated send time instead of repeatedly asking
    // for another equally large IDR. Ordinary frames retain the 80ms deadline.
    void protect_recovery(void* channel,int duration_ms) {
        if(duration_ms<=max_age_ms)return;
        std::lock_guard lock(mutex);if(!running||closed.count(channel))return;
        auto until=clock()+std::clamp(duration_ms,max_age_ms,max_recovery_ms);
        recovery_until[channel]=until;
        for(auto& e:entries)if(e.channel==channel)e.expires=std::max(e.expires,std::min(e.at+max_recovery_ms,until));
    }
    bool push(Packet packet,void* channel,size_t bytes,bool idr) {
        std::deque<Entry> retired;
        VideoQueueStats snapshot; bool accepted=false, request_idr=false;
        {
            std::lock_guard lock(mutex); if(!running||closed.count(channel)) return false;
            auto now=clock(); auto& s=states[channel]; refresh(channel,s,now);
            auto oldest=std::find_if(entries.begin(),entries.end(),[&](const Entry& e){return e.channel==channel;});
            if((oldest!=entries.end()&&now>oldest->expires) || s.frames>=max_frames || bytes>max_bytes || s.bytes>max_bytes-bytes)
                discard(channel,s,retired);
            if(idr && bytes<=max_bytes) s.waiting_idr=false;
            if(!s.waiting_idr && bytes<=max_bytes) {
                auto expiry=std::max(now+max_age_ms,recovery_until[channel]);
                entries.push_back({std::move(packet),channel,bytes,now,expiry}); ++s.frames; s.bytes+=bytes;
                s.peak_frames=std::max(s.peak_frames,s.frames); s.peak_bytes=std::max(s.peak_bytes,s.bytes); accepted=true;
            } else ++s.dropped;
            request_idr=request(s,now); refresh(channel,s,now); snapshot=s;
        }
        observe(channel,snapshot,request_idr,-1); if(accepted) cv.notify_all(); return accepted;
    }
    Packet pop() {
        for(;;) {
            std::deque<Entry> retired;
            Packet result{}; void* channel=nullptr; VideoQueueStats snapshot; bool request_idr=false; double delay=-1;
            {
                std::unique_lock lock(mutex); cv.wait(lock,[&] { return !running || !entries.empty(); });
                if(!running) return {};
                channel=entries.front().channel; auto& s=states[channel]; auto now=clock();
                delay=std::max<int64_t>(0,now-entries.front().at);
                if(now>entries.front().expires) { discard(channel,s,retired); request_idr=request(s,now); delay=-1; }
                else {
                    auto entry=std::move(entries.front()); entries.pop_front(); --s.frames; s.bytes-=entry.bytes;
                    result=std::move(entry.packet);
                }
                refresh(channel,s,now); snapshot=s;
            }
            observe(channel,snapshot,request_idr,delay);
            if(result) return result;
        }
    }
    Packet pop_for(void* channel) {
        for(;;) {
            std::deque<Entry> retired;Packet result{};VideoQueueStats snapshot;bool request_idr=false;double delay=-1;
            {
                std::unique_lock lock(mutex);
                auto find=[&]{return std::find_if(entries.begin(),entries.end(),[&](const Entry& e){return e.channel==channel;});};
                cv.wait(lock,[&]{return !running||closed.count(channel)||find()!=entries.end();});
                if(!running||closed.count(channel))return {};
                auto it=find();auto& s=states[channel];auto now=clock();delay=std::max<int64_t>(0,now-it->at);
                if(now>it->expires){discard(channel,s,retired);request_idr=request(s,now);delay=-1;}
                else {auto entry=std::move(*it);entries.erase(it);--s.frames;s.bytes-=entry.bytes;result=std::move(entry.packet);}
                refresh(channel,s,now);snapshot=s;
            }
            observe(channel,snapshot,request_idr,delay);
            if(result)return result;
        }
    }
    void remove(void* channel) {
        std::deque<Entry> retired;
        { std::lock_guard lock(mutex); closed.insert(channel);recovery_until.erase(channel);auto it=states.find(channel); if(it!=states.end()) { discard(channel,it->second,retired); states.erase(it); } }
        cv.notify_all();
    }
    void invalidate(void* channel) {
        std::deque<Entry> retired; VideoQueueStats snapshot; bool notify=false;
        { std::lock_guard lock(mutex); if(!running) return; recovery_until.erase(channel);auto& s=states[channel];
          auto now=clock(); discard(channel,s,retired); notify=request(s,now); refresh(channel,s,now); snapshot=s; }
        observe(channel,snapshot,notify,-1);
    }
    void stop() {
        std::deque<Entry> retired;
        { std::lock_guard lock(mutex); running=false; retired.swap(entries); states.clear(); recovery_until.clear(); }
        cv.notify_all();
    }
};
}
