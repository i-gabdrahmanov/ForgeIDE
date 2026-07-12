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

- [x] e2e: импорт реальной обвязки → deploy → preflight ok без ручных действий —
      `ImportEndToEndTest#importingForgeliteAgainstTheSampleScaffoldProducesAValidPipeline`
      пишет в корень харнесса, что теперь и `HarnessManifest`/`preflight.py` ждут напрямую
- [x] preflight на «старом» расположении печатает понятное сообщение с путём миграции —
      `DefaultHarnessGuardTest#deployingAHarnessWithSettingsAtTheOldHooksLocationFailsPreflightWithAMigrationMessage`
- [x] ImportEndToEndTest проверяет корневой путь, старый путь не создаётся

## Реализация

- **`ImportSession.result()`** писал `settings.hooks.json` под
  `.gigacode/hooks/`; изменено на `.gigacode/settings.hooks.json` (корень харнесса) —
  тот же путь, что `HarnessLayout.SETTINGS_FILE`, `HarnessManifest.scan` и `preflight.py`
  уже используют. Формат/содержимое файла не менялись — только путь назначения при записи.
- **`preflight.py`**: если `<harness>/settings.hooks.json` не найден, но существует
  `<harness>/hooks/settings.hooks.json`, сообщение явно называет оба пути и просит перенести
  файл в корень (вместо общего «missing settings.hooks.json»); наведён на этот файл через
  `docs/tasks/T32-hooks-path-unification.md`.
- **`ImportEndToEndTest`**: добавлена проверка `doesNotExist()` на старый путь рядом с
  `isRegularFile()` на новый, чтобы регресс на старое поведение падал явно.
- **`docs/manual.md`**: убраны предупреждения/обходной путь про «settings.hooks.json ложится
  не туда» (были актуальны только пока баг не пофикшен); строка про `HARNESS_PREFLIGHT` в
  §12 и обходной путь в §5 теперь описывают только миграцию уже импортированных до T32
  проектов (preflight сам укажет путь переноса).
