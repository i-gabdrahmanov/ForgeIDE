# T29 — Интерфейс PhaseSandbox (заготовка OS-изоляции фаз)

- **Веха:** M5
- **Зависит от:** —
- **Спека:** SDD §8 («заложить интерфейс `PhaseSandbox` в `runtime` сейчас»); Т-2, Т-18; аудит 2026-07 №5

## Скоуп

- Интерфейс `PhaseSandbox` в `runtime`: оборачивает запуск агент-фазы
  (`wrap(ProcessSpec) → ProcessSpec` — команда/env/рабочая директория могут быть
  переписаны реализацией).
- Дефолтная реализация `NoSandbox` — тождественная (текущее поведение байт-в-байт).
- Точка встраивания — `ProcessRunner`/`ProcessGroupLauncher` перед стартом процесса;
  выбор реализации — поле конфигурации проекта (по умолчанию выключено).

## Вне скоупа

- Реальные реализации (sandbox-exec / bubblewrap / контейнер) — пост-MVP по SDD §8.
- Egress-фильтр сети (Т-18) — вместе с реальным sandbox.

## Приёмка

- [x] агент-фазы проходят через `PhaseSandbox.wrap`; с `NoSandbox` весь существующий тест-набор зелёный — `ProcessRunner#run` вызывает `sandbox.wrap` перед `ProcessGroupLauncher.start`, дефолтный конструктор использует `NoSandbox.INSTANCE`
- [x] фикстурная реализация в тесте подменяет команду запуска — точка расширения работает — `ProcessRunnerTest#phaseSandboxWrapRewritesTheCommandBeforeLaunch`
- [x] SDD §8 обновлён: пункт «заложить интерфейс» помечен выполненным

## Реализация

- `runtime/process/PhaseSandbox` — интерфейс с единственным методом `wrap(ProcessSpec) →
  ProcessSpec`; javadoc фиксирует контракт (SDD §8, T-2/T-18) и что реальная реализация —
  пост-MVP.
- `runtime/process/NoSandbox` — синглтон-реализация, тождественная функция.
- `ProcessRunner` получил поле `PhaseSandbox sandbox` (дефолтный конструктор → `NoSandbox
  .INSTANCE`, второй конструктор принимает произвольную реализацию); `run()` прогоняет
  входной `ProcessSpec` через `sandbox.wrap` первой же строкой, до `ProcessGroupLauncher
  .start`.
- Точка встраивания одна на весь модуль: и агент-фазы (`AbstractAgentRuntime`), и `script`-шаги
  (`ScriptExecutor`) идут через один и тот же `ProcessRunner.run`, поэтому оборачивание не
  нужно дублировать в каждом вызывающем коде.

## Не сделано / бэклог

- Поле конфигурации проекта для выбора реализации (упомянуто в скоупе) сознательно не
  заведено: единственная реализация сегодня — `NoSandbox`, поэтому персистентный
  project-level флаг ничего не переключал бы и добавил бы мёртвый код (парсер pipeline.yaml,
  UI, JSON-схема) без потребителя. Точка расширения — конструктор `ProcessRunner(PhaseSandbox)`;
  когда появится первая реальная реализация (post-MVP), тогда и заводить конфиг-поле и
  прокидывать выбор от `ProjectDefinition` через `AbstractAgentRuntime`/`ScriptExecutor` до
  `ProcessRunner`.
