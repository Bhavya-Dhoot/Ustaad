#!/usr/bin/env bash
# markers.sh claim|release
S=C:/Users/DELL/AppData/Local/Temp/claude/H--UStaaf/540b7e43-6936-4950-98e9-c6e7c99a1603/scratchpad
if [ "$1" = claim ]; then
  ls $S | grep phone-
  for p in A B C; do echo memcloud > $S/phone-$p-owner.txt; done
else
  for p in A B C; do rm -f $S/phone-$p-owner.txt; done
fi
echo "markers: $(ls $S | grep -c 'phone-.-owner')"
