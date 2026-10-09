#!/usr/bin/env bash
# Probe one Android device over ADB for SwarmOS feasibility. Usage: scripts/probe-device.sh <serial> [outfile]
# Output: Markdown report in docs/device-probes/<serial>.md
set -u
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"
ADB="${ADB:-/c/Users/DELL/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
S="${1:?serial}"; OUT="${2:-docs/device-probes/$S.md}"
a() { "$ADB" -s "$S" shell "$@" 2>&1; }
sec() { echo; echo "## $1"; echo; echo '```'; }
end() { echo '```'; }
{
echo "# Device probe: $S"; echo; echo "Date: $(date -u +%Y-%m-%dT%H:%MZ)"
sec "Identity"
for p in ro.product.model ro.product.brand ro.product.device ro.build.version.release ro.build.version.sdk ro.build.display.id ro.board.platform ro.hardware ro.soc.model ro.vivo.os.version ro.vivo.os.build.display.id ro.vivo.rom.version; do echo "$p = $(a getprop $p | tr -d '\r')"; done
end
sec "CPU / memory"
a cat /proc/cpuinfo | grep -E 'processor|model name|Hardware|Features' | sort | uniq -c | head -20
a cat /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq | tr '\n' ' '; echo
a cat /proc/meminfo | head -5
a dumpsys activity | grep -iE 'largeHeap|heap' | head -5
a getprop dalvik.vm.heapsize; a getprop dalvik.vm.heapgrowthlimit
end
sec "Wi-Fi features (pm list features)"
a pm list features | grep -iE 'wifi|bluetooth|uwb|nfc|vulkan|opengles|nnapi|neural|hardware.type' | sort
end
sec "Wi-Fi Aware"
a dumpsys wifiaware | head -60
end
sec "Wi-Fi P2P / Direct"
a dumpsys wifip2p | head -40
end
sec "Wi-Fi current link + capabilities"
a dumpsys wifi | grep -iE 'mWifiInfo|SSID|Frequency|Link speed|Wifi standard|11be|11ax|MLO|band|6GHz|supports' | head -40
a cmd wifi status 2>&1 | head -20
a cmd wifi get-softap-supported-features 2>&1 | head; a cmd wifi is-wifi-band-supported 6 2>&1; a cmd wifi is-wifi-band-supported 5 2>&1
end
sec "Network interfaces / IP"
a ip addr show | grep -E '^[0-9]+:|inet ' | head -30
a ip route | head
end
sec "GPU / NPU / accelerators"
a getprop | grep -iE 'gpu|adreno|vulkan|opencl|npu|hexagon|dsp|qnn|nnapi|snpe' | head -30
a ls /vendor/lib64 | grep -iE 'opencl|qnn|snpe|htp|hexagon|nnapi|vulkan|cdsp' | head -40
a ls /vendor/lib/rfsa/adsp 2>/dev/null | head; a ls /vendor/dsp 2>/dev/null | head
a cmd nnapi 2>&1 | head -3; a dumpsys android.hardware.neuralnetworks 2>&1 | head -5
a ls /dev | grep -iE 'kgsl|ion|dma_heap|fastrpc|adsprpc|cdsprpc' 
end
sec "Thermal"
a dumpsys thermalservice | head -40
end
sec "Battery"
a dumpsys battery | head -20
end
sec "Storage"
a df -h /data | tail -1
end
sec "Installed relevant apps (termux, iperf, llm apps)"
a pm list packages | grep -iE 'termux|iperf|llama|mlc|pocketpal|layla|aiedge|google.android.aicore|nearby|gms' | head -20
a pm list packages -f | grep -iE 'bluelm|vivo.ai|jovi|vivo.agent' | head -10
end
sec "Background policy / battery optimization whitelist (for our app later)"
a dumpsys deviceidle whitelist | head -10
a settings get global low_power 2>&1
a getprop persist.sys.vivo.bg 2>&1
end
sec "ABI / NDK targets"
a getprop ro.product.cpu.abilist; a getprop ro.product.cpu.abi
end
sec "SELinux / root"
a getenforce; a id | head -1
end
} > "$OUT"
echo "wrote $OUT"
