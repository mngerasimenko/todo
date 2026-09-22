#!/bin/bash
# УСТАРЕЛО. Скрипт ничего не устанавливает и намеренно отказывается работать.
#
# Он писался до портфельной опрашивалки и делал три вещи, каждая из которых
# сегодня ломает прод:
#
#   1. `crontab -l 2>/dev/null | grep -v server-monitor.sh | crontab -` —
#      не различает «crontab пуст» и «crontab не прочитан». Во втором случае
#      таблица прода из восьми строк молча превращается в одну: уезжают бэкап,
#      восстановительная тренировка, проба replyAI, продление сертификатов,
#      опрашивалка и ежедневный прогон сборщика тарифов vpscan. Без бэкапа.
#   2. Фильтр по подстроке снимает и чужие строки, где встретилось это имя.
#   3. Ставит строку на /root/monitoring/server-monitor.sh, тогда как на проде
#      cron запускает копию из /home/deploy/todo/monitoring, рядом с которой
#      лежат monitor.conf и vk-send.sh. В /root их нет — задача падала бы
#      каждые пять минут, а «тестовая отправка» шла бы в мёртвый Telegram и всё
#      равно печатала «Готово».
#
# Что делать вместо этого — monitoring/README.md, раздел «Установка»:
#   bash monitoring/install-portfolio-monitor.sh peer       # прод
#   bash monitoring/install-portfolio-monitor.sh external   # стейдж
# Установщик идемпотентен, метит свои строки, снимает только их, кладёт бэкап
# crontab и сверяет записанное. Но ведёт он ровно три строки: опрашивалку,
# продление сертификатов и локальный прогон vpscan. Строки server-monitor.sh и
# stats-report.sh и юнит server-monitor-bot он НЕ ставит — их кладут руками, и
# точные команды тоже в README, разделе «Установка».

cat >&2 <<'EOF'
setup-monitoring.sh устарел и ничего не делает: он затирал root-crontab целиком,
когда crontab -l не читался, и ставил cron на каталог без monitor.conf и
vk-send.sh.

Используй установщик портфельной опрашивалки:
  bash monitoring/install-portfolio-monitor.sh peer       # прод
  bash monitoring/install-portfolio-monitor.sh external   # стейдж
Он ведёт три строки: опрашивалку, продление сертификатов и прогон vpscan. Строки
server-monitor.sh и stats-report.sh и юнит server-monitor-bot ставятся руками —
команды в monitoring/README.md, раздел «Установка».
EOF
exit 2
