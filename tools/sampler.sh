#!/system/bin/sh
# Samples the game session every INTERVAL seconds for DURATION seconds (run as the app through run-as).
#   sampler.sh OUT [DURATION] [INTERVAL]
out=$1; duration=${2:-900}; interval=${3:-5}
end=$(( $(date +%s) + duration ))
: > "$out"
while [ "$(date +%s)" -lt "$end" ]; do
  echo "T $(date +%s)" >> "$out"
  for c in 0 3 7; do echo "F cpu$c $(cat /sys/devices/system/cpu/cpu$c/cpufreq/scaling_cur_freq) $(cat /sys/devices/system/cpu/cpu$c/cpufreq/scaling_max_freq)" >> "$out"; done
  for p in $(ps -A -o PID,NAME | awk '/dndclient|wineserver|libproot|gamescope|Xwayland|libpulseaudio|ddomobile/ {print $1}'); do
    name=$(cat /proc/$p/comm 2>/dev/null)
    for t in /proc/$p/task/*; do
      read -r st < "$t/stat" 2>/dev/null || continue
      # The shell's own read: an external tr on the comm of an exiting thread once spun at 100% for half an hour.
      read -r comm < "$t/comm" 2>/dev/null || continue
      set -- ${st##*) }
      echo "P $p $name ${t##*/} ${comm%% *} ${12} ${13} ${37}" >> "$out"
    done
    case "$name" in *dndclient*) echo "IO $(grep -E '^(rchar|read_bytes|syscr)' /proc/$p/io | awk '{printf "%s ", $2}')" >> "$out";; esac
  done
  tail -1 /data/user/0/com.ddomobile.launcher/files/run/compositor.log 2>/dev/null | grep "last 10" | sed 's/^/C /' >> "$out"
  sleep "$interval"
done
