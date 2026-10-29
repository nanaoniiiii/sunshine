#pragma once
#include <utility>
namespace sunshine_android {
// Default streams use the host's actual pixels. Fullscreen streams keep each
// client's original request while the host display is fitted to the owner.
inline std::pair<int,int> stream_canvas(int width,int height,int source_width,int source_height,bool source_canvas) {
    if(source_canvas && source_width>0 && source_height>0)
        return {(source_width+1)&~1,(source_height+1)&~1};
    return {width,height};
}
// Keep the negotiated pixel budget; follow the display's current orientation.
inline std::pair<int,int> oriented_canvas(int width,int height,int source_width,int source_height,bool follow) {
    if(!follow||source_width<=0||source_height<=0||source_width==source_height||width==height)
        return {width,height};
    return (width>height)==(source_width>source_height)?std::make_pair(width,height):std::make_pair(height,width);
}
}
