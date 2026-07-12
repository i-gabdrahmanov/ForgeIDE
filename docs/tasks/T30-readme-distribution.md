# T30 — README и кросс-платформенный дистрибутив

- **Веха:** M5
- **Зависит от:** —
- **Спека:** NFR-5 (macOS + Linux); аудит 2026-07 №6, №7

## Скоуп

- `README.md` в корне репо: что такое ForgeIDE, требования (JDK 21, git, python3),
  запуск из исходников (`./gradlew :ui:run`), сборка и запуск дистрибутива,
  карта модулей, ссылки на docs/bt, docs/sd, docs/sdd, docs/tasks.
- Кросс-платформенная дистрибуция — выбрать и зафиксировать один из вариантов:
  1. per-OS артефакты: javafx-classifier по платформе, имя zip содержит платформу
     (`ui-0.1.0-mac-aarch64.zip`, `ui-0.1.0-linux-x64.zip`);
  2. jlink-образ на macOS + Linux.
  Текущий `ui-0.1.0.zip` содержит только `mac-aarch64` JavaFX-jars без указания
  платформы в имени — так оставлять нельзя.

## Вне скоупа

- Инсталляторы, подпись, нотаризация; Windows (пост-MVP по NFR-5).

## Решение

Вариант 1 (per-OS артефакты). `./gradlew :ui:platformDistZips` собирает
`ui-<version>-mac-aarch64.zip` и `ui-<version>-linux-x64.zip` на одной машине:
каждый таргет резолвит `org.openjfx:javafx-*` через platform/arch-атрибуты
конфигурации (`org.gradle.native.operatingSystem`/`architecture`) — тот же
механизм, что и сам javafx-gradle-plugin, без classifier-нотации (она
конфликтует с variant-based резолюцией и падает с ambiguity). См.
`ui/build.gradle.kts`.

## Приёмка

- [x] README покрывает запуск с нуля на чистой машине (macOS и Linux) — проверка по шагам
- [x] артефакты дистрибуции либо per-OS с платформой в имени, либо jlink-образ на обе ОС
- [x] `:ui:run` и запуск из дистрибутива работают по инструкции README —
      mac-aarch64 запущен локально (реальное окно JavaFX), linux-x64 проверен
      в Linux x86_64 контейнере (Xvfb, окно "ForgeIDE" отрендерилось, скриншот
      подтверждён)
