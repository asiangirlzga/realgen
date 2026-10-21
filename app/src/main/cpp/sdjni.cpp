// Thin JNI wrapper around stable-diffusion.cpp for single-image generation.
#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <mutex>
#include <string>
#include <vector>

#include "stable-diffusion.h"

#define TAG "sdjni"

static JavaVM* g_vm = nullptr;
static jclass g_cls = nullptr;
static jmethodID g_on_progress = nullptr;

static sd_ctx_t* g_ctx = nullptr;
static std::mutex g_log_mu;
static std::string g_log;

static void log_cb(enum sd_log_level_t level, const char* text, void*) {
    if (!text) return;
    __android_log_print(level >= SD_LOG_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_INFO, TAG, "%s", text);
    if (level >= SD_LOG_WARN) {
        std::lock_guard<std::mutex> l(g_log_mu);
        g_log += text;
        if (g_log.size() > 3000) g_log.erase(0, g_log.size() - 3000);
    }
}

static void progress_cb(int step, int steps, float, void*) {
    if (!g_vm || !g_cls || !g_on_progress) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    env->CallStaticVoidMethod(g_cls, g_on_progress, step, steps);
    if (attached) g_vm->DetachCurrentThread();
}

static std::string to_str(JNIEnv* env, jstring s) {
    if (!s) return "";
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

static jstring ascii_jstring(JNIEnv* env, std::string s) {
    for (auto& ch : s) {
        if (static_cast<unsigned char>(ch) > 126) ch = '?';
    }
    return env->NewStringUTF(s.c_str());
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass c = env->FindClass("com/realgen/offline/Native");
    if (!c) return JNI_ERR;
    g_cls = static_cast<jclass>(env->NewGlobalRef(c));
    g_on_progress = env->GetStaticMethodID(g_cls, "onProgress", "(II)V");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_realgen_offline_Native_load(JNIEnv* env, jclass, jstring jpath, jint threads) {
    if (g_ctx) {
        free_sd_ctx(g_ctx);
        g_ctx = nullptr;
    }
    {
        std::lock_guard<std::mutex> l(g_log_mu);
        g_log.clear();
    }
    sd_set_log_callback(log_cb, nullptr);
    sd_set_progress_callback(progress_cb, nullptr);

    std::string path = to_str(env, jpath);
    sd_ctx_params_t p;
    sd_ctx_params_init(&p);
    p.model_path = path.c_str();
    p.n_threads = threads;
    p.enable_mmap = true;
    g_ctx = new_sd_ctx(&p);
    return g_ctx != nullptr ? JNI_TRUE : JNI_FALSE;
}

// Returns [width, height, argb pixels...] or null on failure/cancel.
extern "C" JNIEXPORT jintArray JNICALL
Java_com_realgen_offline_Native_generate(JNIEnv* env, jclass, jstring jprompt, jstring jneg,
                                         jstring jlora, jfloat loraMul, jint w, jint h,
                                         jint steps, jfloat cfg, jlong seed) {
    if (!g_ctx) return nullptr;

    std::string prompt = to_str(env, jprompt);
    std::string neg = to_str(env, jneg);
    std::string lora_path = to_str(env, jlora);

    sd_cancel_generation(g_ctx, SD_CANCEL_RESET);

    sd_img_gen_params_t g;
    sd_img_gen_params_init(&g);
    g.prompt = prompt.c_str();
    g.negative_prompt = neg.c_str();
    g.width = w;
    g.height = h;
    g.seed = seed;
    g.batch_count = 1;

    sd_sample_params_init(&g.sample_params);
    g.sample_params.sample_steps = steps;
    g.sample_params.guidance.txt_cfg = cfg;
    g.sample_params.sample_method = EULER_A_SAMPLE_METHOD;
    g.sample_params.scheduler = sd_get_default_scheduler(g_ctx, EULER_A_SAMPLE_METHOD);

    // Tiled VAE keeps peak memory low on small phones.
    g.vae_tiling_params.enabled = true;

    sd_lora_t lora;
    if (!lora_path.empty()) {
        lora.is_high_noise = false;
        lora.multiplier = loraMul;
        lora.path = lora_path.c_str();
        g.loras = &lora;
        g.lora_count = 1;
    }

    sd_image_t* imgs = nullptr;
    int n = 0;
    bool ok = generate_image(g_ctx, &g, &imgs, &n);
    if (!ok || !imgs || n < 1 || !imgs[0].data) {
        if (imgs) free_sd_images(imgs, n);
        return nullptr;
    }

    const sd_image_t& im = imgs[0];
    const size_t count = static_cast<size_t>(im.width) * im.height;
    std::vector<jint> out(2 + count);
    out[0] = static_cast<jint>(im.width);
    out[1] = static_cast<jint>(im.height);
    const uint32_t ch = im.channel < 3 ? 3 : im.channel;
    for (size_t i = 0; i < count; i++) {
        const uint8_t* px = im.data + i * ch;
        const uint32_t a = (ch >= 4) ? px[3] : 255;
        out[2 + i] = static_cast<jint>((a << 24) | (px[0] << 16) | (px[1] << 8) | px[2]);
    }
    free_sd_images(imgs, n);

    jintArray arr = env->NewIntArray(static_cast<jsize>(out.size()));
    if (!arr) return nullptr;
    env->SetIntArrayRegion(arr, 0, static_cast<jsize>(out.size()), out.data());
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_realgen_offline_Native_cancel(JNIEnv*, jclass) {
    if (g_ctx) sd_cancel_generation(g_ctx, SD_CANCEL_ALL);
}

extern "C" JNIEXPORT void JNICALL
Java_com_realgen_offline_Native_unload(JNIEnv*, jclass) {
    if (g_ctx) {
        free_sd_ctx(g_ctx);
        g_ctx = nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_realgen_offline_Native_lastLog(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> l(g_log_mu);
    return ascii_jstring(env, g_log);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_realgen_offline_Native_sysInfo(JNIEnv* env, jclass) {
    const char* s = sd_get_system_info();
    return ascii_jstring(env, s ? s : "");
}
