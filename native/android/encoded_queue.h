#pragma once
#include "video.h"
#include "network_policy.h"
#include "datagram_batch.h"
#include <string_view>
namespace android_host {
void record_video_send_buffer(int bytes);
void reset_video_queue();
void stop_video_queue();
void push_video_packet(video::packet_t packet);
video::packet_t pop_video_packet();
video::packet_t pop_video_packet(void* channel);
void open_video_channel(void* channel);
void remove_video_channel(void* channel);
void protect_video_recovery(void* channel,int milliseconds);
void wait_video_packet(void* channel,int64_t nanoseconds);
void record_pacing_slack(void* channel,int64_t observed,int64_t applied,bool reset);
void record_video_stage(void* channel,int stage,double milliseconds);
int64_t video_thread_cpu_ns();
void record_network_send(void* channel,uint64_t data,uint64_t parity,uint64_t bytes,uint64_t errors,uint64_t retries,uint64_t syscalls,double fec_ms,double pacing_ms,double send_ms,double frame_ms,bool fec_skipped,int last_errno);
uint32_t datagram_retries();
sunshine_android::BatchResult send_video_batch(platf::batched_send_info_t& batch);
int pacing_for_channel(void* channel);
void record_frame_sent(void* channel,uint32_t frame);
void network_feedback(void* channel,uint16_t type,std::string_view payload);
void network_rtt(void* channel,int rtt);
int fec_for_channel(void* channel);
}
