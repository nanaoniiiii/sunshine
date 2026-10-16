#include "android_host.h"
#include "platform/common.h"
#include "encoded_queue.h"
#include "process.h"
#include "datagram_retry.h"
#include "udp_batch_io.h"
#include <boost/asio/ip/address.hpp>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/resource.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <android/log.h>
#include <thread>
#include <deque>
#include <condition_variable>
#include <cmath>
#include <cerrno>

namespace {
struct AudioSubscriber {
    std::mutex mutex;
    std::condition_variable ready;
    std::deque<float> stereo;
    size_t limit=3840;
};
std::mutex audio_mutex;
std::condition_variable audio_ready;
jobject audio_source=nullptr;
std::vector<std::weak_ptr<AudioSubscriber>> subscribers;
std::thread audio_thread;
void produce_audio() {
    android_host::Jvm java;if(!java.env)return;
    auto buffer=java.env->NewFloatArray(960);
    std::vector<float> samples(960);
    while(!android_host::stopping) {
        jobject source=nullptr;
        {
            std::unique_lock lock(audio_mutex);
            audio_ready.wait_for(lock,std::chrono::milliseconds(20),[]{return audio_source||android_host::stopping;});
            if(audio_source)source=java.env->NewLocalRef(audio_source);
        }
        if(!source)continue;
        auto cls=java.env->GetObjectClass(source);
        auto read=java.env->GetMethodID(cls,"read","([FIII)I");
        int count=read?java.env->CallIntMethod(source,read,buffer,0,960,0):0;
        java.env->DeleteLocalRef(cls);java.env->DeleteLocalRef(source);
        if(java.env->ExceptionCheck()){java.env->ExceptionClear();count=0;}
        count=std::clamp(count,0,960)&~1;
        if(count==0){std::this_thread::sleep_for(std::chrono::milliseconds(5));continue;}
        java.env->GetFloatArrayRegion(buffer,0,count,samples.data());
        std::lock_guard lock(audio_mutex);
        for(auto it=subscribers.begin();it!=subscribers.end();) {
            if(auto subscriber=it->lock()) {
                {
                    std::lock_guard queue_lock(subscriber->mutex);
                    while(subscriber->stereo.size()+count>subscriber->limit)subscriber->stereo.pop_front();
                    subscriber->stereo.insert(subscriber->stereo.end(),samples.begin(),samples.begin()+count);
                }
                subscriber->ready.notify_one();++it;
            } else it=subscribers.erase(it);
        }
    }
    java.env->DeleteLocalRef(buffer);
}
class AndroidMicrophone:public platf::mic_t {
    std::shared_ptr<AudioSubscriber> source=std::make_shared<AudioSubscriber>();
    int channels,frames;
public:
    AndroidMicrophone(int channel_count,int frame_count):channels(channel_count),frames(frame_count) {
        source->limit=std::max(3840,frames*2);
        std::lock_guard lock(audio_mutex);subscribers.push_back(source);
    }
    platf::capture_e sample(std::vector<int16_t>& buffer) override {
        std::unique_lock lock(source->mutex);
        if(!source->ready.wait_for(lock,std::chrono::milliseconds(30),[&]{return android_host::stopping||source->stereo.size()>=size_t(frames*2);}))return platf::capture_e::timeout;
        if(android_host::stopping)return platf::capture_e::interrupted;
        buffer.assign(frames*channels,0);
        for(int i=0;i<frames;++i) {
            float l=source->stereo.front();source->stereo.pop_front();
            float r=source->stereo.front();source->stereo.pop_front();
            if(channels==1)buffer[i]=int16_t(std::clamp((l+r)*0.5f,-1.f,1.f)*32767);
            else {buffer[i*channels]=int16_t(std::clamp(l,-1.f,1.f)*32767);buffer[i*channels+1]=int16_t(std::clamp(r,-1.f,1.f)*32767);}
        }
        return platf::capture_e::ok;
    }
};
class AndroidAudio:public platf::audio_control_t {
public:
    int set_sink(const std::string&) override{return 0;}
    std::unique_ptr<platf::mic_t> microphone(const uint8_t*,int channels,uint32_t sample_rate,uint32_t frames) override {
        if(sample_rate!=48000||channels<1||channels>8||frames==0||frames>4800)return nullptr;
        return std::make_unique<AndroidMicrophone>(channels,frames);
    }
    std::optional<platf::sink_t> sink_info() override {
        return platf::sink_t{"Android",platf::sink_t::null_t{"Android","Android","Android"}};
    }
};
class PlatformLifetime:public platf::deinit_t {
public:
    ~PlatformLifetime() override {
        android_host::stopping=true;audio_ready.notify_all();
        if(audio_thread.joinable())audio_thread.join();
        android_host::Jvm java;
        std::lock_guard lock(audio_mutex);
        if(audio_source&&java.env)java.env->DeleteGlobalRef(audio_source);
        audio_source=nullptr;subscribers.clear();
    }
};
void integer_event(const char* name,int a,int b) {
    jvalue args[2];args[0].i=a;args[1].i=b;android_host::input_event(name,"(II)V",args);
}
static std::atomic<uint32_t> next_client{0};
class ClientInput:public platf::deinit_t {
public:
    uint32_t id=++next_client;
    ~ClientInput() override {jvalue args[1];args[0].j=id;android_host::input_event("clearClient","(J)V",args);}
};
}
namespace android_host {
int64_t video_thread_cpu_ns(){timespec value{};clock_gettime(CLOCK_THREAD_CPUTIME_ID,&value);return int64_t(value.tv_sec)*1000000000+value.tv_nsec;}
void wait_video_packet(void* channel,int64_t nanoseconds) {
    // Android may rewrite a running process's timer slack when screen/importance
    // changes. Startup-only tuning cannot protect an already connected peer.
    auto observed=prctl(PR_GET_TIMERSLACK);
    bool reset=observed>1&&prctl(PR_SET_TIMERSLACK,1UL)==0;
    auto applied=reset?prctl(PR_GET_TIMERSLACK):observed;
    thread_local bool first=true;
    if(first||observed!=1){record_pacing_slack(channel,observed,applied,reset);first=false;}
    std::this_thread::sleep_for(std::chrono::nanoseconds(nanoseconds));
}
thread_local uint32_t last_datagram_retries=0;
uint32_t datagram_retries(){return last_datagram_retries;}
sunshine_android::BatchResult send_video_batch(platf::batched_send_info_t& info) {
    static sunshine_android::DatagramBatchGate gate;
    sockaddr_storage target{};socklen_t length;
    if(info.target_address.is_v4()) {
        auto& address=(sockaddr_in&)target;address.sin_family=AF_INET;address.sin_port=htons(info.target_port);
        address.sin_addr.s_addr=htonl(info.target_address.to_v4().to_uint());length=sizeof(address);
    } else {
        auto& address=(sockaddr_in6&)target;address.sin6_family=AF_INET6;address.sin6_port=htons(info.target_port);
        auto ip=info.target_address.to_v6();auto bytes=ip.to_bytes();
        std::copy(bytes.begin(),bytes.end(),address.sin6_addr.s6_addr);address.sin6_scope_id=ip.scope_id();length=sizeof(address);
    }
    return sunshine_android::send_udp_batch(int(info.native_socket),(sockaddr*)&target,length,info.buffer,info.block_size,info.block_count,&gate);
}
void set_audio_source(JNIEnv* env,jobject source) {
    std::lock_guard lock(audio_mutex);
    if(audio_source)env->DeleteGlobalRef(audio_source);
    audio_source=source?env->NewGlobalRef(source):nullptr;
    audio_ready.notify_one();
}
}
namespace platf {
std::unique_ptr<deinit_t> init(){audio_thread=std::thread(produce_audio);return std::make_unique<PlatformLifetime>();}
std::filesystem::path appdata(){return android_host::files_path;}
std::string get_mac_address(const std::string_view&){return "00:00:00:00:00:00";}
std::string from_sockaddr(const sockaddr* address){
    char buffer[INET6_ADDRSTRLEN]{};
    if(address->sa_family==AF_INET)inet_ntop(AF_INET,&((const sockaddr_in*)address)->sin_addr,buffer,sizeof(buffer));
    else if(address->sa_family==AF_INET6)inet_ntop(AF_INET6,&((const sockaddr_in6*)address)->sin6_addr,buffer,sizeof(buffer));
    return buffer;
}
std::pair<uint16_t,std::string> from_sockaddr_ex(const sockaddr* address){
    auto port=address->sa_family==AF_INET?((const sockaddr_in*)address)->sin_port:((const sockaddr_in6*)address)->sin6_port;
    return {ntohs(port),from_sockaddr(address)};
}
std::unique_ptr<audio_control_t> audio_control(){return std::make_unique<AndroidAudio>();}
std::shared_ptr<display_t> display(mem_type_e,const std::string&,const video::config_t&){return nullptr;}
std::vector<std::string> display_names(mem_type_e){return {"Android Screen"};}
bool needs_encoder_reenumeration(){return false;}
void adjust_thread_priority(thread_priority_e priority){
    int requested=priority==thread_priority_e::low?5:
        (priority==thread_priority_e::high||priority==thread_priority_e::critical?-4:0);
    // Use Android's thread API so its scheduling-policy bookkeeping accompanies
    // the nice change. A bare setpriority(0) never promoted latency-sensitive work.
    android_host::Jvm java;
    if(java.env&&android_host::bridge) {
        auto method=java.env->GetStaticMethodID(android_host::bridge,"setWorkerPriority","(I)I");
        if(method)java.env->CallStaticIntMethod(android_host::bridge,method,jint(requested));
        if(java.env->ExceptionCheck())java.env->ExceptionClear();
    }
    if(priority==thread_priority_e::high||priority==thread_priority_e::critical) {
        // Android background threads may inherit tens of milliseconds of timer
        // slack. Packet pacing needs sub-millisecond sleeps on this thread only.
        auto before=prctl(PR_GET_TIMERSLACK);
        auto result=prctl(PR_SET_TIMERSLACK,1UL);
        __android_log_print(ANDROID_LOG_INFO,"SunshinePacing","nice=%d requested=%d timer_slack_ns=%d -> %d result=%d",getpriority(PRIO_PROCESS,0),requested,before,prctl(PR_GET_TIMERSLACK),result);
    }
}
void streaming_will_start(){}
void streaming_will_stop(){proc::proc.terminate();}
void restart(){android_host::stopping=true;}
bool send(send_info_t& info) {
    sockaddr_storage target{};socklen_t length;
    if(info.target_address.is_v4()) {
        auto& address=(sockaddr_in&)target;address.sin_family=AF_INET;address.sin_port=htons(info.target_port);
        address.sin_addr.s_addr=htonl(info.target_address.to_v4().to_uint());length=sizeof(address);
    } else {
        auto& address=(sockaddr_in6&)target;address.sin6_family=AF_INET6;address.sin6_port=htons(info.target_port);
        auto bytes=info.target_address.to_v6().to_bytes();std::copy(bytes.begin(),bytes.end(),address.sin6_addr.s6_addr);
        length=sizeof(address);
    }
    auto result=sunshine_android::send_datagram(info.size,[&] {
        auto sent=sendto(info.native_socket,info.buffer,info.size,0,(sockaddr*)&target,length);
        return sunshine_android::DatagramAttempt{sent,sent<0?errno:0};
    },[] {std::this_thread::sleep_for(std::chrono::microseconds(100));},[] {
        return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
    });
    android_host::last_datagram_retries=result.retries;
    return result.sent;
}
bool send_batch(batched_send_info_t& info) {
    // No prefix is sent here: the protocol's individual-send path owns the
    // whole batch and therefore never resends an already delivered prefix.
    return false;
}
std::unique_ptr<deinit_t> enable_socket_qos(uintptr_t socket,boost::asio::ip::address&,uint16_t,qos_data_type_e type,bool enable) {
    if(type==qos_data_type_e::video)android_host::record_video_send_buffer(sunshine_android::ensure_video_send_buffer(int(socket)));
    if(enable){int tos=0x88;setsockopt(socket,IPPROTO_IP,IP_TOS,&tos,sizeof(tos));}return nullptr;
}
void open_url(const std::string&){}
bool request_process_group_exit(uintptr_t){return false;}
bool process_group_running(uintptr_t){return false;}
void freeInput(void* pointer){delete (int*)pointer;}
input_t input(){return input_t{new int(0)};}
void move_mouse(input_t&,int x,int y){integer_event("relativeMouse",x,y);}
void abs_mouse(input_t&,const touch_port_t& port,float x,float y){
    jvalue args[4];args[0].f=x;args[1].f=y;args[2].i=port.width;args[3].i=port.height;
    android_host::input_event("absoluteMouse","(FFII)V",args);
}
void button_mouse(input_t&,int button,bool release){integer_event("mouseButton",button,release?0:1);}
void scroll(input_t&,int distance){integer_event("scroll",0,distance);}
void hscroll(input_t&,int distance){integer_event("scroll",distance,0);}
void keyboard(input_t&,uint16_t code,bool release,uint8_t flags){
    jvalue args[3];args[0].i=code;args[1].z=!release;args[2].i=flags;
    android_host::input_event("virtualKey","(IZI)V",args);
}
void unicode(input_t&,char* utf8,int size){
    android_host::Jvm java;if(!java.env)return;
    std::string value(utf8,size);auto text=java.env->NewStringUTF(value.c_str());
    jvalue args[1];args[0].l=text;android_host::input_event("text","(Ljava/lang/String;)V",args);
    java.env->DeleteLocalRef(text);
}
std::unique_ptr<client_input_t> allocate_client_input_context(input_t&){return std::make_unique<ClientInput>();}
void touch(client_input_t* input,const touch_port_t& port,const touch_input_t& t){
    jvalue args[7];args[0].i=t.eventType;args[1].j=t.pointerId;args[2].f=t.x;args[3].f=t.y;args[4].f=t.pressureOrDistance;
    args[1].j=(uint64_t(((ClientInput*)input)->id)<<32)|t.pointerId;
    args[5].i=port.width;args[6].i=port.height;
    android_host::input_event("nativeTouch","(IJFFFII)V",args);
}
void pen(client_input_t* input,const touch_port_t& port,const pen_input_t& pen){
    touch_input_t t{pen.eventType,pen.rotation,0,pen.x,pen.y,pen.pressureOrDistance,pen.contactAreaMajor,pen.contactAreaMinor};touch(input,port,t);
}
void gamepad(input_t&,int,const gamepad_state_t&){}
void gamepad_touch(input_t&,const gamepad_touch_t&){}
void gamepad_motion(input_t&,const gamepad_motion_t&){}
void gamepad_battery(input_t&,const gamepad_battery_t&){}
int alloc_gamepad(input_t&,const gamepad_id_t&,const gamepad_arrival_t&,feedback_queue_t){return -1;}
void free_gamepad(input_t&,int){}
platform_caps::caps_t get_capabilities(){return platform_caps::pen_touch;}
std::vector<std::string_view>& supported_gamepads(){static std::vector<std::string_view> values{"none"};return values;}
namespace publish {std::unique_ptr<deinit_t> start(){return nullptr;}}
}
