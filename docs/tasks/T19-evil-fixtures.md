# T19 — Анти-обходная приёмка (злые фикстуры)

- **Веха:** M3 (закрывает веху)
- **Зависит от:** T14, T15, T16, T17, T18
- **Спека:** SDD §7 (приёмка), §4.1 (реестр Т-*), FR-11

## Скоуп

- Скрипт-имитатор агент-фазы (fake-runtime, подключается как AgentRuntime) с режимами:
  - Т-1: дописывает шаг в manifest.json;
  - Т-4: пытается `git push` / пишет вне маски;
  - Т-7: правит скрипт судьи/хук;
  - Т-9: оставляет `nohup`-процесс;
  - Т-12: правит pipeline.yaml/промпт во время прогона;
  - Т-13: коммитит в master / `reset --hard`;
  - Т-14: отдаёт пустые/мусорные артефакты;
  - Т-15: зацикливает pending_questions.
- Автотест на каждый режим: ожидаемый статус (`FAILED(*)` / `STOPPED(*)`) + событие
  инцидента в аудите (SDD §7.2).
- Автотесты таксономии FR-11: каждый код ошибки воспроизводится фикстурой (§7.5).
- Позитивный e2e: полный forgelite на фикстурном рантайме — все гейты, судьи, outward (§7.3).

## Вне скоупа

- Реальные прогоны на живой модели (ручная приёмка вехи M3).

## Приёмка

- [x] весь набор злых фикстур зелёный в CI-прогоне
- [x] каждый код FR-11 покрыт минимум одним тестом
- [x] порча audit.jsonl руками → STOPPED(audit-chain) при открытии (§7.4)

### Где что лежит

Т-9 (осиротевший процесс) не был доведён до конца прежними тасками — `ProcessRunner.sweepOrphans`
существовал, но ничего не вызывало его после агент-фазы. Добавлено: `ProcessSweepPort` (core),
`EngineCommand.OrphanProcessesSwept` + разбор в `PipelineEngine.dispatchAgent`/`handle`, и
`ProcessRunner implements ProcessSweepPort` (runtime) — теперь каждая агент-фаза метётся
безусловно, попавшие под метлу pid попадают в аудит как `incident.orphan_process`.

Злые фикстуры, не требующие реального git/процесса (Т-12, Т-14, Т-15 + добор таксономии
FR-11 по `FAILED(stream)`) — `core/.../engine/support/EvilAgentRuntime.java` +
`core/.../engine/EvilFixturesTest.java`. Т-1 (подмена манифеста) и Т-7 (дрейф обвязки) уже имели
собственные полноценные тесты на реальном стеке (`ManifestProjectorTamperTest`,
`PipelineEngineHarnessDriftTest`) и не продублированы. Т-4/Т-13 (scope-diff на реальном git) и
Т-9 (реальный процесс) — `runtime/.../evil/EvilAgentRuntime.java` +
`runtime/.../evil/EvilFixturesRuntimeTest.java`. Позитивный e2e (agent → judge → gate → branch →
outward, реальный git + фейковый GitHub) — `runtime/.../outward/ForgeliteFixtureE2ETest.java`.
Остальные коды FR-11 (ARTIFACTS/JUDGE/SCOPE/TAMPERED/BUDGET/INTERRUPTED/QUESTIONS/harness-drift/
audit-chain/script/gate-declined) уже были покрыты `PipelineEngineTransitionsTest` и соседями —
не продублированы, только сверены.
