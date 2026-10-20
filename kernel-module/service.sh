#!/system/bin/sh

PACKAGE="com.codex.aicallguard"
COMPONENT="$PACKAGE/.GuardService"
ACTION="$PACKAGE.START"

until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 2
done

sleep 3

while true; do
    am start-foreground-service -n "$COMPONENT" -a "$ACTION" >/dev/null 2>&1
    sleep 300
done
