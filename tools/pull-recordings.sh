#!/usr/bin/env bash
# Забирает записи режима «Endpoint recordings (debug)» (проба S03b) с подключённого по USB телефона
# в diagnostics/recordings/ и собирает архив diagnostics/recordings-<дата>.tar.gz — его прикладывают
# в чат исполнителя S03b. Печатает, сколько записей по каждому условию, и путь к архиву.
#
# Запуск из любого каталога:  tools/pull-recordings.sh
#
# Записи голоса в Git не попадают никогда: каталог diagnostics/ в .gitignore.
# Нужна debug-сборка (записи лежат в личном хранилище приложения, их читает `run-as`)
# и включённая отладка по USB. adb ищется так же, как в tools/pull-log.sh.
set -euo pipefail

PKG=com.example.listeningtrainer
REC_DIR=files/recordings        # filesDir/recordings (TrainerService.RECORDINGS_DIR)

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$ROOT/diagnostics/recordings"

die() { echo "pull-recordings: $*" >&2; exit 1; }

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

listing="$("$ADB" shell run-as "$PKG" ls "$REC_DIR" 2>&1 | tr -d '\r' || true)"
if grep -q "not debuggable" <<<"$listing"; then
    die "на телефоне release-сборка: записи достаются только из debug-сборки."
fi
if grep -qi "unknown package\|is unknown" <<<"$listing"; then
    die "приложение $PKG не установлено на телефоне."
fi
names="$(grep -E '^ep_[0-9]{13}_[a-z_]+_[0-9]{2}\.(wav|json)$' <<<"$listing" || true)"
[[ -n "$names" ]] || die "записей нет ($REC_DIR пуст или не создан). Ответ телефона: $listing"

mkdir -p "$OUT_DIR"
copied=0
while IFS= read -r name; do
    "$ADB" exec-out run-as "$PKG" cat "$REC_DIR/$name" >"$OUT_DIR/$name.tmp"
    mv "$OUT_DIR/$name.tmp" "$OUT_DIR/$name"
    copied=$((copied + 1))
done <<<"$names"

echo "Скачано файлов: $copied → $OUT_DIR"
echo "Записей по условиям (все дубли):"
for cond in silence water street steady_noise; do
    n=$(find "$OUT_DIR" -maxdepth 1 -name "ep_*_${cond}_??.json" | wc -l)
    lines=$(find "$OUT_DIR" -maxdepth 1 -name "ep_*_${cond}_??.json" -printf '%f\n' | sed -E 's/.*_([0-9]{2})\.json/\1/' | sort -u | wc -l)
    printf '  %-13s %3d записей, реплик %2d из 12\n' "$cond" "$n" "$lines"
done

archive="$ROOT/diagnostics/recordings-$(date +%Y%m%d-%H%M).tar.gz"
tar -czf "$archive" -C "$ROOT/diagnostics" recordings
echo "Архив для чата: $archive"
