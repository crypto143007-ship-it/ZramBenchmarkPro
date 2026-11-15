# ZRAM Benchmark Pro — live active ZRAM observer (0.3.0)

A no-root Android app that observes the ZRAM swap device Android is already using.

## Live active ZRAM test

- Attempts to identify the active ZRAM swap device from `/proc/swaps`.
- Reads the currently selected codec from `/sys/block/zramN/comp_algorithm` when Android permissions allow it.
- Samples the kernel's ZRAM `mm_stat` and Linux `/proc/vmstat` `pswpout` / `pswpin` counters.
- Applies a small bounded in-app anonymous-memory workload while sampling, so the kernel remains in control of whether pages are reclaimed and swapped.
- Reports the current ZRAM original/compressed size and compression ratio, plus system-wide swap-out/swap-in throughput observed during the test.
- Requires no root, does not execute `su`, does not create another ZRAM device, and never writes to a ZRAM block device, resets ZRAM, changes the selected codec, or calls `swapoff`.

**Important measurement limits:** Android decides whether and when to swap pages. A foreground app cannot force the kernel to swap a particular app's pages, and a short test may observe no swap traffic. In that case throughput is zero / not observed, not a fabricated codec score. Swap counters are system-wide, not attributable to this app. If Android restricts `/proc/swaps`, `/proc/vmstat`, or ZRAM sysfs, the app reports the unavailable data rather than assuming access. This is a live-system observation, not an isolated codec microbenchmark.

## Other features

- Dashboard, Algorithms, Benchmark, System info, Results.
- Read-only RAM, swap, ZRAM, battery and Android device telemetry.
- CSV result sharing.

## Build

Requirements: Android SDK Platform 36, Build Tools 36.x, JDK 17+, Android Gradle Plugin 8.13.2, Kotlin 2.2.20, Gradle 8.13.

```sh
gradle :app:assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk` (debug-signed and installable; not Play Store production-signed).
