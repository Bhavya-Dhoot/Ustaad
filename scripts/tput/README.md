# tput: TCP throughput / RTT probe for Android

Build (NDK 28.2, from repo root, Git Bash):
```bash
/c/Users/DELL/AppData/Local/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android30-clang.cmd -O2 -fPIE -pie -o scripts/tput/tput-arm64 scripts/tput/tput.c
```
Install: `adb -s <serial> push scripts/tput/tput-arm64 /data/local/tmp/tput && adb -s <serial> shell chmod 755 /data/local/tmp/tput`

Usage on phone:
- receiver: `/data/local/tmp/tput s 5201` (prints Mbps per connection; add a 4th arg to exit after one)
- sender: `/data/local/tmp/tput c <receiver-ip> 5201 <MB>`
- echo server: `/data/local/tmp/tput e 5202` ; RTT client: `/data/local/tmp/tput p <ip> 5202 200`

Hold the receiver open from the PC with `adb shell ... &` in Bash; a listener started with `nohup ... &` inside `adb shell` dies when the shell exits. See docs/benchmarks/ for the runbook and results.
