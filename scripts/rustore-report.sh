#!/bin/bash
# ============================================================
# Отчёт по позиции приложения в RuStore
# Запуск: bash scripts/rustore-report.sh
# Требует: agent-browser (npm install -g agent-browser)
# ============================================================

set -e

SESSION="rustore-$(date +%s)"
TMP_DIR="/tmp/rustore-report"
mkdir -p "$TMP_DIR"

echo "📊 RuStore Report — $(date '+%Y-%m-%d %H:%M:%S')"
echo "============================================"

# --- Шаг 1: Страница приложения ---
echo ""
echo "📱 Шаг 1: Страница приложения..."
agent-browser open "https://www.rustore.ru/catalog/app/ru.mngerasimenko.todolist" --session "$SESSION"
sleep 3
agent-browser wait --load networkidle --session "$SESSION"
agent-browser snapshot -i --session "$SESSION" > "$TMP_DIR/app.txt" 2>&1

RATING=$(grep -oP '0,\d' "$TMP_DIR/app.txt" | head -1 || echo "N/A")
echo "  Рейтинг: $RATING"

# --- Шаг 2: Поиск по запросу ---
QUERY="${1:-Список задач}"
ENCODED_QUERY=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$QUERY'))")

echo ""
echo "🔍 Шаг 2: Поиск по '$QUERY'..."
echo "  URL: https://www.rustore.ru/catalog/search?query=$ENCODED_QUERY"

# Страница 1
agent-browser open "https://www.rustore.ru/catalog/search?query=$ENCODED_QUERY" --session "$SESSION"
sleep 5
agent-browser wait --load networkidle --session "$SESSION"
agent-browser snapshot -i --session "$SESSION" > "$TMP_DIR/page1.txt" 2>&1

TOTAL_RESULTS=$(grep -oP 'найдено \d+' "$TMP_DIR/page1.txt" | grep -oP '\d+' || echo "?")
echo "  Всего результатов: $TOTAL_RESULTS"

# Ищем на странице 1
if grep -q "Список задач.*Полезные инструменты" "$TMP_DIR/page1.txt" && ! grep -q "mngerasimenko" "$TMP_DIR/page1.txt"; then
    # Наше приложение может быть среди результатов с названием "Список задач"
    POSITION_ON_PAGE1=$(grep -n "Список задач" "$TMP_DIR/page1.txt" | grep -v "Метка\|Календарь" | head -1 | cut -d: -f1)
    echo "  ⚠️ Найдено на странице 1 (позиция ~$POSITION_ON_PAGE1)"
else
    echo "  ❌ Не найдено на странице 1"
fi

# Страница 2
echo ""
echo "  Проверяю страницу 2..."
sleep 10  # Задержка от 429
agent-browser open "https://www.rustore.ru/catalog/search/page-2?query=$ENCODED_QUERY" --session "$SESSION"
sleep 5
agent-browser wait --load networkidle --session "$SESSION"
agent-browser screenshot --session "$SESSION" "$TMP_DIR/screenshot.png" 2>/dev/null || true
agent-browser snapshot -i --session "$SESSION" > "$TMP_DIR/page2.txt" 2>&1

# Считаем позицию
APPS_PER_PAGE=32

# Находим нашу строку
LINE_NUM=$(grep -n "Список задач.*Полезные инструменты" "$TMP_DIR/page2.txt" | head -1 | cut -d: -f1)
if [ -n "$LINE_NUM" ]; then
    # Считаем количество "link" до нашей позиции
    APPS_BEFORE=$(head -n "$LINE_NUM" "$TMP_DIR/page2.txt" | grep -c "link.*Полезные инструменты\|link.*Образ жизни\|link.*Бизнес-сервисы")
    POSITION_ON_PAGE2=$APPS_BEFORE
    ABSOLUTE_POSITION=$((APPS_PER_PAGE + POSITION_ON_PAGE2))
    echo "  ✅ Найдено на странице 2!"
    echo "  Позиция на странице: $POSITION_ON_PAGE2"
    echo "  Абсолютная позиция: #$ABSOLUTE_POSITION из $TOTAL_RESULTS"
    echo "  Процентиль: ТОП $(echo "scale=1; $ABSOLUTE_POSITION * 100 / $TOTAL_RESULTS" | bc)%"
else
    echo "  ❌ Не найдено на странице 2"
fi

# --- Финал ---
echo ""
echo "📸 Скриншот: $TMP_DIR/screenshot.png"
agent-browser close --session "$SESSION"

echo ""
echo "============================================"
echo "✅ Отчёт завершён"
echo "============================================"
