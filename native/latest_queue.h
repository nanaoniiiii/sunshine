#pragma once
#include <condition_variable>
#include <deque>
#include <mutex>
#include <optional>
#include <algorithm>

namespace sunshine_android {
template<class T> class LatestQueue {
    std::mutex mutex;
    std::condition_variable ready;
    std::deque<T> items;
    size_t capacity;
    bool running=true;
public:
    explicit LatestQueue(size_t limit):capacity(std::max<size_t>(1,limit)) {}
    void raise(T value) {
        std::lock_guard lock(mutex);
        if(!running)return;
        while(items.size()>=capacity)items.pop_front();
        items.push_back(std::move(value));ready.notify_one();
    }
    std::optional<T> pop() {
        std::unique_lock lock(mutex);
        ready.wait(lock,[&]{return !running||!items.empty();});
        if(!running)return {};
        T value=std::move(items.front());items.pop_front();return value;
    }
    void stop() {std::lock_guard lock(mutex);running=false;items.clear();ready.notify_all();}
};
}
