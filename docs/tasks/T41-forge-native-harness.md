---

# T41 — ForgeIDE работает как forge-обвязка (двухфайловая модель + резолвер)

- **Веха:** M5
- **Зависит от:** T32, T38, T40
- **Отменяет:** конвенцию T32 (эталон в корне `.gigacode/`) и T40-self-heal (перенос в корень)

Закрывает **Problem #3 из T40**: ForgeIDE навязывал собственную конвенцию (`settings.hooks.json`
в корне `.gigacode/`, свой preflight, `settings.json` не генерился), несовместимую с нативной
моделью forge-обвязки. При деплое forge-native обвязки (напр. `pprb-kid`, `assembled-project`) они
ломали друг друга об один файл.

## Корень проблемы

- **gigacode** (агентный рантайм) читает `.gigacode/settings.json` (резолвнутый), находя его по
  cwd = корень проекта; ForgeIDE путь к settings не передаёт.
- **ForgeIDE-деплой не генерил `settings.json`** — только валидировал `settings.hooks.json`.
  Значит хуки в рантайме не поднимались.
- forge-обвязка: эталон под `hooks/settings.hooks.json` (с `${PROJECT_ROOT}`/`${PYTHON}`),
  `hooks/resolve_hook_paths.py` → генерит `settings.json`, свой `hooks/preflight.py`.

## Решение

ForgeIDE переходит на forge-модель. `DefaultHarnessGuard.deploy()`:

1. `healLayout` — эталон из корня `.gigacode/` → `hooks/` (обратный self-heal старых T32-проектов);
2. `HarnessManifest.scan` — hooks/ + skills/ (эталон под hooks/); `settings.json` не хешируется;
3. `deploy.sh` (mkdir+chmod);
4. `resolveSettings` — **запускает `hooks/resolve_hook_paths.py` обвязки** (генерит `settings.json`),
   fallback — встроенный `resolve.py`;
5. `runPreflight` — **`hooks/preflight.py` обвязки** (если есть), иначе встроенный. Маппинг кодов
   forge-preflight: `0` = ok, `2` = задеплоено/не инициализирован (энфорсмент включён → passed,
   сохраняет T37), `1` = enforcement off → fail.

Fallback для простых обвязок (без резолвера/preflight) — встроенные `resolve.py`/`preflight.py`.

## Затронуто

- `runtime`: `HarnessLayout` (forge-хелперы), `HarnessManifest` (без корневого спец-кейса),
  `DefaultHarnessGuard` (heal→resolve→preflight), bundled `resolve.py` (новый), `preflight.py`
  (под forge-раскладку).
- `importer`: `ImportSession.result()` — эталон под `.gigacode/hooks/settings.hooks.json`.
- `ui`: `HarnessStatusText` — рендер JSON-вывода forge-preflight (`errors`/`init_needed`/`warnings`).
- Тесты: `DefaultHarnessGuardTest` (21, включая запуск резолвера/preflight обвязки и exit 0/1/2),
  `HarnessDeployPipelineTest`, `ImportEndToEndTest`, `HarnessStatusTextTest`.

## Проверено

- `./gradlew test` — зелёный (core/runtime/importer/ui).
- Реальный forge-native `dev/npf/assembled-project`: forge-резолвер exit 0 (собрал `settings.json`),
  forge-preflight exit 2 → ForgeIDE трактует как passed. Деплой проходит чисто.

## Ссылки

- T32 — (отменена) единый путь `settings.hooks.json` в корне.
- T40 — разворот `${PROJECT_ROOT}` в preflight; Problem #3 закрыт этой задачей.
