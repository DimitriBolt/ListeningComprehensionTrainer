# Пробы S05b — короткие слова

Код — `tools/probe/s05b.py`, запуск — `python tools/probe/probe.py s05b` (`--part commands|corrector`).

Всего потрачено на пробы S05b: ≈ $0.002.

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
