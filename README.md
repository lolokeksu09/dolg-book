# Расчёт
Нативная долговая книга для небольшого магазина. Android, Kotlin/Java, Jetpack Compose, SQLite, частичная MVVM, Rust JNI в release. Пакет dev.ventry.raschet; minSdk 26. Сайт Ventry представляет продукт, но не хранит реальную книгу.

## Возможности
Учёт долгов, частичных оплат, история и календарь, карточки клиентов, сроки возврата и обещания, аналитика, отчёты, JSON/CSV/PDF, защищённое локальное восстановление. Лицензируемый профиль добавляет Ed25519 entitlement и зашифрованные cloud backups; автоматический слот 3 — в текущих исходниках, физическая приёмка отдельно. Приложение не является кассой или сервисом синхронизации сотрудников.

## Начало работы
- [Состояние и следующий этап](docs/CONTINUATION.md)
- [Карточка проекта и контракты](PROJECT.md)
- [Android: сборка и структура](android/README.md)
- [Приёмка подписанной сборки «Расчёта»](docs/RASCHET-ACCEPTANCE.md)
- [Общий план Ventry](https://github.com/lolokeksu09/Ventry/blob/main/docs/ECOSYSTEM-STATUS.md)
- [Правила разработки](AGENTS.md), [проверки](TESTING-LEVELS.md)

Локальная debug-проверка:
```bash
gradle -p android :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
```
Release требует Rust для четырёх ABI через scripts/build-rust-android.sh. Подписанный лицензируемый кандидат — отдельный manual Actions workflow на точном main. Не считать debug APK или обычный RC коммерческим релизом. Не включать реальные данные, ключи подписи, пароли или копии в GitHub.

## Исторические компоненты
[Браузерный прототип v0.2](docs/WEB-PROTOTYPE-HISTORY.md), dist/, server/ и Amvera сохранены для истории и отдельных экспериментов. Они не являются текущим Android runtime. Не запускать на них реальные магазины без отдельной архитектурной приёмки.
