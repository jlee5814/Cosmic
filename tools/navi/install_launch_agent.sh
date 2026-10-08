#!/bin/sh
# Installs the Navi service as a macOS LaunchAgent: starts at login, restarts if it exits.
# Safe to rerun; it replaces the existing agent. Uninstall:
#   launchctl bootout gui/$(id -u)/local.navi.service && rm ~/Library/LaunchAgents/local.navi.service.plist
set -eu

LABEL="local.navi.service"
SCRIPT="$(cd "$(dirname "$0")" && pwd)/navi_service.py"
PYTHON="$(command -v python3)"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
LOG="$HOME/.navi/navi.log"

mkdir -p "$HOME/.navi" "$HOME/Library/LaunchAgents"
chmod 700 "$HOME/.navi"

cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array>
    <string>$PYTHON</string>
    <string>$SCRIPT</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>10</integer>
  <key>StandardOutPath</key><string>$LOG</string>
  <key>StandardErrorPath</key><string>$LOG</string>
</dict>
</plist>
EOF

launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
echo "installed $LABEL ($PYTHON $SCRIPT), log: $LOG"
