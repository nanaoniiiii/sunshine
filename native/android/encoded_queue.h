#pragma once
#include "video.h"
#include <string_view>
namespace android_host {
void reset_video_queue();
void stop_video_queue();
void push_video_packet(video::packet_t packet);
video::packet_t pop_video_packet();
void remove_video_channel(void* channel);
void record_network_send(void* channel,uint64_t data,uint64_t parity,uint64_t bytes,uint64_t errors,double fec_ms,double send_ms);
void record_frame_sent(void* channel,uint32_t frame);
void network_feedback(void* channel,uint16_t type,std::string_view payload);
void network_rtt(void* channel,int rtt);
int fec_for_channel(void* channel);
}
