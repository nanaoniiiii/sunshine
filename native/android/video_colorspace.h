#ifndef SUNSHINE_ANDROID_VIDEO_COLORSPACE_H
#define SUNSHINE_ANDROID_VIDEO_COLORSPACE_H
#pragma once
namespace video {
enum class colorspace_e { rec601, rec709, bt2020sdr, bt2020 };
struct sunshine_colorspace_t { colorspace_e colorspace; bool full_range; unsigned bit_depth; };
}

#endif
