#pragma once
#include <jni.h>
#include <string>
#include <atomic>
#include <cstdint>
#include <memory>
#include <boost/property_tree/ptree.hpp>
#include <openssl/ssl.h>
namespace android_host {
extern JavaVM* vm;
extern jclass bridge,input_bridge;
extern std::atomic<bool> stopping;
extern std::string files_path;
class Jvm {
    bool attached=false;
public:
    JNIEnv* env=nullptr;
    Jvm() {
        if(vm && vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)==JNI_EDETACHED) {
            attached=vm->AttachCurrentThread(&env,nullptr)==JNI_OK;
        }
    }
    ~Jvm() { if(attached) vm->DetachCurrentThread(); }
};
void pin_requested();
void input_activity();
bool prepare_app(int id);
std::string app_catalog();
void report_error(const std::string& error);
int avc_profile(const std::string& encoder);
int bitrate_mode(const std::string& encoder,const std::string& mime);
void stream_started(JNIEnv* env,int width,int height,int fps,int audio_ms,jobject surface,int app_id,const std::string& client_id,const std::string& client_address);
void stream_stopped(JNIEnv* env);
bool stream_paused();
bool stream_resized(JNIEnv* env,int width,int height,jobject surface);
void configure_canvas(int width,int height,bool follow,int fps,bool source);
std::shared_ptr<void> retain_channel(void* channel);
void video_packet_released(int64_t thread_id,int64_t presentation_us);
void video_frame_progress(int64_t thread_id,int64_t presentation_us,bool released);
void input_event(const char* name,const char* signature,jvalue* args);
std::string video_snapshot();
bool video_control(int64_t session_id,int operation,int value);
void configure_video(int low,int high,int fps);
void configure_encoders(JNIEnv* env,jstring avc,jstring hevc,jobjectArray avc_names,jobjectArray hevc_names);
void set_audio_source(JNIEnv* env,jobject source);
boost::property_tree::ptree import_pairing(const boost::property_tree::ptree& root);
void export_pairing(boost::property_tree::ptree& root);
std::string paired_client(SSL* ssl);
bool paired_authorized(SSL* ssl);
void client_discovered(const std::string& address);
void server_uuid(const std::string& uuid);
}
