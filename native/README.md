# Android native engine

The release engine is now rebuilt from the Android port in
[kumu-ze/connect-screen.com](https://github.com/kumu-ze/connect-screen.com/tree/7552cc395d5d017def154a2a8cbac114803ac6a9),
not patched as an opaque binary. `sources.lock.json` fixes the upstream commit,
submodules, NDK revision, Boost archive and OpenSSL archives. The upstream GPLv3
license is preserved in `LICENSE.upstream`; dependencies retain their licenses in
the pinned checkout. The upstream Opus static libraries remain pinned artifacts
within that commit. APK signing is separate and uses the existing release key.

`patches/android-host.patch` adds:

- One capture producer and one bounded PCM queue/packetizer per client, including
  clients negotiating different packet durations. A joining client starts with no
  stale partial packet. Each native queue holds at most 40ms, or one longer
  negotiated packet; this is separate from the host's configurable PCM buffer.
- Removal of the subscriber and queue shutdown before joining the Opus thread,
  including when the capture source is silent. An encoder failure only stops that
  client's queue.
- JNI attachment ownership: nested callbacks do not detach the video thread or
  invalidate its Surface. The host detaches EGL synchronously before MediaCodec
  is stopped, and the Surface JNI local reference is released afterwards.
- Audio capture remains enabled regardless of the client's host-speaker flag:
  Android playback capture still needs to feed remote audio in either mode.
- Per-session video diagnostics, bounded encoder-output timestamp samples, live
  bitrate bounds and manual bitrate/keyframe/disconnect commands. MediaCodec
  operations run on their owning video thread; driver failures are reported.
- Correct output-buffer offsets, honest codec creation failures, negotiated low
  frame rates, and UDP batch fallback that retries only the unsent suffix.

The Kotlin host retains its isolated `:engine` process and last-client recovery.
Desktop-specific display drivers, automatic network bitrate adaptation and
adaptive FEC are not part of this patch. Manual bitrate control is documented in
[docs/native-video-controls.md](../docs/native-video-controls.md).
`patches/openssl-download.patch` enforces the archive hash instead
of allowing the upstream unchecked download.

## Build

Requires Python 3, Git, CMake 3.31.6, Ninja and NDK 27.2.12479018. On Windows:

```powershell
python native/build.py --ndk D:/android-sdk-extra/ndk/27.2.12479018 --cmake D:/android-sdk-extra/cmake/3.31.6/bin/cmake.exe --ninja D:/android-sdk-extra/cmake/3.31.6/bin/ninja.exe --tests --install
./gradlew.bat :app:assembleRelease :app:testDebugUnitTest :app:lintRelease
```

The ignored `native/.build` directory contains the pinned source and ABI builds.
`--install` copies stripped `libsunshine.so` and matching OpenSSL shared libraries
into the APK inputs for both ABIs. Without it, the checked-in APK inputs stay
unchanged. The driver refuses unexpected source revisions or patches rather than
resetting a developer's changes. Never uninstall or clear application data to
work around an APK signature mismatch.

`--tests` also builds three silent console executables per ABI: packet continuity,
mixed durations, joining-client state and surround mapping; and bounded queue,
silent-source shutdown and independent-client fanout; and video bounds, commands,
buffer ranges, timestamp windows and partial-batch progress. Run them through ADB on a
device supporting that ABI. They produce no audio. Device streaming regression
uses Douyin when audio validation is requested. The v2026.10.12.2 regression is
video-only, with both endpoints muted and a silent visual diagnostic activity.
