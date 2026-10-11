#include "../audio_packetizer.h"
#include <cassert>
#include <iostream>

using sunshine_android::AudioPacketizer;
int main() {
    std::vector<float> input(1920);
    for (std::size_t i = 0; i < input.size(); ++i) input[i] = float(i);
    AudioPacketizer fiveMs(240, 2), tenMs(480, 2);
    std::vector<std::vector<float>> five, ten;
    auto collectFive = [&](auto packet) { five.push_back(std::move(packet)); };
    auto collectTen = [&](auto packet) { ten.push_back(std::move(packet)); };
    // Mixed packet lengths and irregular capture chunk sizes retain identical PCM.
    for (std::size_t offset = 0; offset < input.size(); offset += 160) {
        fiveMs.append(input.data() + offset, 160, collectFive);
        tenMs.append(input.data() + offset, 160, collectTen);
    }
    assert(five.size() == 4 && ten.size() == 2);
    std::vector<float> reassembled;
    for (auto& packet : five) reassembled.insert(reassembled.end(), packet.begin(), packet.end());
    assert(reassembled == input);
    reassembled.clear();
    for (auto& packet : ten) reassembled.insert(reassembled.end(), packet.begin(), packet.end());
    assert(reassembled == input);
    assert(fiveMs.pendingFloats() == 0 && tenMs.pendingFloats() == 0);

    // A joining client starts empty and never receives its predecessor's partial packet.
    AudioPacketizer previous(240, 2), joining(240, 2);
    previous.append(input.data(), 200, collectFive);
    std::vector<std::vector<float>> fresh;
    joining.append(input.data() + 200, 480, [&](auto p) { fresh.push_back(std::move(p)); });
    assert(fresh.size() == 1 && fresh.front().front() == 200.f);
    assert(previous.pendingFloats() == 200);

    // Android stereo capture fills front left/right and leaves surround channels silent.
    AudioPacketizer surround(240, 6);
    std::vector<float> multi;
    surround.append(input.data(), 480, [&](auto p) { multi = std::move(p); });
    assert(multi.size() == 1440);
    for (std::size_t f = 0; f < 240; ++f) {
        assert(multi[f * 6] == input[f * 2] && multi[f * 6 + 1] == input[f * 2 + 1]);
        for (std::size_t ch = 2; ch < 6; ++ch) assert(multi[f * 6 + ch] == 0.f);
    }
    bool rejected = false;
    try { AudioPacketizer invalid(0, 2); } catch (const std::invalid_argument&) { rejected = true; }
    assert(rejected);
    std::cout << "audio packetizer: mixed durations, PCM continuity, joining client, surround and bounds passed\n";
}
