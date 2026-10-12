#pragma once
#include <set>
#include <string>
#include <vector>
namespace sunshine_android {
struct EncoderAttempt { bool success=false; std::string actual, error; int attempts=0; };
// Empty name means createEncoderByType (the platform default), never a guessed name.
template<class Try> EncoderAttempt select_encoder(const std::string& choice,const std::vector<std::string>& names,Try attempt) {
    EncoderAttempt result; std::set<std::string> tried; int count=0;
    auto run=[&](const std::string& name) {
        tried.insert(name); result=attempt(name); result.attempts=++count;
        if(!result.actual.empty()) tried.insert(result.actual);
        return result.success;
    };
    if(choice!="auto" && !choice.empty()) { run(choice); return result; }
    if(run("")) return result;
    for(auto& name:names) if(!name.empty() && !tried.count(name) && run(name)) return result;
    return result;
}
}
