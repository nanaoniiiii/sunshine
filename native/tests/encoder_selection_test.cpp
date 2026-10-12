#include "../encoder_selection.h"
#include <cassert>
using namespace sunshine_android;
int main() {
    std::vector<std::string> attempts;
    auto ok_default=select_encoder("auto",{"other"},[&](auto name) { attempts.push_back(name); return EncoderAttempt{true,"default",{}}; });
    assert(ok_default.success && ok_default.attempts==1 && attempts==std::vector<std::string>{""});
    attempts.clear();
    auto fallback=select_encoder("auto",{"default","bad","bad","good"},[&](auto name) {
        attempts.push_back(name); return EncoderAttempt{name=="good",name.empty()?"default":name,"failed"}; });
    assert(fallback.success && fallback.actual=="good" && fallback.attempts==3);
    assert(attempts==std::vector<std::string>({"","bad","good"}));
    attempts.clear();
    auto explicit_failure=select_encoder("bad",{"good"},[&](auto name) { attempts.push_back(name); return EncoderAttempt{false,name,"configure failed"}; });
    assert(!explicit_failure.success && explicit_failure.attempts==1 && attempts==std::vector<std::string>{"bad"});
    auto no_candidates=select_encoder("auto",{},[](auto) { return EncoderAttempt{false,{},"create failed"}; });
    assert(!no_candidates.success && no_candidates.attempts==1);
}
