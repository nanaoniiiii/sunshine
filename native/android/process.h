#ifndef SUNSHINE_ANDROID_PROCESS_H
#define SUNSHINE_ANDROID_PROCESS_H
#pragma once
#include <string>
#include <vector>
#include <memory>
#include "rtsp.h"
namespace proc {
struct ctx_t { std::string name,id,image_path; };
class proc_t {
public:
    int execute(int id,std::shared_ptr<rtsp_stream::launch_session_t> launch);
    int running();
    void terminate();
    std::vector<ctx_t>& get_apps();
    std::string get_app_image(int id);
    std::string get_last_run_app_name();
};
extern proc_t proc;
}

#endif
