# Android native engine

The protocol engine is built directly from official [Sunshine v0.23.1](https://github.com/LizardByte/Sunshine/tree/8b21db64fb8e8ffb9c24a412dbc66b7410699211).
`sources.lock.json` pins Sunshine, its protocol dependencies, Opus, Boost,
OpenSSL, NDK and archive digests. `android/prepare_source.py` applies the
Android integration to a separate generated source tree.

This project's Android adapters provide MediaCodec H.264/HEVC encoding,
JNI lifecycle management, display surfaces, per-client audio capture fanout,
touch/mouse/keyboard delivery through the existing Shizuku service, pairing
metadata migration, application launch and streaming diagnostics. Official
Sunshine provides pairing, certificate authentication, RTSP, encryption,
RTP packetization and FEC. Opus is compiled from its official source.

Existing pairing credentials and device names are migrated in place; the
first engine start preserves a `.before-official` state backup. The input
worker runs serially to preserve key and modifier order. Each audio client
has its own bounded PCM buffer and packet queue; an idle source returns
within a bounded wait so disconnect does not stall audio shutdown.

## Build

Requires Python 3.11.8+, Git, CMake, Ninja and NDK 27.2.12479018:

```powershell
python native/build.py --ndk D:/android-sdk-extra/ndk/27.2.12479018 --cmake D:/android-sdk-extra/cmake/3.31.6/bin/cmake.exe --ninja D:/android-sdk-extra/cmake/3.31.6/bin/ninja.exe --tests --install
./gradlew.bat :app:assembleRelease :app:testDebugUnitTest :app:lintRelease
```

The ignored `native/.official-build` directory contains the official checkouts,
verified vendor archives, generated source and both ABI builds. The driver
rejects altered source revisions. `--install` copies stripped native libraries
and matching OpenSSL libraries into the APK inputs. `--tests` builds six Android
console test executables for ADB execution. APK signing remains separate.

Sunshine's GPLv3 license is in `LICENSE.sunshine`; dependency notices are in
`licenses/` and packaged in the app's `assets/licenses/`. The upstream
moonlight-common-c files used by Sunshine provide protocol definitions and the
RTSP parser; they contain no Moonlight Android application or permissions proxy.

See [video controls](../docs/native-video-controls.md),
[network feedback](../docs/network-feedback.md) and
[queue and encoder controls](../docs/queue-encoder-controls.md).
