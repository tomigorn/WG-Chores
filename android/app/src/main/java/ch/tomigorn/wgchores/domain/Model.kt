package ch.tomigorn.wgchores.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class By(val id: Long, @SerialName("display_name") val displayName: String, val former: Boolean = false)

@Serializable
data class LastDone(@SerialName("done_on") val doneOn: String, val by: By)

@Serializable
data class Chore(
    val id: Long,
    @SerialName("household_id") val householdId: Long = 0,
    val name: String,
    val category: String? = null,
    val interval: String? = null,
    val archived: Boolean = false,
    val last: LastDone? = null,
    @SerialName("days_since") val daysSince: Int? = null,
    @SerialName("due_level") val dueLevel: String = Due.NEVER,
    @SerialName("days_overdue") val daysOverdue: Int = 0,
    @SerialName("usual_interval") val usualInterval: Int? = null,
)

@Serializable
data class Household(val id: Long, val name: String, @SerialName("member_count") val memberCount: Int = 1)
