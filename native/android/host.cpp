#include "android_host.h"
#include "config.h"
#include "globals.h"
#include "input.h"
#include "nvhttp.h"
#include "rtsp.h"
#include "video.h"
#include "httpcommon.h"
#include "process.h"
#include "logging.h"
#include "platform/common.h"
#include "crypto.h"
#include <boost/property_tree/json_parser.hpp>
#include <filesystem>
#include <fstream>
#include <thread>
#include <mutex>
#include <openssl/pem.h>
#include "uuid.h"

namespace android_host {
JavaVM* vm=nullptr;
jclass bridge=nullptr,input_bridge=nullptr;
std::atomic<bool> stopping{false};
std::string files_path="/data/local/tmp";
static std::string string(JNIEnv* e,jstring s) {
    if(!s)return {};
    const char* data=e->GetStringUTFChars(s,nullptr);
    std::string value=data?data:"";
    if(data)e->ReleaseStringUTFChars(s,data);
    return value;
}
static void call_void(const char* method,const char* signature,jvalue* args=nullptr) {
    Jvm java;
    if(!java.env || !bridge)return;
    auto id=java.env->GetStaticMethodID(bridge,method,signature);
    if(id)java.env->CallStaticVoidMethodA(bridge,id,args);
    if(java.env->ExceptionCheck()) {java.env->ExceptionDescribe();java.env->ExceptionClear();}
}
void pin_requested(){call_void("onPinRequested","()V");}
void input_activity(){call_void("onRemoteInput","()V");}
void client_discovered(const std::string& address) {
    Jvm java;if(!java.env)return;
    auto text=java.env->NewStringUTF(address.c_str());jvalue args[1];args[0].l=text;
    call_void("onClientPaired","(Ljava/lang/String;)V",args);java.env->DeleteLocalRef(text);
}
void server_uuid(const std::string& uuid) {
    Jvm java;if(!java.env)return;
    auto text=java.env->NewStringUTF(uuid.c_str());jvalue args[1];args[0].l=text;
    call_void("onServerUuid","(Ljava/lang/String;)V",args);java.env->DeleteLocalRef(text);
}
struct PairingName {std::string uuid,name;};
static std::map<std::string,PairingName> pairing_names;
static std::mutex pairing_mutex;
static std::string fingerprint(X509* cert) {
    if(!cert)return {};
    unsigned char hash[EVP_MAX_MD_SIZE];unsigned int size=0;
    if(!X509_digest(cert,EVP_sha256(),hash,&size))return {};
    const char* hex="0123456789abcdef";std::string value;
    for(unsigned i=0;i<size;++i){value.push_back(hex[hash[i]>>4]);value.push_back(hex[hash[i]&15]);}return value;
}
static std::string certificate_id(const std::string& pem) {
    auto bio=BIO_new_mem_buf(pem.data(),pem.size());
    auto cert=PEM_read_bio_X509(bio,nullptr,nullptr,nullptr);BIO_free(bio);
    auto value=fingerprint(cert);if(cert)X509_free(cert);return value;
}
boost::property_tree::ptree import_pairing(const boost::property_tree::ptree& root) {
    using boost::property_tree::ptree;
    std::lock_guard lock(pairing_mutex);pairing_names.clear();
    ptree devices;
    if(auto named=root.get_child_optional("root.named_devices")) {
        for(auto& row:*named) {
            auto cert=row.second.get<std::string>("cert","");auto id=certificate_id(cert);if(id.empty())continue;
            auto uuid=row.second.get<std::string>("uuid",id);
            pairing_names[id]={uuid,row.second.get<std::string>("name","Device")};
            ptree device,certs,cert_node;device.put("uniqueid",uuid);cert_node.put_value(cert);certs.push_back({"",cert_node});
            device.add_child("certs",certs);devices.push_back({"",device});
        }
    } else if(auto legacy=root.get_child_optional("root.devices"))devices=*legacy;
    return devices;
}
void export_pairing(boost::property_tree::ptree& root) {
    using boost::property_tree::ptree;
    std::lock_guard lock(pairing_mutex);
    ptree named;
    for(auto& device:root.get_child("root.devices"))for(auto& row:device.second.get_child("certs")) {
        auto cert=row.second.get_value<std::string>();auto id=certificate_id(cert);if(id.empty())continue;
        auto found=pairing_names.find(id);
        if(found==pairing_names.end())found=pairing_names.emplace(id,PairingName{uuid_util::uuid_t::generate().string(),"Device"}).first;
        ptree node;node.put("name",found->second.name);node.put("uuid",found->second.uuid);node.put("cert",cert);
        named.push_back({"",node});
    }
    root.put_child("root.named_devices",named);
}
std::string paired_client(SSL* ssl) {
    auto cert=SSL_get1_peer_certificate(ssl);auto id=fingerprint(cert);if(cert)X509_free(cert);
    std::lock_guard lock(pairing_mutex);auto it=pairing_names.find(id);return it==pairing_names.end()?id:it->second.uuid;
}
bool paired_authorized(SSL* ssl) {
    auto cert=SSL_get1_peer_certificate(ssl);auto id=fingerprint(cert);if(cert)X509_free(cert);
    if(id.empty())return false;
    try {
        boost::property_tree::ptree root;boost::property_tree::read_json(config::nvhttp.file_state,root);
        if(auto named=root.get_child_optional("root.named_devices")) {
            for(auto& row:*named)if(certificate_id(row.second.get<std::string>("cert",""))==id)return true;
            return false;
        }
        for(auto& device:root.get_child("root.devices"))for(auto& row:device.second.get_child("certs"))
            if(certificate_id(row.second.get_value<std::string>())==id)return true;
    } catch(...) {}
    return false;
}
bool prepare_app(int id) {
    Jvm java;if(!java.env)return false;
    auto method=java.env->GetStaticMethodID(bridge,"prepareApp","(I)Z");
    bool result=method&&java.env->CallStaticBooleanMethod(bridge,method,id);
    if(java.env->ExceptionCheck()){java.env->ExceptionClear();return false;}return result;
}
std::string app_catalog() {
    Jvm java;if(!java.env)return "{\"apps\":[]}";
    auto method=java.env->GetStaticMethodID(bridge,"getAppCatalog","()Ljava/lang/String;");
    auto result=method?(jstring)java.env->CallStaticObjectMethod(bridge,method):nullptr;
    if(java.env->ExceptionCheck()){java.env->ExceptionClear();return "{\"apps\":[]}";}
    auto data=string(java.env,result);if(result)java.env->DeleteLocalRef(result);return data;
}
void report_error(const std::string& error) {
    Jvm java;if(!java.env)return;
    auto text=java.env->NewStringUTF(error.c_str());
    jvalue args[1];args[0].l=text;call_void("showEncoderError","(Ljava/lang/String;)V",args);
    java.env->DeleteLocalRef(text);
}
int avc_profile(const std::string& encoder) {
    Jvm java;if(!java.env)return 0;
    auto method=java.env->GetStaticMethodID(bridge,"getAvcProfile","(Ljava/lang/String;)I");
    auto name=java.env->NewStringUTF(encoder.c_str());
    int value=method?java.env->CallStaticIntMethod(bridge,method,name):0;
    java.env->DeleteLocalRef(name);
    if(java.env->ExceptionCheck()){java.env->ExceptionClear();return 0;}
    return value;
}
void stream_started(JNIEnv* env,int w,int h,int fps,int audio_ms,jobject surface,int app_id,const std::string& client) {
    auto text=env->NewStringUTF(client.c_str());
    jvalue args[8];args[0].i=w;args[1].i=h;args[2].i=fps;args[3].i=audio_ms;
    args[4].l=surface;args[5].z=true;args[6].i=app_id;args[7].l=text;
    call_void("createVirtualDisplay","(IIIILandroid/view/Surface;ZILjava/lang/String;)V",args);
    env->DeleteLocalRef(text);
}
void stream_stopped(JNIEnv*) {call_void("stopVirtualDisplay","()V");}
bool stream_paused() {
    Jvm java;if(!java.env)return false;
    auto method=java.env->GetStaticMethodID(bridge,"pauseVirtualDisplay","()Z");
    bool result=method&&java.env->CallStaticBooleanMethod(bridge,method);
    if(java.env->ExceptionCheck()){java.env->ExceptionClear();return false;}return result;
}
bool stream_resized(JNIEnv* env,int width,int height,jobject surface) {
    auto method=env->GetStaticMethodID(bridge,"resizeVirtualDisplay","(IILandroid/view/Surface;)Z");
    bool result=method&&env->CallStaticBooleanMethod(bridge,method,width,height,surface);
    if(env->ExceptionCheck()){env->ExceptionClear();return false;}return result;
}
void input_event(const char* name,const char* signature,jvalue* args) {
    input_activity();Jvm java;if(!java.env || !input_bridge)return;
    auto method=java.env->GetStaticMethodID(input_bridge,name,signature);
    if(method)java.env->CallStaticVoidMethodA(input_bridge,method,args);
    if(java.env->ExceptionCheck()){java.env->ExceptionDescribe();java.env->ExceptionClear();}
}
}

namespace proc {
proc_t proc;
static std::atomic<int> active_app{0};
static std::vector<ctx_t> apps;
int proc_t::execute(int id,std::shared_ptr<rtsp_stream::launch_session_t>) {
    if(!android_host::prepare_app(id))return 503;
    active_app=id;return 0;
}
int proc_t::running(){return active_app;}
void proc_t::terminate(){active_app=0;}
std::vector<ctx_t>& proc_t::get_apps() {
    try {
        boost::property_tree::ptree root;
        std::istringstream in(android_host::app_catalog());boost::property_tree::read_json(in,root);
        std::vector<ctx_t> current;
        for(auto& row:root.get_child("apps")) {
            current.push_back({row.second.get<std::string>("name","Desktop"),row.second.get<std::string>("id","1"),""});
        }
        if(current.empty())current.push_back({"Desktop","1",""});
        apps=std::move(current);
    } catch(...) {apps={{"Desktop","1",""}};}
    return apps;
}
std::string proc_t::get_app_image(int){return android_host::files_path+"/desktop.png";}
std::string proc_t::get_last_run_app_name(){return "Android";}
}
namespace http {
std::string unique_id;
net::net_e origin_web_ui_allowed=net::net_e::LAN;
int create_creds(const std::string& pkey,const std::string& cert) {
    auto credentials=crypto::gen_creds("Sunshine Android",2048);
    std::ofstream key(pkey),certificate(cert);
    key<<credentials.pkey;certificate<<credentials.x509;
    return key&&certificate?0:-1;
}
int init(){
    if(!std::filesystem::exists(config::nvhttp.pkey)||!std::filesystem::exists(config::nvhttp.cert))
        return create_creds(config::nvhttp.pkey,config::nvhttp.cert);
    return 0;
}
}
namespace lifetime {
char** argv=nullptr;
std::atomic_int desired_exit_code{0};
char** get_argv(){return argv;}
void exit_sunshine(int code,bool){desired_exit_code=code;android_host::stopping=true;mail::man->event<bool>(mail::shutdown)->raise(true);}
void debug_trap(){exit_sunshine(1,true);}
}
void launch_ui(){}
void launch_ui_with_path(std::string){}

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm,void*) {
    android_host::vm=vm;JNIEnv* env=nullptr;
    if(vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)!=JNI_OK)return JNI_ERR;
    auto server=env->FindClass("com/alkaidlab/sunshine/engine/NativeServer");
    if(!server)return JNI_ERR;
    android_host::bridge=(jclass)env->NewGlobalRef(server);env->DeleteLocalRef(server);
    auto input=env->FindClass("com/alkaidlab/sunshine/engine/InputBroker");
    if(!input)return JNI_ERR;
    android_host::input_bridge=(jclass)env->NewGlobalRef(input);env->DeleteLocalRef(input);
    return JNI_VERSION_1_6;
}
#define JNI_NAME(name) Java_com_alkaidlab_sunshine_engine_NativeServer_##name
extern "C" JNIEXPORT void JNICALL JNI_NAME(setCertPath)(JNIEnv* e,jclass,jstring p){config::nvhttp.cert=android_host::string(e,p);}
extern "C" JNIEXPORT void JNICALL JNI_NAME(setPkeyPath)(JNIEnv* e,jclass,jstring p){config::nvhttp.pkey=android_host::string(e,p);}
extern "C" JNIEXPORT void JNICALL JNI_NAME(setFileStatePath)(JNIEnv* e,jclass,jstring p){
    config::nvhttp.file_state=android_host::string(e,p);
    android_host::files_path=std::filesystem::path(config::nvhttp.file_state).parent_path().string();
}
extern "C" JNIEXPORT void JNICALL JNI_NAME(setSunshineName)(JNIEnv* e,jclass,jstring p){config::nvhttp.sunshine_name=android_host::string(e,p);}
extern "C" JNIEXPORT void JNICALL JNI_NAME(enableH265)(JNIEnv*,jclass){video::active_hevc_mode=2;config::video.hevc_mode=2;}
extern "C" JNIEXPORT void JNICALL JNI_NAME(submitPin)(JNIEnv* e,jclass,jstring p){nvhttp::pin(android_host::string(e,p));}
extern "C" JNIEXPORT void JNICALL JNI_NAME(configureVideo)(JNIEnv*,jclass,jint a,jint b,jint c){android_host::configure_video(a,b,c);}
extern "C" JNIEXPORT void JNICALL JNI_NAME(configureCanvas)(JNIEnv*,jclass,jint width,jint height,jboolean follow){android_host::configure_canvas(width,height,follow);}
extern "C" JNIEXPORT void JNICALL JNI_NAME(configureEncoders)(JNIEnv* e,jclass,jstring a,jstring b,jobjectArray c,jobjectArray d){android_host::configure_encoders(e,a,b,c,d);}
extern "C" JNIEXPORT jstring JNICALL JNI_NAME(videoSnapshot)(JNIEnv* e,jclass){return e->NewStringUTF(android_host::video_snapshot().c_str());}
extern "C" JNIEXPORT jboolean JNICALL JNI_NAME(controlVideo)(JNIEnv*,jclass,jlong id,jint op,jint value){return android_host::video_control(id,op,value);}
extern "C" JNIEXPORT void JNICALL JNI_NAME(startAudioRecording)(JNIEnv* e,jclass,jobject source,jint){android_host::set_audio_source(e,source);}
extern "C" JNIEXPORT jboolean JNICALL JNI_NAME(exitServer)(JNIEnv*,jclass){
    lifetime::exit_sunshine(0,true);return true;
}
extern "C" JNIEXPORT void JNICALL JNI_NAME(cleanup)(JNIEnv*,jclass){lifetime::exit_sunshine(0,true);}
extern "C" JNIEXPORT void JNICALL JNI_NAME(start)(JNIEnv* env,jclass){
    try {
        android_host::stopping=false;
        if(std::filesystem::exists(config::nvhttp.file_state)&&!std::filesystem::exists(config::nvhttp.file_state+".before-official"))
            std::filesystem::copy_file(config::nvhttp.file_state,config::nvhttp.file_state+".before-official");
        mail::man=std::make_shared<safe::mail_raw_t>();
        config::stream.channels=16;
        config::sunshine.address_family="both";
        std::ifstream settings(android_host::files_path+"/sunshine.conf");
        std::string line;
        while(std::getline(settings,line)) {
            auto separator=line.find('=');if(separator==std::string::npos)continue;
            auto key=line.substr(0,separator),value=line.substr(separator+1);
            auto trim=[](std::string& text){auto first=text.find_first_not_of(" \t\r");if(first==std::string::npos){text.clear();return;}text=text.substr(first,text.find_last_not_of(" \t\r")-first+1);};
            trim(key);trim(value);
            try {
                if(key=="port")config::sunshine.port=std::clamp(std::stoi(value),1024,65510);
                else if(key=="address_family"&&(value=="ipv4"||value=="both"))config::sunshine.address_family=value;
                else if(key=="fec_percentage")config::stream.fec_percentage=std::clamp(std::stoi(value),0,35);
            } catch(const std::exception&) {}
        }
        config::video.av1_mode=1;
        config::sunshine.flags[config::flag::PIN_STDIN]=false;
        config::input.controller=false;
        if(http::init()!=0){android_host::report_error("Unable to prepare pairing credentials");return;}
        auto platform=platf::init();auto input=input::init();
        // Upstream input dispatch relies on this pool preserving event order.
        task_pool.start(1);
        enet_initialize();
        std::thread protocol([]{rtsp_stream::rtpThread();});
        nvhttp::start();
        mail::man->event<bool>(mail::shutdown)->raise(true);
        if(protocol.joinable())protocol.join();
        task_pool.stop();
    } catch(const std::exception& error) {android_host::report_error(error.what());}
}
