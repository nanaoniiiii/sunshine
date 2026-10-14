#include "canvas_policy.h"
#include <cassert>
int main() {
    using sunshine_android::oriented_canvas;
    assert(oriented_canvas(1440,3200,3200,1440,true)==std::make_pair(3200,1440));
    assert(oriented_canvas(1440,3200,1440,3200,true)==std::make_pair(1440,3200));
    assert(oriented_canvas(1280,720,1440,3200,true)==std::make_pair(720,1280));
    assert(oriented_canvas(1280,720,3200,1440,true)==std::make_pair(1280,720));
    assert(oriented_canvas(1440,3200,3200,1440,false)==std::make_pair(1440,3200));
    assert(oriented_canvas(1440,3200,0,0,true)==std::make_pair(1440,3200));
    assert(oriented_canvas(720,720,3200,1440,true)==std::make_pair(720,720));
    assert(oriented_canvas(1440,3200,1000,1000,true)==std::make_pair(1440,3200));
    // Two clients retain their independent resolution budgets through rotation.
    for(int i=0;i<100;++i) {
        auto a=oriented_canvas(1440,3200,i%2?3200:1440,i%2?1440:3200,true);
        auto b=oriented_canvas(1280,720,i%2?3200:1440,i%2?1440:3200,true);
        assert(a.first*a.second==1440*3200&&b.first*b.second==1280*720);
        assert((a.first>a.second)==(b.first>b.second));
    }
}
