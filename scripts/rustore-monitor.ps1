# ============================================================
# Мониторинг позиции приложения в RuStore (PowerShell)
# Запуск: .\scripts\rustore-monitor.ps1
# ============================================================

$ErrorActionPreference = "Stop"

$DATE = Get-Date -Format "yyyy-MM-dd HH:mm"
$SESSION = "rustore-$([datetimeoffset]::Now.ToUnixTimeSeconds())"
$TMP_DIR = "$env:TEMP\rustore-monitor"
New-Item -ItemType Directory -Force -Path $TMP_DIR | Out-Null

Write-Host "📊 RuStore Monitor — $DATE" -ForegroundColor Green
Write-Host "============================================"

# --- Проверка страницы приложения ---
Write-Host ""
Write-Host "📱 Проверка страницы приложения..." -ForegroundColor Cyan
agent-browser open "https://www.rustore.ru/catalog/app/ru.mngerasimenko.todolist" --session $SESSION
Start-Sleep -Seconds 3
agent-browser wait --load networkidle --session $SESSION
agent-browser snapshot -i --session $SESSION > "$TMP_DIR\app.txt" 2>&1

$appContent = Get-Content "$TMP_DIR\app.txt" -Raw
$RATING = if ($appContent -match '(\d,\d)') { $Matches[1] } else { "N/A" }
Write-Host "  Рейтинг: $RATING" -ForegroundColor Yellow

# --- Функция поиска позиции ---
function Find-Position {
    param([string]$Query)

    $EncodedQuery = [System.Uri]::EscapeDataString($Query)
    $MaxPage = 10
    $AppsPerPage = 32

    Write-Host ""
    Write-Host "🔍 Поиск: '$Query'..." -ForegroundColor Cyan

    for ($Page = 1; $Page -le $MaxPage; $Page++) {
        Write-Host "  Страница $Page/$MaxPage..." -ForegroundColor Gray

        # Задержка от 429
        Start-Sleep -Seconds 15

        $Url = "https://www.rustore.ru/catalog/search/page-${Page}?query=${EncodedQuery}"
        agent-browser open $Url --session $SESSION
        Start-Sleep -Seconds 3
        agent-browser wait --load networkidle --session $SESSION
        agent-browser snapshot -i --session $SESSION > "$TMP_DIR\page${Page}.txt" 2>&1

        $Content = Get-Content "$TMP_DIR\page${Page}.txt" -Raw

        # Получаем общее количество результатов
        if ($Page -eq 1 -and $Content -match 'найдено (\d+)') {
            $Total = $Matches[1]
        }

        # Ищем наше приложение
        $Lines = $Content -split "`n"
        $FoundLine = -1
        for ($i = 0; $i -lt $Lines.Count; $i++) {
            if ($Lines[$i] -match 'Список задач.*Полезные инструменты') {
                $FoundLine = $i
                break
            }
        }

        if ($FoundLine -ge 0) {
            # Считаем количество приложений до нашего
            $AppsBefore = 0
            for ($i = 0; $i -lt $FoundLine; $i++) {
                if ($Lines[$i] -match '^\- link.*Полезные инструменты') {
                    $AppsBefore++
                }
            }
            $PosOnPage = $AppsBefore
            $AbsPos = (($Page - 1) * $AppsPerPage) + $PosOnPage
            $Percentile = [math]::Round(($AbsPos / $Total) * 100, 1)

            Write-Host "  ✅ Найдено! Позиция: #$AbsPos из $Total (страница $Page)" -ForegroundColor Green
            Write-Host "  Процентиль: ТОП ${Percentile}%" -ForegroundColor Yellow

            # Сохраняем результат
            "$DATE | $Query | #$AbsPos | $Total | ТОП ${Percentile}% | Рейтинг $RATING" | Out-File -Append "$TMP_DIR\result.csv" -Encoding UTF8
            return
        }
    }

    Write-Host "  ❌ Не найдено за $MaxPage страниц" -ForegroundColor Red
    "$DATE | $Query | >$($MaxPage * $AppsPerPage) | $Total | - | Рейтинг $RATING" | Out-File -Append "$TMP_DIR\result.csv" -Encoding UTF8
}

# --- Проверка по запросам ---
Find-Position -Query "Список задач"
Find-Position -Query "Список дел"

# --- Обновляем файл мониторинга ---
if (Test-Path "$TMP_DIR\result.csv") {
    Write-Host ""
    Write-Host "📝 Обновление monitoring/rustore-position.md..." -ForegroundColor Cyan

    $MonitorFile = "monitoring\rustore-position.md"
    $Results = Get-Content "$TMP_DIR\result.csv" -Encoding UTF8

    foreach ($Line in $Results) {
        $Parts = $Line -split '\|'
        if ($Parts.Count -ge 6) {
            $Date = $Parts[0].Trim()
            $Query = $Parts[1].Trim()
            $Pos = $Parts[2].Trim()
            $Total = $Parts[3].Trim()
            $Pct = $Parts[4].Trim()
            $Note = $Parts[5].Trim()

            $NewRow = "| $Date | $Query | $Pos | $Total | $Pct | $Note"
            $Content = Get-Content $MonitorFile -Raw
            # Вставляем после строки "### 📝 Лог проверок"
            $Content = $Content -replace '(### 📝 Лог проверок\n)', "`$1`n$NewRow`n"
            Set-Content -Path $MonitorFile -Value $Content -NoNewline
        }
    }
}

# --- Финал ---
Write-Host ""
Write-Host "📸 Скриншоты: $TMP_DIR\" -ForegroundColor Cyan
agent-browser close --session $SESSION

Write-Host ""
Write-Host "============================================" -ForegroundColor Green
Write-Host "✅ Мониторинг завершён" -ForegroundColor Green
Write-Host "📁 Результаты: $TMP_DIR\" -ForegroundColor Gray
Write-Host "📄 Лог: monitoring/rustore-position.md" -ForegroundColor Gray
Write-Host "============================================" -ForegroundColor Green
