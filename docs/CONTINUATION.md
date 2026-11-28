# Точка продолжения

## Снимок 28.11.2026
- Автокопирование добавлено в PR #31 (7/7 CI); [main contract](https://github.com/lolokeksu09/dolg-book/actions/runs/46123275955) success. Автокопирование использует слот 3, ручные копии — слоты 1 и 2. Физический JobScheduler/Keystore ещё не принят.
- Последний подписанный кандидат [46111775220](https://github.com/lolokeksu09/dolg-book/actions/runs/46111775220), `31ec0b320d0dfd52b19780837a71503f58507324`, `0.31.0-beta.1` / `36`, до автоматики. ZIP в Actions до 05.12.2026 08:25 МСК. Для E-02 сначала согласовать следующую версию профиля в Gradle и Actions, затем пройти [приёмку подписанного кандидата](RASCHET-ACCEPTANCE.md).
- Ventry owner подтвердил schema 007, private/public cloud ready и 404 синтетических отказов. Реальный клиент, перенос, restore и expiry — не проверены.
- E-05: разделение MainActivity и состояния, понятный статус автокопии при превышении облачного лимита. Копия JSON поддерживает импорт до 256 МиБ, а облачный JSON до 16 МиБ минус 48 байт. Общая книга и сотрудники относятся к E-07.
- Навигация и параметры SAF в Kotlin `BookNavigationViewModel`. В `MainActivity.java` остаются экранные фильтры, диалоги и связывание UI, в Java `LedgerStore` — транзакции SQLite. Rust JNI в release выполняет расчёт исправлений.
- Источник общего статуса, владельческих решений и зависимостей: [ECOSYSTEM-STATUS](https://github.com/lolokeksu09/Ventry/blob/main/docs/ECOSYSTEM-STATUS.md). После новой ревизии обновить короткий снимок, не дописывать повтор истории.


[Исторические планы и журнал до 28.11.2026](https://github.com/lolokeksu09/dolg-book/tree/e36eeb8f9fb9ab0c9de702b737687c82e6f5d167/docs).
