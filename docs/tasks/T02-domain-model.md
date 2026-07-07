# T02 — Доменная модель core

- **Веха:** M1
- **Зависит от:** T01
- **Спека:** SD §2; SDD §5.1 (типы шагов), FR-3.2

## Скоуп

- `PipelineDefinition` + sealed `StepDefinition`: `AgentStep`, `ScriptStep`, `JudgeStep`,
  `GateStep`, `BranchStep`, `PerTaskLoop`, `OutwardStep` (SR-4).
- Рантайм-модель: `PipelineRun`, `StepRun`, статусы `PENDING/READY/RUNNING/PASSED/FAILED/
  WAITING_GATE/WAITING_INPUT/SKIPPED`, типизированные причины сбоев (таксономия FR-11).
- Иммутабельные `RunSnapshot` + иерархии `EngineCommand` / `EngineEvent`.
- Порты (интерфейсы в core): `StateStore`, `AgentRuntimePort`, `ScriptRunnerPort`,
  `GatePresenter`, `Clock` — реализации придут из `runtime`/`ui`.
- Политики как значения: `RetryPolicy`, `FailPolicy`, `TokenBudget`.

## Вне скоупа

- Логика переходов (T06), персистентность (T07).

## Приёмка

- [ ] юнит-тесты инвариантов модели (уникальность id, корректность статусных enum'ов)
- [ ] `core` компилируется без внешних зависимостей кроме jackson-аннотаций/slf4j
