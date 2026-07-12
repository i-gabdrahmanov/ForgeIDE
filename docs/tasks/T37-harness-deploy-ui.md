# T37 — Деплой обвязки из UI

- **Веха:** M5
- **Зависит от:** T18, T24
- **Спека:** SDD FR-1.4/SR-7, SD §9 («деплой хуков кнопкой»); ревью 2026-07-11 (manual §5)

## Скоуп

- Сейчас `HarnessGuardPort.deploy()` не вызывается ни из одного экрана — кнопки
  нет, и первый прогон любого проекта останавливается на `HARNESS_PREFLIGHT`;
  единственный обходной путь — трюк с сохранением файла обвязки через редактор
  плитки (который к тому же помечает preflight пройденным без запуска
  `preflight.py`).
- Кнопка **Deploy harness** на карточке проекта: запускает
  `deploy.sh` + `preflight.py`, показывает результат (ok / список проблем).
- Статус обвязки на карточке проекта: задеплоена ли, пройден ли preflight,
  дата baseline.
- Прогон, вставший на `HARNESS_PREFLIGHT`, подсказывает причину и путь:
  «Задеплойте обвязку на карточке проекта» (или прямая кнопка из Run view).
- После успешного импорта (T24) — предложение сразу задеплоить.

## Вне скоупа

- Изменение семантики drift/accept/rollback (T18 — как есть).
- Автодеплой без участия человека (деплой — осознанное действие, SR-7).

## Приёмка

- [x] свежий проект: Import scaffold → Deploy harness → Start run проходит без обходных путей
- [x] проваленный preflight показывает вывод `preflight.py` списком проблем
- [x] карточка проекта отражает статус обвязки; прогон на `HARNESS_PREFLIGHT` ведёт пользователя к деплою

## Реализация

- `HarnessGuardPort.PreflightStatus` (`core`): третье поле `Optional<Instant> deployedAt` — дата
  baseline для карточки проекта. `DefaultHarnessGuard.preflightStatus()` берёт его из уже
  существующего `HarnessRegistry.Entry.deployedAt()`; пустое, если харнесс не деплоился ни разу.
- `ProjectDetailView` (`ui`): новый раздел **Harness** (по образцу уже существующего
  `runtimesSection`) — кнопка **Deploy harness**, статус-лейбл и (при проваленном preflight)
  список проблем построчно из вывода `preflight.py`. Асинхронно (virtual thread + `Platform.
  runLater`), как и остальные секции карточки. Текст статуса/список проблем вынесены в чистый
  хелпер `HarnessStatusText` (по образцу `FlagsText`) — тестируется без JavaFX-тулкита.
- `ProjectsController.showImport`: после успешного импорта — `Alert.CONFIRMATION` «Deploy the
  harness now?»; согласие передаёт `autoDeployHarness=true` в `ProjectDetailView`, который сам же
  «нажимает» ту же кнопку `Deploy harness` (`Button#fire()`) сразу после отрисовки — один код,
  а не дублирование логики деплоя в контроллере.
- `RunView` (`ui`): баннер под шапкой (по образцу T36's `dirtyTreeBanner`) для
  `PAUSED(HARNESS_PREFLIGHT)` — статичный текст-подсказка + кнопка **Go to project card**
  (переиспользует код кнопки «← Back»). Синхронный, без загрузки аудит-цепочки: причина остановки
  уже есть в каждом `RunSnapshot`, и `HARNESS_PREFLIGHT`-остановка в рамках одного прогона не
  снимается сама.
- Тесты: `DefaultHarnessGuardTest` — `deployedAt` пусто/непусто; новый
  `HarnessDeployPipelineTest` (`runtime`) — end-to-end через реальный `DefaultHarnessGuard` +
  `PipelineEngine`: без деплоя прогон встаёт `PAUSED(HARNESS_PREFLIGHT)`, после `deploy()` тот же
  харнесс пропускает прогон дальше. Новый `HarnessStatusTextTest` (`ui`) — чистая логика форматирования.
- Ручная проверка: реальный запуск `./gradlew :ui:run`, две тестовые «карточки» на throwaway
  git-репозиториях — подтверждены все три пункта приёмки (скриншоты не сохранены, тестовые
  проекты и `~/.forgeide` тестовых данных удалены после проверки).
