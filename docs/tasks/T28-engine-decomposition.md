# T28 — Декомпозиция PipelineEngine

- **Веха:** M5
- **Зависит от:** T26 (CI как страховка перед крупным рефакторингом)
- **Спека:** SD §3 (актор, один поток мутаций); аудит 2026-07 №4 (1980 строк, 11% main-кода)

## Скоуп

- Выделить из `PipelineEngine` коллабораторов по доменам, сохранив актор-инвариант
  (мутации `RunContext` — только на actor-потоке):
  - судьи: `dispatchJudge` / `runJudgeChecks` / эскалации и override;
  - outward-шаги и их пред-условия;
  - гейты и вопросы (`GateAnswered` / `QuestionsAnswered` / раунды / эскалация вопросов);
  - dry-run и предпросмотр промпта (T21-ветка);
  - replay аудита при resume (восстановление gateAnswers/accumulatedErrors/questionRounds).
- Публичный API движка (`start`, `submit`, `snapshot`, `subscribe`, конструкторы) не меняется.
- Существующие тесты не переписываются: правки только там, где меняются конструкторы/импорты.

## Вне скоупа

- Любые изменения поведения, форматов стейта/аудита, новые фичи.
- Декомпозиция UI-классов (`ConstructorCanvasView` и др.) — отдельным решением при росте.

## Приёмка

- [x] `PipelineEngine.java` ≤ 600 строк; ни один выделенный класс не превышает 500 —
      594 строки; крупнейший коллаборатор (`PhaseDispatcher`) — 446
- [x] весь существующий тест-набор зелёный без изменения сценариев тестов — ни один файл
      в `core/src/test`/`runtime/src/test` не тронут (`git status` подтверждает: изменены
      только `PipelineEngine.java`/`RunContext.java`, добавлены только новые
      main-классы), `./gradlew build` зелёный
- [x] `PipelineEngineThreadSafetyTest` зелёный — актор-инвариант сохранён
- [x] диффы форматов на диске — нулевые на фикстурном прогоне до/после: временным тестом
      (script→judge→gate пайплайн через реальные `FileStateStore`/`PipelineEngine`) снят
      дамп `run.json`+`audit.jsonl` на дорефакторинговом коде (`git stash`) и на текущем;
      после нормализации только `runId`/его производного `checksum` (случаен per-run) —
      файлы побайтово идентичны, включая весь hash-chain (`prev`/`hash`) и все payload'ы.
      `manifest.json`/`meta.json` пишет код вне `core.engine` (`ManifestProjector`,
      `AbstractAgentRuntime` в `runtime`), который T28 не трогал, — отдельно не гонялись

## Реализация

Пакет `dev.forgeide.core.engine` разложен на `PipelineEngine` (актор: поля, 12
конструкторов, публичный API, actor-loop, command-роутер `handle`/`dispatch`,
`handleStepCompleted`/`handleStepFailed`, планировщик `advance`, реестр запущенных
ранов, аудит/персист/паблиш-примитивы) и коллабораторов, которые актор вызывает через
package-private методы (без публичного интерфейса — иначе Java потребовала бы делать их
`public`, что расширило бы паблик API):

- `JudgeCoordinator` — `dispatchJudge`/`runJudgeChecks`/`handleJudgeOutcome`/эскалации/override.
- `OutwardCoordinator` — `dispatchOutward` и `git_push`/`create_pr`/`jira_*`.
- `GateAndQuestionCoordinator` — гейты, раунды `pending_questions`, их эскалация.
- `DryRunAndPreviewCoordinator` — T21 dry-run судьи и предпросмотр промпта.
- `ResumeReplay` — replay аудит-цепочки и ре-экспансия `per_task_loop` при resume.
- `RunLifecycle` — `bootstrap`/`rehydrate` (два входа в актор: старт и восстановление).
- `PhaseDispatcher` — `dispatchScript`/`dispatchAgent`/`dispatchBranch`/
  `dispatchPerTaskLoop`, T20 trusted-edit (`prompt.edited`/`harness.edited`), SR-8
  harness-drift stop/resume; не выделен явно в скоупе задачи, но понадобился, чтобы
  уложиться в лимит 600 строк — после выноса пяти названных доменов в `PipelineEngine`
  оставался самый тяжёлый кусок, `dispatchAgent` (~100 строк), и смежные с ним по смыслу
  обработчики.
- `AuditPayloads` — все билдеры `ObjectNode`-payload'ов аудита в одном месте (чистый
  перенос кода, самый безопасный шаг декомпозиции).

Точечные упрощения по ходу: `resetQuestionRounds`/`rawPromptForDispatch` переехали из
`PipelineEngine` в `RunContext` как instance-методы — они трогают только поля `ctx` и
нужны нескольким коллабораторам одновременно, так проще, чем тащить их через каждый.
