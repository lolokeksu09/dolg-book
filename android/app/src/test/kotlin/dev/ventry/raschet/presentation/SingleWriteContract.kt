package dev.ventry.raschet.presentation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

fun verifySingleWrite() = runBlocking {
    val requests = Channel<Pair<String, CompletableDeferred<Int>>>(Channel.UNLIMITED)
    var calls = 0
    val writer = SingleWrite(this) { command: String ->
        calls++
        val response = CompletableDeferred<Int>()
        requests.send(command to response)
        response.await()
    }
    check(writer.state.value == WriteState.Idle)
    check(writer.submit("create"))
    check(!writer.submit("duplicate"))
    val (first, response) = requests.receive()
    check(first == "create" && calls == 1)
    check(writer.state.value == WriteState.Saving)
    writer.acknowledge(WriteState.Saving)
    check(!writer.submit("still busy"))
    response.complete(1)
    yield()
    val done = writer.state.value
    check(done is WriteState.Done && done.value == 1)
    check(!writer.submit("unacknowledged"))
    writer.acknowledge(WriteState.Done(1))
    check(writer.state.value === done)
    writer.acknowledge(done)
    check(writer.submit("invalid"))
    requests.receive().second.completeExceptionally(IllegalArgumentException("invalid name"))
    yield()
    val failed = writer.state.value
    check(failed is WriteState.Failed && failed.message == "invalid name")
    check(!writer.submit("retry before acknowledgment"))
    writer.acknowledge(failed)
    check(writer.submit("retry"))
    requests.receive().second.complete(2)
    yield()
    val retried = writer.state.value
    check(retried is WriteState.Done && retried.value == 2 && calls == 3)
    writer.acknowledge(retried)
    check(writer.state.value == WriteState.Idle)
    requests.close()
}

fun main() { verifySingleWrite(); println("SINGLE_WRITE_CONTRACT_PASS") }
