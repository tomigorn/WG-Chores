package ch.tomigorn.wgchores.notify

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Ref(val id: Long, val name: String)

@Serializable
data class DoneEvent(val household: Ref, val chore: Ref, val by: String, @SerialName("done_on") val doneOn: String)

@Serializable
data class DueGroup(val household: Ref, val chores: List<Ref>)

@Serializable
data class PollAnswer(val cursor: String, val done: List<DoneEvent> = emptyList(), val due: List<DueGroup> = emptyList(), val more: Boolean = false)
