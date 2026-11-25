package dev.ventry.raschet.presentation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

fun verifyLatestRead() = runBlocking {
    val requests = Channel<CompletableDeferred<String>>(Channel.UNLIMITED)
    val reader = LatestRead(this) {
        val response = CompletableDeferred<String>()
        requests.send(response)
        // Model a blocking database operation that finishes after cancellation.
        withContext(NonCancellable) { response.await() }
    }
    check(reader.state.value == ReadState.Idle)
    reader.refresh()
    val old = requests.receive()
    check(reader.state.value is ReadState.Loading)
    reader.refresh()
    val fresh = requests.receive()
    fresh.complete("fresh")
    yield()
    check((reader.state.value as ReadState.Ready).value == "fresh")
    old.complete("stale")
    yield()
    check((reader.state.value as ReadState.Ready).value == "fresh")
    reader.refresh()
    val pending = requests.receive()
    reader.clear()
    pending.complete("private")
    yield()
    check(reader.state.value == ReadState.Idle)
    reader.refresh()
    requests.receive().completeExceptionally(IllegalStateException("read failed"))
    yield()
    check(reader.state.value is ReadState.Failed)
    reader.refresh()
    requests.receive().complete("retry")
    yield()
    check((reader.state.value as ReadState.Ready).value == "retry")
    // A navigation burst joins the pending request and reuses its completed value.
    reader.clear()
    check(reader.ensureFresh { true } == null)
    val shared = requests.receive()
    repeat(20) { check(reader.ensureFresh { true } == null) }
    check(requests.tryReceive().isFailure)
    shared.complete("today")
    yield()
    repeat(20) { check(reader.ensureFresh { it == "today" } == "today") }
    check(requests.tryReceive().isFailure)
    // Date rollover and explicit invalidation require another read.
    check(reader.ensureFresh { it == "tomorrow" } == null)
    requests.receive().complete("tomorrow")
    yield()
    check(reader.ensureFresh { it == "tomorrow" } == "tomorrow")
    reader.clear()
    check(reader.ensureFresh { true } == null)
    val afterClear = requests.receive()
    reader.clear()
    afterClear.complete("private")
    yield()
    check(reader.state.value == ReadState.Idle)
    requests.close()
}

fun main() { verifyLatestRead(); println("LATEST_READ_CONTRACT_PASS") }
