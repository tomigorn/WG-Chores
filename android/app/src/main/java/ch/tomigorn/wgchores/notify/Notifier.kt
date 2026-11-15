package ch.tomigorn.wgchores.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ch.tomigorn.wgchores.MainActivity
import ch.tomigorn.wgchores.R

/** Local notifications: one per household for due chores, one per household for done by others; a tap opens that household. */
class Notifier(private val context: Context) : NotifyShow {
    private val nm get() = NotificationManagerCompat.from(context)

    fun createChannels() {
        nm.createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_DUE, NotificationManagerCompat.IMPORTANCE_DEFAULT).setName(context.getString(R.string.notify_channel_due)).build(),
                NotificationChannelCompat.Builder(CHANNEL_DONE, NotificationManagerCompat.IMPORTANCE_DEFAULT).setName(context.getString(R.string.notify_channel_done)).build(),
            ),
        )
    }

    override fun allowed(): Boolean = nm.areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < NotifyPermission.ASK_FROM_SDK || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    override fun due(account: String, household: Ref, title: String, body: String) = post(CHANNEL_DUE, "due", account, household, title, body)

    override fun done(account: String, household: Ref, title: String, body: String) = post(CHANNEL_DONE, "done", account, household, title, body)

    fun cancelAll() = nm.cancelAll()

    @SuppressLint("MissingPermission")
    private fun post(channel: String, kind: String, account: String, household: Ref, title: String, body: String) {
        check(allowed()) { "notifications not allowed" }
        val tag = "$kind|$account|${household.id}"
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.launcher_bg))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(if (kind == "due") NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_SOCIAL)
            .setContentIntent(openIntent(context, account, household.id, tag.hashCode()))
            .setAutoCancel(true)
            .build()
        nm.notify(tag, ID, n)
    }

    companion object {
        const val CHANNEL_DUE = "due"
        const val CHANNEL_DONE = "done"
        const val ACTION_OPEN = "ch.tomigorn.wgchores.action.OPEN_HOUSEHOLD"
        const val EXTRA_ACCOUNT = "ch.tomigorn.wgchores.extra.ACCOUNT"
        const val EXTRA_HOUSEHOLD = "ch.tomigorn.wgchores.extra.HOUSEHOLD"
        const val ID = 1

        fun openIntent(context: Context, account: String, household: Long, requestCode: Int): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN)
                .putExtra(EXTRA_ACCOUNT, account)
                .putExtra(EXTRA_HOUSEHOLD, household)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        /** The household a notification tap asks for; only for the account signed in now (the activity is exported). */
        fun household(intent: Intent?, signedIn: String?): Long? {
            if (intent?.action != ACTION_OPEN || signedIn == null) return null
            if (intent.getStringExtra(EXTRA_ACCOUNT) != signedIn) return null
            return intent.getLongExtra(EXTRA_HOUSEHOLD, 0).takeIf { it > 0 }
        }
    }
}
