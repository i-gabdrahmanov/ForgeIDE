# ForgeIDE

Десктопная IDE (JavaFX) для управляемого запуска агентных пайплайнов: фазы-агенты
(Claude Code / GigaCode CLI) исполняются как изолированные процессы, а переходы,
гейты человека, судьи, бюджеты и аудит контролирует детерминированный движок —
модель никогда не управляет собственным прогоном.

Ключевая идея: `pipeline.yaml` описывает граф шагов (плиток) — агентные фазы,
скрипты, судьи, гейты, outward-действия. IDE рисует его на канвасе, запускает,
показывает live-лог и требует подтверждения человека в контрольных точках.

## Требования

- **JDK 21** (виртуальные потоки, records) — например `brew install openjdk@21`
  на macOS или `apt install openjdk-21-jdk` на Debian/Ubuntu
- **git** — scope-diff и контроль HEAD работают через git-plumbing
- **python3** — preflight обвязки и judge-скрипты `check_*.py`
- CLI-рантайм агента: `claude` и/или `gigacode` — путь к бинарю настраивается
  per-project в форме проекта

## Запуск из исходников

Проверено на чистой машине (macOS: Apple Silicon; Linux: x86_64) — после
установки требований выше:

```bash
git clone git@github.com:i-gabdrahmanov/ForgeIDE.git
cd ForgeIDE
./gradlew :ui:run
```

Первый прогон качает Gradle-зависимости и JavaFX-jar под текущую платформу —
нужен доступ в интернет. `./gradlew` — обёртка (Gradle wrapper), локально
устанавливать Gradle не нужно.

Сборка и тесты:

```bash
./gradlew build          # все модули + тесты
./gradlew :core:test     # только движок
```

## Дистрибутив

Кросс-платформенные zip-дистрибутивы собираются на любой одной машине: задачи
ниже резолвят нужные `javafx-*` jar по platform/arch-атрибуту через Maven
Central (тот же механизм, что использует сам `javafx-gradle-plugin`), так что
Linux-архив можно собрать и на macOS, и наоборот — отдельная Linux-машина для
сборки не нужна.

```bash
./gradlew :ui:platformDistZips
```

Результат — в `ui/build/distributions/`:

| Файл | Платформа |
|---|---|
| `ui-0.1.0-mac-aarch64.zip` | macOS (Apple Silicon) |
| `ui-0.1.0-linux-x64.zip` | Linux (x86_64) |

Отдельную платформу можно собрать точечно: `./gradlew :ui:distMacAarch64Zip`
или `./gradlew :ui:distLinuxX64Zip`.

Запуск из распакованного дистрибутива — одинаково на macOS и Linux:

```bash
unzip ui-0.1.0-<platform>.zip
cd ui-0.1.0-<platform>
./bin/ui
```

На Linux для отрисовки окна нужны системные X11/GTK-библиотеки (на обычном
десктоп-окружении они уже есть; на headless-хосте — как минимум `libX11` и
`gtk3`). Windows и инсталляторы/подпись — вне скоупа MVP (NFR-5,
[T30](docs/tasks/T30-readme-distribution.md)).

## Карта модулей

| Модуль | Содержимое |
|---|---|
| `core` | доменная модель, `pipeline.yaml` (парсер/валидатор/writer), `PipelineEngine` (актор переходов), библиотека плиток |
| `runtime` | процессы: AgentRuntime (stream-json), ScriptExecutor, scope-diff (git), целостность обвязки (hash-manifest, preflight), стейт-стор, секреты |
| `importer` | импорт Forge-обвязки: сканер `skills/*/SKILL.md`, нарезка контрактов, привязка к плиткам шаблона, реестр `SKILLS-REGISTRY.md` |
| `ui` | JavaFX: проекты, канвас/конструктор, импортёр, run view (timeline, логи, гейты, вопросы), редакторы плиток |

## Границы контроля (scope-diff)

Движок проверяет каждую агентную фазу (`allowed_write`-маски, сдвиг HEAD,
tamper-check манифеста), но эти проверки — не полноценный sandbox. Известные
слепые зоны (ревью импортёра 2026-07-11 №5, [T36](docs/tasks/T36-dirty-tree-warning.md)):

- **грязное git-дерево ослабляет scope-diff** — файл, уже изменённый до старта
  прогона, агент может дописывать дальше незаметно (сравниваются статус-коды
  `git status` до/после фазы, а не содержимое). При старте прогона на грязном
  дереве IDE показывает предупреждение и пишет audit-событие `run.dirty_tree` —
  прогон не блокируется. Запускайте прогоны на чистом дереве;
- **gitignored-пути и записи вне репозитория** scope-diff не видит — `git
  status` их не показывает, а полный обход дерева ради этого противоречил бы
  бюджету производительности (NFR-4);
- **сеть** внутри фазы IDE не ограничивает — вторая линия защиты — хуки CLI
  (SD §9); превенция на уровне ОС — пост-MVP ([T29](docs/tasks/T29-phase-sandbox.md), `PhaseSandbox`).

Подробнее и с разбором типовых остановок — [Мануал пользователя, §11](docs/manual.md#11-что-контролируется-а-что-нет).

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
