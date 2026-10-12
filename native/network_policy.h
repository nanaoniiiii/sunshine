#pragma once
#include <algorithm>
#include <array>
#include <cstdint>
#include <optional>
#include <string_view>

namespace sunshine_android {
inline uint16_t read_be16(std::string_view bytes, size_t offset) {
    return (uint16_t(uint8_t(bytes[offset])) << 8) | uint8_t(bytes[offset + 1]);
}
inline uint32_t read_be32(std::string_view bytes, size_t offset) {
    return (uint32_t(read_be16(bytes, offset)) << 16) | read_be16(bytes, offset + 2);
}
struct FecReport {
    uint32_t frame;
    int data, parity, received_data, received_parity, percentage, block, blocks;
    int missing_data() const { return data - received_data; }
    bool unrecoverable() const { return received_data + received_parity < data; }
};
// Moonlight SS_FRAME_FEC_STATUS is a packed 21-byte, big-endian payload.
inline std::optional<FecReport> parse_fec_report(std::string_view bytes) {
    if (bytes.size() != 21) return {};
    FecReport r{read_be32(bytes, 0), read_be16(bytes, 10), read_be16(bytes, 12),
        read_be16(bytes, 14), read_be16(bytes, 16), uint8_t(bytes[18]), uint8_t(bytes[19]), uint8_t(bytes[20])};
    if (r.data <= 0 || r.data + r.parity > 255 || r.received_data > r.data ||
        r.received_parity > r.parity || r.percentage > 100 || r.blocks < 1 || r.blocks > 4 || r.block >= r.blocks) return {};
    return r;
}
inline int nominal_budget(int video_kbps, int fec) { return int((int64_t(video_kbps) * (100 + fec) + 99) / 100); }
inline int encoding_budget(int total_kbps, int fec) { return int(int64_t(total_kbps) * 100 / (100 + fec)); }
inline int pacing_budget(int video_fec_kbps) { return video_fec_kbps+std::min(500,video_fec_kbps/10); }
inline int64_t pacing_delay_ns(uint64_t packets, uint64_t packet_bytes, int kbps) {
    return int64_t(packets * packet_bytes * 8000000ULL / uint64_t(std::max(1, kbps)));
}
struct NetworkPolicy {
    int fec = 20, base_fec = 20, total_budget = 0, pending_fec = -1, pending_target = 0;
    bool adaptive = false;
    uint64_t reports = 0, duplicate_reports = 0, rejected_reports = 0, recovered_blocks = 0, unrecoverable_blocks = 0, missing_data = 0, adaptations = 0;
    uint32_t last_sent_frame = 0;
    bool has_sent_frame = false;
    std::array<uint64_t,256> report_keys{};
    size_t key_next = 0, key_count = 0;
    int rtt = -1;
    int64_t rtt_at = -1, report_at = -1, last_bad = -1, last_change = -1, last_sent_at = -1;
    const char* state = "disabled";
    void initialize(int negotiated, int initial_fec) {
        fec = base_fec = std::clamp(initial_fec,0,100);
        total_budget = nominal_budget(negotiated, base_fec);
    }
    bool feedback(const FecReport& report, int fps, int64_t now) {
        if (!has_sent_frame || last_sent_at<0 || now-last_sent_at>5000 || int32_t(last_sent_frame - report.frame) < 0 || uint32_t(last_sent_frame - report.frame) > uint32_t(std::max(1,fps)*5)) {
            ++rejected_reports; return false;
        }
        uint64_t key = (uint64_t(report.frame) << 2) | report.block;
        if (std::find(report_keys.begin(),report_keys.begin()+key_count,key) != report_keys.begin()+key_count) { ++duplicate_reports; return false; }
        report_keys[key_next] = key; key_next = (key_next+1)%report_keys.size(); key_count=std::min(key_count+1,report_keys.size());
        ++reports; missing_data += report.missing_data(); report_at=now;
        if (report.unrecoverable()) ++unrecoverable_blocks; else ++recovered_blocks;
        if (report.missing_data() > 0) last_bad=now;
        return true;
    }
    void enabled(bool value, int64_t now) {
        adaptive=value; pending_fec=-1; state=value?"waiting_feedback":"disabled"; last_change=now;
    }
    // Returns a bitrate target; FEC commits only after a successful driver update.
    int propose_fec(int percentage, int current, int floor) {
        int target=std::min(current,encoding_budget(total_budget,percentage));
        if (target < floor) { state="floor_limited"; return 0; }
        pending_fec=percentage; pending_target=target; return target;
    }
    int react(const FecReport& report,int current,int floor,int64_t now) {
        if (!adaptive || report.missing_data()==0 || (last_change>=0 && now-last_change<2000) || pending_fec>=0) return 0;
        int next_fec=std::max(fec,std::min(35,fec+(report.unrecoverable()?5:2)));
        int target=std::max(floor,std::min(current*85/100,encoding_budget(total_budget,next_fec)));
        if (nominal_budget(target,next_fec)>total_budget) next_fec=fec;
        if(target==current && next_fec==fec) { state="floor_limited"; return 0; }
        pending_fec=next_fec; pending_target=target; last_change=now; state="loss_backoff"; return target;
    }
    int recover(int current,int ceiling,int64_t now) {
        // A quiet loss-report channel is not proof of zero video loss. Probe slowly
        // only with a fresh, healthy reliable-control RTT and advancing video output.
        if (!adaptive || pending_fec>=0 || rtt_at<0 || now-rtt_at>5000 || rtt>100 ||
            last_bad<0 || now-last_bad<20000 || now-last_change<5000 || (current>=ceiling && fec<=base_fec)) return 0;
        int next_fec=std::max(base_fec,fec-1);
        int target=std::min({ceiling,encoding_budget(total_budget,next_fec),current+std::max(100,current/20)});
        if (target<=current && next_fec==fec) return 0;
        last_change=now; state="cautious_probe"; pending_fec=next_fec; pending_target=target;
        return target;
    }
    void driver_result(int target,bool success) {
        if (pending_fec<0 || target!=pending_target) return;
        if (success) { adaptations+=(fec!=pending_fec); fec=pending_fec; } else state="driver_rejected";
        pending_fec=-1;
    }
};
}
