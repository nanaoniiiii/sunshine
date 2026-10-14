#ifndef SUNSHINE_ANDROID_LOGGING_H
#define SUNSHINE_ANDROID_LOGGING_H
#pragma once
#include <android/log.h>
#include <sstream>
#include <memory>
#include <string>
class AndroidLog {
    std::ostringstream text;
    const char* severity;
public:
    explicit AndroidLog(const char* level):severity(level) {}
    template<class T> AndroidLog& operator<<(const T& value) { text << value; return *this; }
    AndroidLog& operator<<(std::ostream&(*fn)(std::ostream&)) { fn(text); return *this; }
    ~AndroidLog() { if(std::string(severity)!="debug"&&std::string(severity)!="verbose") __android_log_write(ANDROID_LOG_INFO,"SunshineNative",text.str().c_str()); }
};
#ifdef BOOST_LOG
#undef BOOST_LOG
#endif
#define BOOST_LOG(level) AndroidLog(#level)
namespace logging {
    class deinit_t {};
    inline void deinit() {}
    inline void log_flush() {}
    inline void print_help(const char*) {}
    inline void setup_av_logging(int) {}
    inline std::unique_ptr<deinit_t> init(int,const std::string&) { return std::make_unique<deinit_t>(); }
}

#endif
