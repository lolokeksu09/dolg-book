package dev.ventry.raschet.data

sealed interface ClientCommand {
    data class Create(val id: String, val name: String, val phone: String, val allowDuplicate: Boolean) : ClientCommand
    data class Edit(val id: String, val name: String, val phone: String) : ClientCommand
}
