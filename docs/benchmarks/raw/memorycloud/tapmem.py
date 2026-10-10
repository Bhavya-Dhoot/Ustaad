import re, sys, subprocess
# usage: tapmem.py <phone letter> [label]   dumps the UI of the phone and taps the bottom-bar label (default "Memory")
D = "C:/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/540b7e43-6936-4950-98e9-c6e7c99a1603/scratchpad/mc"
phone = sys.argv[1]
label = sys.argv[2] if len(sys.argv) > 2 else "Memory"
xml = subprocess.run(["bash", D + "/ph3.sh", phone, "uiautomator dump /sdcard/mc-ui.xml >/dev/null 2>&1; cat /sdcard/mc-ui.xml"], capture_output=True, text=True).stdout
best = None
for m in re.finditer(r'<node[^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    t, x1, y1, x2, y2 = m.group(1), *map(int, m.groups()[1:])
    if t == label or (t.startswith(label) and len(t) < len(label) + 3):
        c = ((x1 + x2) // 2, (y1 + y2) // 2)
        if best is None or c[1] > best[1]:   # the bottom bar is the lowest match
            best = c
print("tab", label, "at", best)
if best:
    subprocess.run(["bash", D + "/ph3.sh", phone, "input tap %d %d" % best])
