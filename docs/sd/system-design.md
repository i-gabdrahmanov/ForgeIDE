# SD — ForgeIDE: системный дизайн реализации

> Основание: [БТ](../bt/init-bt.md). Стек: Java 21, Gradle, JavaFX.
> Термины: **движок** = детерминированный оркестратор; **агент-фаза** = один headless-вызов рантайма.

---

## 1. Архитектура верхнего уровня

```
┌────────────────────────── ForgeIDE (один JVM-процесс) ──────────────────────────┐
│                                                                                  │
│  ui (JavaFX)          core (домен + движок)         runtime (исполнители)        │
│  ┌──────────────┐     ┌──────────────────────┐      ┌──────────────────────┐     │
│  │ Canvas       │     │ PipelineEngine       │      │ AgentRuntime         │──┐  │
│  │ Inspector    │◄───►│  (актор, 1 поток)    │─────►│  claude/qwen/gigacode│  │  │
│  │ RunView      │ бас │ StateStore           │      │ ScriptExecutor       │  │  │
│  │ GateDialog   │     │ AuditLog             │      │ JudgeExecutor        │  │  │
│  └──────────────┘     └──────────────────────┘      └──────────────────────┘  │  │
│                                   │                                           │  │
└───────────────────────────────────┼───────────────────────────────────────────┼──┘
                                    ▼                                           ▼
                      ~/.forgeide/state/…  (source of truth,          подпроцессы: claude -p /
                       вне досягаемости агента)                       gigacode --experimental-hooks -p /
                                    │ проекция                        python3 check_*.py
                                    ▼
                      <project>/ground/statements/…/manifest.json
                       (для хуков внутри агент-процессов, read-only для модели)
```

Правило зависимостей: `ui → core ← runtime`; `core` не знает ни про JavaFX, ни про ProcessBuilder
(только интерфейсы). Это позволяет позже добавить `cli`-модуль (headless-режим IDE для CI) без правок ядра.

### Модули Gradle

| Модуль | Содержимое | Зависимости |
|---|---|---|
| `core` | домен, граф пайплайна, движок, стейт, аудит, порты (интерфейсы) | jackson, slf4j |
| `runtime` | адаптеры рантаймов, запуск процессов, парсинг stream-json, судьи | core |
| `importer` | разбор Forge-обвязки (`skills/`, `hooks/settings.hooks.json`) → PipelineDefinition | core |
| `ui` | JavaFX-приложение, канвас, редакторы, диалоги | core, runtime, importer |

Пакеты: `dev.forgeide.<module>.*`. DI — ручная сборка в `ui`-Main (без Spring: прототипу не нужны
контейнер и время старта).

---

## 2. Доменная модель (`core`)

```java
// Определение пайплайна — то, что лежит в YAML и рисуется на канвасе
record PipelineDefinition(String id, int version, List<StepDefinition> steps) {}

sealed interface StepDefinition permits AgentStep, ScriptStep, JudgeStep, GateStep, BranchStep, PerTaskLoop {
    String id();
    List<String> dependsOn();
}
record AgentStep(String id, List<String> dependsOn, String runtimeRef,
                 Path promptTemplate, List<Path> expectedArtifacts,
                 RetryPolicy retry, TokenBudget budget) implements StepDefinition {}
record ScriptStep(String id, List<String> dependsOn, List<String> command, Duration timeout) …
record JudgeStep(String id, List<String> dependsOn, String targetStepId,
                 Optional<AgentStep> llmJudge, Optional<ScriptStep> deterministicCheck,
                 FailPolicy failPolicy /* maxIterations=3, escalate */) …
record GateStep(String id, List<String> dependsOn, String question,
                List<String> options, List<Path> showArtifacts) …
record BranchStep(String id, List<String> dependsOn, Map<String,String> routes /* ответ гейта → ветка */) …
record PerTaskLoop(String id, List<String> dependsOn, Path taskPlanJson,
                   List<StepDefinition> template /* размножается по задачам */) …

// Прогон — рантайм-экземпляр
final class PipelineRun {           // mutable, живёт только внутри движка
    RunId id; String featureSlug; Map<String, StepRun> steps; RunStatus status;
}
final class StepRun {
    StepStatus status;              // PENDING → READY → RUNNING → PASSED | FAILED | WAITING_GATE | WAITING_INPUT | SKIPPED
    int iteration;                  // счётчик ре-итераций (судья FAIL → retry)
    List<JudgeVerdict> verdicts;    // хранятся здесь, агенту не видны
    List<AuditRef> events;
}
```

Ключевое: `PipelineRun` мутируется **только** внутри потока движка. Всё снаружи получает
иммутабельные снапшоты (`RunSnapshot`) через событийную шину.

---

## 3. Движок (`PipelineEngine`)

Актор с одним потоком и очередью команд — детерминизм переходов гарантируется отсутствием
конкурентных мутаций:

```java
final class PipelineEngine implements AutoCloseable {
    private final BlockingQueue<EngineCommand> inbox;     // команды от UI и исполнителей
    private final ExecutorService workers =               // тяжёлая работа — вне актора
        Executors.newVirtualThreadPerTaskExecutor();

    // Цикл: взять команду → мутировать RunState → персистировать → раздать события → диспатчить READY-шаги
}
```

- **Готовность шага**: все `dependsOn` в `PASSED` → шаг диспатчится соответствующему исполнителю
  на виртуальном потоке; результат возвращается в inbox как команда (`StepCompleted`, `StepFailed`,
  `GateAnswered`, `AgentQuestionsPending`).
- **Политика FAIL судьи**: `iteration < maxIterations` → перезапуск целевой агент-фазы с
  `accumulated_errors` (errors.json — пишет движок); иначе `WAITING_GATE` с эскалацией
  (сброс / отмена / override с причиной).
- **Персистентность**: каждый переход синхронно пишется в StateStore ДО публикации события.
  Рестарт IDE → `PipelineRun` восстанавливается из последнего снапшота, шаги `RUNNING`
  переводятся в `FAILED(interrupted)` и предлагаются к ретраю.
- **Параллельные фичи** = несколько инстансов `PipelineRun` в одном движке; шаги разных прогонов
  исполняются независимо.

### Диаграмма состояний шага

```
PENDING ──deps ok──► READY ──dispatch──► RUNNING ──ok──► PASSED
                                            │ judge FAIL, iter<N ──► READY (re-run, errors.json)
                                            │ judge FAIL, iter=N ──► WAITING_GATE (эскалация)
                                            │ timeout/обрыв ───────► FAILED ──retry──► READY
                                            │ pending_questions ─► WAITING_INPUT ──ответы──► READY (re-run с answers)
GATE-шаг: READY ──► WAITING_GATE ──ответ──► PASSED (ответ+кто+когда → аудит)
```

---

## 4. Стейт и совместимость с хуками Forge

Два уровня, разделение принципиальное:

1. **Source of truth**: `~/.forgeide/state/<project-hash>/<pipeline>/<feature>/run.json` —
   каталог IDE, агент туда физически не пишет (не его working dir, пути ему не сообщаются).
2. **Проекция для хуков**: перед каждой агент-фазой движок записывает
   `<project>/ground/statements/<pipeline>/<feature>/manifest.json` **в формате pipeline-state
   Forge** — хуки внутри агент-процесса (tdd-guard, sod-enforcer, risk_ladder…) продолжают
   резолвить активный шаг по свежайшему манифесту и работать как вторая линия обороны без
   единой правки.

**Tamper-контроль**: после завершения агент-фазы движок сравнивает hash проекции с ожидаемым.
Расхождение = модель писала в control-plane (мимо state-write-guard) → шаг `FAILED(tampered)`,
проекция перезаписывается из source of truth, инцидент в аудит.

**Аудит**: append-only JSONL `~/.forgeide/state/.../audit.jsonl` (пишет только движок) +
зеркало агент-логов в `<project>/ground/ai-logs/<feature>/iter-NN/` (совместимо с `watch-agents.sh`
и `agentops.py`). Метрики MVP считаются по JSONL напрокат; SQLite-индекс — пост-MVP.

---

## 5. Формат пайплайн-файла

YAML в репо целевого проекта: `<project>/.forgeide/pipeline.yaml` (версионируется и ревьюится).
Канвас — редактор этого файла. Подстановки `${project.*}`, `${feature.*}`, `${params.*}`.

```yaml
version: 1
id: forgelite
params:                # расширяемая форма проекта (БТ §4.1)
  jira_key: {required: true}
  spec_path: {required: true, hint: "source of truth спека"}
steps:
  - id: lite-ground
    type: agent
    runtime: gigacode
    prompt: prompts/lite-ground.md          # относительный путь в репо
    expects: [ground/grounding-excerpt.json]
  - id: lite-design
    type: agent
    depends_on: [lite-ground]
    prompt: prompts/lite-design.md
    expects: [docs/forgelite/${feature.slug}/tech-design.md, task-plan.json]
  - id: gate-design
    type: gate
    depends_on: [lite-design]
    question: "Утвердить tech-design?"
    show: [docs/forgelite/${feature.slug}/tech-design.md]
  - id: lite-red
    type: agent
    depends_on: [gate-design]
    prompt: prompts/lite-red.md
  - id: judge-red
    type: judge
    target: lite-red
    check: {command: [python3, .gigacode/skills/forgelite/scripts/check_tests_red.py]}
    fail_policy: {max_iterations: 3}
  # … lite-green → judge-coverage → gate-deliver → lite-deliver
```

Валидация при загрузке/сохранении: ацикличность (топосорт), достижимость всех шагов, у каждого
`agent`-шага перед деливери есть судья, ссылки `target`/`routes` существуют. Ошибки — с координатами
шага, подсветка на канвасе.

---

## 6. Адаптеры рантаймов (`runtime`)

```java
interface AgentRuntime {
    AgentResult execute(AgentInvocation inv, Consumer<AgentEvent> onEvent) throws AgentException;
}
record AgentInvocation(Path workingDir, String prompt, Duration timeout,
                       long tokenBudget, Map<String,String> env) {}
record AgentResult(int exitCode, Optional<JsonNode> finalJson,   // step_id, artifacts, pending_questions
                   TokenUsage usage, Path rawLog) {}
```

| Адаптер | Команда | Поток вывода |
|---|---|---|
| `ClaudeRuntime` | `claude -p <prompt> --output-format stream-json --verbose` | JSONL-события; итоговое `result`-событие → finalJson, cost/usage |
| `GigacodeRuntime` | `gigacode --experimental-hooks -p <prompt>` | JSONL; формат фиксируется fixtures-тестами |
| `QwenRuntime` | `qwen --experimental-hooks -p <prompt>` | аналогично |

Общая база `ProcessRunner`:
- `ProcessBuilder` с `workingDir` = целевой проект; env санитизируется (белый список + креды MCP из конфига проекта);
- stdout читается построчно на виртуальном потоке → `AgentEvent` (для live-лога UI и учёта токенов);
- **токен-бюджет**: `usage` из stream-событий; превышение → `destroyForcibly()` всего дерева
  процессов (`ProcessHandle.descendants()`), шаг `FAILED(budget)`;
- таймаут аналогично; обрыв стрима (EOF без result-события) → `FAILED(stream)` → ретрай по политике;
- перед первым запуском прогона — `preflight.py` (exit != 0 → прогон не стартует: «enforcement off»).

`pending_questions` в finalJson → движок публикует `GateRequest` c вопросами → ответы человека →
перезапуск той же фазы с блоком `answers` в промпте (паттерн jira-task-writer).

**Судьи**: `JudgeExecutor` = композиция. LLM-судья — тот же `AgentRuntime` (отдельный дешёвый вызов),
verdict.json забирает движок (`--from-output`), затем детерминированный `--recheck`/`check_*.py`
через `ScriptExecutor`. Вердикт хранится в `StepRun.verdicts` — агенту недоступен.

### 6.1 Перехват stdio (`ProcessRunner`)

```
        движок (JVM)                                    агент-процесс
  промпт ──► stdin: write + close ────────────────────► читает промпт
  pump-поток №1 ◄── pipe stdout ◄────────────────────── stream-json (JSONL)
  pump-поток №2 ◄── pipe stderr ◄────────────────────── варнинги/диагностика
```

1. **stdin.** Промпт передаётся через stdin (`echo | claude -p`), а не аргументом командной строки:
   нет лимита ARG_MAX, текст промпта не светится в `ps aux` и истории процессов. После записи
   stdin закрывается — headless-режим не интерактивен, вопросы агента идут только через
   `pending_questions` в итоговом JSON.
2. **stdout.** `redirectErrorStream(false)` — потоки НЕ смешиваются, иначе stderr-мусор ломает
   парсинг JSONL. Pump-поток (виртуальный) читает построчно (`BufferedReader`, UTF-8):
   - строка сразу append'ится на диск в raw-лог — синхронная запись и есть дренаж пайпа;
   - затем попытка Jackson-парсинга: JSON → типизированный `AgentEvent` (`tool_use`, `usage`,
     `result`); не JSON → событие `RawLine` (рантаймы печатают варнинги в stdout — парсер
     толерантный, не падает);
   - события уходят батчами в UI (100 мс) и в учёт токен-бюджета.
3. **stderr.** Отдельный pump-поток → `stderr.log` + кольцевой буфер для UI. Дренировать
   ОБА пайпа обязательно: незабранный пайп заполняется (~64 КБ) и дочерний процесс виснет
   на `write` — классический дедлок `ProcessBuilder`.
4. **Завершение.** `process.waitFor()` + join обоих pump-потоков (иначе теряется хвост вывода).
   Критерий успеха = exit-код 0 **и** наличие `result`-события; EOF без result = `FAILED(stream)`.
5. **Кап на вывод.** Лимит raw-лога на шаг (дефолт 512 МБ) → kill дерева процессов,
   `FAILED(log_overflow)` — защита от зациклившегося на выводе процесса.

### 6.2 Схема логов — кто что пишет

| Файл | Кто пишет | Содержимое | Доверие |
|---|---|---|---|
| `~/.forgeide/state/.../audit.jsonl` | движок | конверт `{ts, runId, stepId, iter, type, payload}`: переходы, вердикты, ответы гейтов, hash промптов | trusted |
| `ground/ai-logs/<feature>/iter-NN/<step>/stdout.jsonl` | pump-поток | сырой stream-json как есть | untrusted |
| `…/<step>/stderr.log` | pump-поток | stderr как есть | untrusted |
| `…/<step>/meta.json` | движок | команда, env-белый список, отрендеренный промпт целиком, тайминги, exit-код, usage | trusted |
| лог самой IDE (logback) | IDE | ошибки приложения, не прогонов | — |

- Полный отрендеренный промпт лежит в `meta.json` — фаза воспроизводима байт-в-байт;
  в `audit.jsonl` только hash (не раздувать журнал).
- Маскирование секретов (токены, `Authorization`, значения из env) — регэксп-маскер на
  trusted-копиях ДО записи; raw-логи не трогаем, но `ground/ai-logs/` добавляется в
  `.gitignore` целевого проекта.
- Раскладка `ai-logs/<feature>/iter-NN/` совместима с `watch-agents.sh` и `agentops.py` —
  живой tail и метрики Forge работают и снаружи IDE.

---

## 7. UI (`ui`, JavaFX)

MVVM: ViewModel подписан на событийную шину движка; мост в FX-поток — `Platform.runLater`
(события — иммутабельные снапшоты, гонок нет).

| Компонент | Реализация |
|---|---|
| Канвас пайплайна | свой node-graph на `Pane`: узел = `Region` (стилизация CSS по типу/статусу), ребро = `CubicCurve`; layout — топологические колонки (упрощённый Sugiyama). Готовые FX-graph-библиотеки не берём (заброшены) |
| Инспектор плитки | `TabPane`: промпт (RichTextFX + markdown-подсветка), конфиг (форма по типу шага), diff с git-версией; dry-run судьи на артефактах последней итерации; предпросмотр рендера промпта |
| Палитра/конструктор | drag&drop плиток из палитры, протяжка рёбер = `depends_on`, undo/redo; live-валидация с бейджами ошибок; вкладка YAML — второе представление той же модели |
| Run view | тот же канвас с оверлеем статусов (цвет/бейдж итераций) + нижняя панель: live-лог (tail JSONL, фильтр по шагу), вкладка артефактов |
| Гейт | модальный `Dialog` c рендером markdown-артефакта/диффа и кнопками-вариантами; ответ уходит командой `GateAnswered(user, ts)` |
| Проект | форма создания + динамические params из `pipeline.yaml`; менеджер рантаймов (путь к бинарю, флаги, проверка `--version`) |

Live-лог: `Tailer` на виртуальном потоке → батчинг строк (раз в 100 мс) → FX-поток, кольцевой буфер
10k строк на шаг (защита от OOM на gradle-выводах).

---

## 8. Импорт Forge-обвязки (`importer`)

Разбор прозы SKILL.md ненадёжен → **шаблонный подход**:

1. В ресурсах IDE лежат готовые `pipeline.yaml`-шаблоны для `forgelite` и `feature-pipeline`,
   написанные вручную по `FORGE.md` / `pipeline-technical.md`.
2. Импортёр сканирует указанный каталог обвязки: находит `skills/*/SKILL.md`,
   `subagent-prompts.md` (контракты §4.x — режутся по заголовкам в отдельные prompt-файлы),
   `hooks/settings.hooks.json`, скрипты `check_*.py` — и **привязывает пути** к плиткам шаблона.
3. Несматченные плитки подсвечиваются: пользователь указывает файл вручную.
4. Реестр плиток заполняется из `SKILLS-REGISTRY.md` (owner/validity/scope) — протухшая validity
   → warning-бейдж на плитке.

---

## 9. Инварианты безопасности → механизмы

| Инвариант (БТ §5) | Механизм |
|---|---|
| Модель не управляет переходами | `PipelineRun` мутируется только актором движка; source of truth вне working dir агента; tamper-hash проекции манифеста |
| Гейт нельзя не показать | `GateStep` исполняется UI-диалогом; движок не имеет кода «пропустить гейт», кроме явного ответа с аудитом |
| Судья вне досягаемости | вердикты в памяти/стейте IDE; ингест LLM-вердикта всегда завершается детерминированным `--recheck` |
| Лимиты в рантайме | таймаут и токен-бюджет → kill дерева процессов; счётчик итераций — поле движка |
| Структурная изоляция фаз | одна фаза = один процесс со своим контекстом; «inline» не существует как понятие |
| Хуки — вторая линия | проекция манифеста в формате pipeline-state + деплой хуков кнопкой (обёртка deploy.sh) + preflight перед прогоном |
| Аудит без модели | JSONL пишет только движок; логи агентов — сырые стримы, помечены как untrusted |

---

## 10. Технологии и библиотеки

| Область | Выбор | Примечание                                                                             |
|---|---|----------------------------------------------------------------------------------------|
| JDK | 21 LTS | виртуальные потоки, records, sealed, pattern matching                                  |
| UI | JavaFX 21 (openjfx plugin) | дистрибуция — jar с последующим запуском на локальной машине                           |
| Редактор | RichTextFX | markdown/python/json подсветка через RegEx-лексеры                                     |
| JSON/YAML | Jackson (+ dataformat-yaml) | одна либа на конфиг, стейт, stream-json                                                |
| Логи | SLF4J + Logback | лог самой IDE ≠ аудит прогонов                                                         |
| Тесты | JUnit 5 + AssertJ | движок — property-style тесты переходов; адаптеры — на записанных fixtures stream-json |
| DI/фреймворк | нет (ручная сборка) | Spring не нужен прототипу                                                              |

---

## 11. План реализации (вехи)

| Веха | Содержимое | Критерий готовности |
|---|---|---|
| **M1 Каркас** | модули, домен, загрузка/валидация `pipeline.yaml`, канвас read-only, проект+рантаймы | открыть шаблон forgelite → граф на канвасе; невалидный YAML → ошибки с подсветкой |
| **M2 Движок** | PipelineEngine + StateStore + аудит; ScriptExecutor; один AgentRuntime (gigacode или claude); run view + live-лог | пайплайн из script-шагов и 1 агент-фазы проходит end-to-end, резюмится после kill IDE |
| **M3 Гейты и судьи** | GateDialog, JudgeExecutor (LLM+recheck), fail-policy c итерациями, эскалация/override, проекция манифеста + tamper-hash, preflight | полный forgelite на реальном проекте: jira→…→deliver, все гейты через UI |
| **M4 Редактирование, конструктор, импорт** | инспектор с редакторами промптов и скриптов судей (+dry-run), конструктор на канвасе (палитра, рёбра, live-валидация), импортёр обвязки, реестр плиток | изменил промпт плитки → перезапуск шага с новым промптом; пайплайн, собранный из палитры, валиден и запускается |

Пост-MVP: полный feature-pipeline (per-task циклы, eval-plan), мульти-рантайм сравнение,
метрики/стоимость, `cli`-модуль для CI, удалённые гейты.

---

## 12. Риски и меры

| Риск | Мера |
|---|---|
| Форматы stream-json у рантаймов различаются/дрейфуют | fixtures-тесты на записанных прогонах; адаптер падает громко при неизвестном формате |
| Дрейф формата манифеста pipeline-state (хуки перестанут понимать проекцию) | контракт-тест: проекция скармливается реальным хукам из forge-репо (`test_state-recorder` и др.) |
| Прожорливость live-лога (gradle/JaCoCo) | кольцевой буфер, батчинг, полные логи только на диске |
| Свой канвас окажется дорогим | M1 ограничен read-only рендером; drag&drop конструктор — в M4, до этого правка YAML руками |
| Модель находит новый способ писать в control-plane | tamper-hash + инцидент в аудит; каталог source of truth вне проекта |
