# T32 — Единый путь settings.hooks.json (импортёр ↔ preflight)

- **Веха:** M5
- **Зависит от:** T18, T24
- **Спека:** SD §8, SDD FR-1.4/SR-8; ревью импортёра 2026-07-11 №1

## Скоуп

- Канонический путь — `<project>/.gigacode/settings.hooks.json` (корень харнесса):
  так ждут `preflight.py`, `HarnessLayout.SETTINGS_FILE` и хэш-манифест. Сейчас
  `ImportSession.result()` кладёт файл в `.gigacode/hooks/settings.hooks.json` —
  свежеимпортированный проект не проходит preflight и останавливается с
  `HARNESS_PREFLIGHT`, пока файл не перенесут руками.
- `ImportSession` пишет файл в корень харнесса; `ImportEndToEndTest` закрепляет
  новый путь.
- `preflight.py`: если файла нет в корне, но есть `hooks/settings.hooks.json` —
  явное сообщение «найден в hooks/, перенесите в корень харнесса» (миграция уже
  импортированных проектов).

## Вне скоупа

- Изменение формата/семантики самого `settings.hooks.json`.

## Приёмка

- [ ] e2e: импорт реальной обвязки → deploy → preflight ok без ручных действий
- [ ] preflight на «старом» расположении печатает понятное сообщение с путём миграции
- [ ] ImportEndToEndTest проверяет корневой путь, старый путь не создаётся
