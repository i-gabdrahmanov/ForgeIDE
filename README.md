# ForgeIDE

Десктопная IDE (JavaFX) для управляемого запуска агентных пайплайнов: фазы-агенты
(Claude Code / GigaCode CLI) исполняются как изолированные процессы, а переходы,
гейты человека, судьи, бюджеты и аудит контролирует детерминированный движок —
модель никогда не управляет собственным прогоном.

Ключевая идея: `pipeline.yaml` описывает граф шагов (плиток) — агентные фазы,
скрипты, судьи, гейты, outward-действия. IDE рисует его на канвасе, запускает,
показывает live-лог и требует подтверждения человека в контрольных точках.

## Требования

- **JDK 21** (виртуальные потоки, records)
- **git** — scope-diff и контроль HEAD работают через git-plumbing
- **python3** — preflight обвязки и judge-скрипты `check_*.py`
- CLI-рантайм агента: `claude` и/или `gigacode` — путь к бинарю настраивается
  per-project в форме проекта

## Запуск из исходников

```bash
./gradlew :ui:run
```

Сборка и тесты:

```bash
./gradlew build          # все модули + тесты
./gradlew :core:test     # только движок
```

Дистрибутив: `./gradlew :ui:distZip` (сейчас артефакт платформозависимый,
кросс-платформенная сборка — задача [T30](docs/tasks/T30-readme-distribution.md)).

## Карта модулей

| Модуль | Содержимое |
|---|---|
| `core` | доменная модель, `pipeline.yaml` (парсер/валидатор/writer), `PipelineEngine` (актор переходов), библиотека плиток |
| `runtime` | процессы: AgentRuntime (stream-json), ScriptExecutor, scope-diff (git), целостность обвязки (hash-manifest, preflight), стейт-стор, секреты |
| `importer` | импорт Forge-обвязки: сканер `skills/*/SKILL.md`, нарезка контрактов, привязка к плиткам шаблона, реестр `SKILLS-REGISTRY.md` |
| `ui` | JavaFX: проекты, канвас/конструктор, импортёр, run view (timeline, логи, гейты, вопросы), редакторы плиток |

## Документация

- **[Мануал пользователя](docs/manual.md)** — как этим пользоваться: от создания
  проекта до разбора инцидентов прогона
- [Бизнес-требования](docs/bt/) · [System design](docs/sd/system-design.md) ·
  [SDD](docs/sdd/) · [Task-план](docs/tasks/README.md)

## Где ForgeIDE хранит данные

| Путь | Что |
|---|---|
| `~/.forgeide/` | стейт прогонов, кэш обвязки, baseline-реестр, `secrets.json` (mode 600) |
| `<проект>/.forgeide/` | `pipeline.yaml`, манифест импорта |
| `<проект>/.gigacode/` | обвязка: `skills/`, `hooks/`, `settings.hooks.json`, `SKILLS-REGISTRY.md` |
| `<проект>/ground/ai-logs/` | сырые логи агентных фаз по итерациям |
