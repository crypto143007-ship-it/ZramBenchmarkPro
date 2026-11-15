# ZRAM Benchmark Pro — native kernel benchmark (0.2.0)

Android application for inspecting ZRAM and benchmarking the codec selected by the running kernel.

## Native kernel benchmark

- Identifies the active ZRAM swap device from `/proc/swaps` and reads the selected codec from its `comp_algorithm` file.
- Requires root (KernelSU/Magisk) and kernel support for `/sys/class/zram-control/hot_add` and `hot_remove`.
- Creates a separate temporary ZRAM device, selects the same codec as active swap, and sets a bounded 1–32 MiB test size.
- Writes and reads a deterministic mixed-content dataset through the temporary ZRAM block device, verifies the data byte-for-byte, and reads kernel `mm_stat`.
- Reports kernel block-write throughput (compression path), block-read throughput (decompression path), compressed/original byte ratio, bytes saved, and median write/read latency.
- Uses a cleanup trap to remove the temporary device after the test.

**Safety:** the benchmark never writes to, resets, changes the algorithm of, or runs `swapoff` on the active swap device. It refuses to run if the active codec cannot be confirmed or safe temporary-device creation/removal is unavailable. If the kernel does not support dynamic scratch ZRAM devices, the app reports that limitation instead of substituting a Java benchmark.

This is an end-to-end kernel ZRAM block-I/O test, not an isolated codec-only timing test. Throughput includes block-layer and memory-copy overhead. CPU cycles and energy are not measured. The default dataset is 8 MiB; the size can be adjusted from 1 to 32 MiB.

## Other features

- Jetpack Compose + Material 3 screens: Dashboard, Algorithms, Benchmark, System info, Results.
- Dynamic discovery of kernel-advertised codecs, with the active selected codec identified separately.
- Read-only snapshots of `/proc/meminfo`, `/proc/swaps`, ZRAM `mm_stat`, ZRAM size, Android build/ABI and battery telemetry.
- CSV result sharing.

## Build

Requirements: Android SDK Platform 36, Build Tools 36.x, JDK 17+, Android Gradle Plugin 8.13.2, Kotlin 2.2.20, Gradle 8.13.

```sh
gradle :app:assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk` (debug-signed and installable; not Play Store production-signed).
