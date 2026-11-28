# Расчёт — Android-продукт
Цель: простая долговая книга небольшого магазина, offline-first, телефон/настенный планшет. Пакет dev.ventry.raschet; Kotlin/Java, Compose, SQLite, частичная MVVM, Rust JNI release. Не веб-сервис. [Ventry](https://github.com/lolokeksu09/Ventry) — отдельные корпоративный сайт, Delivery, Admin. Публичное demo использует вымышленные данные. Исторические dist/, server/, deploy/, Amvera не являются Android runtime или действующей общей книгой; не удалять их без решения владельца.

## Контракты
- Клиенты, долги/частичная оплата, календарь/история, исправления append-only, сроки, каталог, обещания, аналитика/отчёты, локальный экспорт и защищённое восстановление. Целые копейки, сохранение ID/порядка и существующих операций.
- SQLite хранит книгу; JSON версии 2–5 выбираются по содержимому; старые импорты сохраняются. Книга не равна всем настройкам: ClientDirectory и черновики имеют отдельное хранение. Restore создаёт локальную защищённую точку до изменения.
- Публичный лицензируемый вариант (opt-in Gradle profile): HTTPS license.ventrylab.top, Ed25519 entitlement. Обычная версия 0.30.0-beta.1 code35 нелицензируемая; public candidate 0.31.0-beta.1 code36. Signed кандидат и фактически зарегистрированный APK проверять отдельно.
- Модель разработки: trial 7 дней, 299 ₽/месяц, 799 ₽/3 месяца, 2490 ₽/год. Не действующая оферта. Истечение блокирует новые долги, сохраняет чтение/оплату/экспорт.
- Cloud VRB1: 3×16 МиБ, AES-GCM/PBKDF2, ручные 1/2, опциональный автоматический 3; пароль вне сервера, Android Keystore device-only wrapping для автоматики. Не совместная синхронизация. Политики хранения и recovery after expiry на новом устройстве не утверждены.
- Не менять подпись, package, пользовательские данные, schema/формат или финансовый смысл без проверенной совместимости. Не хранить реальные данные или секреты в публичном repo.

## Источники
[Точка продолжения](docs/CONTINUATION.md) · [единый план экосистемы](https://github.com/lolokeksu09/Ventry/blob/main/docs/ECOSYSTEM-STATUS.md) · [Android README](android/README.md) · [Cloud](docs/RASCHET-CLOUD-BACKUPS.md) · [License](docs/RASCHET-PUBLIC-LICENSE-CANDIDATE.md) · [Rust migration](docs/KOTLIN-MVVM-RUST-MIGRATION.md) · [AGENTS](AGENTS.md) · [TESTING-LEVELS](TESTING-LEVELS.md). Исторические [PLAN](docs/PLAN.md), [launch-plan](docs/launch-plan.md) описывают прототип/гипотезы, не активную очередь. Сотрудники и синхронизация — отдельный этап E-07.
Android: gradle -p android :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon; release требует четырёх ABI через scripts/build-rust-android.sh. Фактическое исполнение и результаты записывать отдельно. Новый signed main dispatch не автоматическая коммерческая публикация.
