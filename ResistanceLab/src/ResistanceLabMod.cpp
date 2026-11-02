#include <pl/Mod.hpp>
#include <pl/ModMenu.hpp>
#include <pl/memory/Hook.hpp>

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <string_view>

static constexpr uintptr_t OFF_SPEED_GET_AT = 0xc057f48;
static constexpr uintptr_t OFF_FRICTION_GET_AT = 0xc022688;
static constexpr float ICE_FRICTION = 0.98f;

static std::atomic<bool> g_speedOn{false};
static std::atomic<bool> g_iceOn{false};
static std::atomic<float> g_mult{2.0f};

using GetAtFn = float* (*)(void*, uint32_t);
static GetAtFn origSpeedGetAt = nullptr;
static GetAtFn origFrictionGetAt = nullptr;

static thread_local float s_speedShadow;
static thread_local float s_frictionShadow;

static float* hookSpeedGetAt(void* storage, uint32_t entity) {
    float* p = origSpeedGetAt(storage, entity);
    if (p && g_speedOn.load(std::memory_order_relaxed)) {
        s_speedShadow = (*p) * g_mult.load(std::memory_order_relaxed);
        return &s_speedShadow;
    }
    return p;
}

static float* hookFrictionGetAt(void* storage, uint32_t entity) {
    float* p = origFrictionGetAt(storage, entity);
    if (p && g_iceOn.load(std::memory_order_relaxed)) {
        s_frictionShadow = ICE_FRICTION;
        return &s_frictionShadow;
    }
    return p;
}

static uintptr_t findLibBase(const char* libName) {
    FILE* f = std::fopen("/proc/self/maps", "r");
    if (!f) return 0;

    char line[512];
    uintptr_t base = 0;
    while (std::fgets(line, sizeof(line), f)) {
        if (!std::strstr(line, libName)) continue;
        uintptr_t start = 0;
        if (std::sscanf(line, "%lx-", &start) == 1) {
            if (base == 0 || start < base) base = start;
        }
    }
    std::fclose(f);
    return base;
}

class ResistanceLabMod {
public:
    bool load(pl::mod::ModContext& context) {
        return setupMenu(context);
    }

    bool enable(pl::mod::ModContext& context) {
        const uintptr_t base = findLibBase("libminecraftpe.so");
        if (!base) {
            context.logger().error("ResistanceLab: libminecraftpe.so base not found");
            return false;
        }

        mSpeedHook = pl::memory::HookHandle(
            reinterpret_cast<void*>(base + OFF_SPEED_GET_AT),
            reinterpret_cast<void*>(&hookSpeedGetAt),
            reinterpret_cast<void**>(&origSpeedGetAt));
        mIceHook = pl::memory::HookHandle(
            reinterpret_cast<void*>(base + OFF_FRICTION_GET_AT),
            reinterpret_cast<void*>(&hookFrictionGetAt),
            reinterpret_cast<void**>(&origFrictionGetAt));

        if (!mSpeedHook.installed() || !mIceHook.installed()) {
            context.logger().error("ResistanceLab: hook installation failed");
            mSpeedHook = {};
            mIceHook = {};
            return false;
        }

        context.logger().info("ResistanceLab enabled (speed={} , ice={})",
                              g_speedOn.load(), g_iceOn.load());
        return true;
    }

    bool disable() {
        g_speedOn = false;
        g_iceOn = false;
        g_mult = 1.0f;
        mSpeedHook = {};
        mIceHook = {};
        return true;
    }

    bool unload(pl::mod::ModContext&) {
        disable();
        pl::modmenu::unregisterButton("resistance.speed_toggle");
        pl::modmenu::unregisterButton("resistance.mult_cycle");
        pl::modmenu::unregisterButton("resistance.ice_toggle");
        pl::modmenu::unregisterModule("resistance.lab");
        return true;
    }

private:
    pl::memory::HookHandle mSpeedHook;
    pl::memory::HookHandle mIceHook;

    bool setupMenu(pl::mod::ModContext& context) {
        const std::string& modId = context.id();
        if (!pl::modmenu::ModuleBuilder("resistance.lab", "Resistance Lab")
            .modId(modId)
            .description("Speed multiplier + ice-slide friction (singleplayer)")
            .registerModule()) {
            context.logger().error("ResistanceLab: module registration failed");
            return false;
        }

        const bool speedRegistered =
            pl::modmenu::ButtonBuilder("resistance.speed_toggle", "Speed/Resistance Mod")
            .moduleId("resistance.lab")
            .modId(modId)
            .label("Speed Mod: OFF")
            .behavior(pl::modmenu::ButtonBehavior::Toggle)
            .onEvent([](std::string_view, pl::modmenu::ButtonEvent event, float state) {
                if (event == pl::modmenu::ButtonEvent::StateChanged)
                    g_speedOn.store(state > 0.5f);
            })
            .registerButton();

        const bool multiplierRegistered =
            pl::modmenu::ButtonBuilder("resistance.mult_cycle", "Multiplier")
            .moduleId("resistance.lab")
            .modId(modId)
            .label("Cycle Multiplier")
            .behavior(pl::modmenu::ButtonBehavior::Click)
            .onEvent([](std::string_view, pl::modmenu::ButtonEvent event, float) {
                if (event != pl::modmenu::ButtonEvent::Click) return;
                static constexpr float presets[] = {0.5f, 1.0f, 1.5f, 2.0f, 3.0f, 5.0f};
                static int idx = 3;
                idx = (idx + 1) % 6;
                g_mult.store(presets[idx]);
            })
            .registerButton();

        const bool iceRegistered =
            pl::modmenu::ButtonBuilder("resistance.ice_toggle", "Ice Slide (Friction)")
            .moduleId("resistance.lab")
            .modId(modId)
            .label("Ice Slide: OFF")
            .behavior(pl::modmenu::ButtonBehavior::Toggle)
            .onEvent([](std::string_view, pl::modmenu::ButtonEvent event, float state) {
                if (event == pl::modmenu::ButtonEvent::StateChanged)
                    g_iceOn.store(state > 0.5f);
            })
            .registerButton();

        if (!speedRegistered || !multiplierRegistered || !iceRegistered) {
            pl::modmenu::unregisterButton("resistance.speed_toggle");
            pl::modmenu::unregisterButton("resistance.mult_cycle");
            pl::modmenu::unregisterButton("resistance.ice_toggle");
            pl::modmenu::unregisterModule("resistance.lab");
            context.logger().error("ResistanceLab: button registration failed");
            return false;
        }
        return true;
    }
};

PL_REGISTER_MOD(ResistanceLabMod, ResistanceLabMod{})
