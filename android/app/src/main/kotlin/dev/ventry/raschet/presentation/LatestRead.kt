package dev.ventry.raschet.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ReadState<out T> {
    data object Idle : ReadState<Nothing>
    data class Loading(val request: Long) : ReadState<Nothing>
    data class Ready<T>(val request: Long, val value: T) : ReadState<T>
    data class Failed(val request: Long) : ReadState<Nothing>
}

/** All commands and state publications run on the owning main-thread scope. */
class LatestRead<T>(private val scope: CoroutineScope, private val load: suspend () -> T) {
    private val mutable = MutableStateFlow<ReadState<T>>(ReadState.Idle)
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null

    /** Reuse a valid committed result; join an in-flight read instead of restarting it.
     * Failed/idle/stale results trigger a fresh read. Explicit refresh and clear stay authoritative.
     */
    fun ensureFresh(valid: (T) -> Boolean): T? {
        return when (val current = mutable.value) {
            is ReadState.Ready -> if (valid(current.value)) current.value else { refresh(); null }
            is ReadState.Loading -> null
            else -> { refresh(); null }
        }
    }

    fun refresh() {
        val request = ++generation
        job?.cancel()
        mutable.value = ReadState.Loading(request)
        job = scope.launch {
            try {
                val result = load()
                if (request == generation) mutable.value = ReadState.Ready(request, result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (request == generation) mutable.value = ReadState.Failed(request)
            }
        }
    }

    fun clear() {
        generation++
        job?.cancel()
        job = null
        mutable.value = ReadState.Idle
    }
}
