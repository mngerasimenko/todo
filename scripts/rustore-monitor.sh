#!/bin/bash
# ============================================================
# Мониторинг позиции приложения в RuStore
# Запуск: bash scripts/rustore-monitor.sh
# Добавляет запись в monitoring/rustore-position.md
# ============================================================

set -e

DATE=$(date '+%Y-%m-%d %H:%M')
SESSION="rustore-mon-$(date +%s)"
TMP_DIR="/tmp/rustore-monitor"
mkdir -p "$TMP_DIR"

echo "📊 RuStore Monitor — $DATE"
echo "============================================"

# --- Проверка страницы приложения ---
echo ""
echo "📱 Проверка страницы приложения..."
agent-browser open "https://www.rustore.ru/catalog/app/ru.mngerasimenko.todolist" --session "$SESSION"
sleep 3
agent-browser wait --load networkidle --session "$SESSION"
agent-browser snapshot -i --session "$SESSION" > "$TMP_DIR/app.txt" 2>&1

RATING=$(grep -oP '0,\d' "$TMP_DIR/app.txt" | head -1 || echo "N/A")
echo "  Рейтинг: $RATING"

# --- Функция поиска позиции ---
find_position() {
    local QUERY="$1"
    local ENCODED=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$QUERY'))")
    local MAX_PAGE=10
    local APPS_PER_PAGE=32

    echo ""
    echo "🔍 Поиск: '$QUERY'..."

    for PAGE in $(seq 1 $MAX_PAGE); do
        echo "  Страница $PAGE/$MAX_PAGE..."

        # Задержка от 429
        sleep 15

        local URL="https://www.rustore.ru/catalog/search/page-${PAGE}?query=${ENCODED}"
        agent-browser open "$URL" --session "$SESSION"
        sleep 3
        agent-browser wait --load networkidle --session "$SESSION"
        agent-browser snapshot -i --session "$SESSION" > "$TMP_DIR/page${PAGE}.txt" 2>&1

        # Получаем общее количество результатов
        if [ "$PAGE" -eq 1 ]; then
            TOTAL=$(grep -oP 'найдено \d+' "$TMP_DIR/page1.txt" | grep -oP '\d+' || echo "?")
        fi

        # Ищем наше приложение
        local LINE=$(grep -n "Список задач" "$TMP_DIR/page${PAGE}.txt" | head -1)
        if [ -n "$LINE" ]; then
            # Проверяем что это наше (без рейтинга)
            local BEFORE_LINES=$(echo "$LINE" | cut -d: -f1)
            local APPS_BEFORE=$(head -n "$BEFORE_LINES" "$TMP_DIR/page${PAGE}.txt" | grep -c "^- link" || echo "0")
            local POS_ON_PAGE=$APPS_BEFORE
            local ABS_POS=$(( (PAGE - 1) * APPS_PER_PAGE + POS_ON_PAGE ))
            local PERCENTILE=$(echo "scale=1; $ABS_POS * 100 / $TOTAL" | bc 2>/dev/null || echo "?")

            echo "  ✅ Найдено! Позиция: #$ABS_POS из $TOTAL (страница $PAGE)"
            echo "  Процентиль: ТОП ${PERCENTILE}%"

            # Логируем
            echo "$DATE | $QUERY | #$ABS_POS | $TOTAL | ТОП ${PERCENTILE}% | Рейтинг $RATING" >> "$TMP_DIR/result.csv"
            return 0
        fi
    done

    echo "  ❌ Не найдено за $MAX_PAGE страниц"
    echo "$DATE | $QUERY | >$((MAX_PAGE * APPS_PER_PAGE)) | $TOTAL | - | Рейтинг $RATING" >> "$TMP_DIR/result.csv"
    return 1
}

# --- Проверка по запросам ---
find_position "Список задач"
find_position "Список дел"

# --- Обновляем файл мониторинга ---
if [ -f "$TMP_DIR/result.csv" ]; then
    echo ""
    echo "📝 Обновление monitoring/rustore-position.md..."

    while IFS='|' read -r dt query pos total pct note; do
        # Добавляем строку в таблицу
        sed -i "/| 2026-04-03 | Список дел/a | $dt | $query | $pos | $total | $pct | $note" \
            monitoring/rustore-position.md
    done < "$TMP_DIR/result.csv"
fi

# --- Финал ---
echo ""
echo "📸 Скриншот: $TMP_DIR/*.png"
agent-browser close --session "$SESSION"

echo ""
echo "============================================"
echo "✅ Мониторинг завершён"
echo "📁 Результаты: $TMP_DIR/"
echo "📄 Лог: monitoring/rustore-position.md"
echo "============================================"
