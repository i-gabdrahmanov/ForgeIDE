# T09 — ScriptExecutor + AgentRuntime (stream-json)

- **Веха:** M2
- **Зависит от:** T08
- **Спека:** SD §6; SDD FR-4.1–4.3, §5.2, §5.4

## Скоуп

- `ScriptExecutor`: python/shell-шаги — exit-код, stdout/stderr, timeout; реализация `ScriptRunnerPort`.
- `AgentRuntime` для **двух** рантаймов (gigacode + claude): сборка команды, парсинг
  stream-json → типизированные `AgentEvent` (`tool_use`, `usage`, `result`), извлечение
  итогового JSON по контракту §5.2 (step_id, artifacts, pending_questions, summary).
- `meta.json` фазы (§5.4): команда, env-ключи, полный промпт + sha256, тайминги, exit-код,
  usage, head_before/after (git).
- Валидация артефактов FR-4.3: expects существуют, непустые, md/json парсятся →
  иначе `FAILED(artifacts)`.
- Токен-бюджет: по usage-событиям; fallback при их отсутствии — оценка по байтам + wall-clock.
- Fixtures: записанные стримы обоих рантаймов (успех, обрыв, мусор в stdout, отсутствие result).

## Вне скоупа

- Env-скоупинг по шагам (T16), судьи (T14).

## Приёмка

- [ ] фикстурные стримы обоих рантаймов парсятся в одинаковую модель событий
- [ ] EOF без result-события → `FAILED(stream)`; мусорные строки в stdout не роняют разбор
- [ ] реальный smoke-вызов хотя бы одного рантайма («ответь OK») проходит end-to-end
- [ ] в env и промпте фазы нет путей `~/.forgeide` (SR-1, автотест)
