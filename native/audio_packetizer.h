#pragma once
#include <algorithm>
#include <cstddef>
#include <stdexcept>
#include <vector>

namespace sunshine_android {
// Each client owns its partial packet. Captured stereo is never consumed by
// another client's encoder, including when clients negotiate different durations.
class AudioPacketizer {
public:
    AudioPacketizer(std::size_t frames, std::size_t channels)
        : frames_(frames), channels_(channels) {
        if (frames == 0 || frames > 4800 ||
            (channels != 2 && channels != 6 && channels != 8))
            throw std::invalid_argument("Unsupported audio packet shape");
        pending_.reserve(frames * 2);
    }

    template<class Sink>
    void append(const float* stereo, std::size_t count, Sink&& sink) {
        // Android capture returns complete interleaved stereo sample frames.
        count -= count % 2;
        while (count) {
            const auto take = std::min(count, frames_ * 2 - pending_.size());
            pending_.insert(pending_.end(), stereo, stereo + take);
            stereo += take;
            count -= take;
            if (pending_.size() != frames_ * 2) continue;
            std::vector<float> packet(frames_ * channels_, 0.f);
            for (std::size_t frame = 0; frame < frames_; ++frame) {
                packet[frame * channels_] = pending_[frame * 2];
                packet[frame * channels_ + 1] = pending_[frame * 2 + 1];
            }
            pending_.clear();
            sink(std::move(packet));
        }
    }
    std::size_t pendingFloats() const { return pending_.size(); }
private:
    std::size_t frames_, channels_;
    std::vector<float> pending_;
};
}
