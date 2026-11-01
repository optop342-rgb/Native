#include "../src/CinematicPath.hpp"

#include <array>
#include <cassert>
#include <cmath>

int main() {
    const std::array keyframes{
        herocine::CameraKeyframe{0.0f, 0.0f, 0.0f, 0.0f, 350.0f, 0.0f, 60.0f},
        herocine::CameraKeyframe{100.0f, 10.0f, 4.0f, -2.0f, 10.0f, 20.0f, 100.0f},
    };

    const auto start = herocine::samplePath(keyframes, -1.0f);
    assert(start.x == 0.0f);
    assert(start.yaw == 350.0f);

    const auto middle = herocine::samplePath(keyframes, 50.0f);
    assert(std::abs(middle.x - 5.0f) < 0.001f);
    assert(std::abs(middle.y - 2.0f) < 0.001f);
    assert(std::abs(middle.z + 1.0f) < 0.001f);
    assert(std::abs(middle.yaw - 360.0f) < 0.001f);
    assert(std::abs(middle.fov - 80.0f) < 0.001f);

    const auto end = herocine::samplePath(keyframes, 101.0f);
    assert(end.x == 10.0f);
    assert(end.yaw == 10.0f);
}
