package dev.ventry.raschet.presentation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import dev.ventry.raschet.CollectionsInsights
import dev.ventry.raschet.ClientWriteService
import dev.ventry.raschet.data.ClientCommand
import dev.ventry.raschet.LedgerStore
import dev.ventry.raschet.data.LedgerSnapshot
import dev.ventry.raschet.data.SqliteLedgerRepository
import java.time.LocalDate
import java.util.Collections
import java.util.function.Consumer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class BookScreenData(val snapshot: LedgerSnapshot, val today: LocalDate, statuses: List<CollectionsInsights.CustomerStatus>) {
    val statuses: List<CollectionsInsights.CustomerStatus> = Collections.unmodifiableList(ArrayList(statuses))
}

/** No Activity, View or activity-owned SQLite helper is retained. */
class BookViewModel(application: Application) : AndroidViewModel(application) {
    private val readMutex = Mutex()
    private val reader = LatestRead(viewModelScope) {
        val snapshot = withContext(Dispatchers.IO) {
            readMutex.withLock {
                LedgerStore(application).use { store -> SqliteLedgerRepository(store).snapshot() }
            }
        }
        withContext(Dispatchers.Default) {
            val today = LocalDate.now()
            BookScreenData(snapshot, today, CollectionsInsights.analyze(snapshot.clients, snapshot.entries, today))
        }
    }
    val state = reader.state
    private val writer = SingleWrite<ClientCommand, Boolean>(viewModelScope) { command ->
        withContext(Dispatchers.IO) {
            readMutex.withLock { ClientWriteService.execute(application, command) }
        }
    }
    val writeState = writer.state

    fun submitClient(command: ClientCommand): Boolean {
        if (!writer.submit(command)) return false
        reader.clear()
        return true
    }

    fun acknowledgeWrite(result: WriteState<Boolean>) = writer.acknowledge(result)
    fun hasPendingWrite(): Boolean = writeState.value != WriteState.Idle

    fun observeWrites(owner: ComponentActivity, listener: Consumer<WriteState<Boolean>>) {
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                writeState.collect { listener.accept(it) }
            }
        }
    }

    fun refresh() { if (!hasPendingWrite()) reader.refresh() }
    fun clear() = reader.clear()

    /** Java Views collect only while started; Compose can later collect the same StateFlow. */
    fun observe(owner: ComponentActivity, listener: Consumer<ReadState<BookScreenData>>) {
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                state.collect { listener.accept(it) }
            }
        }
    }

    override fun onCleared() {
        reader.clear()
        super.onCleared()
    }
}
