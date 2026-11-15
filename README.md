# ZRAM Benchmark Pro

Android application project for inspecting available ZRAM interfaces and running a clearly labelled standalone compression benchmark.

## Build and release APK

The Android source project is currently stored in `ZramBenchmarkPro-source.zip`. GitHub Actions extracts it and builds an installable, debug-signed APK using JDK 17, Gradle 8.13, and Android SDK 36.

To start a build manually:

1. Open the **Actions** tab in this repository.
2. Select **Build and Release APK**.
3. Tap **Run workflow** and choose the `main` branch.
4. Wait for the job to finish. The APK is attached to the GitHub Release `v0.1.0`.

The workflow also uploads a 30-day Actions artifact named `ZramBenchmarkPro-APK`.

## Important benchmark scope

This initial 0.1.0 milestone reads available ZRAM information without changing active swap. It discovers kernel-advertised codecs, but its measured benchmark is Java `java.util.zip` DEFLATE only. It does **not** measure kernel LZ4/ZSTD/LZO ZRAM performance, switch active codecs, or write to sysfs. Do not interpret its DEFLATE score as a kernel-ZRAM codec score.

See the full project notes in `ZramBenchmarkPro-source.zip`.
