# T38 — Зелёный CI и рабочая цепочка «импорт → deploy → preflight»

- **Веха:** M5
- **Зависит от:** T26, T32, T37
- **Спека:** SDD FR-1.4/FR-9/SR-8, SD §8/§9; повторный аудит 2026-07-12 (после мержа M5)

Обе находки аудита — одного класса: «зелёное локально, красное в реальности», потому что
ни один автотест не пересекал нужную границу (локальная машина ↔ CI-раннер; модуль
importer ↔ модуль runtime).

## Скоуп

1. **CI на GitHub никогда не был зелёным** — все 12 прогонов master (T26→T37) красные,
   PR мержились поверх красного CI. Корень (доказан по jacoco-XML): порог runtime 0.83
   снят с локального прогона, где `ClaudeAgentRuntimeSmokeTest` исполняется; в CI он
   штатно скипается (нет `/opt/homebrew/bin/claude`) → 9 строк `ClaudeAgentRuntime`
   теряют покрытие → 82.75% < 83.00% → `jacocoTestCoverageVerification` валит каждую
   сборку. Фикс: юнит-тест на `buildCommand` (бинарь не нужен) — порог не трогаем
   (правило T26: «never lower it to make a red build green»).
2. **Цепочка «импорт → deploy → preflight ok» (приёмка T32/T37) фактически не работала**
   для обвязки, чей `settings.hooks.json` ссылается на скрипты хуков — включая
   собственную фикстуру `sample-scaffold`. Складывались две причины:
   - `ImportSession.result()` копировал только `settings.hooks.json`; сами скрипты
     (`hooks/*.py`) в целевой проект не ехали;
   - `preflight.py` трактовал команду `python3 hooks/tdd-guard.py` целиком как один путь
     (`endswith(".py")` по всей строке) — даже существующий скрипт не резолвился.
   Фикс: импортёр копирует каталог хуков (кроме самого конфига, он в корне харнесса —
   T32) в `.gigacode/hooks/` с тем же `SKIP_DIRS`-фильтром, что у T34; preflight режет
   строки по пробелам и проверяет каждый токен `*.py`/`*.sh`.

## Вне скоупа

- Семантика/формат `settings.hooks.json`, манифеста, drift (T18 — как есть).
- Gradle-кэш в CI и обновление actions (Node 20 deprecation warnings) — гигиена, отдельно.
- Поведенческий разрыв `FAILED(stream)` vs `FAILED(budget)` и асимметрия
  `GateStep.question` — прежний бэклог, не трогались.

## Приёмка

- [x] runtime `jacocoTestCoverageVerification` зелёный БЕЗ смоук-теста — симуляция CI
      (тест временно изъят из прогона): verification прошёл; `ClaudeAgentRuntimeTest`
      покрывает `buildCommand` без бинаря
- [ ] реальный прогон GitHub Actions зелёный — проверить после push/PR; заодно закрывает
      два открытых пункта приёмки T26
- [x] импорт `sample-scaffold` → deploy → preflight ok без ручных действий —
      `ImportDeployPreflightChainTest`, первый тест через границу importer→runtime
      (test-only зависимость `importer` → `:runtime`, цикла нет)
- [x] скрипты хуков едут при импорте: `.gigacode/hooks/tdd-guard.py` в целевом проекте,
      re-import/конфликты T35 накрывают их автоматически (едут через `ImportResult#files`)
      — `ImportEndToEndTest`
- [x] preflight разбирает команды: `python3 hooks/tdd-guard.py` резолвится в
      `hooks/tdd-guard.py`; при отсутствии скрипта проблема называет токен, а не всю
      команду — `DefaultHarnessGuardTest` (два новых теста)

## Реализация

- `runtime/.../agent/ClaudeAgentRuntimeTest` — пиновка точной формы CLI из таблицы SD §6
  (`claude -p --output-format stream-json --verbose`, промпт через stdin) на фейковом
  пути бинаря.
- `ImportSession#copyHookScripts` — по образцу `copySkillDir` (T34): всё из каталога
  рядом с `settings.hooks.json` → `.gigacode/hooks/...`; no-op, если конфиг лежит в корне
  обвязки (нечего копировать, а обход корня утащил бы весь checkout).
- `preflight.py` — токенизация значений перед проверкой `*.py`/`*.sh`; абсолютные пути
  по-прежнему проверяются как есть, относительные — от корня харнесса.
- Фикстура `sample-scaffold` дополнена `hooks/tdd-guard.py` (раньше конфиг ссылался на
  несуществующий скрипт — фикстура сама была не деплоябельна).
- Хэш-манифест (SR-8) автоматически накрывает приехавшие скрипты хуков —
  `HarnessManifest.scan` хэширует всё под `.gigacode/`, отдельных правок не требовалось.
