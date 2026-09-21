#!/bin/bash
# send-list.sh — Envía una lista M3U al teléfono y/o a la TV (uso personal).
# Simplemente ejecutar: ./send-list.sh
# El script pregunta todo paso a paso. También acepta argumentos:
#   ./send-list.sh "URL" "Nombre" phone|tv|both
#
# Verifica HTTP 200 antes, dispara el alta por intent (backdoor add_list de
# MainActivity) y espera hasta que la lista aparece (o falla con cartel).
# Requiere adb + devices autorizados. NO tocar el teléfono/TV mientras corre.

set -u
TV="192.168.0.10:5555"
TIMEOUT_SECS="${TIMEOUT_SECS:-1200}"
POLL_SECS=30

phone_serial() {
    adb devices 2>/dev/null | awk '$2=="device" {print $1}' | grep -v "192.168.0.10" | head -1
}

URL="${1:-}"
NAME="${2:-}"
TARGET="${3:-}"

echo "=== Enviar lista IPTV a mis dispositivos ==="
echo

# 1) URL (obligatoria, tiene que empezar con http).
while :; do
    if [ -z "$URL" ]; then
        read -r -p "Ponga la URL: " URL
    fi
    # Quitar espacios accidentales al principio/fin.
    URL="$(echo "$URL" | sed 's/^ *//;s/ *$//')"
    if [ -z "$URL" ]; then
        echo "  La URL no puede estar vacía."
        URL=""
    elif [[ "$URL" != http://* && "$URL" != https://* ]]; then
        echo "  Eso no parece una URL (tiene que empezar con http:// o https://)."
        URL=""
    else
        break
    fi
done

# 2) Nombre (obligatorio).
while [ -z "$NAME" ]; do
    read -r -p "Nombre a su Lista: " NAME
    NAME="$(echo "$NAME" | sed 's/^ *//;s/ *$//')"
    if [ -z "$NAME" ]; then
        echo "  El nombre no puede estar vacío."
    fi
done

# 3) Destino (solo 1, 2 o 3; repite hasta válido).
PHONE_DESC=""
TV_DESC=""
if adb devices 2>/dev/null | grep -q "192.168.0.10"; then
    TV_DESC=" (en línea)"
else
    TV_DESC=" (SIN CONEXIÓN ahora)"
fi
if [ -n "$(phone_serial 2>/dev/null)" ]; then
    PHONE_DESC=" (en línea)"
else
    PHONE_DESC=" (SIN CONEXIÓN ahora)"
fi
# 3) Destino (solo 1, 2 o 3; repite hasta válido). Con argumento válido se saltea.
case "$TARGET" in
    phone|tv|both) ;;
    *)
        TARGET=""
        while :; do
            echo
            echo "Elija a qué dispositivo enviar:"
            echo "  1) TV$TV_DESC"
            echo "  2) Telefono$PHONE_DESC"
            echo "  3) Ambos"
            read -r -p "Opción [1/2/3]: " OPT
            case "$OPT" in
                1) TARGET="tv"; break ;;
                2) TARGET="phone"; break ;;
                3) TARGET="both"; break ;;
                *) echo "  Opción inválida: escriba 1, 2 o 3." ;;
            esac
        done
        ;;
esac

echo
echo "== Verificando URL…"
CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 25 -A "Mozilla/5.0" "$URL")
if [ "$CODE" != "200" ]; then
    echo "ERROR: la URL responde HTTP $CODE (se esperaba 200)."
    echo "Revisá el link, usuario o clave. No se envió nada."
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
    echo "Revisá el Wi-Fi y la depuración inalámbrica e intentá de nuevo."
    exit 1
fi

# Confirmación final antes de actuar.
echo
echo "Voy a enviar la lista '$NAME' a: $*"
read -r -p "¿Confirmar? [S/n]: " CONFIRM
case "$CONFIRM" in
    [nN]*) echo "Cancelado, no se hizo nada."; exit 0 ;;
esac

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
            echo "  OK: '$N' presente en el dispositivo."
            return 0
        fi
        echo "  … esperando ($((waited / 60)) min) — no toques el dispositivo."
    done
    echo "  TIMEOUT tras $((TIMEOUT_SECS / 60)) min: la lista no apareció."
    return 1
}

FAIL=0
for SER in $DEVICES; do
    send_one "$SER" "$NAME" "$URL" || FAIL=1
done

if [ "$FAIL" -eq 0 ]; then
    echo "== Listo: '$NAME' enviado."
else
    echo "== Terminado con fallos (ver arriba)."
    exit 1
fi
