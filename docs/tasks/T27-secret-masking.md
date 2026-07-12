# T27 — Маскирование секретов на trusted-копиях + выравнивание спеки

- **Веха:** M5
- **Зависит от:** —
- **Спека:** SD §6.2 (регэксп-маскер), SDD SR-5; SD §6.1.5 ↔ SDD FR-11 (`log_overflow`); аудит 2026-07 №3, №13

## Скоуп

- Маскер на trusted-записях движка — применяется ДО записи на диск:
  - значения всех секретов, выданных в `env_scope` текущей фазы (`FileSecretStore`),
    заменяются на `***` в: detail судьи (`judge.verdict`, errors.json/accumulated_errors),
    payload'ах аудита, `meta.json`;
  - типовые паттерны поверх точных значений: `Authorization: Bearer …`, `token=…`,
    `ghp_…`/`glpat-…`-подобные.
- Raw-логи (`stdout.jsonl`, `stderr.log`) по спеке НЕ трогаем; проверить, что
  `ground/ai-logs/` попадает в `.gitignore` целевого проекта при деплое обвязки
  (SD §6.2, вторая половина обещания) — реализовать, если нет.
- Выравнивание спеки: убрать `FAILED(log_overflow)` из SD §6.1 в пользу
  `FAILED(budget)` (как в SDD FR-11 и в коде) — одно место правды.

## Вне скоупа

- Маскирование внутри raw-стримов агента (untrusted-логи остаются как есть по спеке).
- Подпись стейта ключом из keychain (пост-MVP по SDD §8).

## Приёмка

- [x] секрет, отпечатанный check-скриптом в stdout, не попадает в detail `judge.verdict` / errors.json в открытом виде (автотест) — `PipelineEngineSecretMaskingTest`
- [x] значение секрета из `env_scope` фазы отсутствует во всех trusted-файлах прогона (meta.json, audit.jsonl, run.json) — автотест сканом — `PipelineEngineSecretMaskingTest` (audit.jsonl/run.json-эквивалент через `JudgeVerdict`), `AbstractAgentRuntimeTest#metaJsonMasksAnEnvSecretValueThatEndsUpInThePromptText`
- [x] `ground/ai-logs/` гарантированно в `.gitignore` целевого проекта после деплоя обвязки — `ImportWriter#ensureAiLogsIgnored`, `ImportEndToEndTest`
- [x] SD §6.1 и SDD FR-11 согласованы по коду ошибки капа вывода — SD §6.1 п.5 приведён к `FAILED(budget)`

## Реализация

- `core/secret/SecretMasker` — точные значения секретов (env_scope) + типовые паттерны
  (`Authorization: Bearer …`, `token=…`, `ghp_…`/`glpat-…`), применяется до записи на диск.
- Единая точка маскирования — `PipelineEngine#runJudgeChecks`: вывод детерминированного
  check-скрипта маскируется до того, как попадёт в `JudgeVerdict.detail` (run.json),
  `judge.verdict`-пейлоуд аудита (audit.jsonl) и `accumulated_errors` (folds в следующий
  промпт → meta.json).
- Вторая линия защиты — `AbstractAgentRuntime#writeMeta` маскирует текст промпта по
  значениям `invocation.env()` перед записью meta.json (сегодня в проде это no-op, так как
  секреты не попадают в промпт через `VariableResolver`, но защищает от будущих регрессий).
- `ImportWriter#ensureAiLogsIgnored` — идемпотентно добавляет `ground/ai-logs/` в
  `.gitignore` целевого проекта при каждом деплое обвязки.

## Не сделано / бэклог

- Обнаружен реальный (не только текстовый) разрыв со спекой: превышение `output_mb`
  сегодня в коде приводит к `FAILED(stream)` («no result event»), а не к `FAILED(budget)` —
  `ProcessOutcome.outputCapExceeded()/timedOut()` из `ProcessRunner` никогда не долетают до
  `core` (`AgentResult` их не несёт). SD/SDD теперь согласованы по ТЕКСТУ («кап вывода —
  FAILED(budget)»), но само поведение это не чинит. В скоуп T27 это не входило (акцептанс
  просил только согласовать документацию), но стоит завести отдельную задачу на проводку
  этих флагов через `AgentResult` → `FailureReason.BUDGET`.
