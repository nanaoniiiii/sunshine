#ifndef SUNSHINE_ANDROID_VIDEO_H
#define SUNSHINE_ANDROID_VIDEO_H
#pragma once
#include "input.h"
#include "thread_safe.h"
#include "video_colorspace.h"
#include <vector>
#include <optional>
namespace video {
struct packet_raw_t {
    virtual ~packet_raw_t()=default;
    virtual bool is_idr()=0;
    virtual int64_t frame_index()=0;
    virtual uint8_t* data()=0;
    virtual size_t data_size()=0;
    struct replace_t { std::string_view old,_new; };
    std::vector<replace_t>* replacements=nullptr;
    void* channel_data=nullptr;
    bool after_ref_frame_invalidation=false;
    std::optional<std::chrono::steady_clock::time_point> frame_timestamp;
};
using packet_t=std::unique_ptr<packet_raw_t>;
struct packet_raw_generic: packet_raw_t {
    std::vector<uint8_t> bytes;
    int64_t index;
    bool key;
    packet_raw_generic(std::vector<uint8_t>&& value,int64_t frame,bool idr):bytes(std::move(value)),index(frame),key(idr){}
    bool is_idr() override {return key;}
    int64_t frame_index() override {return index;}
    uint8_t* data() override {return bytes.data();}
    size_t data_size() override {return bytes.size();}
};
struct hdr_info_raw_t {
    bool enabled;
    SS_HDR_METADATA metadata{};
    explicit hdr_info_raw_t(bool value):enabled(value){}
};
using hdr_info_t=std::unique_ptr<hdr_info_raw_t>;
struct config_t {
    int width,height,framerate,bitrate,slicesPerFrame,numRefFrames,encoderCscMode,videoFormat,dynamicRange;
    int appid=1;
    int audioPacketMs=10;
    std::string clientId;
    std::string clientAddress;
};
extern int active_hevc_mode,active_av1_mode;
extern bool last_encoder_probe_supported_ref_frames_invalidation;
void capture(safe::mail_t mail,config_t config,void* channel_data);
int probe_encoders();
}

#endif
