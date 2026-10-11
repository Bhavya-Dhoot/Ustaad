# usage: wake.sh <ip> : wake the screen and dismiss the keyguard (input only, no setting)
. /c/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/4c63cb2d-c9b9-4951-b966-cdc239e78ce8/scratchpad/envA.sh >/dev/null
"$ADB" -s $1:5555 shell 'input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard' >/dev/null 2>&1
