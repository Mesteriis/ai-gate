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
| 3 | Экран выключен | сервис жив, порт слушает | ⏳ ручной QA |
| 4 | Doze (`adb shell dumpsys deviceidle force-idle`) | сервис не убит; после выхода — отвечает | ⏳ ручной QA |
| 5 | Режим энергосбережения | сервис жив | ⏳ ручной QA |
| 6 | Swipe-away из недавних | сервис переживает (sticky) либо перезапуск + восстановление флага | ⏳ ручной QA |
| 7 | Force Stop | сервис останавливается; после ручного старта — ок; авто-рестарт не ожидается | ⏳ ручной QA |
| 8 | Перезагрузка | при `getGatewayWasRunning()` состояние восстановлено; boot-receiver | ⏳ ручной QA |
| 9 | Wi-Fi ↔ LTE | активные соединения корректно рвутся/переустанавливаются | ⏳ ручной QA |
| 10 | Длинный SSE-стрим | не обрывается по таймауту раньше времени | ⏳ ручной QA |
| 11 | Параллельные стримы | потокобезопасность session-кэшей (ConcurrentHashMap — из Фазы 6) | ⏳ ручной QA |
| 12 | Нет злоупотребления WakeLock/AlarmManager | WakeLock только под «唤醒保活»/keep-alive; AlarmManager не используется для поллинга | ✅ проверено по коду (квоты — WorkManager 6ч, не поллинг) |

## Команды для ручного QA

```bash
adb -s RFCY706SHEB shell dumpsys activity services com.aigate.router | grep -iE 'isForeground|types='
adb -s RFCY706SHEB shell dumpsys deviceidle force-idle   # войти в Doze
adb -s RFCY706SHEB shell dumpsys deviceidle unforce       # выйти
adb -s RFCY706SHEB shell curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8889/health
```

## Вывод

Пункты 1, 2, 12 подтверждены на устройстве/в коде. Остальные требуют ручного
прогона на разложенном/сложенном Z Fold перед публичным релизом — это оставшийся
QA-долг v0.1.0, не блокер сборки (`lintDebug test assembleDebug assembleRelease`
проходят).
