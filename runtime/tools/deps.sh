set -u
sed 's/^DownloadUser/#DownloadUser/' /etc/pacman.conf > /tmp/pacman.conf
PAC="pacman --config /tmp/pacman.conf"
P=$(ls -d "/root/.local/share/Steam/compatibilitytools.d/DroidDeck-Proton"*/ | head -1)
{ find "$P/files/lib/wine/aarch64-unix" -name '*.so'; find "$P/files/bin-arm64" -type f; ls /usr/local/bin/gamescope /usr/bin/gamescope /usr/bin/Xwayland /usr/bin/python3 2>/dev/null; } > /tmp/targets.txt
echo "targets: $(wc -l < /tmp/targets.txt)"
while IFS= read -r f; do ldd "$f" 2>/dev/null | awk '/not found/ {print "MISSING " $1; next} /=>/ {print $3}'; done < /tmp/targets.txt | sort -u > /tmp/needed.txt
echo "== missing"; grep MISSING /tmp/needed.txt
echo "== packages"; grep -v MISSING /tmp/needed.txt | while IFS= read -r l; do [ -e "$l" ] && $PAC -Qoq "$l" 2>/dev/null; done | sort -u | tr '\n' ' '; echo
echo "== dlopen'd by wine (winex11/winevulkan/etc)"; strings "$P/files/lib/wine/aarch64-unix/winex11.so" 2>/dev/null | grep -E '^lib[A-Za-z0-9_+-]+\.so(\.[0-9]+)*$' | sort -u | tr '\n' ' '; echo
strings "$P/files/lib/wine/aarch64-unix/"*.so 2>/dev/null | grep -E '^lib[A-Za-z0-9_+-]+\.so(\.[0-9]+)*$' | sort -u | tr '\n' ' '; echo
echo "== gamescope"; $PAC -Qi gamescope 2>/dev/null | grep -E "^Version|^Depends"; echo "pkgs: $($PAC -Q | wc -l)"
