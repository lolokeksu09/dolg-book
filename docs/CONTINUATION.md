# Точка продолжения

## Снимок 28.11.2026
- main dcbf02c0d91e5bce730e74b6bb0ac58fa419be09, PR #31 7/7 success; [main contract](https://github.com/lolokeksu09/dolg-book/actions/runs/46123275955) success. Автоматический слот 3 в исходниках; ручные слоты, локальная книга и restore сохранены. Физический JobScheduler/Keystore ещё не принят.
- Последний подписанный кандидат [46111775220](https://github.com/lolokeksu09/dolg-book/actions/runs/46111775220), `31ec0b320d0dfd52b19780837a71503f58507324`, `0.31.0-beta.1` / `36`, до автоматики. ZIP в Actions до 05.12.2026 08:25 МСК. Для E-02 сначала согласовать следующую версию профиля в Gradle и Actions, затем пройти [приёмку подписанного кандидата](RASCHET-ACCEPTANCE.md).
- Ventry owner подтвердил schema 007, private/public cloud ready и 404 синтетических отказов. Реальный клиент, перенос, restore и expiry — не проверены.
- E-05: разделение MainActivity и состояния, понятный статус автокопии при превышении облачного лимита. Копия JSON поддерживает импорт до 256 МиБ, а облачный JSON до 16 МиБ минус 48 байт. Общая книга и сотрудники относятся к E-07.
- MainActivity.java 2294 строки, LedgerStore.java 780 строк; Compose/ViewModel есть, миграция неполная. Не заявлять полную Rust/MVVM замену.
- Источник общего статуса, владельческих решений и зависимостей: [ECOSYSTEM-STATUS](https://github.com/lolokeksu09/Ventry/blob/main/docs/ECOSYSTEM-STATUS.md). После новой ревизии обновить короткий снимок, не дописывать повтор истории.


[Исторический журнал до 28.11.2026](CONTINUATION-HISTORY.md) — открывать по конкретному вопросу.
