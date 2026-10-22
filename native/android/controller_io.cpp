// Independent Linux evdev/uinput adapter. Only dedicated gamepads and remotes
// are eligible; touchscreens, mice, full keyboards and phone power keys are not.
#include <jni.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <sys/ioctl.h>
#include <fcntl.h>
#include <unistd.h>
#include <dirent.h>
#include <sys/eventfd.h>
#include <sys/inotify.h>
#include <poll.h>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include <algorithm>
#include <cmath>
#include <time.h>

namespace {
template<size_t N> bool bit(const unsigned char (&bits)[N], int code) {
    return code >= 0 && size_t(code/8) < N && (bits[code/8] & (1u << (code%8)));
}
std::string utf(JNIEnv* env, jstring text) {
    const char* chars = env->GetStringUTFChars(text, nullptr);
    std::string result(chars ? chars : "");
    if(chars) env->ReleaseStringUTFChars(text, chars);
    return result;
}
bool path_ok(const std::string& path) {
    const std::string prefix = "/dev/input/event";
    return path.size() > prefix.size() && path.compare(0,prefix.size(),prefix)==0 &&
        path.find_first_not_of("0123456789",prefix.size())==std::string::npos;
}
std::string eligible(int fd) {
    unsigned char keys[(KEY_MAX+8)/8] {}, abs[(ABS_MAX+8)/8] {}, rel[(REL_MAX+8)/8] {};
    if(ioctl(fd,EVIOCGBIT(EV_KEY,sizeof(keys)),keys)<0) return {};
    ioctl(fd,EVIOCGBIT(EV_ABS,sizeof(abs)),abs);
    ioctl(fd,EVIOCGBIT(EV_REL,sizeof(rel)),rel);
    char name[256] {};
    ioctl(fd,EVIOCGNAME(sizeof(name)),name);
    if(strncmp(name,"Sunshine RemotePad",18)==0 || bit(keys,BTN_TOUCH) || bit(abs,ABS_MT_POSITION_X)) return {};
    const bool pad = bit(keys,BTN_SOUTH) && bit(keys,BTN_EAST) && bit(abs,ABS_X) && bit(abs,ABS_Y);
    const bool remote = !bit(keys,KEY_A) && !bit(keys,KEY_Z) && !bit(rel,REL_X) &&
        bit(keys,KEY_UP) && bit(keys,KEY_DOWN) && bit(keys,KEY_LEFT) && bit(keys,KEY_RIGHT) &&
        (bit(keys,KEY_ENTER) || bit(keys,KEY_OK));
    // Some Bluetooth controllers expose Guide/Home on a separate consumer node.
    // Only controller-named, home-only nodes qualify; never grab phone power keys.
    int key_count=0; for(int code=0;code<=KEY_MAX;code++) if(bit(keys,code)) ++key_count;
    const bool controller_home = bit(keys,KEY_HOMEPAGE) && key_count<=4 &&
        (strstr(name,"Controller") || strstr(name,"Gamepad")) && !bit(keys,KEY_POWER) && !bit(keys,KEY_VOLUMEUP);
    if(!pad && !remote && !controller_home) return {};
    std::string clean(name);
    std::replace(clean.begin(),clean.end(),'|',' ');
    std::replace(clean.begin(),clean.end(),'\n',' ');
    return clean + (pad ? "|gamepad" : "|remote");
}
jintArray integers(JNIEnv* env, const std::vector<jint>& values) {
    auto array=env->NewIntArray(values.size());
    if(array && !values.empty()) env->SetIntArrayRegion(array,0,values.size(),values.data());
    return array;
}
bool emit(int fd, int type, int code, int value) {
    input_event event {}; event.type=type; event.code=code; event.value=value;
    return write(fd,&event,sizeof(event))==sizeof(event);
}
const int masks[] = {4096,8192,16384,32768,256,512,32,16,1024,64,128};
const int codes[] = {BTN_SOUTH,BTN_EAST,BTN_NORTH,BTN_WEST,BTN_TL,BTN_TR,BTN_SELECT,BTN_START,BTN_MODE,BTN_THUMBL,BTN_THUMBR};
}

#define JNI_METHOD(name) Java_com_alkaidlab_sunshine_controller_ControllerNative_##name
extern "C" JNIEXPORT jobjectArray JNICALL JNI_METHOD(devices)(JNIEnv* env,jobject) {
    std::vector<std::string> values;
    DIR* dir=opendir("/dev/input");
    if(dir) {
        while(auto entry=readdir(dir)) {
            std::string path=std::string("/dev/input/")+entry->d_name;
            if(!path_ok(path)) continue;
            int fd=::open(path.c_str(),O_RDONLY|O_NONBLOCK|O_CLOEXEC);
            if(fd<0) continue;
            auto description=eligible(fd); ::close(fd);
            if(!description.empty()) values.push_back(path+"|"+description);
        }
        closedir(dir);
    }
    auto type=env->FindClass("java/lang/String");
    auto array=env->NewObjectArray(values.size(),type,nullptr);
    for(size_t i=0;i<values.size();i++) { auto value=env->NewStringUTF(values[i].c_str()); env->SetObjectArrayElement(array,i,value); env->DeleteLocalRef(value); }
    env->DeleteLocalRef(type);
    return array;
}
extern "C" JNIEXPORT jint JNICALL JNI_METHOD(open)(JNIEnv* env,jobject,jstring text) {
    auto path=utf(env,text); if(!path_ok(path)) return -EINVAL;
    int fd=::open(path.c_str(),O_RDONLY|O_NONBLOCK|O_CLOEXEC);
    if(fd<0) return -errno;
    if(eligible(fd).empty()) { ::close(fd); return -EPERM; }
    int clock=CLOCK_MONOTONIC;
    if(ioctl(fd,EVIOCSCLOCKID,&clock)<0) { int error=errno; ::close(fd); return -error; }
    return fd;
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(close)(JNIEnv*,jobject,jint fd) { if(fd>=0) ::close(fd); }
extern "C" JNIEXPORT jboolean JNICALL JNI_METHOD(grab)(JNIEnv*,jobject,jint fd,jboolean enabled) { return ioctl(fd,EVIOCGRAB,enabled?1:0)==0; }
extern "C" JNIEXPORT jintArray JNICALL JNI_METHOD(read)(JNIEnv* env,jobject,jint fd) {
    input_event events[128];
    ssize_t count=::read(fd,events,sizeof(events));
    if(count<0) return integers(env,(errno==EAGAIN||errno==EINTR)?std::vector<jint>{}:std::vector<jint>{-1,errno,0});
    if(count==0 || count%sizeof(input_event)) return integers(env,{-1,ENODEV,0});
    std::vector<jint> result;
    for(size_t i=0;i<size_t(count)/sizeof(input_event);i++) {
        result.push_back(events[i].type); result.push_back(events[i].code);
        result.push_back(events[i].type==EV_SYN && events[i].code==SYN_REPORT ?
            jint(uint64_t(events[i].time.tv_sec)*1000+events[i].time.tv_usec/1000) : events[i].value);
    }
    return integers(env,result);
}
extern "C" JNIEXPORT jintArray JNICALL JNI_METHOD(axis)(JNIEnv* env,jobject,jint fd,jint code) {
    input_absinfo info {};
    if(code<0 || code>ABS_MAX || ioctl(fd,EVIOCGABS(code),&info)<0) return integers(env,{});
    return integers(env,{info.value,info.minimum,info.maximum,info.flat});
}
extern "C" JNIEXPORT jint JNICALL JNI_METHOD(createWake)(JNIEnv*,jobject) { return eventfd(0,EFD_NONBLOCK|EFD_CLOEXEC); }
extern "C" JNIEXPORT jint JNICALL JNI_METHOD(watchInputs)(JNIEnv*,jobject) {
    int fd=inotify_init1(IN_NONBLOCK|IN_CLOEXEC);
    if(fd>=0 && inotify_add_watch(fd,"/dev/input",IN_CREATE|IN_DELETE|IN_ATTRIB)<0) { ::close(fd); return -1; }
    return fd;
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(wake)(JNIEnv*,jobject,jint fd) { uint64_t one=1; if(fd>=0) (void)write(fd,&one,sizeof(one)); }
extern "C" JNIEXPORT jint JNICALL JNI_METHOD(awaitInput)(JNIEnv* env,jobject,jintArray array,jint wake,jint watch,jint timeout) {
    std::vector<jint> fds(env->GetArrayLength(array));
    if(!fds.empty()) env->GetIntArrayRegion(array,0,fds.size(),fds.data());
    std::vector<pollfd> polls; for(auto fd:fds) polls.push_back({fd,POLLIN,0});
    polls.push_back({wake,POLLIN,0}); polls.push_back({watch,POLLIN,0});
    int result=poll(polls.data(),polls.size(),std::clamp(timeout,0,5000));
    if(result<=0) return 2;
    int flags=1; char buffer[4096];
    if(polls[polls.size()-2].revents) { while(::read(wake,buffer,sizeof(buffer))>0) {} flags|=2; }
    if(polls.back().revents) { while(::read(watch,buffer,sizeof(buffer))>0) {} flags|=2; }
    return flags;
}
extern "C" JNIEXPORT jint JNICALL JNI_METHOD(createGamepad)(JNIEnv*,jobject,jint id) {
    int fd=::open("/dev/uinput",O_WRONLY|O_NONBLOCK|O_CLOEXEC);
    if(fd<0) return -errno;
    auto fail=[&](){ int error=errno; ::close(fd); return -error; };
    if(ioctl(fd,UI_SET_EVBIT,EV_KEY)<0 || ioctl(fd,UI_SET_EVBIT,EV_ABS)<0) return fail();
    for(auto code:codes) if(ioctl(fd,UI_SET_KEYBIT,code)<0) return fail();
    uinput_user_dev device {};
    snprintf(device.name,sizeof(device.name),"Sunshine RemotePad %d",id);
    device.id.bustype=BUS_USB; device.id.vendor=0x045e; device.id.product=0x028e; device.id.version=0x0114;
    for(auto code:{ABS_X,ABS_Y,ABS_RX,ABS_RY,ABS_Z,ABS_RZ,ABS_HAT0X,ABS_HAT0Y}) {
        if(ioctl(fd,UI_SET_ABSBIT,code)<0) return fail();
        bool trigger=code==ABS_Z || code==ABS_RZ;
        bool hat=code==ABS_HAT0X || code==ABS_HAT0Y;
        device.absmin[code]=trigger?0:(hat?-1:-32768);
        device.absmax[code]=trigger?255:(hat?1:32767);
        device.absflat[code]=(trigger||hat)?0:2048;
    }
    if(write(fd,&device,sizeof(device))!=sizeof(device) || ioctl(fd,UI_DEV_CREATE)<0) return fail();
    return fd;
}
extern "C" JNIEXPORT jboolean JNICALL JNI_METHOD(writeGamepad)(JNIEnv*,jobject,jint fd,jint buttons,
    jfloat lx,jfloat ly,jfloat rx,jfloat ry,jfloat lt,jfloat rt) {
    bool ok=true;
    for(size_t i=0;i<sizeof(codes)/sizeof(codes[0]);i++) ok=emit(fd,EV_KEY,codes[i],(buttons&masks[i])?1:0)&&ok;
    auto stick=[](float value){return int(std::clamp(std::isfinite(value)?value:0.f,-1.f,1.f)*32767);};
    auto trigger=[](float value){return int(std::clamp(std::isfinite(value)?value:0.f,0.f,1.f)*255);};
    ok=emit(fd,EV_ABS,ABS_X,stick(lx))&&ok; ok=emit(fd,EV_ABS,ABS_Y,stick(ly))&&ok;
    ok=emit(fd,EV_ABS,ABS_RX,stick(rx))&&ok; ok=emit(fd,EV_ABS,ABS_RY,stick(ry))&&ok;
    ok=emit(fd,EV_ABS,ABS_Z,trigger(lt))&&ok; ok=emit(fd,EV_ABS,ABS_RZ,trigger(rt))&&ok;
    ok=emit(fd,EV_ABS,ABS_HAT0X,((buttons&8)?1:0)-((buttons&4)?1:0))&&ok;
    ok=emit(fd,EV_ABS,ABS_HAT0Y,((buttons&2)?1:0)-((buttons&1)?1:0))&&ok;
    return emit(fd,EV_SYN,SYN_REPORT,0)&&ok;
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(destroyGamepad)(JNIEnv*,jobject,jint fd) {
    if(fd>=0) { ioctl(fd,UI_DEV_DESTROY); ::close(fd); }
}
