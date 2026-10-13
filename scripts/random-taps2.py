#!/usr/bin/env python3
"""Random taps on a phone's screen through `adb shell input tap`. Nothing else is done on the phone.

Usage: python random-taps2.py <adb-serial> <minutes> <log-file> <margin-fraction> <lo1> <hi1> [<lo2> <hi2> ...]
Before every tap one of the gap ranges is picked (each equally likely), then a gap uniformly inside it. The point is drawn
uniformly from the screen minus <margin-fraction> on every side (0.25 leaves the central half of each axis). All draws come
from the operating system's random source (secrets.SystemRandom): no seed, not reproducible.
A tap is sent only while the phone is awake and unlocked and the vivo Remote Control session screen is in front, so a tap
never lands on anything else. Create <log-file>.stop to end early.
"""
import os
import re
import secrets
import subprocess
import sys
import time

ADB = r"C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe"
ONLY = "com.vivo.remotecontrol/.ui.remotecontrol.control.RemoteControlActivity"
serial, minutes, log, margin = sys.argv[1], float(sys.argv[2]), sys.argv[3], float(sys.argv[4])
nums = [float(a) for a in sys.argv[5:]]
RANGES = list(zip(nums[0::2], nums[1::2]))
assert RANGES and len(nums) % 2 == 0 and all(0 < lo <= hi for lo, hi in RANGES) and 0 <= margin < 0.5
R = secrets.SystemRandom()
STATE = "dumpsys power | grep -m1 mWakefulness=; dumpsys window | grep -m1 mDreamingLockscreen; dumpsys window displays | grep -m1 cur=; dumpsys activity activities | grep -m1 topResumedActivity"


def sh(cmd):
    try:
        p = subprocess.run([ADB, "-s", serial, "shell", cmd], capture_output=True, text=True, timeout=30, stdin=subprocess.DEVNULL)
        return p.stdout.replace("\r", "")
    except Exception as e:  # a dropped adb link must not end a long run
        return f"adb failed: {type(e).__name__}"


def state():
    """(awake and unlocked, screen width, screen height in the current rotation, component in front)"""
    out = sh(STATE)
    size = re.search(r"cur=(\d+)x(\d+)", out)
    front = re.search(r"topResumedActivity=\S+ \S+ (\S+)", out)
    ok = "mWakefulness=Awake" in out and "mDreamingLockscreen=false" in out and size is not None
    return ok, int(size.group(1)) if size else 0, int(size.group(2)) if size else 0, front.group(1) if front else "?"


taps = held = 0
end = time.time() + minutes * 60
with open(log, "a", encoding="utf-8") as f:
    def note(line):
        f.write(f"{time.strftime('%H:%M:%S')} {line}\n")
        f.flush()

    _, w, h, front = state()
    note(f"start: {serial}, screen {w}x{h}, in front {front}, {minutes:g} min, gap ranges {RANGES} s (one picked per tap), {margin:.0%} margin on every side, only on {ONLY}")
    due = time.time()
    while time.time() < end and not os.path.exists(log + ".stop"):
        time.sleep(max(0.0, due - time.time()))
        ok, w, h, front = state()
        if not ok or front != ONLY:
            held += 1
            note(f"held back: {'asleep, locked or no answer from the phone' if not ok else 'in front now: ' + front}")
            due = time.time() + 5
            continue
        x, y = R.randint(int(w * margin), int(w * (1 - margin))), R.randint(int(h * margin), int(h * (1 - margin)))
        sh(f"input tap {x} {y}")
        taps += 1
        lo, hi = R.choice(RANGES)
        gap = R.uniform(lo, hi)
        note(f"tap ({x},{y}) on {w}x{h}; range {lo:g}-{hi:g} s, next in {gap:.1f} s")
        due = time.time() + gap
    note(f"end: {taps} taps, {held} held back")
