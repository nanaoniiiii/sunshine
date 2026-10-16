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
// Shape bursts, not the encoder's long-term average. Leave room for parity
// rounding, codec VBV variation and scheduler overhead instead of creating a
// second rate-control queue behind the encoder. This is an 80Mbps-or-higher
// instantaneous burst rate, not a claim about the peer's measured bandwidth.
inline int pacing_budget(int video_fec_kbps) { return std::max(80000,video_fec_kbps*2+500); }
// Scene-change P-frames can exceed IDRs in size. Bound the burst estimate for
// every frame, targeting 20ms and capped at 160Mbps without lowering the base.
inline int frame_pacing_kbps(int base, uint64_t wire_bytes) {
    auto burst = int(std::min<uint64_t>(160000, (wire_bytes * 8 + 19) / 20));
    return std::max(base, burst);
}
// The protocol supports four FEC blocks. Preserve upstream's three-block split
// for ordinary large frames, but use the fourth when needed to keep each block
// within Reed-Solomon's 255-shard limit. Larger frames may still exceed it.
inline size_t video_fec_blocks(size_t packets,size_t percentage,size_t minimum_parity) {
    if(packets<=90)return 1;
    auto capacity=std::max<size_t>(1,std::min(255-std::min<size_t>(254,minimum_parity),25500/(100+percentage)));
    return std::clamp<size_t>((packets+capacity-1)/capacity,3,4);
}
inline int64_t pacing_delay_ns(uint64_t packets, uint64_t packet_bytes, int kbps) {
    return int64_t(packets * packet_bytes * 8000000ULL / uint64_t(std::max(1, kbps)));
}
// One instance per complete video frame, shared by its FEC blocks. A late
// sender starts a new burst from now instead of catching up in a packet flood.
struct DatagramPacer {
    int kbps;
    uint64_t packets=0;
    static constexpr uint64_t burst_packets=16;
    int64_t deadline_ns=0;
    explicit DatagramPacer(int rate):kbps(rate) {}
    int64_t wait_ns(int64_t now) const { return packets%burst_packets==0?std::max<int64_t>(0,deadline_ns-now):0; }
    void account(size_t bytes,int64_t now) {
        if(packets%burst_packets==0)deadline_ns=std::max(deadline_ns,now);
        deadline_ns+=pacing_delay_ns(1,bytes,kbps);
        ++packets;
    }
};
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
    int64_t queue_at=-1, pressure_at=-1, stable_at=-1, loss_window_at=-1, rtt_pressure_at=-1;
    double queue_age=0;
    size_t queue_depth=0;
    int pressure_samples=0, loss_events=0, loss_unrecoverable=0, baseline_rtt=-1;
    uint64_t control_updates=0;
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
        pressure_at=stable_at=loss_window_at=rtt_pressure_at=-1;
        pressure_samples=loss_events=loss_unrecoverable=0;
    }
    // Returns a bitrate target; FEC commits only after a successful driver update.
    int propose_fec(int percentage, int current, int floor) {
        int target=std::min(current,encoding_budget(total_budget,percentage));
        if (target < floor) { state="floor_limited"; return 0; }
        pending_fec=percentage; pending_target=target; return target;
    }
    int react(const FecReport& report,int current,int floor,int64_t now) {
        if(!adaptive || report.missing_data()==0 || pending_fec>=0 || (last_change>=0 && now-last_change<2000)) return 0;
        if(loss_window_at<0 || now-loss_window_at>2000) { loss_window_at=now; loss_events=loss_unrecoverable=0; }
        ++loss_events; loss_unrecoverable+=report.unrecoverable();
        bool severe=report.unrecoverable() && report.missing_data()*5>=report.data;
        if(!severe && (now-loss_window_at<200 || (loss_events<3 && loss_unrecoverable<2))) { state="watching_loss"; return 0; }
        int next_fec=std::max(fec,std::min(35,fec+(report.unrecoverable()?5:2)));
        int target=std::max(floor,std::min(current*85/100,encoding_budget(total_budget,next_fec)));
        if (nominal_budget(target,next_fec)>total_budget) next_fec=fec;
        if(target==current && next_fec==fec) { state="floor_limited"; return 0; }
        pending_fec=next_fec; pending_target=target; last_change=now; state="loss_backoff";
        loss_window_at=-1; return target;
    }
    int congestion(int current,int floor,int64_t now,const char* reason) {
        last_bad=now;
        if(!adaptive || pending_fec>=0 || now-last_change<2000) return 0;
        int target=std::max(floor,current*90/100);
        if(target>=current) { state="floor_limited"; return 0; }
        pending_fec=fec; pending_target=target; last_change=now; state=reason; return target;
    }
    int observe_queue(double age,size_t depth,int current,int floor,int64_t now,bool ready=true) {
        queue_at=now; queue_age=age; queue_depth=depth;
        if(age>=40 || depth>=3) {
            stable_at=-1;
            if(pressure_at<0 || now-pressure_at>2000) { pressure_at=now; pressure_samples=0; }
            ++pressure_samples;
            if(now-pressure_at>=500 && pressure_samples>=4 && ready) return congestion(current,floor,now,"queue_backoff");
        } else if(age<=15 && depth<=1) {
            if(stable_at<0) stable_at=now;
            if(now-stable_at>=500) { pressure_at=-1; pressure_samples=0; }
        } else stable_at=-1;
        return 0;
    }
    int observe_rtt(int value,int current,int floor,int64_t now,bool ready=true) {
        rtt=value; rtt_at=now;
        baseline_rtt=baseline_rtt<0?value:std::min(baseline_rtt,value);
        if(value>std::max(100,baseline_rtt*2+20)) {
            if(rtt_pressure_at<0) rtt_pressure_at=now;
            if(now-rtt_pressure_at>=1000 && ready) return congestion(current,floor,now,"rtt_backoff");
        } else rtt_pressure_at=-1;
        return 0;
    }
    int recover(int current,int ceiling,int64_t now) {
        // A quiet loss-report channel is not proof of zero video loss. Probe slowly
        // only with a fresh, healthy reliable-control RTT and advancing video output.
        if (!adaptive || pending_fec>=0 || rtt_at<0 || now-rtt_at>5000 || rtt>std::max(100,baseline_rtt*2+20) ||
            queue_at<0 || now-queue_at>2000 || queue_age>15 || queue_depth>1 ||
            last_bad<0 || now-last_bad<20000 || now-last_change<5000 || (current>=ceiling && fec<=base_fec)) return 0;
        int next_fec=std::max(base_fec,fec-1);
        int target=std::min({ceiling,encoding_budget(total_budget,next_fec),current+std::max(100,current/20)});
        if (target<=current && next_fec==fec) return 0;
        last_change=now; state="cautious_probe"; pending_fec=next_fec; pending_target=target;
        return target;
    }
    void driver_result(int target,bool success) {
        if (pending_fec<0 || target!=pending_target) return;
        if (success) { adaptations+=(fec!=pending_fec); ++control_updates; fec=pending_fec; } else state="driver_rejected";
        pending_fec=-1;
    }
};
}
