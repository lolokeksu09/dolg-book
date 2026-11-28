# Точка продолжения

## Снимок 28.11.2026
- Автокопирование добавлено в PR #31 (7/7 CI); [main contract](https://github.com/lolokeksu09/dolg-book/actions/runs/46123275955) success. Автокопирование использует слот 3, ручные копии — слоты 1 и 2. Физический JobScheduler/Keystore ещё не принят.
- Единый подписанный APK `0.31.0-beta.2` / `37`: [Actions 46133123937](https://github.com/lolokeksu09/dolg-book/actions/runs/46133123937) success, exact revision `1cb645bf84b6c280dccb009d4841929d7b813ab8`; [артефакт 15257838809](https://github.com/lolokeksu09/dolg-book/actions/runs/46133123937/artifacts/15257838809), ZIP SHA-256 `9b4a5a17f1ddab5cc72104584662358295e11f609d0f4e211d875f5302f3edf1`, доступен до 05.12.2026 13:04 МСК. Проверки CI подписи, manifest cloud и Rust JNI прошли; физическая [приёмка](RASCHET-ACCEPTANCE.md) и утверждение в Delivery отдельно. Постоянное правило единого APK — [AGENTS](../AGENTS.md).
- Ventry owner подтвердил schema 007, private/public cloud ready и 404 синтетических отказов. Реальный клиент, перенос, restore и expiry — не проверены.
- E-05: разделение MainActivity и состояния, понятный статус автокопии при превышении облачного лимита. Копия JSON поддерживает импорт до 256 МиБ, а облачный JSON до 16 МиБ минус 48 байт. Общая книга и сотрудники относятся к E-07.
- Навигация и параметры SAF в Kotlin `BookNavigationViewModel`. В `MainActivity.java` остаются экранные фильтры, диалоги и связывание UI, в Java `LedgerStore` — транзакции SQLite. Rust JNI в release выполняет расчёт исправлений.
- Источник общего статуса, владельческих решений и зависимостей: [ECOSYSTEM-STATUS](https://github.com/lolokeksu09/Ventry/blob/main/docs/ECOSYSTEM-STATUS.md). После новой ревизии обновить короткий снимок, не дописывать повтор истории.


[Исторические планы и журнал до 28.11.2026](https://github.com/lolokeksu09/dolg-book/tree/e36eeb8f9fb9ab0c9de702b737687c82e6f5d167/docs).
