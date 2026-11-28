# Точка продолжения

## Снимок 28.11.2026
- main dcbf02c0d91e5bce730e74b6bb0ac58fa419be09, PR #31 7/7 success; [main contract](https://github.com/lolokeksu09/dolg-book/actions/runs/46123275955) success. Автоматический слот 3 в исходниках; ручные слоты, локальная книга и restore сохранены. Физический JobScheduler/Keystore ещё не принят.
- Предыдущий подписанный кандидат [46111775220](https://github.com/lolokeksu09/dolg-book/actions/runs/46111775220), revision 31ec0b320d0dfd52b19780837a71503f58507324, code36, до автоматики. Следующий signed main dispatch с существующим публичным pin; проверить certificate, SHA, upgrade и различимость версии. Не выдавать предыдущий APK за новый.
- Ventry owner подтвердил schema 007, private/public cloud ready и 404 синтетических отказов. Реальный клиент, перенос, restore и expiry — не проверены.
- Следующий этап E-02: signed physical acceptance, затем E-05: дальнейшее разделение MainActivity, восстановление состояния, измеренная устойчивость cloud job. Общая книга/сотрудники только E-07 после утверждения архитектуры, не исторический server pilot.
- MainActivity.java 2294 строки, LedgerStore.java 780 строк; Compose/ViewModel есть, миграция неполная. Не заявлять полную Rust/MVVM замену.
- Источник общего статуса, владельческих решений и зависимостей: [ECOSYSTEM-STATUS](https://github.com/lolokeksu09/Ventry/blob/main/docs/ECOSYSTEM-STATUS.md). После новой ревизии обновить короткий снимок, не дописывать повтор истории.


[Исторический журнал до 28.11.2026](CONTINUATION-HISTORY.md) — открывать только по конкретному вопросу. 
