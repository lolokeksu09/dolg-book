package dev.ventry.raschet.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface WriteState<out T> {
    data object Idle : WriteState<Nothing>
    data object Saving : WriteState<Nothing>
    data class Done<T>(val value: T) : WriteState<T>
    data class Failed(val message: String) : WriteState<Nothing>
}

/** Main-thread owned single-flight command; a result must be acknowledged before another write. */
class SingleWrite<C, T>(private val scope: CoroutineScope, private val execute: suspend (C) -> T) {
    private val mutable = MutableStateFlow<WriteState<T>>(WriteState.Idle)
    val state = mutable.asStateFlow()

    fun submit(command: C): Boolean {
        if (mutable.value != WriteState.Idle) return false
        mutable.value = WriteState.Saving
        scope.launch {
            try {
                mutable.value = WriteState.Done(execute(command))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IllegalArgumentException) {
                mutable.value = WriteState.Failed(failure.message ?: "Не удалось сохранить запись.")
            } catch (_: Exception) {
                mutable.value = WriteState.Failed("Не удалось сохранить запись.")
            }
        }
        return true
    }

    fun acknowledge(result: WriteState<T>) {
        if (mutable.value === result && (result is WriteState.Done || result is WriteState.Failed)) {
            mutable.value = WriteState.Idle
        }
    }
}
