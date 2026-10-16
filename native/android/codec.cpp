#include "android_host.h"
#include "encoded_queue.h"
#include "video_controls.h"
#include "globals.h"
#include "logging.h"
#include "config.h"
#include "canvas_policy.h"
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <android/native_window_jni.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <cstring>

namespace {
using namespace sunshine_android;
FreshVideoQueue<video::packet_t> packets(record_queue);
int source_width=0,source_height=0,source_fps=0;
bool follow_orientation=true;
int fps_for(int requested) {
    std::lock_guard lock(sunshine_android::video_mutex);
    return sunshine_android::video_policy.fps(requested,source_fps);
}
std::pair<int,int> canvas_for(int width,int height) {
    std::lock_guard lock(video_mutex);
    return oriented_canvas(width,height,source_width,source_height,follow_orientation);
}
std::string java_string(JNIEnv* env,jstring s) {
    if(!s)return {};
    auto text=env->GetStringUTFChars(s,nullptr);std::string value=text?text:"";
    if(text)env->ReleaseStringUTFChars(s,text);return value;
}
std::vector<std::string> names(JNIEnv* env,jobjectArray source) {
    std::vector<std::string> out;if(!source)return out;
    for(int i=0;i<env->GetArrayLength(source);++i) {
        auto s=(jstring)env->GetObjectArrayElement(source,i);out.push_back(java_string(env,s));env->DeleteLocalRef(s);
    }return out;
}
}
namespace android_host {
void record_video_send_buffer(int bytes) {
    std::lock_guard lock(sunshine_android::video_mutex);
    sunshine_android::video_send_buffer_bytes=bytes;
    for(auto& [id,s]:sunshine_android::video_sessions)s->socket_send_buffer_bytes=bytes;
}
void configure_canvas(int width,int height,bool follow,int fps) {
    std::lock_guard lock(sunshine_android::video_mutex);
    source_width=width;source_height=height;follow_orientation=follow;source_fps=fps>0?std::clamp(fps,1,240):0;
}
std::string video_snapshot(){return sunshine_android::video_snapshot();}
bool video_control(int64_t id,int op,int value){return sunshine_android::control_video(id,op,value);}
void configure_video(int min,int max,int fps){sunshine_android::configure_video(min,max,fps);}
void configure_encoders(JNIEnv* e,jstring avc,jstring hevc,jobjectArray a,jobjectArray b) {
    sunshine_android::configure_encoders(java_string(e,avc),java_string(e,hevc),names(e,a),names(e,b));
}
void reset_video_queue(){packets.reset();}
void stop_video_queue(){packets.stop();}
void push_video_packet(video::packet_t packet){
    auto channel=packet->channel_data;auto size=packet->data_size();auto key=packet->is_idr();
    packets.push(std::move(packet),channel,size,key);
}
video::packet_t pop_video_packet(){return packets.pop();}
video::packet_t pop_video_packet(void* channel){return packets.pop_for(channel);}
void open_video_channel(void* channel){packets.open(channel);}
void remove_video_channel(void* channel){packets.remove(channel);}
void protect_video_recovery(void* channel,int milliseconds){packets.protect_recovery(channel,milliseconds);}
void record_video_stage(void* channel,int stage,double milliseconds){
    std::lock_guard lock(sunshine_android::video_mutex);
    for(auto& [id,s]:sunshine_android::video_sessions)if(s->channel==channel){
        if(stage==0)s->frame_prepare_delay.add(milliseconds);
        else if(stage==1)s->packet_header_delay.add(milliseconds);
        else if(stage==2)s->frame_cpu_delay.add(milliseconds);
        break;
    }
}
void record_pacing_slack(void* channel,int64_t observed,int64_t applied,bool reset){
    std::lock_guard lock(sunshine_android::video_mutex);
    for(auto& [id,s]:sunshine_android::video_sessions)if(s->channel==channel){
        s->pacing_slack_ns=applied;s->pacing_slack_peak_ns=std::max(s->pacing_slack_peak_ns,observed);s->pacing_slack_resets+=reset;break;
    }
}
void record_network_send(void* channel,uint64_t data,uint64_t parity,uint64_t bytes,uint64_t errors,uint64_t retries,uint64_t syscalls,double fec_ms,double pacing_ms,double send_ms,double frame_ms,bool fec_skipped,int last_errno) {
    sunshine_android::record_send(channel,data,parity,bytes,errors,fec_ms,pacing_ms,send_ms,frame_ms,fec_skipped);
    std::lock_guard lock(sunshine_android::video_mutex);
    for(auto& [id,s]:sunshine_android::video_sessions)if(s->channel==channel){s->send_buffer_retries+=retries;s->send_syscalls+=syscalls;if(last_errno)s->last_send_errno=last_errno;break;}
}
int pacing_for_channel(void* channel) {
    using namespace sunshine_android;
    std::lock_guard lock(video_mutex);
    for(auto& [id,s]:video_sessions)if(s->channel==channel)
        return pacing_budget(nominal_budget(s->applied,s->network.fec));
    return 10000;
}
void record_frame_sent(void* channel,uint32_t frame) {
    using namespace sunshine_android;std::lock_guard lock(video_mutex);
    for(auto& [id,s]:video_sessions)if(s->channel==channel) {
        s->network.has_sent_frame=true;s->network.last_sent_frame=frame;s->network.last_sent_at=video_now_ms();return;
    }
}
void network_feedback(void* channel,uint16_t type,std::string_view payload) {
    if(type!=0x5502)return;
    using namespace sunshine_android;auto report=parse_fec_report(payload);if(!report)return;
    std::lock_guard lock(video_mutex);
    for(auto& [id,s]:video_sessions)if(s->channel==channel) {
        auto now=video_now_ms();
        if(s->network.feedback(*report,s->configured_fps,now)) {
            auto target=s->network.react(*report,s->applied,video_policy.floor(s->negotiated),now);
            if(target){s->desired=s->override_kbps=target;++s->revision;}
        }return;
    }
}
void network_rtt(void* channel,int rtt) {
    using namespace sunshine_android;std::lock_guard lock(video_mutex);
    for(auto& [id,s]:video_sessions)if(s->channel==channel) {
        auto target=s->network.observe_rtt(rtt,s->applied,video_policy.floor(s->negotiated),video_now_ms(),s->desired==s->applied);
        if(target){s->desired=s->override_kbps=target;++s->revision;}return;
    }
}
int fec_for_channel(void* channel) {
    std::lock_guard lock(video_mutex);
    for(auto& [id,s]:video_sessions)if(s->channel==channel)return s->network.fec;
    return 20;
}
}
namespace video {
int active_hevc_mode=1,active_av1_mode=1;
bool last_encoder_probe_supported_ref_frames_invalidation=false;
int probe_encoders(){return 0;}
void capture(safe::mail_t mail,config_t config,void* channel) {
    using namespace sunshine_android;
    platf::adjust_thread_priority(platf::thread_priority_e::high);
    android_host::Jvm java;
    if(!java.env)return;
    auto channel_owner=android_host::retain_channel(channel);
    const bool hevc=config.videoFormat==1;
    const char* mime=hevc?"video/hevc":"video/avc";
    auto policy=current_video_policy();
    int fps=fps_for(config.framerate),bitrate=policy.bitrate(config.bitrate,0);
    AMediaCodec* codec=nullptr;ANativeWindow* window=nullptr;
    auto selection=encoder_options(hevc);
    std::vector<std::string> options;
    if(selection.first.empty()||selection.first=="auto") { options.push_back("");options.insert(options.end(),selection.second.begin(),selection.second.end()); }
    else options.push_back(selection.first);
    int attempts=0,selected_rate_mode=1;
    auto canvas=canvas_for(config.width,config.height);
    auto start_codec=[&]() {
    for(const auto& candidate:options) {
      for(int rate_attempt=0;rate_attempt<2;++rate_attempt) {
        ++attempts;
        codec=candidate.empty()?AMediaCodec_createEncoderByType(mime):AMediaCodec_createCodecByName(candidate.c_str());
        if(!codec)break;
        std::string actual_name;
        char* component_name=nullptr;
        if(AMediaCodec_getName(codec,&component_name)==AMEDIA_OK&&component_name) {
            actual_name=component_name;AMediaCodec_releaseName(codec,component_name);
        }
        int preferred_mode=android_host::bitrate_mode(actual_name,mime);
        int rate_mode=rate_attempt?1:preferred_mode;
        auto format=AMediaFormat_new();
        AMediaFormat_setString(format,"mime",mime);
        AMediaFormat_setInt32(format,"width",canvas.first);
        AMediaFormat_setInt32(format,"height",canvas.second);
        AMediaFormat_setInt32(format,"bitrate",bitrate*1000);
        AMediaFormat_setInt32(format,"frame-rate",fps);
        // Resource planning must reflect a realtime stream, rather than the
        // default best-effort workload when multiple codecs share the device.
        AMediaFormat_setInt32(format,"priority",0);
        AMediaFormat_setFloat(format,"operating-rate",float(fps));
        AMediaFormat_setInt32(format,"latency",1);
        // Match Sunshine's on-demand IDR model. A periodic full-resolution IDR
        // is a multi-megabyte burst on high-entropy content, even on a healthy
        // connection. Startup, decoder recovery and explicit requests still
        // generate keyframes through the existing request-sync path.
        AMediaFormat_setInt32(format,"i-frame-interval",-1);
        AMediaFormat_setInt32(format,"color-format",0x7f000789);
        AMediaFormat_setInt32(format,"color-standard",1);
        AMediaFormat_setInt32(format,"color-range",2);
        AMediaFormat_setInt32(format,"color-transfer",3);
        AMediaFormat_setInt32(format,"bitrate-mode",rate_mode);
        AMediaFormat_setInt32(format,"max-bframes",0);
        if(!hevc) {
            char* encoder_name=nullptr;
            if(AMediaCodec_getName(codec,&encoder_name)==AMEDIA_OK&&encoder_name) {
                int profile=android_host::avc_profile(encoder_name);
                if(profile)AMediaFormat_setInt32(format,"profile",profile);
                AMediaCodec_releaseName(codec,encoder_name);
            }
        }
        auto result=AMediaCodec_configure(codec,format,nullptr,nullptr,AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
        AMediaFormat_delete(format);
        if(result==AMEDIA_OK)result=AMediaCodec_createInputSurface(codec,&window);
        if(result==AMEDIA_OK)result=AMediaCodec_start(codec);
        if(result==AMEDIA_OK){selected_rate_mode=rate_mode;return true;}
        if(window){ANativeWindow_release(window);window=nullptr;}
        AMediaCodec_delete(codec);codec=nullptr;
        if(preferred_mode!=2)break;
      }
    }
    return false;
    };
    auto release_codec=[&]() {
        if(codec){AMediaCodec_stop(codec);AMediaCodec_delete(codec);codec=nullptr;}
        if(window){ANativeWindow_release(window);window=nullptr;}
    };
    if(!start_codec()){android_host::report_error("无法启动所选硬件编码器，请切换编码器");mail->event<bool>(mail::shutdown)->raise(true);return;}
    jobject surface=ANativeWindow_toSurface(java.env,window);
    if(!surface){AMediaCodec_stop(codec);AMediaCodec_delete(codec);ANativeWindow_release(window);return;}
    auto stats=std::make_shared<VideoSession>();
    stats->channel=channel;stats->tid=syscall(SYS_gettid);stats->negotiated=config.bitrate;
    stats->configured_fps=fps;stats->requested_fps=config.framerate;stats->codec=mime;
    stats->applied=bitrate;stats->encoder_choice=selection.first;stats->encoder_attempts=attempts;
    stats->bitrate_mode=selected_rate_mode;
    stats->canvas_width=canvas.first;stats->canvas_height=canvas.second;
    stats->requested_width=config.width;stats->requested_height=config.height;
    stats->network.initialize(config.bitrate,config::stream.fec_percentage);
    char* codec_name=nullptr;
    if(AMediaCodec_getName(codec,&codec_name)==AMEDIA_OK&&codec_name){stats->encoder=codec_name;AMediaCodec_releaseName(codec,codec_name);}
    auto shutdown=mail->event<bool>(mail::shutdown);
    stats->disconnect=[shutdown]{shutdown->raise(true);};
    register_video(stats);
    android_host::stream_started(java.env,canvas.first,canvas.second,config.framerate,config.audioPacketMs,surface,config.appid,config.clientId,config.clientAddress);
    android_host::client_discovered(config.clientAddress);
    auto update_input=[&]() {
        input::touch_port_t viewport;
        viewport.offset_x=viewport.offset_y=0;viewport.width=viewport.env_width=canvas.first;viewport.height=viewport.env_height=canvas.second;
        viewport.client_offsetX=viewport.client_offsetY=0;viewport.scalar_inv=1;
        mail->event<input::touch_port_t>(mail::touch_port)->raise(viewport);
    };
    update_input();
    auto idr=mail->event<bool>(mail::idr);
    std::vector<uint8_t> configuration;
    int64_t index=1;
    while(!shutdown->peek()&&!android_host::stopping) {
        auto next_canvas=canvas_for(config.width,config.height);
        auto next_fps=fps_for(config.framerate);
        if(next_canvas!=canvas||next_fps!=fps) {
            bool geometry_changed=next_canvas!=canvas;
            // Detach EGL synchronously before the owning thread stops its codec.
            // This changes only the surface; session, audio and RTP numbering survive.
            if(!android_host::stream_paused())break;
            java.env->DeleteLocalRef(surface);surface=nullptr;
            release_codec();packets.invalidate(channel);configuration.clear();
            canvas=next_canvas;fps=next_fps;
            {std::lock_guard lock(video_mutex);bitrate=stats->applied;}
            if(!start_codec()) {android_host::report_error("显示参数变化后无法重新启动编码器，请切换编码器");shutdown->raise(true);break;}
            surface=ANativeWindow_toSurface(java.env,window);
            if(!surface||!android_host::stream_resized(java.env,canvas.first,canvas.second,surface)) {shutdown->raise(true);break;}
            update_input();
            {std::lock_guard lock(video_mutex);stats->canvas_width=canvas.first;stats->canvas_height=canvas.second;stats->canvas_reconfigurations+=geometry_changed;++stats->encoder_reconfigurations;stats->configured_fps=fps;stats->encoder_attempts=attempts;stats->bitrate_mode=selected_rate_mode;}
        }
        auto command=take_video_command(stats);
        if(command.bitrate) {
            auto params=AMediaFormat_new();AMediaFormat_setInt32(params,"video-bitrate",command.bitrate*1000);
            auto result=AMediaCodec_setParameters(codec,params);AMediaFormat_delete(params);
            std::lock_guard lock(video_mutex);stats->bitrate_status=result;
            stats->network.driver_result(command.bitrate,result==AMEDIA_OK);
            if(result==AMEDIA_OK)stats->applied=command.bitrate;
        }
        if(idr->peek()||command.idr) {
            if(idr->peek())idr->pop();
            auto params=AMediaFormat_new();AMediaFormat_setInt32(params,"request-sync",0);
            auto result=AMediaCodec_setParameters(codec,params);AMediaFormat_delete(params);
            std::lock_guard lock(video_mutex);stats->idr_status=result;
        }
        AMediaCodecBufferInfo info{};
        auto buffer=AMediaCodec_dequeueOutputBuffer(codec,&info,20000);
        if(buffer==AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            auto format=AMediaCodec_getOutputFormat(codec);configuration.clear();
            int32_t reported_latency=-1;
            AMediaFormat_getInt32(format,"latency",&reported_latency);
            {std::lock_guard lock(video_mutex);stats->reported_latency=reported_latency;}
            for(auto key:{"csd-0","csd-1","csd-2"}) {
                void* data=nullptr;size_t size=0;
                if(AMediaFormat_getBuffer(format,key,&data,&size)&&data&&size)
                    configuration.insert(configuration.end(),(uint8_t*)data,(uint8_t*)data+size);
            }AMediaFormat_delete(format);continue;
        }
        if(buffer<0)continue;
        size_t capacity=0;auto data=AMediaCodec_getOutputBuffer(codec,buffer,&capacity);
        bool valid=data&&info.offset>=0&&info.size>=0&&size_t(info.offset)<=capacity&&size_t(info.size)<=capacity-size_t(info.offset);
        if(valid&&info.size>0) {
            if(info.flags&AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG)configuration.assign(data+info.offset,data+info.offset+info.size);
            else {
                bool key=info.flags&AMEDIACODEC_BUFFER_FLAG_KEY_FRAME;
                if(!hevc&&key) {
                    for(size_t n=0;n+7<configuration.size();++n) {
                        size_t header=0;
                        if(configuration[n]==0&&configuration[n+1]==0&&configuration[n+2]==1)header=n+3;
                        else if(configuration[n]==0&&configuration[n+1]==0&&configuration[n+2]==0&&configuration[n+3]==1)header=n+4;
                        if(header&&(configuration[header]&31)==7) {
                            std::lock_guard lock(video_mutex);stats->profile_idc=configuration[header+1];stats->level_idc=configuration[header+3];break;
                        }
                    }
                }
                std::vector<uint8_t> bytes;
                if(key)bytes=configuration;
                bytes.insert(bytes.end(),data+info.offset,data+info.offset+info.size);
                auto packet=std::make_unique<packet_raw_generic>(std::move(bytes),index++,key);
                packet->channel_data=channel;
                packet->channel_owner=channel_owner;
                if(info.presentationTimeUs>0)packet->released=[tid=stats->tid,pts=info.presentationTimeUs]{android_host::video_packet_released(tid,pts);};
                if(info.presentationTimeUs>0)packet->frame_timestamp=std::chrono::steady_clock::time_point(std::chrono::microseconds(info.presentationTimeUs));
                record_video_output(stats,packet->data_size(),key,info.presentationTimeUs);
                if(info.presentationTimeUs>0)android_host::video_frame_progress(stats->tid,info.presentationTimeUs,false);
                android_host::push_video_packet(std::move(packet));
            }
        } else if(!valid) {std::lock_guard lock(video_mutex);++stats->invalid_buffers;}
        AMediaCodec_releaseOutputBuffer(codec,buffer,false);
    }
    // The host releases its EGL producer before destroying the codec's input window.
    android_host::stream_stopped(java.env);
    android_host::remove_video_channel(channel);
    unregister_video(stats->id);
    if(surface)java.env->DeleteLocalRef(surface);
    release_codec();
}
}
