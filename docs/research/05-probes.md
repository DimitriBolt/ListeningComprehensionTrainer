# Пробы S05 — перебивание и команды голосом

Код — `tools/probe/s05.py`, запуск — `python tools/probe/probe.py s05` (`--part commands|slower|interrupted|corrector-long`). Числа ниже — из последнего прогона каждой части.

Всего потрачено на пробы S05: ≈ $0.143.

## Команды голосом (D82)

Фразы читает синтез Realtime голосами alloy, ash, coral; звук — в сессию `gpt-live-transcribe` с `prompt` приложения (учитель только что сказал: «Do you like hot weather or cold weather?»), 0.6 с тишины до и 1.0 с после. Узнавание — копией `VoiceCommand.of` по списку из `VoiceCommand.kt`. 2026-11-27T01:12:37.

- **Команды узнаны:** 47 из 48.
- **Ложные команды** (обычная короткая реплика принята за команду): 0 из 24.

| Голос | Сказано | Распознано | Команда | Ждали | |
|---|---|---|---|---|---|
| alloy | Stop. | Stop. | stop | stop | ✓ |
| alloy | Wait. | Wait, | stop | stop | ✓ |
| alloy | Stop, please. | Stop please. | stop | stop | ✓ |
| alloy | Wait a moment. | Wait, a moment. | stop | stop | ✓ |
| alloy | Again. | Again, | again | again | ✓ |
| alloy | Repeat. | Repeat: | again | again | ✓ |
| alloy | Say again. | Say again. | again | again | ✓ |
| alloy | One more time. | One more time. | again | again | ✓ |
| alloy | Again, please. | Again, please. | again | again | ✓ |
| alloy | Repeat, please. | Repeat please. | again | again | ✓ |
| alloy | Say it again. | Say it again. | again | again | ✓ |
| alloy | Slower. | Slower, | slower | slower | ✓ |
| alloy | Slow down. | Slow down. | slower | slower | ✓ |
| alloy | More slowly. |  | — | slower | ✗ |
| alloy | Slower, please. | Slower. Please. | slower | slower | ✓ |
| alloy | Speak slowly. | Speak slowly | slower | slower | ✓ |
| alloy | What? | What? | — | — | ✓ |
| alloy | I don't understand. | I don't understand. | — | — | ✓ |
| alloy | At noon. | At noon, | — | — | ✓ |
| alloy | I stopped. | I stopped. | — | — | ✓ |
| alloy | Wait for me here. | Wait for me here. | — | — | ✓ |
| alloy | Yes, again tomorrow. | Yes. Again tomorrow. | — | — | ✓ |
| alloy | Slow food. | Slow food. | — | — | ✓ |
| alloy | Stop the car now. | Stop the car now. | — | — | ✓ |
| ash | Stop. | Stop, | stop | stop | ✓ |
| ash | Wait. | Wait, | stop | stop | ✓ |
| ash | Stop, please. | Stop. Please. | stop | stop | ✓ |
| ash | Wait a moment. | Wait a moment. | stop | stop | ✓ |
| ash | Again. | Again, | again | again | ✓ |
| ash | Repeat. | Repeat: | again | again | ✓ |
| ash | Say again. | Say again | again | again | ✓ |
| ash | One more time. | One more time. | again | again | ✓ |
| ash | Again, please. | Again, please. | again | again | ✓ |
| ash | Repeat, please. | Repeat, please. | again | again | ✓ |
| ash | Say it again. | Say it again. | again | again | ✓ |
| ash | Slower. | Slower. | slower | slower | ✓ |
| ash | Slow down. | Slow down. | slower | slower | ✓ |
| ash | More slowly. | More slowly. | slower | slower | ✓ |
| ash | Slower, please. | Slower please. | slower | slower | ✓ |
| ash | Speak slowly. | Speak slowly | slower | slower | ✓ |
| ash | What? | What? | — | — | ✓ |
| ash | I don't understand. | I don't understand. | — | — | ✓ |
| ash | At noon. | At noon | — | — | ✓ |
| ash | I stopped. | I stopped. | — | — | ✓ |
| ash | Wait for me here. | Wait for me here. | — | — | ✓ |
| ash | Yes, again tomorrow. | Yes, again tomorrow. | — | — | ✓ |
| ash | Slow food. | Slow food. | — | — | ✓ |
| ash | Stop the car now. | Stop the car now. | — | — | ✓ |
| coral | Stop. | Stop. | stop | stop | ✓ |
| coral | Wait. | Wait, | stop | stop | ✓ |
| coral | Stop, please. | Stop. Please, | stop | stop | ✓ |
| coral | Wait a moment. | Wait a moment. | stop | stop | ✓ |
| coral | Again. | Again, | again | again | ✓ |
| coral | Repeat. | Repeat: | again | again | ✓ |
| coral | Say again. | Say again. | again | again | ✓ |
| coral | One more time. | One more time. | again | again | ✓ |
| coral | Again, please. | Again, please. | again | again | ✓ |
| coral | Repeat, please. | Repeat please: | again | again | ✓ |
| coral | Say it again. | Say it again. | again | again | ✓ |
| coral | Slower. | Slower. | slower | slower | ✓ |
| coral | Slow down. | Slow down. | slower | slower | ✓ |
| coral | More slowly. | More slowly. | slower | slower | ✓ |
| coral | Slower, please. | Slower, please. | slower | slower | ✓ |
| coral | Speak slowly. | Speak slowly. | slower | slower | ✓ |
| coral | What? | What? | — | — | ✓ |
| coral | I don't understand. | I don't understand. | — | — | ✓ |
| coral | At noon. | At noon | — | — | ✓ |
| coral | I stopped. | I stopped. | — | — | ✓ |
| coral | Wait for me here. | Wait for me here. | — | — | ✓ |
| coral | Yes, again tomorrow. | Yes, again tomorrow. | — | — | ✓ |
| coral | Slow food. | Sweet food | — | — | ✓ |
| coral | Stop the car now. | Stop the car now. | — | — | ✓ |

## «Slower» — синтез медленнее

Голос `verse`, одна сессия: каждая группа обычной скоростью 0.88 и медленной 0.7, скорость меняется `session.update` между ответами (документация OpenAI: `audio.output.speed` от 0,25 до 1,5, только между ответами). 2026-11-27T01:12:50.

- **Длительность медленной / обычной:** медиана 1.28.
- **Сверка (дословно):** обычная 8 из 8, медленная 8 из 8.
- **`session.update` со сменой скорости:** до 0.09 с.

| Группа | Скорость | Звук, с | Сверка | Расшифровка |
|---|---|---|---|---|
| Yesterday I went | 0.88 | 1.79 | verbatim | Yesterday I went |
| Yesterday I went | 0.7 | 2.40 | verbatim | Yesterday I went |
| to the market. | 0.88 | 1.67 | verbatim | to the market. |
| to the market. | 0.7 | 1.47 | verbatim | to the market. |
| I bought apples | 0.88 | 1.45 | verbatim | I bought apples |
| I bought apples | 0.7 | 2.19 | verbatim | I bought apples |
| and some bread. | 0.88 | 1.23 | verbatim | and some bread. |
| and some bread. | 0.7 | 1.76 | verbatim | and some bread. |
| The weather was warm, | 0.88 | 1.85 | verbatim | The weather was warm, |
| The weather was warm, | 0.7 | 2.26 | verbatim | The weather was warm, |
| so I walked home. | 0.88 | 1.57 | verbatim | so I walked home. |
| so I walked home. | 0.7 | 2.26 | verbatim | so I walked home. |
| What did you do | 0.88 | 1.85 | verbatim | What did you do |
| What did you do | 0.7 | 2.05 | verbatim | What did you do |
| yesterday? | 0.88 | 1.16 | verbatim | yesterday? |
| yesterday? | 0.7 | 1.26 | verbatim | yesterday? |

## Учитель после перебивания (D81)

В истории учителя — прозвучавшие группы ответа и пометка `[interrupted by the student]`, затем новая реплика ученика. Повтор — доля предложений прерванного ответа (3+ слова), которые новый ответ сказал дословно. 2026-11-27T01:10:24.

- **Повторил прерванное целиком (доля ≥ 0,5):** 0 из 6.
- **Пометка попала в ответ:** 0 из 6.
- **Вопрос в конце:** 6 из 6.

**I1.1 — ученик продолжил рассказ (пауза после законченного предложения).** Повтор 0.00, своих слов 35.

- Ученик: Yesterday I got up early.
- Учитель (весь ответ, 8 групп): You got up early yesterday, so you had time. Many birds sing before the sun comes up. Some people enjoy a quiet walk then. What do birds do before sunrise?
- Прозвучало до перебивания: You got up early yesterday, so you had time. Many birds sing `[interrupted by the student]`
- Ученик перебил: I made coffee and went to the park.
- Учитель: Coffee before a walk sounds like a nice morning. In the park, birds sing and people walk their dogs. Some people sit on benches and watch the trees. What did people do in the park?

**I1.2 — ученик продолжил рассказ (пауза после законченного предложения).** Повтор 0.00, своих слов 32.

- Ученик: Yesterday I got up early.
- Учитель (весь ответ, 7 групп): Getting up early gives you a calm start. Many birds sing before the sun comes up. Some people enjoy a quiet walk then. What do many birds do early?
- Прозвучало до перебивания: Getting up early gives you a calm start. Many birds sing `[interrupted by the student]`
- Ученик перебил: I made coffee and went to the park.
- Учитель: That sounds like a peaceful morning for you. Some parks have quiet paths beneath tall trees. People often walk there before the day gets busy. What did you see in the park?

**I2.1 — ученик переспросил слово посреди ответа.** Повтор 0.00, своих слов 34.

- Ученик: Tell me about the Grand Canyon.
- Учитель (весь ответ, 5 групп): The Grand Canyon is very large and beautiful. Do you want to hear about its animals or rocks?
- Прозвучало до перебивания: The Grand Canyon is very large and beautiful. Do you want to hear about its animals `[interrupted by the student]`
- Ученик перебил: Wait, what is a canyon?
- Учитель: A canyon is a deep space between high rocks. Water can slowly make a canyon over many years. The Grand Canyon has high sides and a low floor. What can slowly make a canyon?

**I2.2 — ученик переспросил слово посреди ответа.** Повтор 0.00, своих слов 29.

- Ученик: Tell me about the Grand Canyon.
- Учитель (весь ответ, 5 групп): The Grand Canyon is very big and beautiful. Do you want to hear about its animals or its rocks?
- Прозвучало до перебивания: The Grand Canyon is very big and beautiful. Do you want to hear about its animals `[interrupted by the student]`
- Ученик перебил: Wait, what is a canyon?
- Учитель: A canyon is a deep space between high rocks. Rivers often make canyons over many years. The Grand Canyon has tall, colorful rock walls. What can make a canyon?

**I3.1 — ученик ответил раньше вопроса.** Повтор 0.00, своих слов 27.

- Ученик: I like summer.
- Учитель (весь ответ, 6 групп): Summer is warm, and many people swim outside. Children often play in parks during summer. What do you like doing outside in summer?
- Прозвучало до перебивания: Summer is warm, and many people swim outside. `[interrupted by the student]`
- Ученик перебил: Because I like swimming.
- Учитель: Swimming feels good on a warm summer day. Many people swim in lakes or pools. Some people swim at the beach. Where do you like to swim?

**I3.2 — ученик ответил раньше вопроса.** Повтор 0.00, своих слов 28.

- Ученик: I like summer.
- Учитель (весь ответ, 7 групп): Summer is warm, and the days are long. Many people swim in lakes or pools. Some families eat dinner outside in summer. What do many people swim in?
- Прозвучало до перебивания: Summer is warm, and the days are long. `[interrupted by the student]`
- Ученик перебил: Because I like swimming.
- Учитель: Swimming feels nice when the water is cool. Many people swim in pools during summer. Some swim at the beach with friends. Where do you like to swim?

## Поправщик на длинной реплике (D80)

Склеенные реплики 33–40 слов без знаков (так пишет поток), `max_output_tokens` 250, проверка ответа — копия `Correction.check` (сходство по лучшему окну, S05). 2026-11-27T01:10:09.

- **Не `completed`:** 0 из 12; выходных токенов — до 48.
- **Поправка на репликах с ошибкой:** 6 из 6; ложных на правильных — 1 из 6; отклонено проверкой — 0.

| Слов | Ошибка | Статус | Итог | Сходство | Токенов | с | Ответ |
|---|---|---|---|---|---|---|---|
| 41 | да | completed | fix | 0.88 | 46 | 2.22 | yesterday I got up early and I made coffee then I went to the park with my dog it was very nice weather and we walked for one hour after that I came back home and cooked breakfast for my wife |
| 41 | да | completed | fix | 0.88 | 46 | 1.21 | yesterday I got up early and I made coffee then I went to the park with my dog it was very nice weather and we walked for one hour after that I came back home and cooked breakfast for my wife |
| 43 | да | completed | fix | 0.95 | 48 | 1.67 | I want to tell you about my trip to the mountains last summer we drove four hours to Flagstaff and we stayed in a small hotel near the forest every morning we walked on the trails and in the evening we ate pizza |
| 43 | да | completed | fix | 0.95 | 48 | 0.91 | I want to tell you about my trip to the mountains last summer we drove four hours to Flagstaff and we stayed in a small hotel near the forest every morning we walked on the trails and in the evening we ate pizza |
| 37 | нет | completed | none | 0.0 | 5 | 0.94 | OK |
| 37 | нет | completed | none | 0.0 | 5 | 1.03 | OK |
| 39 | нет | completed | fix | 0.95 | 43 | 0.79 | I work as an engineer in a big company I write programs for computers every day I have many meetings and sometimes they are boring but I like my colleagues they are very friendly and help me a lot |
| 39 | нет | completed | none | 1.0 | 43 | 1.01 | I work as an engineer in a big company I write programs for computers every day I have many meetings and sometimes it is boring but I like my colleagues they are very friendly and help me a lot |
| 43 | да | completed | fix | 0.93 | 48 | 1.52 | last weekend my son and I went to the lake we wanted to catch fish but the water was very cold and the fish didn't want to eat so we caught nothing but we had a good time and we saw two eagles |
| 43 | да | completed | fix | 0.93 | 48 | 1.02 | last weekend my son and I went to the lake we wanted to catch fish but the water was very cold and the fish didn't want to eat so we caught nothing but we had a good time and we saw two eagles |
| 34 | нет | completed | none | 1.0 | 39 | 1.21 | In the morning I usually drink coffee and read the news on my phone then I take a shower and drive to my office it takes about twenty minutes when there is no traffic. |
| 34 | нет | completed | none | 1.0 | 38 | 1.45 | In the morning I usually drink coffee and read the news on my phone then I take a shower and drive to my office it takes about twenty minutes when there is no traffic |

