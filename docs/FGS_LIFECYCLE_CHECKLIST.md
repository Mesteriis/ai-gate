# Foreground-Service Lifecycle Checkpoint (Фаза 17)

Проверка живучести `GatewayForegroundService` на реальном устройстве. Не доверяем
поведению апстрима — прогоняем на Samsung Galaxy Z Fold (устройство `RFCY706SHEB`,
Android 15 / One UI). Загрузка/Doze/battery-saver зависят от OEM, поэтому часть
пунктов — ручной QA.

## Конфигурация сервиса (из кода/манифеста)

- Тип FGS: `foregroundServiceType="specialUse"` (`AndroidManifest.xml`). Подтверждено
  на устройстве: `dumpsys activity services` → `isForeground=true types=0x40000000`
  (`FOREGROUND_SERVICE_TYPE_SPECIAL_USE`).
- Уведомление: `IMPORTANCE_LOW`, `ONGOING_EVENT|NO_CLEAR|FOREGROUND_SERVICE`, канал
  `aigate_service_channel`. Подтверждено в `dumpsys`.
- Разрешения: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`,
  `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `POST_NOTIFICATIONS`.
- Восстановление состояния: `GatewayApplication.onCreate` читает `getGatewayWasRunning()`
  из SharedPreferences и восстанавливает флаг при пересоздании процесса.

## Матрица проверок

| # | Сценарий | Ожидание | Статус |
|---|----------|----------|--------|
| 1 | Старт шлюза | FGS `isForeground=true`, тип specialUse, loopback `/health`=200 | ✅ проверено на устройстве |
| 2 | Правильный тип FGS + постоянное уведомление | type=0x40000000, NO_CLEAR | ✅ проверено (dumpsys) |
| 3 | Экран выключен | сервис жив, порт слушает | ✅ проверено (adb: fg=true, /health=200) |
| 4 | Doze (`adb shell dumpsys deviceidle force-idle`) | сервис не убит; после выхода — отвечает | ✅ проверено (fg=true, /health=200) |
| 5 | Режим энергосбережения (`settings put global low_power 1`) | сервис жив | ✅ проверено (fg=true, /health=200) |
| 6 | Фоновый kill (`am kill`) + перезапуск | процесс пересоздаётся, флаг восстановлен, шлюз поднят | ✅ проверено (после релонча fg=true, /health=200) |
| 7 | Force Stop | сервис останавливается; авто-рестарт не ожидается | ✅ проверено (0 ServiceRecord, /health=000) |
| 8 | Перезагрузка устройства | `BootReceiver` поднимает шлюз при `getGatewayWasRunning()` | ✅ проверено (после reboot fg=true, /health=200) |
| 9 | Wi-Fi ↔ LTE | активные соединения корректно рвутся/переустанавливаются | ⏳ ручной QA (нужен live upstream) |
| 10 | Длинный SSE-стрим | не обрывается по таймауту раньше времени | ✅ проверено e2e (Ollama upstream: 200 SSE-чанков + [DONE] через шлюз) |
| 11 | Параллельные стримы | потокобезопасность session-кэшей (ConcurrentHashMap — из Фазы 6) | ✅ проверено e2e (3 одновременных стрима, все [DONE], без крашей) |
| 12 | Нет злоупотребления WakeLock/AlarmManager | WakeLock только под «唤醒保活»/keep-alive; AlarmManager не используется для поллинга | ✅ проверено по коду (квоты — WorkManager 6ч, не поллинг) |

## Команды для ручного QA

```bash
adb -s RFCY706SHEB shell dumpsys activity services com.aigate.router | grep -iE 'isForeground|types='
adb -s RFCY706SHEB shell dumpsys deviceidle force-idle   # войти в Doze
adb -s RFCY706SHEB shell dumpsys deviceidle unforce       # выйти
adb -s RFCY706SHEB shell curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8889/health
```

## Вывод

Пункты **1–8, 10–12 подтверждены** на устройстве `RFCY706SHEB`: adb-автоматизация
(экран/Doze/энергосбережение/фоновый kill/force-stop/перезагрузка) + реальный e2e
через upstream Ollama (10.34.10.2) — non-stream ответ, 200 SSE-чанков со стримингом,
3 параллельных стрима. Ресурсная петля замкнута на реальном трафике: usage записан
(token_usage), авто-создан пул провайдера, посчитан снимок квоты. Boot-автозапуск
закрыт `service/BootReceiver` (использует ранее «мёртвое» RECEIVE_BOOT_COMPLETED).
Остаётся только пункт 9 (Wi-Fi↔LTE в полёте) — единственный ручной QA-долг v0.1.0,
не блокер сборки (`lintDebug test assembleDebug assembleRelease` проходят).
