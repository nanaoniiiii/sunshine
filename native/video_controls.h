#pragma once
#include "video_policy.h"
#include "network_policy.h"
#include <chrono>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <sstream>
#include <locale>
#include <unistd.h>

// Included only by sunshine.cpp: one registry in the isolated engine process.
namespace sunshine_android {
using VideoClock = std::chrono::steady_clock;
struct VideoSession {
    void* channel = nullptr;
    NetworkPolicy network;
    uint64_t id = 0;
    int tid = 0, negotiated = 0, configured_fps = 0, requested_fps = 0;
    int desired = 0, applied = 0, override_kbps = 0, bitrate_status = 0, idr_status = 0;
    bool pending_idr = false;
    uint64_t revision = 0, attempted_revision = 0, frames = 0, bytes = 0, keyframes = 0, invalid_buffers = 0;
    uint64_t idr_requests = 0;
    std::string codec, encoder;
    std::function<void()> disconnect;
    OutputDelayWindow delay;
    VideoClock::time_point interval = VideoClock::now(), last_output = interval;
    uint64_t interval_frames = 0, interval_bytes = 0;
    double fps = 0, kbps = 0;
};
static std::mutex video_mutex;
static VideoPolicy video_policy;
static std::map<uint64_t, std::shared_ptr<VideoSession>> video_sessions;
static uint32_t next_video_id = 0;
inline int64_t video_now_ms() { return std::chrono::duration_cast<std::chrono::milliseconds>(VideoClock::now().time_since_epoch()).count(); }
inline int video_ceiling(const VideoSession& s) {
    return std::min(video_policy.ceiling(s.negotiated),encoding_budget(s.network.total_budget?s.network.total_budget:nominal_budget(s.negotiated,20),s.network.fec));
}

inline VideoPolicy current_video_policy() { std::lock_guard lock(video_mutex); return video_policy; }
inline void configure_video(int minimum, int maximum, int max_fps) {
    std::lock_guard lock(video_mutex);
    video_policy.maximum = std::clamp(maximum, 1000, 150000);
    video_policy.minimum = std::clamp(minimum, 1000, video_policy.maximum);
    video_policy.max_fps = std::clamp(max_fps, 15, 240);
    for (auto& [id, s] : video_sessions) {
        s->network.pending_fec=-1;
        if (video_ceiling(*s)<video_policy.floor(s->negotiated)) s->network.fec=s->network.base_fec;
        int target = std::min(video_policy.bitrate(s->negotiated, s->override_kbps),video_ceiling(*s));
        if (target != s->desired) { s->desired = target; ++s->revision; }
    }
}
inline void register_video(const std::shared_ptr<VideoSession>& s) {
    std::lock_guard lock(video_mutex);
    s->id = (static_cast<uint64_t>(getpid()) << 32) | ++next_video_id;
    if (!s->network.total_budget) s->network.initialize(s->negotiated,20);
    s->desired = video_policy.bitrate(s->negotiated, s->override_kbps);
    if (s->desired != s->applied) ++s->revision;
    video_sessions.emplace(s->id, s);
}
inline void unregister_video(uint64_t id) { std::lock_guard lock(video_mutex); video_sessions.erase(id); }
inline bool control_video(uint64_t id, int op, int value) {
    std::function<void()> stop;
    {
        std::lock_guard lock(video_mutex);
        auto it = video_sessions.find(id);
        if (it == video_sessions.end()) return false;
        auto& s = *it->second;
        if (op == 1) {
            // Reject out-of-budget manual requests; never silently exceed the negotiation.
            if (value < std::min(video_policy.floor(s.negotiated),video_ceiling(s)) || value > video_ceiling(s)) return false;
            s.network.enabled(false,video_now_ms());
            s.override_kbps = value;
            s.desired = value;
            ++s.revision; // An explicit retry is allowed after a driver rejection.
        } else if (op == 2) { s.pending_idr = true; ++s.idr_requests; }
        else if (op == 3) stop = s.disconnect;
        else if (op == 4) {
            if (value<0 || value>35) return false;
            if (std::min(s.desired,encoding_budget(s.network.total_budget,value))<video_policy.floor(s.negotiated)) return false;
            s.network.enabled(false,video_now_ms());
            int target=s.network.propose_fec(value,s.desired,std::min(video_policy.floor(s.negotiated),video_ceiling(s)));
            if (!target) return false;
            s.desired=s.override_kbps=target; ++s.revision;
        } else if (op == 5) {
            if (value!=0 && value!=1) return false;
            s.network.enabled(value==1,video_now_ms());
        }
        else return false;
    }
    if (stop) stop(); // Never call into another subsystem while holding the registry lock.
    return true;
}
struct VideoCommand { int bitrate = 0; uint64_t revision = 0; bool idr = false; };
inline VideoCommand take_video_command(const std::shared_ptr<VideoSession>& s) {
    std::lock_guard lock(video_mutex);
    VideoCommand command;
    if (s->revision != s->attempted_revision) {
        command.bitrate = s->desired;
        command.revision = s->attempted_revision = s->revision;
    }
    command.idr = s->pending_idr;
    s->pending_idr = false;
    return command;
}
inline void record_video_output(const std::shared_ptr<VideoSession>& s, size_t bytes, bool keyframe, int64_t pts) {
    auto now = VideoClock::now();
    std::lock_guard lock(video_mutex);
    ++s->frames; s->bytes += bytes; s->keyframes += keyframe;
    ++s->interval_frames; s->interval_bytes += bytes; s->last_output = now;
    double elapsed = std::chrono::duration<double>(now - s->interval).count();
    if (elapsed >= 1) {
        s->fps = s->interval_frames / elapsed;
        s->kbps = s->interval_bytes * 8.0 / elapsed / 1000;
        s->interval = now; s->interval_frames = s->interval_bytes = 0;
    }
    // EGL presentation timestamps and steady_clock use CLOCK_MONOTONIC on Android.
    if (pts > 0) s->delay.add((std::chrono::duration_cast<std::chrono::microseconds>(now.time_since_epoch()).count() - pts) / 1000.0);
}
inline std::string video_snapshot() {
    std::lock_guard lock(video_mutex);
    std::ostringstream out;
    out.imbue(std::locale::classic());
    out << '[';
    bool comma = false;
    for (auto& [id, ptr] : video_sessions) {
        auto& s = *ptr;
        bool stale = VideoClock::now() - s.last_output > std::chrono::seconds(2);
        if (comma) out << ','; comma = true;
        out << "{\"session_id\":" << id << ",\"thread_id\":" << s.tid
            << ",\"codec\":" << json_quote(s.codec) << ",\"encoder\":" << json_quote(s.encoder)
            << ",\"requested_fps\":" << s.requested_fps << ",\"configured_fps\":" << s.configured_fps
            << ",\"requested_bitrate_kbps\":" << s.negotiated << ",\"desired_bitrate_kbps\":" << s.desired
            << ",\"applied_bitrate_kbps\":" << s.applied << ",\"bitrate_status\":" << s.bitrate_status
            << ",\"idr_status\":" << s.idr_status << ",\"idr_requests\":" << s.idr_requests
            << ",\"fec_percentage\":" << s.network.fec << ",\"pending_fec_percentage\":" << s.network.pending_fec
            << ",\"nominal_video_fec_budget_kbps\":" << s.network.total_budget << ",\"control_ceiling_kbps\":" << video_ceiling(s)
            << ",\"adaptive_enabled\":" << (s.network.adaptive?"true":"false") << ",\"adaptive_state\":" << json_quote(s.network.state)
            << ",\"fec_reports\":" << s.network.reports << ",\"fec_recovered_blocks\":" << s.network.recovered_blocks
            << ",\"fec_unrecoverable_blocks\":" << s.network.unrecoverable_blocks << ",\"reported_missing_data_packets\":" << s.network.missing_data
            << ",\"fec_duplicate_reports\":" << s.network.duplicate_reports << ",\"fec_rejected_reports\":" << s.network.rejected_reports
            << ",\"fec_changes\":" << s.network.adaptations << ",\"control_rtt_ms\":";
        auto now=video_now_ms();
        if (s.network.rtt_at>=0 && now-s.network.rtt_at<=5000) out << s.network.rtt; else out << "null";
        out << ",\"fec_report_age_ms\":";
        if (s.network.report_at>=0) out << now-s.network.report_at; else out << "null";
        out
            << ",\"encoded_frames\":" << s.frames << ",\"encoded_bytes\":" << s.bytes
            << ",\"keyframes\":" << s.keyframes << ",\"invalid_buffers\":" << s.invalid_buffers
            << ",\"encoded_fps\":" << (stale ? 0 : s.fps) << ",\"encoded_bitrate_kbps\":" << (stale ? 0 : s.kbps)
            << ",\"submit_to_output_p95_ms\":";
        if (s.delay.count()) out << s.delay.p95(); else out << "null";
        out << '}';
    }
    out << ']'; return out.str();
}
}
