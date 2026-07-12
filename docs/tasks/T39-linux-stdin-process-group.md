# T39 — Linux: stdin фаз и группа процессов (вторая причина красного CI)

- **Веха:** M5
- **Зависит от:** T08, T38
- **Спека:** SD §6 (промпт через stdin), SDD SR-9 (kill группы), NFR-5 (macOS + Linux);
  артефакты CI прогона №26 (test-results через nightly.link)

T38 устранил первую причину красного CI (порог покрытия), но прогон №26 остался красным.
Из test-results артефакта: падают два runtime-теста, оба только на Linux —
`ProcessRunnerTest#stdinIsDeliveredThenClosedAndOnlyExplicitEnvIsVisible` (нет строки из
stdin) и `ForgeHooksContractTest#tddGuardResolvesTheActiveStepFromOurProjection` (хук
вернул 0 вместо 2). Оба — один корень, и это продуктовый баг, а не тестовый.

## Корень

Обёртка `ProcessGroupLauncher` запускала ребёнка как `set -m; "$@" & …` через `/bin/sh`:

- **POSIX**: асинхронной (`&`) команде без явного редиректа stdin подменяется на
  `/dev/null`. macOS `/bin/sh` — bash, под `set -m` он stdin сохраняет; ubuntu `/bin/sh` —
  **dash**, который подменяет. На Linux **промпт агента и JSON-payload хуков уходили в
  /dev/null**: агент получал пустой промпт, tdd-guard не видел, что пишут в src/main —
  вся линия хук-энфорсмента была мертва (при этом exit 0 — «всё разрешено»).
- Вдобавок dash без controlling tty молча отключает `set -m` («job control turned off»)
  — фоновый ребёнок оставался в группе процессов обёртки, т.е. `kill -9 -pgid` по SR-9
  бил мимо (pid ребёнка — не лидер группы).
- Проверено на живом dash (он есть и на macOS): голый self-dup `<&0` dash тоже НЕ
  спасает — рабочий приём только fd3-дэнс `exec 3<&0; … <&3 3<&- &`.

## Фикс

`WRAPPER_SCRIPT` теперь: `exec 3<&0; if command -v setsid >/dev/null 2>&1; then setsid
"$@" <&3 3<&- & else set -m; "$@" <&3 3<&- & fi; printf '%s' "$!" > "$0"; wait "$!"`

- **stdin**: fd3-дэнс в обеих ветках — явный редирект отменяет POSIX-подмену на /dev/null;
- **группа процессов**: на Linux — `setsid` (util-linux, есть везде включая ubuntu-latest;
  новая сессия ⇒ новая группа, pid ребёнка == pgid, fd не трогаются); на macOS setsid
  отсутствует — остаётся прежний механизм `set -m` (bash, работает и без tty).

## Приёмка

- [x] dash-матрица локально (macOS `/bin/dash` = тот же шелл, что ubuntu `/bin/sh`):
      репро бага на старом скрипте; новый wrapper — stdin доставлен, exit code
      зеркалится, pgid-файл пишется; bash — stdin доставлен, группа ребёнка отделена
- [x] `:runtime:test` зелёный на macOS (bash-ветка wrapper'а — без регресса)
- [ ] реальный прогон GitHub Actions зелёный (setsid-ветка исполняется только на Linux;
      существующие `ProcessRunnerTest`/`ForgeHooksContractTest` — и есть регрессионные
      тесты, они падали именно под dash) — проверить после push/PR; это же закрывает
      отложенные галочки T26 и T38

## Вне скоупа

- Windows (NFR-5 пост-MVP), egress-фильтры (T29/пост-MVP).
