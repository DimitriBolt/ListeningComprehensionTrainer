# Проба S07 — паузы по потребности

Код — `tools/probe/s07.py`, запуск — `python tools/probe/probe.py s07` (`--part durations|faster`).

Всего потрачено на пробу S07: ≈ $0.543.

## Длительности групп

- ответов 90 (пробы S02c, до первого вопроса — D48), групп 710, разных 422, синтезировано 421; пауз между группами 620
- синтез голосом приложения (`verse`, 0,88), длительность — без тишины по краям (окна 10 мс, края тише самого громкого окна на 35 дБ)
- звук группы: медиана 2150 мс, p10 1120, p90 3350, максимум 6660
- тишина по краям в звуке синтеза: медиана 267 мс, p90 485, максимум 938
- связь длительности со слогами r = 0.868, со словами r = 0.694; плотность — медиана 3.19 слога/с (p10 2.26, p90 4.21)

### Доля длительности: что даёт

`пауза = пауза_по_знаку + доля × звук группы` (множитель 1,0, предел 3000 мс). Прирост реплики — звук + паузы между группами, против нынешних пауз.

| доля | группа 0,5–0,9 с: + мс | группа 2,2–2,8 с: + мс | реплика длиннее, медиана (p10 … p90) | предложение: медиана / p90 | `, : ;`: медиана / p90 | внутри: медиана / p90 | абзац: медиана / p90 | у предела |
|---|---|---|---|---|---|---|---|---|
| 0.0 | 0 (48) | 0 (127) | 0.0 % (0.0 … 0.0) | 1000 / 1000 | 600 / 600 | 250 / 250 | 1600 / 1600 | 0 |
| 0.1 | 73 (48) | 241 (127) | 6.6 % (5.9 … 7.7) | 1215 / 1329 | 776 / 907 | 446 / 563 | 1815 / 1967 | 0 |
| 0.15 | 110 (48) | 362 (127) | 9.9 % (8.9 … 11.5) | 1322 / 1494 | 864 / 1060 | 544 / 720 | 1922 / 2150 | 0 |
| 0.2 | 146 (48) | 482 (127) | 13.2 % (11.8 … 15.3) | 1430 / 1658 | 952 / 1214 | 642 / 876 | 2030 / 2334 | 0 |
| 0.25 | 182 (48) | 602 (127) | 16.5 % (14.8 … 19.2) | 1538 / 1822 | 1040 / 1368 | 740 / 1032 | 2138 / 2518 | 0 |
| 0.3 | 219 (48) | 723 (127) | 19.8 % (17.8 … 23.0) | 1645 / 1987 | 1128 / 1521 | 838 / 1189 | 2245 / 2701 | 0 |
| 0.35 | 255 (48) | 844 (127) | 23.1 % (20.7 … 26.8) | 1752 / 2152 | 1216 / 1674 | 936 / 1346 | 2352 / 2884 | 3 |

### Выбор: доля 0,25

Ориентир брифа: группа ≈ 0,7 с — +0,1–0,2 с; ≈ 2,5 с — +0,6–0,8 с; реплика при множителе 1,0 длиннее на 15–30 %. При 0,25 — +182 мс, +602 мс, реплика длиннее на 16.5 % (p10 … p90 14.8 … 19.2 %). 0,2 не дотягивает до 15 % и до +0,6 с у длинной группы; 0,3 даёт короткой группе больше +0,2 с. Пауза ни разу не дошла до предела 3000 мс.

Слоги и секунды почти одно и то же (r ≈ 0,87), а секунды уже есть в готовом звуке и точнее оценки слогов. Плотность разная (p10 … p90 — 2,3 … 4,2 слога/с): плотная группа по секундам лишней паузы не получает. Довода считать её пока нет — нужна оценка пользователя: если «не успеваю» окажется после быстрых групп, добавить плотность. Остаёмся на секундах.

Число групп по типам знаков: абзац — 55, предложение — 123, , : ; — 197, внутри — 245

### Самые длинные паузы при доле 0.2

| пауза | знак | звук группы | группа |
|---|---|---|---|
| 2490 | 1600 | 4450 | about **45°F (7°C)**. |
| 2490 | 1600 | 4450 | about **45°F (7°C)**. |
| 2444 | 1600 | 4220 | be kept on display rather than flown again. |
| 2388 | 1600 | 3940 | for public display instead of flying again. |
| 2334 | 1600 | 3670 | for public display rather than flown again. |
| 2334 | 1600 | 3670 | for public display rather than flown again. |
| 2266 | 1600 | 3330 | two major SpaceX stories stand out: |
| 2228 | 1600 | 3140 | Space Force mission on November 23. |

### Самые длинные паузы при доле 0.25

| пауза | знак | звук группы | группа |
|---|---|---|---|
| 2712 | 1600 | 4450 | about **45°F (7°C)**. |
| 2712 | 1600 | 4450 | about **45°F (7°C)**. |
| 2655 | 1600 | 4220 | be kept on display rather than flown again. |
| 2585 | 1600 | 3940 | for public display instead of flying again. |
| 2518 | 1600 | 3670 | for public display rather than flown again. |
| 2518 | 1600 | 3670 | for public display rather than flown again. |
| 2432 | 1600 | 3330 | two major SpaceX stories stand out: |
| 2385 | 1600 | 3140 | Space Force mission on November 23. |

### Самые длинные паузы при доле 0.3

| пауза | знак | звук группы | группа |
|---|---|---|---|
| 2935 | 1600 | 4450 | about **45°F (7°C)**. |
| 2935 | 1600 | 4450 | about **45°F (7°C)**. |
| 2866 | 1600 | 4220 | be kept on display rather than flown again. |
| 2782 | 1600 | 3940 | for public display instead of flying again. |
| 2701 | 1600 | 3670 | for public display rather than flown again. |
| 2701 | 1600 | 3670 | for public display rather than flown again. |
| 2599 | 1600 | 3330 | two major SpaceX stories stand out: |
| 2598 | 600 | 6660 | Orleans Pelicans 123–119 in overtime yesterday, |

## Команда «Faster»

- синтез двумя голосами (alloy, coral), файл и поток с подсказкой команд приложения
- команды узнаны: **25 из 28**
- ложных команд на похожих ответах: **0 из 60**
- ответы и команды S05b заново по новому правилу (552 строк): «faster» ложно — 0, всего расхождений с ожиданием — 0 (те же, что в S05b, если не 0 — см. `05b-probes.md`)

| голос | путь | сказано | ожидание | распознано | команда |
|---|---|---|---|---|---|
| alloy | file | Faster. | faster | Aster. | — ✗ |
| alloy | file | Faster, please. | faster | Faster, please. | faster |
| alloy | file | Speed up. | faster | Speed up. | faster |
| alloy | file | Quicker. | faster | Quicker. | faster |
| alloy | file | More quickly. | faster | More quickly. | faster |
| alloy | file | Speak faster. | faster | Speak faster. | faster |
| alloy | file | A bit faster. | faster | A bit faster. | faster |
| alloy | file | Fast food. | — | Fast food. | — |
| alloy | file | Breakfast. | — | Breakfast. | — |
| alloy | file | First. | — | First. | — |
| alloy | file | Fasting. | — | Fasting. | — |
| alloy | file | Fast car. | — | Fast car. | — |
| alloy | file | Last. | — | Last. | — |
| alloy | file | Past. | — | Past. | — |
| alloy | file | Fact. | — | Fact. | — |
| alloy | file | Vast. | — | Vast. | — |
| alloy | file | Forest. | — | Forest. | — |
| alloy | file | Fashion. | — | Fashion. | — |
| alloy | file | Quick lunch. | — | Quick lunch? | — |
| alloy | file | Faster than me. | — | Faster than me. | — |
| alloy | file | Festival. | — | Festival. | — |
| alloy | file | Feast. | — | Feast. | — |
| alloy | live | Faster. | faster | Aster, | — ✗ |
| alloy | live | Faster, please. | faster | Faster, please. | faster |
| alloy | live | Speed up. | faster | Speed up. | faster |
| alloy | live | Quicker. | faster | Quicker. | faster |
| alloy | live | More quickly. | faster | More quickly. | faster |
| alloy | live | Speak faster. | faster | Speak fasten. | — ✗ |
| alloy | live | A bit faster. | faster | A bit faster. | faster |
| alloy | live | Fast food. | — | Fast food. | — |
| alloy | live | Breakfast. | — | Breakfast. | — |
| alloy | live | First. | — | First, | — |
| alloy | live | Fasting. | — | Fasting. | — |
| alloy | live | Fast car. | — | Fast car. | — |
| alloy | live | Last. | — | Last. | — |
| alloy | live | Past. | — | Past. | — |
| alloy | live | Fact. | — | Fact. | — |
| alloy | live | Vast. | — | Vast. | — |
| alloy | live | Forest. | — | Forest | — |
| alloy | live | Fashion. | — | Fashion, | — |
| alloy | live | Quick lunch. | — | Quick lunch? | — |
| alloy | live | Faster than me. | — | Faster, than me. | — |
| alloy | live | Festival. | — | Festival. | — |
| alloy | live | Feast. | — | Feast. | — |
| coral | file | Faster. | faster | Faster. | faster |
| coral | file | Faster, please. | faster | Faster, please. | faster |
| coral | file | Speed up. | faster | Speed up. | faster |
| coral | file | Quicker. | faster | Quicker. | faster |
| coral | file | More quickly. | faster | More quickly. | faster |
| coral | file | Speak faster. | faster | Speak faster. | faster |
| coral | file | A bit faster. | faster | A bit faster. | faster |
| coral | file | Fast food. | — | Fast food. | — |
| coral | file | Breakfast. | — | Breakfast. | — |
| coral | file | First. | — | Burst. | — |
| coral | file | Fasting. | — | Sasting. | — |
| coral | file | Fast car. | — | Fast car. | — |
| coral | file | Last. | — | Last. | — |
| coral | file | Past. | — | Past. | — |
| coral | file | Fact. | — | Fact. | — |
| coral | file | Vast. | — | Vast. | — |
| coral | file | Forest. | — | Forest. | — |
| coral | file | Fashion. | — | Fashion. | — |
| coral | file | Quick lunch. | — | Quick lunch. | — |
| coral | file | Faster than me. | — | Faster than me. | — |
| coral | file | Festival. | — | Festival. | — |
| coral | file | Feast. | — | Feast. | — |
| coral | live | Faster. | faster | Faster. | faster |
| coral | live | Faster, please. | faster | Faster. Please. | faster |
| coral | live | Speed up. | faster | Speed up. | faster |
| coral | live | Quicker. | faster | Quicker. | faster |
| coral | live | More quickly. | faster | More quickly. | faster |
| coral | live | Speak faster. | faster | Speak faster. | faster |
| coral | live | A bit faster. | faster | A bit faster. | faster |
| coral | live | Fast food. | — | Fast food. | — |
| coral | live | Breakfast. | — | Breakfast. | — |
| coral | live | First. | — | Burst. | — |
| coral | live | Fasting. | — | Sasting | — |
| coral | live | Fast car. | — | Fast car. | — |
| coral | live | Last. | — | Last. | — |
| coral | live | Past. | — | Past. | — |
| coral | live | Fact. | — | Fact. | — |
| coral | live | Vast. | — | Vast. | — |
| coral | live | Forest. | — | Forest. | — |
| coral | live | Fashion. | — | Fashion. | — |
| coral | live | Quick lunch. | — | Quick lunch | — |
| coral | live | Faster than me. | — | Faster than me. | — |
| coral | live | Festival. | — | Festival. | — |
| coral | live | Feast. | — | Feast | — |

