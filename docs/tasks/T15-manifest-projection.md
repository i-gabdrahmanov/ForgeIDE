# T15 — Проекция манифеста + tamper-hash

- **Веха:** M3
- **Зависит от:** T07
- **Спека:** SD §4; SDD FR-7.2, SR-2; Т-1; NFR-6

## Скоуп

- Генерация `<project>/ground/statements/<pipeline>/<feature>/manifest.json` в формате
  pipeline-state Forge из SoT перед каждой агент-фазой (маппинг статусов IDE → completed/pending).
- SHA-256 проекции запоминается; после фазы — сверка: расхождение → `FAILED(tampered)`,
  восстановление проекции из SoT, событие `incident.tamper` с диффом.
- Контракт-тесты совместимости: проекция скармливается реальным хукам из forge-репо
  (state-recorder, tdd-guard резолвят активный шаг) — NFR-6.
- Ингест `_origins`-записей от SubagentStop (state-recorder пишет их в ground) — движок
  читает как evidence, но переходы делает сам.

## Вне скоупа

- Деплой хуков и hash обвязки (T18).

## Приёмка

- [x] GWT Т-1/SR-2 автотестом: имитатор дописывает шаг в manifest.json → FAILED(tampered), проекция восстановлена, incident.tamper в аудите
- [x] контракт-тест: `tdd-guard` из forge-репо корректно резолвит активный шаг по проекции
- [x] оверхед генерация+сверка ≤ 2 с (NFR-4, часть бюджета)
