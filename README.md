# RealGen Offline

Pure on-device image generation for Android. No server, no Python: a native
[stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp) engine
runs a quantized Stable Diffusion 1.5 model (Realistic Vision V6.0 B1, 4-bit GGUF)
on the CPU, with optional LoRA support. One image at a time.

## Build the APK on GitHub
Push to any branch; **Actions > Build APK** builds `RealGenOffline-debug-apk`.
The workflow pins stable-diffusion.cpp to a known commit.

## First run
1. Tap **Download model** (about 1.6 GB, once, Wi-Fi recommended) or import your own
   SD 1.5 `.gguf` / `.safetensors` file. After that the app needs no internet.
2. Optional: **Download speed LoRA (LCM)** and set Steps 4-6, CFG 1-1.5, or import
   any SD 1.5 LoRA `.safetensors` and set its strength.
3. Type a prompt, tap **Generate**. Keep the screen on and the app in the foreground.

## Notes
- Targets arm64 phones with ARMv8.2 (Cortex-A76 or newer, dotprod + fp16).
- Needs Android 10+ and about 3 GB of free RAM. Close other apps first.
- Defaults (512x512, 8 steps, CFG 2.5) suit the few-step "Hyper" model.
