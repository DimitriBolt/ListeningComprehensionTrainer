#!/usr/bin/env bash
# Забирает журнал событий приложения с подключённого по USB телефона в diagnostics/latest.log
# и печатает путь к файлу. Этот файл прикладывается в чат архитектора.
# Если на телефоне есть тексты сверки потока и файла (S03d, debug: files/diagnostics/live_vs_file.jsonl),
# они тоже копируются — в diagnostics/live_vs_file.jsonl (вне Git: это тексты реплик ученика).
#
# Запуск из любого каталога:  tools/pull-log.sh
#
# Нужна debug-сборка (журнал лежит в личном хранилище приложения, его читает `run-as`)
# и включённая отладка по USB. adb ищется в PATH, затем в Android SDK
# ($ANDROID_HOME, $ANDROID_SDK_ROOT, sdk.dir из android_app/local.properties, ~/Android/Sdk).
set -euo pipefail

PKG=com.example.listeningtrainer
LOG_DIR=files/logs              # filesDir/logs (TrainerApplication.LOG_DIR)
FILES=(events.1.log events.log) # сначала предыдущий файл, затем текущий

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$ROOT/diagnostics"
OUT="$OUT_DIR/latest.log"

die() { echo "pull-log: $*" >&2; exit 1; }

find_adb() {
    if command -v adb >/dev/null 2>&1; then command -v adb; return; fi
    local sdk_dirs=("${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}")
    local props="$ROOT/android_app/local.properties"
    if [[ -f "$props" ]]; then
        sdk_dirs+=("$(sed -n 's/^sdk\.dir=//p' "$props" | sed 's/\\:/:/g; s/\\\\/\\/g')")
    fi
    sdk_dirs+=("$HOME/Android/Sdk")
    local dir
    for dir in "${sdk_dirs[@]}"; do
        if [[ -n "$dir" && -x "$dir/platform-tools/adb" ]]; then echo "$dir/platform-tools/adb"; return; fi
    done
    return 1
}

ADB="$(find_adb)" || die "adb не найден. Поставьте Android SDK Platform-Tools или добавьте adb в PATH."

"$ADB" start-server >/dev/null 2>&1 || true
state="$("$ADB" get-state 2>&1 || true)"
[[ "$state" == "device" ]] || die "телефон не готов (adb: $state). Подключите телефон, включите отладку по USB и разрешите доступ на экране телефона."

listing="$("$ADB" shell run-as "$PKG" ls "$LOG_DIR" 2>&1 | tr -d '\r' || true)"
if grep -q "not debuggable" <<<"$listing"; then
    die "на телефоне release-сборка: журнал достаётся только из debug-сборки."
fi
if grep -qi "unknown package\|is unknown" <<<"$listing"; then
    die "приложение $PKG не установлено на телефоне."
fi

mkdir -p "$OUT_DIR"
tmp="$OUT.tmp"
: >"$tmp"
found=0
for name in "${FILES[@]}"; do
    if grep -qx "$name" <<<"$listing"; then
        "$ADB" exec-out run-as "$PKG" cat "$LOG_DIR/$name" >>"$tmp"
        found=1
    fi
done
if [[ $found -eq 0 ]]; then
    rm -f "$tmp"
    die "журнала нет ($LOG_DIR пуст или не создан): откройте приложение хотя бы раз. Ответ телефона: $listing"
fi
mv "$tmp" "$OUT"
echo "$OUT"

# Тексты сверки S03d (если есть).
LIVE_VS_FILE=files/diagnostics/live_vs_file.jsonl
if "$ADB" shell run-as "$PKG" ls "$LIVE_VS_FILE" >/dev/null 2>&1; then
    "$ADB" exec-out run-as "$PKG" cat "$LIVE_VS_FILE" >"$OUT_DIR/live_vs_file.jsonl.tmp"
    mv "$OUT_DIR/live_vs_file.jsonl.tmp" "$OUT_DIR/live_vs_file.jsonl"
    echo "$OUT_DIR/live_vs_file.jsonl"
fi
