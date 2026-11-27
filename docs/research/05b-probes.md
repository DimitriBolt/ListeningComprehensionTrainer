# Пробы S05b — короткие слова

Код — `tools/probe/s05b.py`, запуск — `python tools/probe/probe.py s05b` (`--part commands|corrector`).

Всего потрачено на пробы S05b: ≈ $0.428.

## Команды и короткие ответы (D83, D84, D85)

Синтез Realtime голосами alloy, ash, coral (не голос пользователя): 16 команд S05 и 30 обычных коротких ответов, каждая фраза каждым голосом — 48 команд и 90 ответов. Учитель только что сказал: «Do you like hot weather or cold weather?». Поток — сессия `gpt-live-transcribe` с `prompt`, 0,6 с тишины до и 1,0 с после (как S05). Файл — WAV 16 кГц в `gpt-transcribe`, 0,5 с до и 0,3 с после (кусок короткого звука D85). 2026-11-27T15:06:11.

- Подсказка: «The student may also say a short command: Stop, Again, Slower.» (`TranscriptionPrompt.COMMANDS_HINT`).
- Правила узнавания: **приложение** — список + терпимое слово с условиями (Again — первая гласная и «g», Stop — начало «st»); **бриф** — то же без условий на буквы; **список** — только список S05.

| Путь | Подсказка | Команды: приложение | бриф | список | Ложных: приложение | бриф | список | Слов ответов верно | Ответов дословно | Пусто | мс (медиана / p90) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| live | нет | 48/48 | 48 | 48 | 0/90 | 11 | 0 | 95.6 % | 82/90 | 0 | — / — |
| live | да | 48/48 | 48 | 48 | 0/90 | 11 | 0 | 95.1 % | 83/90 | 0 | — / — |
| file | нет | 48/48 | 48 | 48 | 0/90 | 12 | 0 | 99.3 % | 89/90 | 0 | 509 / 847 |
| file | да | 48/48 | 48 | 48 | 0/90 | 12 | 0 | 99.3 % | 89/90 | 0 | 483 / 596 |

### Промахи и ложные команды (правило приложения), ошибки распознавания ответов

| Голос | Путь | Подсказка | Сказано | Распознано | Команда | Ждали | Слов верно |
|---|---|---|---|---|---|---|---|
| alloy | live | нет | Yes, I do. | Yes. | — | — | 0.33 |
| alloy | live | нет | Rain. | Rain. | — (бриф: again) | — | 1.00 |
| alloy | live | нет | Spain. | Spam. | — | — | 0.00 |
| alloy | live | нет | Shop. | Shop. | — (бриф: stop) | — | 1.00 |
| alloy | live | нет | Top. | Top. | — (бриф: stop) | — | 1.00 |
| alloy | live | нет | A gain of five. | I gain of five. | — | — | 0.75 |
| alloy | live | да | Yes, I do. | Yes. | — | — | 0.33 |
| alloy | live | да | Rain. | Rain. | — (бриф: again) | — | 1.00 |
| alloy | live | да | Spain. | Spam. | — | — | 0.00 |
| alloy | live | да | Shop. | Shop. | — (бриф: stop) | — | 1.00 |
| alloy | live | да | Top. | Top. | — (бриф: stop) | — | 1.00 |
| alloy | live | да | A gain of five. | Again, a five. | — | — | 0.50 |
| alloy | file | нет | Yes, I do. | Yes. | — | — | 0.33 |
| alloy | file | нет | Rain. | Rain. | — (бриф: again) | — | 1.00 |
| alloy | file | нет | Spain. | Spain. | — (бриф: again) | — | 1.00 |
| alloy | file | нет | Shop. | Shop. | — (бриф: stop) | — | 1.00 |
| alloy | file | нет | Top. | Top. | — (бриф: stop) | — | 1.00 |
| alloy | file | да | Yes, I do. | Yes. | — | — | 0.33 |
| alloy | file | да | Rain. | Rain. | — (бриф: again) | — | 1.00 |
| alloy | file | да | Spain. | Spain. | — (бриф: again) | — | 1.00 |
| alloy | file | да | Shop. | Shop. | — (бриф: stop) | — | 1.00 |
| alloy | file | да | Top. | Top. | — (бриф: stop) | — | 1.00 |
| ash | live | нет | Rain. | Rain. | — (бриф: again) | — | 1.00 |
| ash | live | нет | Spain. | Spain. | — (бриф: again) | — | 1.00 |
| ash | live | нет | Shop. | Shop. | — (бриф: stop) | — | 1.00 |
| ash | live | нет | Top. | Top. | — (бриф: stop) | — | 1.00 |
| ash | live | да | Rain. | Rain. | — (бриф: again) | — | 1.00 |
| ash | live | да | Spain. | Spain. | — (бриф: again) | — | 1.00 |
| ash | live | да | Shop. | Shop. | — (бриф: stop) | — | 1.00 |
| ash | live | да | Top. | Top. | — (бриф: stop) | — | 1.00 |
| ash | live | да | A gain of five. | Again, of five. | — | — | 0.50 |
| ash | file | нет | Rain. | Rain. | — (бриф: again) | — | 1.00 |
| ash | file | нет | Spain. | Spain. | — (бриф: again) | — | 1.00 |
| ash | file | нет | Shop. | Shop. | — (бриф: stop) | — | 1.00 |
| ash | file | нет | Top. | Top. | — (бриф: stop) | — | 1.00 |
| ash | file | да | Rain. | Rain. | — (бриф: again) | — | 1.00 |
| ash | file | да | Spain. | Spain. | — (бриф: again) | — | 1.00 |
| ash | file | да | Shop. | Shop. | — (бриф: stop) | — | 1.00 |
| ash | file | да | Top. | Top. | — (бриф: stop) | — | 1.00 |
| coral | live | нет | It is slow. | It is swell. | — | — | 0.67 |
| coral | live | нет | Rain. | Rain. | — (бриф: again) | — | 1.00 |
| coral | live | нет | Spain. | Spain | — (бриф: again) | — | 1.00 |
| coral | live | нет | Shop. | Shop. | — (бриф: stop) | — | 1.00 |
| coral | live | нет | Top. | Top. | — (бриф: stop) | — | 1.00 |
| coral | live | нет | Driving license. | Dryving license. | — | — | 0.50 |
| coral | live | нет | Tucson. | Too sun. | — | — | 0.00 |
| coral | live | нет | A gain of five. | I'd gain of five. | — | — | 0.75 |
| coral | live | да | Rain. | Rain. | — (бриф: again) | — | 1.00 |
| coral | live | да | Spain. | Spain. | — (бриф: again) | — | 1.00 |
| coral | live | да | Shop. | Shop. | — (бриф: stop) | — | 1.00 |
| coral | live | да | Top. | Top. | — (бриф: stop) | — | 1.00 |
| coral | live | да | Driving license. | Drying license. | — | — | 0.50 |
| coral | live | да | Tucson. | Too sun. | — | — | 0.00 |
| coral | live | да | A gain of five. | I'd gain of five. | — | — | 0.75 |
| coral | file | нет | Rain. | Rain. | — (бриф: again) | — | 1.00 |
| coral | file | нет | Spain. | Spain. | — (бриф: again) | — | 1.00 |
| coral | file | нет | Shop. | Shop. | — (бриф: stop) | — | 1.00 |
| coral | file | нет | Top. | Top. | — (бриф: stop) | — | 1.00 |
| coral | file | да | Rain. | Rain. | — (бриф: again) | — | 1.00 |
| coral | file | да | Spain. | Spain. | — (бриф: again) | — | 1.00 |
| coral | file | да | Shop. | Shop. | — (бриф: stop) | — | 1.00 |
| coral | file | да | Top. | Top. | — (бриф: stop) | — | 1.00 |

## Поправщик: участок с ошибкой (D87)

6 длинных реплик S05 (33–43 слова без знаков) и 12 коротких из набора S02f (9 с ошибкой, 3 правильных). Проверка — копия `Correction.check` (S05b). 2026-11-27T14:58:05.

- Длинные: самая длинная поправка теперь — **5 слов** (целиком было бы до 43).
- Короткие: поправка изменилась в 0 из 12 (должно быть 0).

| Слов | Длинная | Ошибка | Итог | Слов поправки (было) | Что прозвучит | Ответ поправщика |
|---|---|---|---|---|---|---|
| 41 | да | да | fix | 3 (41) | I think you meant "I made coffee", right? | yesterday I got up early and I made coffee then I went to the park with my dog it was very nice weather and we walked for one hour after that I came back home and cooked breakfast for my wife |
| 43 | да | да | fix | 5 (43) | I think you meant "we stayed in a small", right? | I want to tell you about my trip to the mountains last summer we drove four hours to Flagstaff and we stayed in a small hotel near the forest every morning we walked on the trails and in the evening we ate pizza |
| 37 | да | нет | none | 0 (0) |  | My favorite food is pizza. I like pizza with mushrooms and cheese. My wife likes pizza with pineapple, but I think it is strange. On Fridays we usually order pizza and watch a movie together at home. |
| 39 | да | нет | none | 0 (0) |  | I work as an engineer in a big company I write programs for computers every day I have many meetings and sometimes it is boring but I like my colleagues they are very friendly and help me a lot |
| 43 | да | да | fix | 5 (43) | I think you meant "my son and I went", right? | last weekend my son and I went to the lake we wanted to catch fish but the water was very cold and the fish didn't want to eat so we caught nothing but we had a good time and we saw two eagles |
| 34 | да | нет | none | 0 (0) |  | In the morning I usually drink coffee and read the news on my phone then I take a shower and drive to my office it takes about twenty minutes when there is no traffic |
| 5 | нет | да | fix | 6 (6) | I think you meant "What is the temperature in Tucson?", right? | What is the temperature in Tucson? |
| 6 | нет | да | fix | 7 (7) | I think you meant "I want to buy a new car", right? | I want to buy a new car. |
| 11 | нет | да | fix | 11 (11) | I think you meant "I lived in Moscow until 2000, then I moved to Tucson", right? | I lived in Moscow until 2000, then I moved to Tucson. |
| 4 | нет | да | fix | 4 (4) | I think you meant "She doesn't like coffee", right? | She doesn't like coffee. |
| 7 | нет | да | fix | 7 (7) | I think you meant "There are many people in the park", right? | There are many people in the park. |
| 4 | нет | да | fix | 4 (4) | I think you meant "Why are you sad?", right? | Why are you sad? |
| 6 | нет | да | fix | 6 (6) | I think you meant "I am going to the shop", right? | I am going to the shop. |
| 5 | нет | да | fix | 5 (5) | I think you meant "It depends on the weather", right? | It depends on the weather. |
| 5 | нет | да | fix | 5 (5) | I think you meant "Many people like this city", right? | Many people like this city. |
| 2 | нет | нет | none | 0 (0) |  | OK |
| 1 | нет | нет | none | 0 (0) |  | OK |
| 4 | нет | нет | none | 0 (0) |  | OK |
