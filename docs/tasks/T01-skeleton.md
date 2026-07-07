# T01 — Каркас: модули Gradle, JavaFX bootstrap

- **Веха:** M1
- **Зависит от:** —
- **Спека:** SD §1 (модули, правило зависимостей), §10 (стек)

## Скоуп

- Gradle multi-module: `core`, `runtime`, `importer`, `ui`; Java 21 toolchain.
- `ui`: плагин openjfx, пустое окно приложения запускается (`:ui:run`).
- Зависимости по SD §10: jackson (+yaml), slf4j+logback, richtextfx (ui), junit5+assertj.
- Правило зависимостей `ui → core ← runtime` закреплено в build-скриптах;
  `core` не тянет javafx и не содержит работы с процессами.
- Пакеты `dev.forgeide.<module>.*`; по smoke-тесту в каждом модуле.
- Сборка дистрибутива: обычный runnable jar (решение пользователя в SD §10).

## Вне скоупа

- jpackage/установщики, CI-конфигурация сервера.

## Приёмка

- [ ] `./gradlew build` зелёный, все модули компилируются, тесты идут
- [ ] `./gradlew :ui:run` открывает окно приложения
- [ ] в `core` нет зависимостей на javafx/ProcessBuilder-обёртки (проверка build-скриптом или ArchUnit)
