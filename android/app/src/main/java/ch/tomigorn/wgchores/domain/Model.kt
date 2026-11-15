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

@Serializable
data class Completion(
    val id: String,
    @SerialName("done_on") val doneOn: String,
    val note: String? = null,
    val by: By,
)

@Serializable
data class CompletionPage(val items: List<Completion>, val next: String? = null)

@Serializable
data class Member(
    val id: Long,
    @SerialName("display_name") val displayName: String,
    val username: String? = null,
    @SerialName("picture_url") val pictureUrl: String? = null,
)

@Serializable
data class InviteBy(val id: Long, @SerialName("display_name") val displayName: String)

@Serializable
data class Invite(
    val id: Long,
    @SerialName("created_by") val createdBy: InviteBy,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
data class NewInvite(val id: Long, val url: String, @SerialName("expires_at") val expiresAt: String)

@Serializable
data class InvitePreview(
    @SerialName("household_name") val householdName: String,
    @SerialName("invited_by") val invitedBy: String,
    @SerialName("expires_at") val expiresAt: String,
    val valid: Boolean,
    val reason: String? = null,
)

@Serializable
data class InviteAccepted(@SerialName("household_id") val householdId: Long, @SerialName("already_member") val alreadyMember: Boolean = false)

@Serializable
data class Device(
    val id: String,
    val kind: String = "",
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String = "",
    val current: Boolean = false,
)
