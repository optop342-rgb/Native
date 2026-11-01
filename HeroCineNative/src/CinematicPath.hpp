#pragma once

#include <algorithm>
#include <cmath>
#include <span>

namespace herocine {

struct CameraKeyframe {
    float tick{};
    float x{};
    float y{};
    float z{};
    float yaw{};
    float pitch{};
    float fov{70.0f};
};

struct CameraPose {
    float x{};
    float y{};
    float z{};
    float yaw{};
    float pitch{};
    float fov{70.0f};
};

inline float lerp(float from, float to, float amount) {
    return from + (to - from) * amount;
}

inline float lerpAngle(float from, float to, float amount) {
    float delta = std::remainder(to - from, 360.0f);
    return from + delta * amount;
}

inline CameraPose samplePath(std::span<const CameraKeyframe> keyframes, float tick) {
    if (keyframes.empty()) {
        return {};
    }

    if (tick <= keyframes.front().tick) {
        const auto &first = keyframes.front();
        return {first.x, first.y, first.z, first.yaw, first.pitch, first.fov};
    }

    for (std::size_t i = 1; i < keyframes.size(); ++i) {
        const auto &from = keyframes[i - 1];
        const auto &to = keyframes[i];
        if (tick <= to.tick) {
            const float span = to.tick - from.tick;
            const float linear = span > 0.0f
                ? std::clamp((tick - from.tick) / span, 0.0f, 1.0f)
                : 1.0f;
            const float amount = linear * linear * (3.0f - 2.0f * linear);
            return {
                lerp(from.x, to.x, amount),
                lerp(from.y, to.y, amount),
                lerp(from.z, to.z, amount),
                lerpAngle(from.yaw, to.yaw, amount),
                lerpAngle(from.pitch, to.pitch, amount),
                lerp(from.fov, to.fov, amount),
            };
        }
    }

    const auto &last = keyframes.back();
    return {last.x, last.y, last.z, last.yaw, last.pitch, last.fov};
}

} // namespace herocine
