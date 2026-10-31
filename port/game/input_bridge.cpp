/*
 * Input for Modern Combat 4, and the autopilot that proves the game advances.
 */

#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include <SDL2/SDL.h>

#include "platform.h"
#include "so_util.h"
#include "jni.h"

#include "input_bridge.h"
#include <stdlib.h>

#include "port_env.h"
#include "gl_bridge.h"
#include "trace.h"

#include "mc4.h"

extern "C" long android_gl_textures_uploaded(void);

typedef void (*fn_key_t)(JNIEnv *, jclass, jint);
typedef void (*fn_touch_t)(JNIEnv *, jclass, jint, jint, jint, jint);
typedef void (*fn_bool_t)(JNIEnv *, jclass, jboolean);
typedef jboolean (*fn_query_t)(JNIEnv *, jclass);
typedef void (ABI_ATTR *fn_stick2_t)(JNIEnv *, jclass, jfloat, jfloat);
typedef void (ABI_ATTR *fn_stick3_t)(JNIEnv *, jclass, jfloat, jfloat, jfloat);

static fn_key_t    g_on_key_down;
static fn_key_t    g_on_key_up;
static fn_touch_t  g_touch_event;
static fn_bool_t   g_set_is_moga;
static fn_bool_t   g_set_xperia_play;
static fn_bool_t   g_set_so01d;
static fn_bool_t   g_power_status;
static fn_query_t  g_is_main_menu;
static fn_stick2_t g_left_stick;
static fn_stick2_t g_right_stick;

static JNIEnv *g_env;
static int      g_width  = 640;
static int      g_height = 480;

enum {
    KEY_BACK        = 4,    /* KEYCODE_BACK - acionava moveToBackground() */
    KEY_DPAD_UP     = 19,
    KEY_DPAD_DOWN   = 20,
    KEY_DPAD_LEFT   = 21,
    KEY_DPAD_RIGHT  = 22,
    KEY_DPAD_CENTER = 23,
    KEY_BUTTON_A    = 124,
    KEY_B           = 227,
    KEY_X           = 99,
    KEY_Y           = 100,
    KEY_L1          = 102,
    KEY_R1          = 103,
    KEY_START       = 108,
    KEY_SELECT_PLAY = 200,
};

static int select_keycode(void)
{
    return mc4_is_main_menu() ? KEY_DPAD_CENTER : KEY_SELECT_PLAY;
}

static void send_key(int code, bool down)
{
    /* Intercepta a tecla Voltar (4) para evitar pausar a renderização */
    if (code == KEY_BACK) {
        trace("input: key 4 (BACK) interceptada para prevenir moveToBackground() (menu=%d)", (int)mc4_is_main_menu());
        code = select_keycode();
    }

    static int logged;
    if (logged < 200) {
        logged++;
        trace("input: key %d %s (menu=%d)", code, down ? "down" : "up",
              (int)mc4_is_main_menu());
    }
    if (down) {
        if (g_on_key_down)
            g_on_key_down(g_env, NULL, code);
    } else {
        if (g_on_key_up)
            g_on_key_up(g_env, NULL, code);
    }
}

static float g_cursor_x = 320.0f;
static float g_cursor_y = 240.0f;
static bool  g_cursor_down;
static int   g_cursor_visible = 1;

enum { TOUCH_DOWN = 1, TOUCH_UP = 0, TOUCH_MOVE = 2 };

static void send_touch(int action)
{
    static int logged;
    if (logged < 120) {
        logged++;
        trace("input: touch action=%d at %d,%d (menu=%d)", action,
              (int)g_cursor_x, (int)g_cursor_y, (int)mc4_is_main_menu());
    }
    if (!g_touch_event)
        return;
    g_touch_event(g_env, NULL, action, (jint)g_cursor_x, (jint)g_cursor_y, 0);
}

extern "C" void android_input_cursor_position(float *x, float *y, int *visible)
{
    if (x)       *x = g_cursor_x;
    if (y)       *y = g_cursor_y;
    static int allowed = -1;
    if (allowed < 0)
        allowed = port_getenv_long("CURSOR", 1) != 0;
    if (visible) *visible = allowed && g_cursor_visible && mc4_is_main_menu();
}

void android_input_cursor_set(float x, float y)
{
    g_cursor_x = x < 0 ? 0 : (x > g_width  - 1 ? g_width  - 1 : x);
    g_cursor_y = y < 0 ? 0 : (y > g_height - 1 ? g_height - 1 : y);
    if (g_cursor_down)
        send_touch(TOUCH_MOVE);
}

void android_input_cursor_press(bool down)
{
    if (down == g_cursor_down)
        return;
    g_cursor_down = down;
    send_touch(down ? TOUCH_DOWN : TOUCH_UP);
}

static float g_right_x, g_right_y;
static float g_left_x,  g_left_y;
static Uint32 g_right_centred_at;
static long  g_input_ticks;

static bool g_sprinting;
static bool g_arrow_forward;
static int  g_forward_suppress;

static void stick_as_dpad(void)
{
    static bool held[4];
    const struct { float v; bool positive; int code; } lanes[4] = {
        { g_left_y, false, KEY_DPAD_UP    },
        { g_left_y, true,  KEY_DPAD_DOWN  },
        { g_left_x, false, KEY_DPAD_LEFT  },
        { g_left_x, true,  KEY_DPAD_RIGHT },
    };
    for (int i = 0; i < 4; i++) {
        float a = lanes[i].positive ? lanes[i].v : -lanes[i].v;
        bool want = held[i] ? (a > 0.30f) : (a > 0.45f);
        if (i == 0 && g_forward_suppress > 0)
            want = false;
        if (want != held[i]) {
            held[i] = want;
            send_key(lanes[i].code, want);
        }
    }
    if (g_forward_suppress > 0)
        g_forward_suppress--;
    if (g_sprinting && !held[0] && !g_arrow_forward && g_forward_suppress == 0)
        g_sprinting = false;
}

static void push_sticks(void)
{
    stick_as_dpad();
    if (g_left_stick)
        g_left_stick(g_env, NULL, g_left_x, g_left_y);

    if (mc4_is_main_menu()) {
        if (g_right_x != 0.0f || g_right_y != 0.0f) {
            float x, y;
            int visible;
            android_input_cursor_position(&x, &y, &visible);
            android_input_cursor_set(x + g_right_x * 9.0f,
                                     y + g_right_y * 9.0f);
        }
        if (g_right_stick)
            g_right_stick(g_env, NULL, 0.0f, 0.0f);
        return;
    }

    if (!g_right_stick)
        return;

    float accel;
    if (g_right_x == 0.0f && g_right_y == 0.0f) {
        g_right_centred_at = SDL_GetTicks();
        accel = 0.3f;
    } else {
        Uint32 held = SDL_GetTicks() - g_right_centred_at;
        accel = held > 450 ? 1.2f : held > 250 ? 1.0f : held > 100 ? 0.8f : 0.6f;
    }

    static float scale = -1.0f;
    if (scale < 0.0f) {
        const char *v = port_getenv("RPAD_SCALE");
        int pct = v && *v ? atoi(v) : 100;
        if (pct < 25)  pct = 25;
        if (pct > 400) pct = 400;
        scale = (float)pct / 100.0f;
    }
    g_right_stick(g_env, NULL, g_right_x * 7.0f, g_right_y * 7.0f);
}

static const struct {
    SDL_GameControllerButton button;
    int                      code;
} kButtons[] = {
    { SDL_CONTROLLER_BUTTON_A,              KEY_B           },
    { SDL_CONTROLLER_BUTTON_B,              123             },
    { SDL_CONTROLLER_BUTTON_X,              121             },
    { SDL_CONTROLLER_BUTTON_Y,              KEY_Y           },
    { SDL_CONTROLLER_BUTTON_LEFTSHOULDER,  KEY_L1          },
    { SDL_CONTROLLER_BUTTON_RIGHTSHOULDER, KEY_R1          },
    { SDL_CONTROLLER_BUTTON_DPAD_DOWN,      KEY_DPAD_DOWN   },
    { SDL_CONTROLLER_BUTTON_DPAD_LEFT,      KEY_DPAD_LEFT   },
    { SDL_CONTROLLER_BUTTON_DPAD_RIGHT,     KEY_DPAD_RIGHT  },
    { SDL_CONTROLLER_BUTTON_RIGHTSTICK,    KEY_DPAD_CENTER },
    { SDL_CONTROLLER_BUTTON_LEFTSTICK,     120             },
    { SDL_CONTROLLER_BUTTON_START,          KEY_START       },
};

static Uint8 face_translate(Uint8 b)
{
    static int swap = -1;
    if (swap < 0) {
        const char *v = port_getenv("FACE_LAYOUT");
        swap = (v && strcasecmp(v, "xbox") == 0) ? 1 : 0;
        trace("input: face layout %s", swap ? "xbox" : "nintendo");
    }
    if (!swap)
        return b;
    switch (b) {
    case SDL_CONTROLLER_BUTTON_A: return SDL_CONTROLLER_BUTTON_B;
    case SDL_CONTROLLER_BUTTON_B: return SDL_CONTROLLER_BUTTON_A;
    case SDL_CONTROLLER_BUTTON_X: return SDL_CONTROLLER_BUTTON_Y;
    case SDL_CONTROLLER_BUTTON_Y: return SDL_CONTROLLER_BUTTON_X;
    default:                      return b;
    }
}

static float axis_norm(Sint16 v)
{
    const float dead = 8000.0f;
    float f = (float)v;
    if (f > -dead && f < dead)
        return 0.0f;
    f /= 32767.0f;
    return f < -1.0f ? -1.0f : (f > 1.0f ? 1.0f : f);
}

bool android_input_event(const SDL_Event *event)
{
    switch (event->type) {
    case SDL_QUIT:
        return false;

    case SDL_CONTROLLERDEVICEADDED:
        SDL_GameControllerOpen(event->cdevice.which);
        return true;

    case SDL_CONTROLLERBUTTONDOWN:
    case SDL_CONTROLLERBUTTONUP: {
        bool down = event->type == SDL_CONTROLLERBUTTONDOWN;
        Uint8 btn = face_translate(event->cbutton.button);

        if (btn == SDL_CONTROLLER_BUTTON_BACK) {
            send_key(select_keycode(), down);
            return true;
        }
        if (!mc4_is_main_menu() &&
            btn == SDL_CONTROLLER_BUTTON_DPAD_DOWN) {
            send_key(109, down);
            return true;
        }
        if (!mc4_is_main_menu() &&
            btn == SDL_CONTROLLER_BUTTON_DPAD_LEFT) {
            send_key(122, down);
            return true;
        }
        if (!mc4_is_main_menu() &&
            btn == SDL_CONTROLLER_BUTTON_DPAD_RIGHT) {
            send_key(99, down);
            return true;
        }
        if (btn == SDL_CONTROLLER_BUTTON_DPAD_UP) {
            if (mc4_is_main_menu()) {
                send_key(KEY_DPAD_UP, down);
                return true;
            }
            if (down && g_sprinting) {
                g_sprinting = false;
                g_forward_suppress = 8;
                trace("input: sprint off (forward released for 8 frames)");
                return true;
            }
            if (down) {
                if (g_touch_event) {
                    trace("input: touch action=100 (sprint)");
                    g_touch_event(g_env, NULL, 100, 0, 0, 0);
                }
                g_sprinting = true;
            }
            g_arrow_forward = down;
            send_key(KEY_DPAD_UP, down);
            return true;
        }
        if (btn == SDL_CONTROLLER_BUTTON_A &&
            mc4_is_main_menu()) {
            android_input_cursor_press(down);
            return true;
        }

        bool mapped = false;
        for (unsigned i = 0; i < sizeof(kButtons) / sizeof(kButtons[0]); i++) {
            if (kButtons[i].button == btn) {
                trace("input: controller button=%u %s -> key %d",
                      (unsigned int)btn, down ? "down" : "up", kButtons[i].code);
                send_key(kButtons[i].code, down);
                mapped = true;
                break;
            }
        }
        if (!mapped)
            trace("input: controller button=%u %s -> UNMAPPED (nothing sent)",
                  (unsigned int)btn, down ? "down" : "up");
        return true;
    }

    case SDL_CONTROLLERAXISMOTION:
        switch (event->caxis.axis) {
        case SDL_CONTROLLER_AXIS_LEFTX:  g_left_x  = axis_norm(event->caxis.value); break;
        case SDL_CONTROLLER_AXIS_LEFTY:  g_left_y  = axis_norm(event->caxis.value); break;
        case SDL_CONTROLLER_AXIS_RIGHTX: g_right_x = axis_norm(event->caxis.value); break;
        case SDL_CONTROLLER_AXIS_RIGHTY: g_right_y = axis_norm(event->caxis.value); break;
        case SDL_CONTROLLER_AXIS_TRIGGERLEFT: {
            static bool was_down;
            bool now_down = event->caxis.value > 16000;
            if (mc4_is_main_menu()) {
                was_down = false;
                android_input_cursor_press(now_down);
            } else if (now_down != was_down) {
                was_down = now_down;
                send_key(KEY_L1, now_down);
            }
            break;
        }
        case SDL_CONTROLLER_AXIS_TRIGGERRIGHT: {
            static bool was_down;
            bool now_down = event->caxis.value > 16000;
            if (mc4_is_main_menu()) {
                was_down = false;
                android_input_cursor_press(now_down);
            } else if (now_down != was_down) {
                was_down = now_down;
                send_key(KEY_R1, now_down);
            }
            break;
        }
        default: break;
        }
        return true;

    case SDL_MOUSEMOTION:
        android_input_cursor_set((float)event->motion.x, (float)event->motion.y);
        return true;

    case SDL_MOUSEBUTTONDOWN:
    case SDL_MOUSEBUTTONUP:
        android_input_cursor_set((float)event->button.x, (float)event->button.y);
        android_input_cursor_press(event->type == SDL_MOUSEBUTTONDOWN);
        return true;

    default:
        return true;
    }
}

void android_input_tick(void)
{
    g_input_ticks++;
    if (g_input_ticks == 200) {
        mc4_input_select_gamepad_mode();
        trace("input mode: re-asserted at tick 200");
    }
    push_sticks();
}

static const struct { const char *name; int code; } kControls[] = {
    { "a",     KEY_DPAD_CENTER },
    { "b",     KEY_B           },
    { "x",     KEY_X           },
    { "y",     KEY_Y           },
    { "l1",    KEY_L1          },
    { "r1",    KEY_R1          },
    { "l2",    KEY_L1          },
    { "r2",    KEY_R1          },
    { "start", KEY_START       },
    { "up",    KEY_DPAD_UP     },
    { "down",  KEY_DPAD_DOWN   },
    { "left",  KEY_DPAD_LEFT   },
    { "right", KEY_DPAD_RIGHT  },
};

bool android_input_inject_control(const char *name, bool down)
{
    if (!name)
        return false;

    if (strcmp(name, "select") == 0) {
        send_key(select_keycode(), down);
        return true;
    }
    for (unsigned i = 0; i < sizeof(kControls) / sizeof(kControls[0]); i++) {
        if (strcmp(kControls[i].name, name) == 0) {
            send_key(kControls[i].code, down);
            return true;
        }
    }
    return false;
}

bool android_input_inject_touch(int action, int x, int y, int id)
{
    if (g_input_ticks < 150 || !g_touch_event)
        return false;
    static int logged;
    if (action != 2 && logged < 200) {
        logged++;
        trace("input: touch %d at %d,%d id %d (menu=%d)", action, x, y, id,
              (int)mc4_is_main_menu());
    }
    static long inset = -1;
    if (inset < 0) {
        inset = port_getenv_long("HUD_INSET", 0);
        if (inset < 0 || inset > 20)
            inset = 0;
    }
    if (inset > 0) {
        x = (int)((x - g_width * inset / 100.0f) * 100.0f / (100 - 2 * inset));
        y = (int)((y - g_height * inset / 100.0f) * 100.0f / (100 - 2 * inset));
        x = x < 0 ? 0 : x >= g_width ? g_width - 1 : x;
        y = y < 0 ? 0 : y >= g_height ? g_height - 1 : y;
    }
    g_touch_event(g_env, NULL, action, x, y, id);
    return true;
}

bool android_input_inject_key(int code, bool down)
{
    if (g_input_ticks < 150)
        return false;
    send_key(code, down);
    return true;
}

extern "C" void mc4_update_keyboard_text(const char* text);

bool android_input_inject_text(const char *text) {
    mc4_update_keyboard_text(text);
    return true;
}

bool android_input_inject_stick(const char *name, float x, float y)
{
    if (!name)
        return false;
    if (strcmp(name, "left") == 0) {
        g_left_x = x; g_left_y = y;
        return true;
    }
    if (strcmp(name, "right") == 0) {
        g_right_x = x; g_right_y = y;
        return true;
    }
    return false;
}

bool mc4_is_main_menu(void)
{
    if (g_input_ticks < 150)
        return false;
    if (!g_is_main_menu)
        return false;
    return g_is_main_menu(g_env, NULL) != JNI_FALSE;
}

bool android_input_in_menu(void)
{
    return mc4_is_main_menu();
}

static long   g_auto_keys;
static long   g_auto_scenes;
static uint32_t g_last_hash;
static long   g_last_textures = -1;
static bool   g_auto_ready;
static bool   g_auto_enabled;

static const int kAutopilotKeys[] = {
    KEY_DPAD_CENTER,
    KEY_START,
    KEY_DPAD_DOWN,
    KEY_DPAD_CENTER,
    KEY_B,
    KEY_DPAD_CENTER,
};

static bool autopilot_enabled(void)
{
    if (!g_auto_ready) {
        g_auto_ready = true;
        g_auto_enabled = port_getenv_bool(
            "AUTOPILOT", port_getenv_long("FRAME_LIMIT", 0) != 0 ? 1 : 0);
        if (g_auto_enabled)
            trace("autopilot on: synthetic input every 40 frames");
    }
    return g_auto_enabled;
}

static const long kAutopilotStart = 120;

void android_input_autopilot_tick(long frame)
{
    if (!autopilot_enabled() || frame < kAutopilotStart)
        return;

    frame -= kAutopilotStart;

    long phase = frame % 40;
    long index = (frame / 40) % (long)(sizeof(kAutopilotKeys) / sizeof(kAutopilotKeys[0]));

    if (phase == 0) {
        send_key(kAutopilotKeys[index], true);
        g_auto_keys++;
    } else if (phase == 4) {
        send_key(kAutopilotKeys[index], false);
    }
}

static uint32_t fnv1a(const unsigned char *p, size_t n)
{
    uint32_t h = 2166136261u;
    while (n--) {
        h ^= *p++;
        h *= 16777619u;
    }
    return h;
}

void android_input_autopilot_sample(long frame)
{
    if (!autopilot_enabled() || frame % 30 != 0)
        return;

    static void (*read_pixels)(int, int, int, int, unsigned, unsigned, void *);
    if (!read_pixels) {
        read_pixels = (void (*)(int, int, int, int, unsigned, unsigned, void *))
            SDL_GL_GetProcAddress("glReadPixels");
        if (!read_pixels) {
            trace("autopilot: no glReadPixels - scene changes cannot be counted");
            g_auto_enabled = false;
            return;
        }
    }

    static unsigned char row[4 * 1920];
    int w = g_width > 1920 ? 1920 : g_width;
    read_pixels(0, g_height / 2, w, 1, 0x1908,
                0x1401, row);

    uint32_t hash     = fnv1a(row, (size_t)w * 4);
    long     textures = android_gl_textures_uploaded();

    if (g_last_textures >= 0 && hash != g_last_hash && textures > g_last_textures) {
        g_auto_scenes++;
        trace("autopilot: scene changed at frame %ld (textures %ld -> %ld, "
              "mainmenu=%d)", frame, g_last_textures, textures,
              (int)mc4_is_main_menu());
    }

    g_last_hash     = hash;
    g_last_textures = textures;
}

long android_input_autopilot_keys(void)   { return g_auto_keys; }
long android_input_autopilot_scenes(void) { return g_auto_scenes; }

void android_input_init(so_module *mod, _JNIEnv *env, int width, int height)
{
    g_env    = (JNIEnv *)env;
    g_width  = width;
    g_height = height;
    g_cursor_x = width  / 2.0f;
    g_cursor_y = height / 2.0f;

    g_on_key_down     = (fn_key_t)   so_symbol(mod, MC4_GL2JNILIB("OnKeyDown"));
    g_on_key_up       = (fn_key_t)   so_symbol(mod, MC4_GL2JNILIB("OnKeyUp"));
    g_touch_event     = (fn_touch_t) so_symbol(mod, MC4_GL2JNILIB("touchEvent"));
    g_set_is_moga     = (fn_bool_t)  so_symbol(mod, MC4_GL2JNILIB("nativeSetIsMOGA"));
    g_set_xperia_play = (fn_bool_t)  so_symbol(mod, MC4_GL2JNILIB("nativeSetXperiaPlay"));
    g_set_so01d       = (fn_bool_t)  so_symbol(mod, MC4_GL2JNILIB("nativeSetSO01D"));
    g_power_status    = (fn_bool_t)  so_symbol(mod, MC4_GL2JNILIB("nativeSetPowerAConnected"));
    g_is_main_menu    = (fn_query_t) so_symbol(mod, MC4_GL2JNILIB("nativeIsMainMenuOrIGM"));
    g_left_stick      = (fn_stick2_t)so_symbol(mod, MC4_GL2JNILIB("nativeSetPowerALeftJoystick"));
    g_right_stick     = (fn_stick2_t)so_symbol(mod, MC4_GL2JNILIB("nativeSetPowerARightJoystick"));

    trace("input: keys=%p touch=%p sticks=%p/%p mainmenu=%p",
          (void *)g_on_key_down, (void *)g_touch_event,
          (void *)g_left_stick, (void *)g_right_stick, (void *)g_is_main_menu);

    if (!gl_bridge_enabled())
        SDL_GameControllerOpen(0);
}

static int g_pad_connected = -1;

static bool pad_connected(void)
{
    if (g_pad_connected < 0)
        g_pad_connected = port_getenv_long("TOUCH", 0) ? 0 : 1;
    return g_pad_connected != 0;
}

void android_input_set_pad(bool connected)
{
    g_pad_connected = connected ? 1 : 0;
    if (g_input_ticks >= 200 && g_power_status)
        g_power_status(g_env, NULL, connected ? JNI_TRUE : JNI_FALSE);
    trace("input mode: PowerA %s", connected ? "connected" : "disconnected");
}

void mc4_input_select_gamepad_mode(void)
{
    if (g_set_is_moga)     g_set_is_moga(g_env, NULL, JNI_TRUE);
    if (g_set_xperia_play) g_set_xperia_play(g_env, NULL, JNI_FALSE);
    if (g_set_so01d)       g_set_so01d(g_env, NULL, JNI_FALSE);

    bool pad = pad_connected();
    if (g_power_status)    g_power_status(g_env, NULL, pad ? JNI_TRUE : JNI_FALSE);

    trace("input mode: %s", pad ? "MOGA gamepad, PowerA connected"
                                : "touch phone, no PowerA");
}
