# Скриншоты

Все снимки сделаны на эмуляторе (Android 16) со свежими демо-данными: телефонный
форм-фактор — `wm size 1080x2340`, `wm density 420`; большой экран Z Fold —
родные `1768x2208` при `wm density 380` (ширина ≥720dp включает боковую
NavigationRail). Темы — системные светлая и тёмная.

## Телефон

| Экран | Светлая | Тёмная |
|---|---|---|
| Обзор | ![Обзор](images/app/01-home-light.png) | ![Обзор, тёмная](images/app/01-home-dark.png) |
| Ресурсы · Провайдеры | ![Ресурсы](images/app/02-resources-light.png) | ![Ресурсы, тёмная](images/app/02-resources-dark.png) |
| Ресурсы · Модели | ![Модели](images/app/06-models-light.png) | — |
| Маршруты | ![Маршруты](images/app/03-routes-light.png) | ![Маршруты, тёмная](images/app/03-routes-dark.png) |
| Активность · Графики | ![Активность](images/app/04-activity-light.png) | ![Активность, тёмная](images/app/04-activity-dark.png) |
| Активность · Журнал | ![Журнал](images/app/07-journal-light.png) | — |
| Настройки | ![Настройки](images/app/05-settings-light.png) | ![Настройки, тёмная](images/app/05-settings-dark.png) |
| Настройки (низ) | ![Настройки, низ](images/app/08-settings2-light.png) | — |
| О программе | ![О программе](images/app/09-about-light.png) | — |

## Большой экран (Z Fold развёрнут)

При ширине от 720dp приложение перестраивается: вместо нижней панели — боковая
NavigationRail с брендом.

| Экран | Светлая | Тёмная |
|---|---|---|
| Обзор | ![Обзор](images/app/fold/01-home-light.png) | ![Обзор, тёмная](images/app/fold/01-home-dark.png) |
| Ресурсы | ![Ресурсы](images/app/fold/02-resources-light.png) | ![Ресурсы, тёмная](images/app/fold/02-resources-dark.png) |
| Маршруты | ![Маршруты](images/app/fold/03-routes-light.png) | ![Маршруты, тёмная](images/app/fold/03-routes-dark.png) |
| Активность | ![Активность](images/app/fold/04-activity-light.png) | ![Активность, тёмная](images/app/fold/04-activity-dark.png) |
| Настройки | ![Настройки](images/app/fold/05-settings-light.png) | ![Настройки, тёмная](images/app/fold/05-settings-dark.png) |

## Виджеты домашнего экрана

Обложка — курируемая раскладка, как в [макетах](design/widgets/); галерея —
каждый виджет во всех поддерживаемых ярусах, пять виджетов на страницу.

| Страница | Светлая | Тёмная |
|---|---|---|
| Обложка | ![Обложка](images/widgets/cover-light.png) | ![Обложка, тёмная](images/widgets/cover-dark.png) |
| Галерея 1 — Ресурсы | ![1](images/widgets/gallery-1-light.png) | ![1, тёмная](images/widgets/gallery-1-dark.png) |
| Галерея 2 — Токены, Расход, Доли | ![2](images/widgets/gallery-2-light.png) | ![2, тёмная](images/widgets/gallery-2-dark.png) |
| Галерея 3 — Вызовы, Статус, Окна | ![3](images/widgets/gallery-3-light.png) | ![3, тёмная](images/widgets/gallery-3-dark.png) |
| Галерея 4 — Темп, Трафик, Модели | ![4](images/widgets/gallery-4-light.png) | ![4, тёмная](images/widgets/gallery-4-dark.png) |
| Галерея 5 — Ключи, Скорость | ![5](images/widgets/gallery-5-light.png) | ![5, тёмная](images/widgets/gallery-5-dark.png) |

HTML-макеты, из которых собран комплект виджетов, лежат в
[docs/design/widgets](design/widgets/) — самостоятельные страницы со всеми
размерами в обеих темах (точка входа — `index.html`).

## Как переснять

```bash
# эмулятор в телефонном виде
adb shell wm size 1080x2340 && adb shell wm density 420
# демо-данные + запуск шлюза, затем обложка и страницы галереи виджетов
adb shell am start -n com.aigate.router/.widget.WidgetGalleryActivity \
  --activity-clear-top --es mode cover --ez seed true
adb shell am start -n com.aigate.router/.widget.WidgetGalleryActivity \
  --activity-clear-top --es mode gallery --ei page 1   # страницы 1..5
# тёмная тема
adb shell cmd uimode night yes
# снимок
adb exec-out screencap -p > shot.png
```

`WidgetGalleryActivity` есть только в debug-сборке и рисует настоящие
RemoteViews. GIF-туры собираются ffmpeg из последовательности кадров:

```bash
ffmpeg -framerate 5/7 -i f%02d.png -vf "scale=360:-1:flags=lanczos,split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle" -loop 0 tour.gif
```
