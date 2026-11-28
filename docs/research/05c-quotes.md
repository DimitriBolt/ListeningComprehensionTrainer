# Кавычки — пара (S05c, D63): нарезка 90 ответов S02c до и после

Сгенерировано `python tools/probe/quotes_check.py` (без ключа и сети). Ответы — `android_app/app/src/test/resources/02c-replies.txt`; до — `tools/probe/fixtures/02c_plans_s05b.json`, после — `tools/probe/fixtures/02c_plans.json` (его же сверяет Kotlin-тест `SpeechPlannerCopyTest`).

- ответов: 90; с двойными кавычками: 12
- нарезка или конец реплики (D48) изменились: **7**, все — с кавычками; без кавычек изменилось: **0**
- с кавычками, но без изменений: 5

Пауза — в скобках, мс. `·` — граница группы.

## Ответ 4

> That sounds lovely! A natural way to say it is: “I went to the park yesterday. It was really nice.” What did you enjoy most?

- до: That sounds lovely! (1000) · A natural way (250) · to say it is: (600) · “I went to the park yesterday. (1000) · It was really nice.” (250) · What did you enjoy most? (1000)
- после: That sounds lovely! (1000) · A natural way (250) · to say it is: (600) · “I went to the park yesterday. (1000) · It was really nice.” (1000) · What did you enjoy most? (1000)

## Ответ 6

> That sounds lovely! “I went to the park yesterday. It was very nice” is correct English. What did you do there?

- до: That sounds lovely! (1000) · “I went to the park yesterday. (1000) · It was very (250) · nice” is correct English. (1000) · What did you do there? (1000)
- после: That sounds lovely! (1000) · “I went to the park yesterday. (1000) · It was (250) · very nice” is correct English. (1000) · What did you do there? (1000)

## Ответ 7

> I think you mean, “Yesterday I went to the shop and bought some apples.” We use the past tense because it happened yesterday. What kind of apples did you buy?

- до: I think you mean, (600) · “Yesterday I went to the shop (250) · and bought some apples.” (250) · We use the past tense (250) · because it happened yesterday. (1000) · What kind of (250) · apples did you buy? (1000)
- после: I think you mean, (600) · “Yesterday I went to the shop (250) · and bought some apples.” (1000) · We use the past tense (250) · because it happened yesterday. (1000) · What kind of (250) · apples did you buy? (1000)

## Ответ 8

> I think you mean: “Yesterday I went to the shop and bought some apples.” We use the past tense because it happened yesterday. What kind of apples did you buy?

- до: I think you mean: (600) · “Yesterday I went to the shop (250) · and bought some apples.” (250) · We use the past tense (250) · because it happened yesterday. (1000) · What kind of (250) · apples did you buy? (1000)
- после: I think you mean: (600) · “Yesterday I went to the shop (250) · and bought some apples.” (1000) · We use the past tense (250) · because it happened yesterday. (1000) · What kind of (250) · apples did you buy? (1000)

## Ответ 9

> I think you mean, “Yesterday I went to the shop and bought some apples.” We use the past tense **went** and **bought** for something that happened yesterday.
> 
> What kind of apples did you buy?

- до: I think you mean, (600) · “Yesterday I went to the shop (250) · and bought some apples.” We (250) · use the past tense **went** (250) · and **bought** for something (250) · that happened yesterday. (1600) · What kind of (250) · apples did you buy? (1000)
- после: I think you mean, (600) · “Yesterday I went to the shop (250) · and bought some apples.” (1000) · We use the past tense **went** (250) · and **bought** for something (250) · that happened yesterday. (1600) · What kind of (250) · apples did you buy? (1000)

## Ответ 10

> I think you mean: “Yesterday I **went** to the shop and **bought** some apples.” We use the past tense because you said “yesterday.” How many apples did you buy?

- до: I think you mean: (600) · “Yesterday I **went** to the shop (250) · and **bought** some apples.” (250) · We use the past tense (250) · because you said “yesterday.” How (250) · many apples did you buy? (1000)
- после: I think you mean: (600) · “Yesterday I **went** to the shop (250) · and **bought** some apples.” (1000) · We use the past tense (250) · because you said “yesterday.” (1000) · How many apples did you buy? (1000)

## Ответ 12

> I think you mean: **“Yesterday, I went to the shop and bought some apples.”**
> 
> Because it happened yesterday, use the past tense: **go → went** and **buy → bought**.

- до: I think you mean: (600) · **“Yesterday, (600) · I went to the shop (250) · and bought some apples.”** (1600) · Because it happened yesterday, (600) · use the past tense: (600) · **go → went** (250) · and **buy → bought**. (1000)
- после: I think you mean: (600) · **“Yesterday, I went to the shop (250) · and bought some apples.”** (1600) · Because it happened yesterday, (600) · use the past tense: (600) · **go → went** (250) · and **buy → bought**. (1000)

## С кавычками, без изменений

- ответ 11: Say: (600) · **“Yesterday I went to the shop (250) · and bought some apples.”** (1600) · Because it happened in the past, (600) · use **went** (250) · and **bought**. (1000) · Can you try (250) · another sentence about yesterday? (1000)
- ответ 16: **“Outage”** means a time (250) · when a service (250) · or supply is not available. (1600) · For example: (600) · **“There was a power outage”** (250) · means the electricity stopped working. (1600) · Does (250) · that make sense? (1000)
- ответ 17: An **outage** is a time (250) · when a service stops working. (1000) · For example, (600) · “There was a power outage” means (250) · the electricity stopped working for a (250) · while. (1600) · Would you like another example? (1000)
- ответ 18: An **outage** is a time (250) · when a service stops working (250) · or is unavailable. (1600) · For example: (600) · “There was a power outage” means (250) · the electricity stopped working for a (250) · while. (1600) · Does (250) · that make sense? (1000)
- ответ 38: The biggest recent SpaceX story is Starship’s (250) · first successful catch of an upper stage. (1000) · On November 14, (600) · Ship 42 returned from orbit (250) · and was caught by (250) · the launch tower’s “chopstick” arms. (1000) · SpaceX says it will put the historic spacecraft (250) · on public display rather than fly it again. (1600) · There’s also a legal story today: (600) · Virginia (250) · and Oregon sued (250) · several Elon Musk companies, (600) · including SpaceX (250) · and its AI subsidiary, (600) · over allegations (250) · that the Grok chatbot (250) · enabled harmful sexualized images. (1000) · The companies dispute the claims (250) · and say they have safeguards. (1600) · SpaceX has also been launching (250) · national-security payloads for the U.S. (1000) · Space Force (250) · and National Reconnaissance Office. (1600) · Would you like to (250) · hear more about Starship (250) · or the lawsuits? (1000)
