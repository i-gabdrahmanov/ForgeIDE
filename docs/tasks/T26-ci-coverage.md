# T26 — CI и измерение покрытия

- **Веха:** M5
- **Зависит от:** —
- **Спека:** SDD §7.1 (приёмка T19 «зелёный в CI-прогоне»); аудит 2026-07 №2, №11

## Скоуп

- GitHub Actions workflow: `./gradlew build` на push в master и на PR
  (ubuntu-latest; в раннере должны быть git и python3 — их используют
  runtime/harness-тесты).
- `ClaudeAgentRuntimeSmokeTest` в CI штатно скипается по `Assumptions`
  (бинаря claude нет) — зафиксировать это ожидание в workflow-комментарии.
- JaCoCo для `core`, `runtime`, `importer`: отчёт в артефакты CI + порог line coverage
  (стартовое значение снять с фактического и зафиксировать, не выдумывать).
- Загрузка отчётов тестов при падении (test-results XML в артефакты).

## Вне скоупа

- Релизные пайплайны, публикация дистрибутивов, матрица ОС, кэш-оптимизации.

## Приёмка

- [ ] push/PR запускает workflow, `./gradlew build` зелёный в CI (workflow добавлен
      `.github/workflows/ci.yml`, зелёный прогон `./gradlew build` подтверждён локально;
      фактический прогон в GitHub Actions требует push и остаётся проверить после него)
      — **аудит 2026-07-12: проверка случилась, CI красный с первого прогона** — порог
      runtime-покрытия зависел от `ClaudeAgentRuntimeSmokeTest`, который в CI скипается;
      причина устранена в [T38](T38-import-chain-and-ci-green.md), галочку ставить после
      первого зелёного прогона
- [ ] злые фикстуры T19 исполняются в CI (не отфильтрованы) — приёмка T19 §7.1 наконец
      выполняется буквально (workflow ничего не фильтрует, `EvilFixturesRuntimeTest` требует
      только git — он есть на `ubuntu-latest`; подтвердить после первого реального прогона)
- [x] JaCoCo-порог для core+runtime+importer зафиксирован и проходит; отчёт доступен артефактом
      (пороги сняты с фактического покрытия 2026-07-12: core 84.25%→0.84, runtime 83.08%→0.83,
      importer 90.52%→0.90; `./gradlew build` зелёный локально; workflow загружает
      `core|runtime|importer/build/reports/jacoco/test` артефактом `jacoco-reports`)

### Где что лежит

- `build.gradle.kts` — JaCoCo (`jacocoTestReport` + `jacocoTestCoverageVerification`) подключён
  для `core`/`runtime`/`importer` внутри `plugins.withId("java") { ... }` (важно: `subprojects{}`
  корневого скрипта выполняется раньше, чем модульный `build.gradle.kts` применяет
  `java-library`, поэтому конфигурацию JaCoCo нужно откладывать до применения `java`-плагина —
  иначе `jacocoTestReport` ещё не зарегистрирован и `tasks.named<JacocoReport>(...)` падает с
  `UnknownTaskException`). `ui` сознательно вне порога (см. скоуп задачи).
- `.github/workflows/ci.yml` — триггеры `push` в `master` и `pull_request`, `ubuntu-latest`,
  шаг с явной проверкой `git --version`/`python3 --version` перед сборкой, `./gradlew build`,
  выгрузка `test-results` при падении и `jacoco-reports` всегда. Комментарий в шаге сборки
  фиксирует ожидание: `ClaudeAgentRuntimeSmokeTest` (T09) штатно скипается через `Assumptions` —
  бинаря `claude` на раннере нет и не будет.
