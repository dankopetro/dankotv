#!/bin/bash
# send-list.sh — Envía una lista M3U al teléfono y/o a la TV (uso personal).
# Uso: ./send-list.sh [URL_M3U] [NOMBRE] [phone|tv|both]
# Si falta algo, lo pregunta. Verifica HTTP 200 antes, dispara el alta por
# intent (backdoor add_list de MainActivity) y espera hasta que la lista
# aparece (o falla con cartel). Requiere adb + devices autorizados.
#
# Ej: ./send-list.sh "http://host/get.php?username=U&password=P&type=m3u_plus" MiLista both

set -u
TV="192.168.0.10:5555"
TIMEOUT_SECS="${TIMEOUT_SECS:-1200}"
POLL_SECS=30

URL="${1:-}"
NAME="${2:-}"
TARGET="${3:-}"

[ -z "$URL" ] && read -r -p "URL M3U: " URL
[ -z "$NAME" ] && read -r -p "Nombre de lista: " NAME
if [ -z "$TARGET" ]; then
    echo "Destino: [1] teléfono  [2] TV  [3] ambos"
    read -r -p "Opción (1/2/3): " OPT
    case "$OPT" in
        1) TARGET="phone" ;;
        2) TARGET="tv" ;;
        *) TARGET="both" ;;
    esac
fi

phone_serial() {
    adb devices | awk '$2=="device" {print $1}' | grep -v "192.168.0.10" | head -1
}

echo "== Verificando URL…"
CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 25 -A "Mozilla/5.0" "$URL")
if [ "$CODE" != "200" ]; then
    echo "ERROR: la URL responde HTTP $CODE (se esperaba 200). Revisá el link."
    exit 1
fi
echo "OK: HTTP 200"

DEVICES=""
case "$TARGET" in
    phone) DEVICES="$(phone_serial)" ;;
    tv) DEVICES="$TV" ;;
    *) DEVICES="$(phone_serial) $TV" ;;
esac
# shellcheck disable=SC2086
set -- $DEVICES
if [ $# -eq 0 ] || [ -z "$1" ]; then
    echo "ERROR: ningún dispositivo en línea para '$TARGET'."
    exit 1
fi

send_one() {
    local SER="$1" N="$2" U="$3"
    echo "== $SER <- $N"
    if ! adb -s "$SER" shell pm list packages 2>/dev/null | grep -q tv.danko.app; then
        echo "  ERROR: tv.danko.app no instalada en $SER"
        return 1
    fi
    adb -s "$SER" shell am force-stop tv.danko.app 2>/dev/null
    sleep 3
    adb -s "$SER" shell "am start -n tv.danko.app/tv.danko.app.MainActivity --es add_list_name '$N' --es add_list_url '$U'" > /dev/null 2>&1
    local waited=0
    while [ "$waited" -lt "$TIMEOUT_SECS" ]; do
        sleep "$POLL_SECS"
        waited=$((waited + POLL_SECS))
        adb -s "$SER" shell uiautomator dump /sdcard/sl.xml > /dev/null 2>&1
        local dump
        dump=$(adb -s "$SER" shell "cat /sdcard/sl.xml" 2>/dev/null | grep -oE 'text="[^"]+"' | sed 's/^text="//;s/"$//')
        if echo "$dump" | grep -q "No se pudo cargar"; then
            echo "  FALLO: el servidor rechazó la lista (revisá usuario/clave)."
            return 1
        fi
        if echo "$dump" | grep -q "$N"; then
            local count
            count=$(echo "$dump" | grep -A0 "$N" | head -2 | tail -1)
            echo "  OK: lista presente ($count)"
            return 0
        fi
    done
    echo "  TIMEOUT tras $((TIMEOUT_SECS / 60)) min: la lista no apareció."
    return 1
}

FAIL=0
for SER in $DEVICES; do
    send_one "$SER" "$NAME" "$URL" || FAIL=1
done

if [ "$FAIL" -eq 0 ]; then
    echo "== Listo: '$NAME' en [$TARGET]"
else
    echo "== Terminado con fallos (ver arriba)"
    exit 1
fi
