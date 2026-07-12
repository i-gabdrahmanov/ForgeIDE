# T31 — Дотесты приёмки: палитра e2e, плитка с нуля, NFR-замеры

- **Веха:** M5
- **Зависит от:** —
- **Спека:** SDD §7.6, FR-2.8; NFR-2, NFR-3, NFR-4; аудит 2026-07 №8–10

## Скоуп

- **SDD §7.6 дословно:** пайплайн agent + judge + gate, собранный через
  `PipelineDocument`/`PipelineEdits` (путь палитры), исполняется e2e на фикстурном
  рантайме до `COMPLETED` — сейчас e2e из палитры гоняет только script-шаги
  (`PipelineConstructorEndToEndTest`).
- **T23 дословно:** плитка «с нуля» (`StepDefaults` + `AgentPromptScaffold`) не только
  валидна, но и исполняется на фикстурном рантайме (`NewAgentTileEndToEndTest` дополнить).
- **NFR-3:** замер восстановления прогона из SoT ≤ 5 с на большом стейте
  (десятки шагов, 10k+ событий аудита).
- **NFR-4:** масштаб `manyUntrackedFilesStayWellWithinTheNfr4Budget` — либо довести до
  100k файлов из формулировки SDD, либо осознанно зафиксировать 5k в SDD с обоснованием.
- **NFR-2:** замер задержки «событие движка → снапшот доступен подписчику» ≤ 200 мс
  на уровне движка/вьюмодели (без рендера JavaFX).

## Вне скоупа

- Headless TestFX/Monocle для view-слоя — осознанный отказ остаётся в силе
  (логика вьюх тестируется через вынесенные хелперы).

## Приёмка

- [x] agent+judge+gate из палитры проходит e2e на фикстурном рантайме (SDD §7.6) —
      `PipelineConstructorEndToEndTest#anAgentJudgeGatePipelineAssembledFromThePaletteRunsEndToEndOnTheFixtureRuntime`
- [x] плитка с нуля исполняется e2e на фикстурном рантайме (T23) —
      `NewAgentTileEndToEndTest#freshAgentTileRunsEndToEndOnTheFixtureRuntimeOnceItsScaffoldedPromptIsOnDisk`
- [x] NFR-2, NFR-3 закрыты замерами в тестах; NFR-4 — замером на 100k
      (SDD не правился — бюджет 100k выполним, замена не потребовалась)

## Реализация

- **SDD §7.6** — новый тест собирает agent+judge+gate строго через `PipelineDocument`/
  `PipelineEdits`/`StepDefaults` (тот же путь, что канвас), включая заполнение изначально
  невалидных дефолтов (`target` судьи, промпт агента через `AgentPromptScaffold`, вопрос гейта —
  дефолт пустой и живой валидатор его не требует, но YAML-парсер требует непустой `question`,
  так что до сериализации он тоже заполняется), затем гоняет собранный (и прогнанный через
  YAML round trip, FR-2.7) пайплайн на `PipelineEngine` с fixture-портами до `COMPLETED`.
- **T23** — `NewAgentTileEndToEndTest` дополнен тестом, который берёт ровно тот же
  `StepDefaults.create(AGENT)` + `AgentPromptScaffold.render` путь, что и тест на валидность, но
  оборачивает получившийся шаг в pipeline и гоняет его на `PipelineEngine` до `PASSED`.
- **NFR-2** (≤200 мс событие→снапшот) — `PipelineEngineThreadSafetyTest
  #engineEventReachesASubscriberWithAnUpdatedSnapshotWithin200Ms`: засекает время от возврата
  fixture-скрипта до появления `PASSED`-снапшота у подписчика `PipelineEngine#subscribe`
  (движок/вьюмодель, без JavaFX). В прогоне — единицы мс, запас на два порядка.
- **NFR-3** (≤5 с восстановление из SoT) — `StartupRecoveryTest
  #recoversAnAbandonedRunFromSotWithin5SecondsAtScale`: 40 шагов + 10k событий аудита в
  `FileStateStore`, засекается `StartupRecovery.recover` (перечитывает и проверяет всю
  hash-цепочку через `FileStateStore#load`). Фактически ~0.4 с на десятки шагов + 10k событий.
- **NFR-4** (≤2 с на 100k файлов) — `GitScopeDiffTest#manyUntrackedFilesStayWellWithinTheNfr4Budget`
  поднят с 5k до буквальных 100k файлов из формулировки SDD; создание файлов вынесено за
  пределы замеряемого окна (таймер стартует после подготовки репозитория). `git status
  --porcelain` на 100k нетрекнутых файлов — доли секунды, бюджет выполняется с большим запасом
  (создание файлов в тесте — единственная причина, почему сам тест идёт ~14 с в CI).

## Не сделано / бэклог

- Обнаружена (не в скоупе T31) асимметрия `PipelineValidator`/`PipelineParser`: живой
  валидатор конструктора не требует непустого `question` у `GateStep`, а YAML-парсер при
  повторном чтении — требует, и падает `InvalidPipelineException`. Т.е. можно собрать на
  канвасе «валидный» (по FR-2.6) pipeline с пустым вопросом гейта, сохранить (FR-2.7
  регенерирует YAML), и получить неоткрываемый файл при следующей загрузке. Не чинилось —
  вне заявленного скоупа этой задачи; кандидат на отдельный тикет.
