# Owl 0.6.1-diagnostic.6

Diagnostic build: a targeted fix for the Galaxy Fold8 Neural/AI Max shader compile error. It still needs to be confirmed on the device.

- The Fold8 report pinpointed the failure: Owl's RGB-to-luma input stage (generated GLSL line 81, "no matching overload"), before any CNN pass ran. libplacebo samples hook textures with `textureLod`, which is not defined for the external textures that direct MediaCodec decoding produces. Owl's two input/output stages now sample with `texture()`. CNN weights and structure are unchanged.
- Ordinary untagged SD/HD H.264 clips are no longer excluded from Neural/AI Max as "HDR unknown".
- Shader validation now reproduces the device error with the real libplacebo macros and checks both texture types.

Verify on Fold8: Settings → Diagnostics → clear, play a 480p/720p SDR clip with AI Max, then check that `confirmed_pass` and `effective=AI Max` appear. Until then, Neural/AI Max on Fold8 is unconfirmed.
Includes everything in 0.6.1-diagnostic.5.
