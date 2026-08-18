# ИИ Врата (AiGate)

Локальный OpenAI-совместимый AI-шлюз для Android. Приложение превращает телефон в
маршрутизатор запросов к разным провайдерам LLM (OpenAI, Anthropic, Gemini,
DeepSeek, локальная Ollama и любые OpenAI-совместимые эндпоинты) с автоматическим
выбором самой быстрой модели и failover.

> **Идея и вдохновение взяты из [QiTong AI Gateway](https://github.com/qtgf520/qitong-ai-gateway)**
> (綦桐AI网关, Apache-2.0). AiGate — форк на базе версии `v3.18.16` (@ `7d7e6ed`),
> из которого удалён «комбайн» (чат, память, персонаж, скиллы, групповой чат,
> веб-поиск) и который сфокусирован на безопасном локальном API-роутере.

**Статус:** ранняя версия `0.1.0`, в активной разработке.

## Возможности

- Локальный HTTP-сервер (Ktor CIO) с OpenAI-совместимым API: `/v1/models`,
  `/v1/chat/completions` (стриминг и обычный), `/v1/embeddings`, а также
  Anthropic `/v1/messages`, Gemini `generateContent`, images/audio/rerank и др.
- Виртуальная модель `auto` — шлюз сам выбирает самую быструю доступную модель
  (с историей замеров скорости и failover). Легаси-идентификатор `qtai-sj`
  принимается как алиас.
- Мультипровайдерная маршрутизация, правила маршрутизации (route/block).
- Управление inbound-ключами доступа.
- Поддержка исходящих прокси (HTTP/SOCKS5) с импортом подписок.
- Статистика токенов/трафика, история скорости, резервное копирование
  (GZIP + опциональный AES-256).

## Сборка

Требуется JDK 17 и Android SDK (compileSdk/targetSdk 37).

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=$HOME/Library/Android/sdk   # или свой путь к SDK
./gradlew assembleDebug
```

APK появится в `app/build/outputs/apk/debug/`.

Для релизной подписи задайте переменные окружения `AIGATE_KEYSTORE_PATH`,
`AIGATE_STORE_PASSWORD`, `AIGATE_KEY_ALIAS`, `AIGATE_KEY_PASSWORD` (ключ в репозиторий
не коммитится).

## Отличия от апстрима (QiTong)

- Удалены чат-клиент, система памяти/персонажа, скиллы/инструменты, групповой чат
  и веб-поиск (SearXNG) — оставлен только API-шлюз и его настройка.
- Виртуальная модель переименована `qtai-sj` → `auto`.
- Идентификаторы приложения, БД, настроек и уведомлений перенесены под бренд AiGate
  (`com.aigate.router`).
- Удалена автоматическая отправка крашей в чужой GitHub-репозиторий и чтение токена
  из `/tmp/.git_token`.
- (В работе) привязка сервера к `127.0.0.1`, защищённый LAN-режим, перенос секретов
  в Android Keystore, нормализация OpenAI API, тесты, русский интерфейс.

## Лицензия

Apache License 2.0 — см. [LICENSE](LICENSE). Форк сохраняет лицензию исходного
проекта QiTong AI Gateway.

---

## English

**AiGate (ИИ Врата)** is a local, OpenAI-compatible AI gateway for Android that turns
the phone into a request router across multiple LLM providers with automatic
fastest-model selection and failover.

Idea and inspiration are taken from
[QiTong AI Gateway](https://github.com/qtgf520/qitong-ai-gateway) (Apache-2.0).
AiGate is a fork of upstream `v3.18.16` (@ `7d7e6ed`) with the non-gateway
"combine" features removed and a focus on a secure local API router.

Build with JDK 17 + Android SDK: `./gradlew assembleDebug`. Licensed under
Apache-2.0 (see `LICENSE`); the fork keeps the upstream license.
