#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace sunshine_android {
struct VideoPolicy {
    int minimum = 5000, maximum = 50000, max_fps = 240;
    int ceiling(int negotiated) const { return std::max(1, std::min(negotiated, maximum)); }
    int floor(int negotiated) const { return std::min(minimum, ceiling(negotiated)); }
    int bitrate(int negotiated, int override_kbps = 0) const {
        return std::clamp(override_kbps > 0 ? override_kbps : negotiated, floor(negotiated), ceiling(negotiated));
    }
    int fps(int requested,int source_limit=0) const {
        auto ceiling=source_limit>0?std::min(max_fps,source_limit):max_fps;
        return std::clamp(requested,1,std::max(1,ceiling));
    }
};

// MediaCodec offset/size are signed; check without addition overflow.
inline bool valid_output_range(int32_t offset, int32_t size, std::size_t capacity) {
    return offset >= 0 && size >= 0 && static_cast<std::size_t>(offset) <= capacity &&
        static_cast<std::size_t>(size) <= capacity - static_cast<std::size_t>(offset);
}

class OutputDelayWindow {
    std::array<double, 256> samples_{};
    std::size_t next_ = 0, count_ = 0;
public:
    bool add(double milliseconds) {
        if (!std::isfinite(milliseconds) || milliseconds < 0 || milliseconds > 10000) return false;
        samples_[next_] = milliseconds;
        next_ = (next_ + 1) % samples_.size();
        count_ = std::min(count_ + 1, samples_.size());
        return true;
    }
    std::size_t count() const { return count_; }
    double p95() const {
        if (!count_) return 0;
        std::vector<double> sorted(samples_.begin(), samples_.begin() + count_);
        std::sort(sorted.begin(), sorted.end());
        return sorted[static_cast<std::size_t>(std::ceil(count_ * .95)) - 1];
    }
};

inline std::string json_quote(const std::string& input) {
    std::string out = "\"";
    constexpr char hex[] = "0123456789abcdef";
    for (unsigned char ch : input) {
        if (ch == '\\' || ch == '"') { out += '\\'; out += ch; }
        else if (ch < 32) { out += "\\u00"; out += hex[ch >> 4]; out += hex[ch & 15]; }
        else out += ch;
    }
    return out + '"';
}

// On failure the caller retries only the unsent suffix of the batch.
template<class Batch>
inline void advance_batch(Batch& batch, std::size_t sent) {
    sent = std::min(sent, batch.block_count);
    batch.block_offset += sent;
    batch.block_count -= sent;
}
}
