#pragma once
#include <utility>
namespace sunshine_android {
// Keep the negotiated pixel budget; follow the display's current orientation.
inline std::pair<int,int> oriented_canvas(int width,int height,int source_width,int source_height,bool follow) {
    if(!follow||source_width<=0||source_height<=0||source_width==source_height||width==height)
        return {width,height};
    return (width>height)==(source_width>source_height)?std::make_pair(width,height):std::make_pair(height,width);
}
}
