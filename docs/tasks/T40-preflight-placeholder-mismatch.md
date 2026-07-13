---

# T40 — preflight.py не понимает плейсхолдеры `${PROJECT_ROOT}`/`${PYTHON}` в обвязках

- **Веха:** M5 (post-factum диагностика)
- **Зависит от:** T32, T38
- **Спека:** `runtime/src/main/resources/dev/forgeide/runtime/harness/preflight.py:54–65`

Доклад по результатам диагностики на проекте `pprb-kid` — обвязка с плейсхолдерами
`${PROJECT_ROOT}`/`${PYTHON}` в `settings.hooks.json` не проходит preflight, даже если
все файлы `.py`/`.sh` на месте.

## Скоуп

- Только рассинхрон между форматом, который `preflight.py` понимает, и форматом,
  который используют некоторые обвязки (в частности, `pprb-kid`).
- Конвенция о едином пути `settings.hooks.json` (T32) — не пересматривается, работает
  корректно.
- Изменение семантики `settings.hooks.json` — не входит.

## Проблемы

### 1. preflight не разворачивает `${PROJECT_ROOT}` как токен

**Файл:** `preflight.py:54–65`

```python
for token in value.split():
    if token.endswith(".py") or token.endswith(".sh"):
        candidate = Path(token)
        hook_path = candidate if candidate.is_absolute() else harness / token
        if not hook_path.is_file():
            problems.append(f"hook referenced in settings.hooks.json not found: {token}")
```

В команде `"${PYTHON} ${PROJECT_ROOT}/.gigacode/hooks/destructive-blocker.py"` `value.split()`
даёт токен `"${PROJECT_ROOT}/.gigacode/hooks/destructive-blocker.py"`.

- `endswith(".py")` → True
- `Path(...).is_absolute()` → **False** (начинается с `${`)
- `hook_path = <project>/.gigacode/${PROJECT_ROOT}/.gigacode/hooks/destructive-blocker.py` — **не существует**

**Ожидаемое поведение:** `${PROJECT_ROOT}` — это корень проекта (`sys.argv[1]`).
Если токен начинается с `${PROJECT_ROOT}/`, его надо ресолвить как
`<project_root>/<остаток>`, а не как `<project>/.gigacode/<токен целиком>`.

### 2. preflight не разворачивает `${PYTHON}` (косвенно)

`${PYTHON}` — тоже пробельный токен, не `.py`/`.sh`, поэтому `endswith` не срабатывает.
Сейчас он проходит «на везении» как лишний мусор в split(). Но если бы кто-то написал
`command: "${PYTHON}/now-in-same-token.py"` — сломалось бы так же.

### 3. Две разные конвенции для одного артефакта

- **`HarnessLayout.java:20`** — `SETTINGS_FILE = "settings.hooks.json"`, ожидает в корне
  `.gigacode/`
- **`resolve_hook_paths.py`** — читает эталон из `.gigacode/hooks/settings.hooks.json`,
  пишет результат в `.gigacode/settings.json` (не `settings.hooks.json`)

То есть одна и та же обвязка может писат в два разных файла — и в обоих случая
preflight не найдет то, что ишет (один — потому что при в хookс,  второй — потому
что не в том желе.

### 4. Миграция проектов до T32 — одноразовая и неавтоматизирована

`preflight.py:34–42` содержит миграционное сообщение для старого положения
`hooks/settings.hooks.json`, но не содережит `--migrate` флаг, который перетащил
бы файл автоматически. Для массовых импортов — дорогой ручной труд.

## Воспроизведение

**Любая обвязка**, содержащая в `settings.hooks.json` строки вида:

```json
"${PYTHON} ${PROJECT_ROOT}/.gigacode/hooks/<name>.py"
```

не проходит preflight с ошибкой:

```
hook referenced in settings.hooks.json not found: ${PROJECT_ROOT}/.gigacode/hooks/<name>.py
```

**без единого изменения в коже ForgeIDE** — достаоно просто положит конфиг в стандартное
место и запустит preflight.

## Рекомендации

**Самый дешевый фикс — 1 строки в `preflight.py:54–65`, до цикла `for token in value.split()`:**

```python
if token.startswith("${PROJECT_ROOT}/"):
    token = str(project_root) + token[len("${PROJECT_ROOT}"):]```

где `project_root` — уже есть в `main()` как `Path(sys.argv[1])`.

Этого достаоно, чтобы pprb-kid (и любая другая обвязка с плейколдерами) проходила
preflight без единого изменния в обвязке. Проверка `${PYTHON}` как «не .py/не .sh»
остается на «везении» — для консистенции добавит `--skip ` в сплит.

## Ссылки

- T32 — единый путь `settings.hooks.json` (импортер → preflight)
- T38 — зелены CI / цепочка импорт → deploy → preflight ok
- `preflight.py` — реализация валидации `HarnessLayout.SETTINGS_FILE`
- `resolve_hook_paths.py` — утилита подстановки ${PROJECT_ROOT}/${PYTHON} (в обвязках)

*Подготовлен по результатам диагностики на `pprb-kid`, 2026-07-13.*