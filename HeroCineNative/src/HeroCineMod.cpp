#include "CinematicPath.hpp"

#include <pl/Config.hpp>
#include <pl/Input.hpp>
#include <pl/Mod.hpp>
#include <pl/ModMenu.hpp>
#include <pl/memory/Hook.hpp>

#include <EGL/egl.h>
#include <android/input.h>
#include <dlfcn.h>
#include <imgui.h>
#include <backends/imgui_impl_opengl3.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace {

constexpr std::string_view ModuleId = "herocine_native.editor";
constexpr std::string_view ToggleButtonId = "herocine_native.toggle";
using EglSwapBuffersFn = EGLBoolean (*)(EGLDisplay, EGLSurface);

struct EditorState {
    int durationTicks{100};
    float startX{};
    float startY{};
    float startZ{};
    float endX{4.0f};
    float endY{2.0f};
    float endZ{};
    float startYaw{};
    float endYaw{90.0f};
    float fov{70.0f};
    int presetIndex{};
    float playhead{};
    bool playing{};
    std::string status{"Ready"};
};

struct HeroCineConfig {
    int version{1};
    int durationTicks{100};
    float startX{};
    float startY{};
    float startZ{};
    float endX{4.0f};
    float endY{2.0f};
    float endZ{};
    float startYaw{};
    float endYaw{90.0f};
    float fov{70.0f};
    int presetIndex{};
};

struct TouchInput {
    int action{};
    float x{};
    float y{};
};

EditorState gState;
std::optional<pl::config::ConfigFile<HeroCineConfig>> gConfig;
std::atomic<bool> gEnabled{false};
std::atomic<bool> gUiOpen{false};
std::atomic<bool> gInputEnabled{false};
std::mutex gTouchMutex;
std::vector<TouchInput> gTouchQueue;
EglSwapBuffersFn gOriginalSwapBuffers{};
EGLContext gRenderContext{EGL_NO_CONTEXT};
EGLSurface gRenderSurface{EGL_NO_SURFACE};
bool gImGuiInitialized{};
std::chrono::steady_clock::time_point gLastFrameTime{};

void renderEditor() {
    if (!gEnabled.load(std::memory_order_relaxed) ||
        !gUiOpen.load(std::memory_order_relaxed)) {
        return;
    }

    ImGui::SetNextWindowSize(ImVec2(540.0f, 520.0f), ImGuiCond_FirstUseEver);
    bool open = true;
    if (ImGui::Begin("HeroCine - Camera Path Studio", &open,
                     ImGuiWindowFlags_NoCollapse)) {
        static constexpr std::array<const char *, 5> presets{
            "Dolly", "Orbit", "Crane", "Hero reveal", "Custom"};
        ImGui::TextUnformatted("Native cinematic path editor");
        ImGui::TextDisabled("Path preview only - game camera API is not verified.");
        ImGui::TextDisabled("%s", gState.status.c_str());
        ImGui::Separator();

        if (ImGui::Combo("Shot preset", &gState.presetIndex, presets.data(),
                         static_cast<int>(presets.size()))) {
            switch (gState.presetIndex) {
            case 0:
                gState.endX = gState.startX + 4.0f;
                gState.endY = gState.startY;
                gState.endZ = gState.startZ;
                gState.endYaw = gState.startYaw;
                break;
            case 1:
                gState.endX = gState.startX;
                gState.endY = gState.startY;
                gState.endZ = gState.startZ + 4.0f;
                gState.endYaw = gState.startYaw + 180.0f;
                break;
            case 2:
                gState.endX = gState.startX;
                gState.endY = gState.startY + 4.0f;
                gState.endZ = gState.startZ;
                gState.endYaw = gState.startYaw;
                break;
            case 3:
                gState.endX = gState.startX + 2.0f;
                gState.endY = gState.startY + 1.0f;
                gState.endZ = gState.startZ + 4.0f;
                gState.endYaw = gState.startYaw + 90.0f;
                break;
            default:
                break;
            }
        }
        ImGui::SliderInt("Duration (ticks)", &gState.durationTicks, 20, 1200);
        ImGui::DragFloat3("Start position", &gState.startX, 0.1f);
        ImGui::DragFloat3("End position", &gState.endX, 0.1f);
        ImGui::SliderFloat("Start yaw", &gState.startYaw, -180.0f, 180.0f,
                           "%.1f deg");
        ImGui::SliderFloat("End yaw", &gState.endYaw, -180.0f, 180.0f,
                           "%.1f deg");
        ImGui::SliderFloat("Field of view", &gState.fov, 30.0f, 110.0f,
                           "%.1f deg");

        const auto keyframes = std::array{
            herocine::CameraKeyframe{
                0.0f, gState.startX, gState.startY, gState.startZ,
                gState.startYaw, 0.0f, gState.fov},
            herocine::CameraKeyframe{
                static_cast<float>(gState.durationTicks), gState.endX,
                gState.endY, gState.endZ, gState.endYaw, 0.0f, gState.fov},
        };
        const auto pose = herocine::samplePath(keyframes, gState.playhead);

        ImGui::Separator();
        ImGui::Text("Preview pose: %.2f, %.2f, %.2f | yaw %.1f | FOV %.1f",
                    pose.x, pose.y, pose.z, pose.yaw, pose.fov);
        ImGui::SliderFloat("Timeline", &gState.playhead, 0.0f,
                           static_cast<float>(gState.durationTicks), "%.1f ticks");
        if (gState.playing) {
            if (ImGui::Button("Pause preview")) {
                gState.playing = false;
            }
        } else if (ImGui::Button("Play preview")) {
            gState.playing = true;
            if (gState.playhead >= static_cast<float>(gState.durationTicks)) {
                gState.playhead = 0.0f;
            }
        }
        ImGui::SameLine();
        if (ImGui::Button("Stop")) {
            gState.playing = false;
            gState.playhead = 0.0f;
        }
        ImGui::SameLine();
        if (ImGui::Button("Save path")) {
            if (!gConfig) {
                gState.status = "Config is not loaded";
            } else {
                auto &config = gConfig->value();
                config.durationTicks = gState.durationTicks;
                config.startX = gState.startX;
                config.startY = gState.startY;
                config.startZ = gState.startZ;
                config.endX = gState.endX;
                config.endY = gState.endY;
                config.endZ = gState.endZ;
                config.startYaw = gState.startYaw;
                config.endYaw = gState.endYaw;
                config.fov = gState.fov;
                config.presetIndex = gState.presetIndex;
                gState.status = gConfig->save()
                    ? "Path saved to mod config"
                    : "Could not save path config";
            }
        }
        ImGui::SameLine();
        if (ImGui::Button("Close")) {
            open = false;
        }

        if (gState.playing) {
            const float ticksPerSecond = 20.0f;
            gState.playhead += ImGui::GetIO().DeltaTime * ticksPerSecond;
            if (gState.playhead >= static_cast<float>(gState.durationTicks)) {
                gState.playhead = static_cast<float>(gState.durationTicks);
                gState.playing = false;
            }
        }
    }
    ImGui::End();

    if (!open) {
        gUiOpen.store(false, std::memory_order_relaxed);
    }
}

void processTouchInput() {
    std::vector<TouchInput> pending;
    {
        std::lock_guard lock(gTouchMutex);
        pending.swap(gTouchQueue);
    }

    auto &io = ImGui::GetIO();
    for (const auto &event : pending) {
        io.AddMousePosEvent(event.x, event.y);
        switch (event.action & AMOTION_EVENT_ACTION_MASK) {
        case AMOTION_EVENT_ACTION_DOWN:
        case AMOTION_EVENT_ACTION_POINTER_DOWN:
            io.AddMouseButtonEvent(0, true);
            break;
        case AMOTION_EVENT_ACTION_UP:
        case AMOTION_EVENT_ACTION_POINTER_UP:
        case AMOTION_EVENT_ACTION_CANCEL:
            io.AddMouseButtonEvent(0, false);
            break;
        case AMOTION_EVENT_ACTION_MOVE:
            break;
        default:
            break;
        }
    }
}

bool onTouch(const pl::input::TouchEvent &event) {
    if (!gInputEnabled.load(std::memory_order_relaxed) ||
        !gUiOpen.load(std::memory_order_relaxed)) {
        return false;
    }

    {
        std::lock_guard lock(gTouchMutex);
        if (gTouchQueue.size() >= 64) {
            gTouchQueue.erase(gTouchQueue.begin());
        }
        gTouchQueue.push_back({event.action, event.x, event.y});
    }
    return true;
}

bool initializeImGui(EGLDisplay display, EGLSurface surface) {
    const EGLContext context = eglGetCurrentContext();
    if (context == EGL_NO_CONTEXT || display == EGL_NO_DISPLAY ||
        surface == EGL_NO_SURFACE) {
        return false;
    }
    if (gImGuiInitialized) {
        if (context != gRenderContext) {
            return false;
        }
        if (surface != gRenderSurface) {
            EGLint oldWidth{};
            EGLint oldHeight{};
            const bool oldSurfaceAlive =
                eglQuerySurface(display, gRenderSurface, EGL_WIDTH, &oldWidth) ==
                    EGL_TRUE &&
                eglQuerySurface(display, gRenderSurface, EGL_HEIGHT, &oldHeight) ==
                    EGL_TRUE &&
                oldWidth > 0 && oldHeight > 0;
            if (oldSurfaceAlive) {
                return false;
            }
            gRenderSurface = surface;
        }
        return true;
    }

    EGLint width{};
    EGLint height{};
    if (eglQuerySurface(display, surface, EGL_WIDTH, &width) != EGL_TRUE ||
        eglQuerySurface(display, surface, EGL_HEIGHT, &height) != EGL_TRUE ||
        width <= 0 || height <= 0) {
        return false;
    }

    IMGUI_CHECKVERSION();
    ImGui::CreateContext();
    ImGui::StyleColorsDark();
    auto &io = ImGui::GetIO();
    io.DisplaySize = ImVec2(static_cast<float>(width), static_cast<float>(height));
    io.IniFilename = nullptr;

    if (!ImGui_ImplOpenGL3_Init("#version 300 es")) {
        ImGui::DestroyContext();
        return false;
    }

    gRenderContext = context;
    gRenderSurface = surface;
    gLastFrameTime = std::chrono::steady_clock::now();
    gImGuiInitialized = true;
    return true;
}

void renderImGui(EGLDisplay display, EGLSurface surface) {
    if (!gEnabled.load(std::memory_order_relaxed) ||
        !gUiOpen.load(std::memory_order_relaxed)) {
        return;
    }
    if (!initializeImGui(display, surface)) {
        return;
    }

    EGLint width{};
    EGLint height{};
    if (eglQuerySurface(display, surface, EGL_WIDTH, &width) != EGL_TRUE ||
        eglQuerySurface(display, surface, EGL_HEIGHT, &height) != EGL_TRUE ||
        width <= 0 || height <= 0) {
        return;
    }

    auto &io = ImGui::GetIO();
    io.DisplaySize = ImVec2(static_cast<float>(width), static_cast<float>(height));
    const auto now = std::chrono::steady_clock::now();
    io.DeltaTime = std::clamp(
        std::chrono::duration<float>(now - gLastFrameTime).count(), 1.0f / 240.0f,
        1.0f / 10.0f);
    gLastFrameTime = now;

    processTouchInput();
    ImGui_ImplOpenGL3_NewFrame();
    ImGui::NewFrame();
    renderEditor();
    ImGui::Render();
    ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
}

EGLBoolean EGLAPIENTRY swapBuffersDetour(EGLDisplay display, EGLSurface surface) {
    renderImGui(display, surface);
    if (gOriginalSwapBuffers == nullptr) {
        return EGL_FALSE;
    }
    return gOriginalSwapBuffers(display, surface);
}

void onToggleButton(std::string_view, pl::modmenu::ButtonEvent event, float) {
    if (event == pl::modmenu::ButtonEvent::Click) {
        const bool open = !gUiOpen.load(std::memory_order_relaxed);
        gUiOpen.store(open, std::memory_order_relaxed);
        gInputEnabled.store(open, std::memory_order_relaxed);
    }
}

} // namespace

class HeroCineMod {
public:
    static HeroCineMod &instance() {
        static HeroCineMod mod;
        return mod;
    }

    HeroCineMod() : mSelf(*ll::mod::NativeMod::current()) {}

    bool load() {
        gConfig.emplace();
        if (!gConfig->load()) {
            mSelf.getLogger().error("Failed to load HeroCine config");
            return false;
        }
        const auto &config = gConfig->value();
        gState.durationTicks = std::clamp(config.durationTicks, 20, 1200);
        gState.startX = config.startX;
        gState.startY = config.startY;
        gState.startZ = config.startZ;
        gState.endX = config.endX;
        gState.endY = config.endY;
        gState.endZ = config.endZ;
        gState.startYaw = std::clamp(config.startYaw, -180.0f, 180.0f);
        gState.endYaw = std::clamp(config.endYaw, -180.0f, 180.0f);
        gState.fov = std::clamp(config.fov, 30.0f, 110.0f);
        gState.presetIndex = std::clamp(config.presetIndex, 0, 4);
        mSelf.getLogger().info("Loaded {}", mSelf.getName());
        return true;
    }

    bool enable() {
        if (!mSwapHook.installed()) {
            auto *swapBuffers = dlsym(RTLD_DEFAULT, "eglSwapBuffers");
            if (swapBuffers == nullptr) {
                mSelf.getLogger().error("Could not resolve eglSwapBuffers");
                return false;
            }
            void *original{};
            mSwapHook = pl::memory::HookHandle(
                swapBuffers, reinterpret_cast<void *>(&swapBuffersDetour),
                &original);
            if (!mSwapHook.installed()) {
                mSelf.getLogger().error("Could not install the EGL overlay hook");
                return false;
            }
            gOriginalSwapBuffers = reinterpret_cast<EglSwapBuffersFn>(original);
        }

        const bool moduleRegistered =
            pl::modmenu::ModuleBuilder(std::string(ModuleId), "HeroCine ImGui")
                .modId(mSelf.getId())
                .description("Open the native HeroCine camera-path editor.")
                .defaultEnabled(true)
                .onToggle([](std::string_view, bool enabled) {
                    gEnabled.store(enabled, std::memory_order_relaxed);
                    if (!enabled) {
                        gUiOpen.store(false, std::memory_order_relaxed);
                        gInputEnabled.store(false, std::memory_order_relaxed);
                    }
                })
                .registerModule();
        if (!moduleRegistered) {
            mSwapHook.reset();
            gOriginalSwapBuffers = nullptr;
            mSelf.getLogger().error("Failed to register HeroCine module");
            return false;
        }

        const bool buttonRegistered =
            pl::modmenu::ButtonBuilder(std::string(ToggleButtonId),
                                       "Open HeroCine Studio")
                .moduleId(std::string(ModuleId))
                .modId(mSelf.getId())
                .label("HC")
                .behavior(pl::modmenu::ButtonBehavior::Click)
                .onEvent(onToggleButton)
                .registerButton();
        if (!buttonRegistered) {
            pl::modmenu::unregisterModule(ModuleId);
            mSwapHook.reset();
            gOriginalSwapBuffers = nullptr;
            mSelf.getLogger().error("Failed to register HeroCine button");
            return false;
        }

        if (!mInputRegistered) {
            pl::input::registerTouchCallback(onTouch);
            mInputRegistered = true;
        }
        gEnabled.store(true, std::memory_order_relaxed);
        return true;
    }

    bool disable() {
        gEnabled.store(false, std::memory_order_relaxed);
        gUiOpen.store(false, std::memory_order_relaxed);
        gInputEnabled.store(false, std::memory_order_relaxed);
        pl::modmenu::unregisterButton(ToggleButtonId);
        pl::modmenu::unregisterModule(ModuleId);
        mSwapHook.reset();
        gOriginalSwapBuffers = nullptr;
        return true;
    }

    bool unload() {
        if (mInputRegistered) {
            mSelf.getLogger().warn(
                "Preloader has no touch-callback unregister API; keeping the mod loaded");
            return false;
        }
        if (gImGuiInitialized) {
            if (eglGetCurrentContext() == gRenderContext) {
                ImGui_ImplOpenGL3_Shutdown();
            }
            ImGui::DestroyContext();
            gImGuiInitialized = false;
            gRenderContext = EGL_NO_CONTEXT;
            gRenderSurface = EGL_NO_SURFACE;
        }
        mSwapHook.reset();
        return true;
    }

private:
    ll::mod::NativeMod &mSelf;
    pl::memory::HookHandle mSwapHook;
    bool mInputRegistered{};
};

PL_REGISTER_MOD(HeroCineMod, HeroCineMod::instance())
