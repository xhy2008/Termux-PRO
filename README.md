# Termux-PRO

Termux with the Android-native backend replaced by [proroot](https://github.com/coderred123a/proroot)
and a full **Debian `trixie` arm64 rootfs**. Inside the terminal you get a real glibc Debian
userspace with `apt`, not the Android-bionic `termux-*` packages.

Application id: **`com.termux.pro`** — installs side by side with upstream `com.termux`.

Forked from [termux/termux-app](https://github.com/termux/termux-app) `0.118.0`.

## What differs from upstream Termux

| | upstream | Termux-PRO |
|---|---|---|
| userspace | `$PREFIX` of bionic packages | `/data/data/com.termux.pro/files/rootfs` (Debian `trixie`, glibc) |
| package manager | `pkg` / `apt` against termux packages | `apt` against `mirrors.cloud.tencent.com/debian` |
| process launch | `libtermux-bootstrap` + `forkpty` | `libproroot.so -r rootfs -0 --link2symlink …` |
| ABIs | arm64, arm, x86_64, x86 | **arm64-v8a only** |
| plugins (`Termux:API`, `:Boot`, `:Float`, `:Styling`, `:Tasker`, `:Widget`) | supported | **removed**, and their packages are not looked up |
| external `RUN_COMMAND` interface | supported | kept, under `com.termux.pro.RUN_COMMAND` |

The six plugins cannot work here even in principle: they are separate APKs that resolve
`com.termux.api`-style ids derived from the app id, and the guest-side `termux-*` CLIs they call do
not exist in Debian. Their preference screens, shared-preference classes and constants are deleted
rather than left dead.

`com.termux.pro.RUN_COMMAND`, `com.termux.pro.permission.RUN_COMMAND` and the
`com.termux.pro.documents` / `.files` providers are unchanged in shape, so other apps can still
exec commands in the guest the usual way.

## Building

Requirements (**no NDK** — the JNI libraries are committed prebuilt, see below):

- JDK **17** or **21**
- Android SDK: `platforms;android-36`, `build-tools;36.0.0`
- Gradle comes from the wrapper (`gradle-9.2.1`), network access to Maven Central / Google / jitpack

```sh
export ANDROID_SDK_ROOT=/path/to/android-sdk   # or write sdk.dir=... into local.properties
./gradlew :app:assembleRelease
```

Output: `app/build/outputs/apk/release/termux-app_apt-android-7-release_arm64-v8a.apk`
(≈ 4.3 MB, arm64-v8a, R8-shrunk).

`./gradlew :app:assembleDebug` produces the same single-ABI APK unminified and debuggable.

The `release` build type is signed with the repository's `app/testkey_untrusted.jks`, the same
shared test key upstream Termux uses for GitHub builds, so a release APK installs over a debug one.
Swap `signingConfig` in `app/build.gradle` before distributing anywhere else.

### Building inside Termux on the device

The only host-specific knob: Gradle normally downloads a **Linux x86-64** `aapt2`, which an arm64
Android process cannot exec. Point it at a native `aapt2` instead — in `~/.gradle/gradle.properties`
(machine-local, not committed):

```properties
android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2
```

or pass `-Pandroid.aapt2FromMavenOverride=...` per invocation. A commented copy lives in the
project `gradle.properties`; uncomment it only if you are building on-device.

### Why the `.so` files are committed

`.gitignore` ignores `*.so` but un-ignores `**/jniLibs/**/*.so`. Eight arm64 libraries are
therefore versioned:

| library | origin |
|---|---|
| `libproroot.so`, `libproroot-{runtime,linker,bridge,stub-loader}.so` | proroot release binaries — **proprietary, source not published** |
| `libtermux.so` | `terminal-emulator/src/main/jni/termux.c` |
| `liblocal-socket.so` | `termux-shared/src/main/cpp/local-socket.cpp` |
| `libc++_shared.so` | NDK `c++_shared` runtime, required by `liblocal-socket.so` |

The two JNI libraries built from this repo's C/C++ are committed because their entry points are
resolved by name from Java, so a missing file fails at runtime rather than at link time; keeping one
arm64 build in the tree also removes the NDK from the build requirements. The sources above are
still authoritative if you want to rebuild them with `ndk-build`.

## First launch

On first start the app downloads the Debian rootfs and extracts it under `files/rootfs`, writes
`/etc/apt/sources.list` for the Tencent mirrors, and drops in `dns.conf` / `resolv.conf`. Expect
roughly 20 s of download plus extraction on a phone. Then `apt update` works out of the box.

The image is **pinned by date** in `app/src/main/java/com/termux/app/TermuxInstaller.java`, because
the LXC mirrors do not serve the `latest` symlink:

- `ROOTFS_IMAGE_PATH` — mirror directory, contains `20261015_05%3A24`
- `ROOTFS_IMAGE_SHA256` — checksum of `rootfs.tar.xz`
- `ROOTFS_URLS` — mirror list, tried in order

To move to a newer image: browse `https://mirrors.nju.edu.cn/lxc-images/images/debian/trixie/arm64/default/`,
pick a date, `sha256sum` the `rootfs.tar.xz`, update all three constants, then delete `files/rootfs`
and restart the app so bootstrap re-runs.

## Unsupported / removed

- Six `Termux:*` plugin apps and their settings screens (see table above).
- `termux-am` socket bridge is still present but inert: Debian has no `termux-am` CLI.
- Styling dialog / `colors.properties` editing UI: the theme reload code is kept, the plugin-driven
  entry point is not.
- Play-store and F-Droid release workflows: this fork publishes nothing to either.

## Verification

```sh
./gradlew :app:assembleRelease   # BUILD SUCCESSFUL, one arm64-v8a APK
./gradlew test                   # unit tests (Robolectric needs a glibc host, see below)
```

The unit tests run on a normal x86-64 glibc Linux/macOS host, which is what `.github/workflows/run_tests.yml`
uses. `app` has one Robolectric test (`FileReceiverActivityTest`); Robolectric pulls a glibc
`conscrypt-openjdk` binary and resets Android shadows reflectively, so running the suite **inside**
Termux on arm64 fails on environment setup (`libpthread.so.0` not found, or a `ShadowActivityThread`
NPE depending on the Robolectric version) rather than on any assertion. Build the APK on-device,
run the suite on CI or a desktop.

## License

The Java, C and C++ sources keep upstream's **GPL-3.0-only** terms (`LICENSE.md`, plus the
Apache-2.0 exceptions it lists for `terminal-view` / `terminal-emulator`). The five proroot
binaries under `app/src/main/jniLibs/arm64-v8a/` are **not** GPL: they are proprietary, free to use,
with redistribution of modified binaries not permitted (per the proroot README). Treat them as
separate prebuilt components when redistributing this repository.
