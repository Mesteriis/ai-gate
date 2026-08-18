# ИИ Врата (AiGate)

Локальный OpenAI-совместимый **AI-шлюз, менеджер AI-ресурсов и policy-роутер** для
Android. Приложение превращает телефон в маршрутизатор запросов к разным провайдерам
LLM (OpenAI, Anthropic, Gemini, DeepSeek, локальная Ollama, OpenRouter и любые
OpenAI-совместимые эндпоинты) с автоматическим выбором модели, учётом квот/бюджета и
failover.

> **Идея и вдохновение взяты из [QiTong AI Gateway](https://github.com/qtgf520/qitong-ai-gateway)**
> (綦桐AI网关, Apache-2.0). AiGate — форк на базе версии `v3.18.16` (@ `7d7e6ed`),
> из которого удалён «комбайн» (чат, память, персонаж, скиллы, групповой чат,
> веб-поиск), усилена безопасность и добавлен слой управления AI-ресурсами.

**Статус:** `v0.1.0` — рабочий локальный шлюз + AI Resource Manager.

## Позиционирование

AiGate — это инфраструктура доступа к AI, а не AI-клиент. Три независимых слоя:

```
AUTH      →  API-ключи / OAuth / CredentialStore (Keystore)
QUOTA     →  остатки / лимиты / сброс / бюджеты / цены / история
ROUTING   →  выбор оптимального ресурса (скорость / цена / квота / здоровье)
```

Чат, агенты, документы, OCR, память — это задача приложений-потребителей, которые
ходят в AiGate по локальному API. Сам шлюз в это не лезет.

## Возможности

**Шлюз**
- Локальный HTTP-сервер (Ktor CIO) с OpenAI-совместимым API: `/v1/models`,
  `/v1/chat/completions` (стриминг и обычный), `/v1/embeddings`, а также
  Anthropic `/v1/messages`, Gemini `generateContent`, images/audio/rerank и др.
- Виртуальная модель `auto` — шлюз сам выбирает модель (замеры скорости + failover).
  Легаси-идентификатор `qtai-sj` принимается как алиас.
- Именованные стратегии маршрутизации: **Быстро / Дёшево / Качество / Локально /
  По квоте / Авто**.
- Правила маршрутизации (route/block), управление inbound-ключами, исходящие
  прокси (HTTP/SOCKS5) с импортом подписок.

**Безопасность**
- По умолчанию слушает **только `127.0.0.1`** (loopback без авторизации).
- Отдельный защищённый **LAN-режим**: пароль = постоянный Bearer-токен (constant-time
  сравнение), никакого обхода авторизации для RFC1918.
- Секреты — в **Android Keystore** (AES-256-GCM через CryptoBox); в `Provider`/Room/
  бэкапе/логах их нет (только `credentialId`).
- `network_security_config`: cleartext только на loopback/локальную сеть, апстрим — HTTPS.

**Менеджер AI-ресурсов**
- **Квоты** с честными источниками: `PROVIDER_API` (реальные данные провайдера) vs
  `LOCAL_USAGE`/`USER_CONFIGURED`/`ESTIMATED` (расчёт AiGate) — различаются в UI;
  отсутствие данных показывается как «недоступно», а не выдуманным нулём.
- **Цены и стоимость**: каталог цен (встроенный + пользовательский), оценочная
  стоимость из usage × pricing (всегда помечена как оценка, ≠ баланс провайдера).
- **Бюджеты** и **Resource Pressure** (Свободно/Нормально/Экономить/Критично) с учётом
  времени до сброса и темпа расхода — локальная рекомендация.
- **История расхода** по дням и прогноз на месяц.
- **Домашний виджет** (локальный снимок квот, без сетевого поллинга).
- **Опциональные уведомления** о низком остатке (пороги настраиваются).
- **OAuth Credential Framework** (single-flight refresh) — каркас для OAuth-провайдеров.
- **CLI-сессии** (эксперим.): импорт сохранённой сессии CLI провайдера (Codex/Gemini/
  Claude), хранение в Keystore + автообновление — как в omniroute.

## Подключение провайдеров

1. **API-ключ** (основной путь): вкладка **Провайдеры → Добавить провайдера**,
   укажите тип, Base URL и ключ. Работает с OpenAI, Anthropic, Gemini, DeepSeek,
   OpenRouter, локальной Ollama (без ключа) и любым OpenAI-совместимым сервисом.
   Ключ шифруется в Keystore.
2. **Реальный баланс**: у **OpenRouter** есть публичный endpoint остатка —
   добавьте его как провайдера, и на экране «Ресурсы и квоты» появится реальный
   `PROVIDER_API`-баланс. Для остальных провайдеров показывается локальный расчёт
   расхода (реальный баланс подписки они по API не отдают).
3. **Ollama**: Base URL вида `http://<host>` + порт `11434`, ключ не нужен
   (cleartext к локальной сети разрешён).

4. **CLI-сессии** (эксперим., по образцу omniroute): «Ресурсы и квоты → CLI-сессии».
   Если у вас на десктопе авторизован CLI провайдера (Codex CLI, Gemini CLI, Claude
   Code), можно импортировать его сохранённую OAuth-сессию (`~/.codex/auth.json`,
   `~/.gemini/oauth_creds.json` и т.п.): вставьте JSON, AiGate сохранит токены в
   Keystore, будет переиспользовать как Bearer и **автоматически обновлять**
   (single-flight refresh переживает перезапуск). Base URL и параметры refresh
   (token URL, client_id) задаёте вы — приватные эндпоинты не захардкожены.

> **Про Codex / ChatGPT- и Claude-подписки.** Публичного OAuth-flow и API остатка
> подписки для сторонних клиентов OpenAI/Anthropic не дают, поэтому «Codex 80%» через
> обычный API получить нельзя. Путь через **CLI-сессию** (п. 4) работает, но
> использование приватных эндпоинтов провайдера сторонним клиентом может нарушать их
> Terms — это на ваш риск и помечено как экспериментальное. Всегда доступный
> безопасный путь — их **API-ключи** (`api.openai.com`, `api.anthropic.com`) как
> обычные провайдеры с учётом usage и оценочной стоимости.

## Домашний виджет

Долгое нажатие на рабочем столе → **Виджеты** → **ИИ Врата** → перетащите виджет
квот. Он читает локальный снимок из БД и обновляется после запросов и по расписанию
(WorkManager), без постоянного поллинга.

## Локальный API — быстрый старт

```bash
# на телефоне запущен шлюз (порт 8889 по умолчанию)
curl http://127.0.0.1:8889/v1/models
curl http://127.0.0.1:8889/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"auto","messages":[{"role":"user","content":"привет"}],"stream":true}'
```

В LAN-режиме укажите заголовок `Authorization: Bearer <ваш-пароль>`.

## Сборка

Требуется JDK 17 и Android SDK (compileSdk/targetSdk 37).

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=$HOME/Library/Android/sdk   # или свой путь к SDK
./gradlew assembleDebug          # debug APK
./gradlew lintDebug test         # линт + юнит-тесты
```

APK появится в `app/build/outputs/apk/debug/`. Для релизной подписи задайте
`AIGATE_KEYSTORE_PATH`, `AIGATE_STORE_PASSWORD`, `AIGATE_KEY_ALIAS`,
`AIGATE_KEY_PASSWORD` (ключ в репозиторий не коммитится).

## Отличия от апстрима (QiTong)

- Удалены чат-клиент, память/персонаж, скиллы/инструменты, групповой чат и веб-поиск —
  оставлен только API-шлюз.
- Виртуальная модель `qtai-sj` → `auto`; бренд/пакет → `com.aigate.router`.
- Loopback по умолчанию + защищённый LAN-режим; убран обход авторизации для RFC1918.
- Секреты → Android Keystore; из `Provider`/бэкапа/логов секрет удалён.
- Закрыта дыра с отключённой проверкой TLS у HTTPS-прокси; добавлен
  `network_security_config`.
- Нормализован OpenAI-совместимый API (error-envelope, статусы, route-action,
  tool_calls, идемпотентные ретраи, реальная версия).
- Удалена автоотправка крашей в чужой GitHub и чтение токена из `/tmp/.git_token`.
- Полностью русский интерфейс, тема «врат», адаптивная раскладка (NavigationRail на
  большом экране).
- Добавлен слой AI Resource Manager (квоты, цены, бюджеты, routing по ресурсам,
  история, виджет, уведомления, OAuth-каркас).

## Лицензия

Apache License 2.0 — см. [LICENSE](LICENSE) и [NOTICE](NOTICE). Форк сохраняет
лицензию исходного проекта QiTong AI Gateway.

---

## English

**AiGate (ИИ Врата)** is a local, OpenAI-compatible **AI gateway, AI-resource manager
and policy router** for Android. It turns the phone into a request router across
multiple LLM providers (OpenAI, Anthropic, Gemini, DeepSeek, Ollama, OpenRouter and
any OpenAI-compatible endpoint) with automatic model selection, quota/budget awareness
and failover.

Idea and inspiration are taken from
[QiTong AI Gateway](https://github.com/qtgf520/qitong-ai-gateway) (Apache-2.0). AiGate
is a fork of upstream `v3.18.16` (@ `7d7e6ed`) with the non-gateway "combine" features
removed, security hardened (loopback default, secure LAN, Keystore secrets), and an
AI-resource layer added (quotas, pricing/cost, resource-aware routing, usage history,
home widget, notifications, OAuth framework).

**Provider connection.** Add providers by API key (Providers → Add). OpenRouter exposes
a real balance endpoint; other providers show locally-computed usage. **ChatGPT/Codex
and Claude subscription quotas cannot be connected** — those vendors expose no public
OAuth flow or subscription-quota API to third-party clients (kept experimental by
design); their **API keys** work as normal providers.

Build with JDK 17 + Android SDK: `./gradlew assembleDebug`. Licensed under Apache-2.0
(see `LICENSE`/`NOTICE`); the fork keeps the upstream license.
