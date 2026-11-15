package ch.tomigorn.wgchores.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.res.Configuration
import android.os.Bundle
import java.util.Locale
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

    /** Channel names in [lang] ("en"/"de"); called again after a language change, which renames them. */
    fun createChannels(lang: String) {
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(lang)) }
        val res = context.createConfigurationContext(config)
        nm.createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_DUE, NotificationManagerCompat.IMPORTANCE_DEFAULT).setName(res.getString(R.string.notify_channel_due)).build(),
                NotificationChannelCompat.Builder(CHANNEL_DONE, NotificationManagerCompat.IMPORTANCE_DEFAULT).setName(res.getString(R.string.notify_channel_done)).build(),
            ),
        )
    }

    override fun allowed(): Boolean = nm.areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < NotifyPermission.ASK_FROM_SDK || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    override fun due(account: String, household: Ref, names: List<String>, lang: String) {
        val tag = tag("due", account, household)
        val shown = shown(tag)?.extras?.getStringArrayList(EXTRA_NAMES).orEmpty()
        val all = NotifyMerge.due(shown, names)
        if (all == shown) return
        val (title, body) = NotifyTexts.due(lang, household.name, all)
        post(CHANNEL_DUE, tag, account, household, title, body) { it.putStringArrayList(EXTRA_NAMES, ArrayList(all)) }
    }

    override fun done(account: String, household: Ref, since: String?, items: List<DoneItem>, lang: String) {
        val tag = tag("done", account, household)
        val old = shown(tag)?.extras
        val shownItems = DoneItem.decode(old?.getStringArrayList(EXTRA_ITEMS))
        val all = NotifyMerge.done(shownItems, items)
        if (old != null && all == shownItems) return
        val from = if (old != null) old.getString(EXTRA_SINCE) else since
        post(CHANNEL_DONE, tag, account, household, household.name, NotifyTexts.done(lang, from, all.map { it.by to it.chore })) {
            it.putStringArrayList(EXTRA_ITEMS, DoneItem.encode(all))
            if (from != null) it.putString(EXTRA_SINCE, from)
        }
    }

    private fun tag(kind: String, account: String, household: Ref) = "$kind|$account|${household.id}"

    private fun shown(tag: String): Notification? =
        runCatching { context.getSystemService(NotificationManager::class.java).activeNotifications }.getOrNull()
            ?.firstOrNull { it.tag == tag && it.id == ID }?.notification

    fun cancelAll() = nm.cancelAll()

    @SuppressLint("MissingPermission")
    private fun post(channel: String, tag: String, account: String, household: Ref, title: String, body: String, extras: (Bundle) -> Unit) {
        check(allowed()) { "notifications not allowed" }
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.launcher_bg))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(if (channel == CHANNEL_DUE) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_SOCIAL)
            .addExtras(Bundle().also(extras))
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
        private const val EXTRA_NAMES = "ch.tomigorn.wgchores.notify.NAMES"
        private const val EXTRA_ITEMS = "ch.tomigorn.wgchores.notify.ITEMS"
        private const val EXTRA_SINCE = "ch.tomigorn.wgchores.notify.SINCE"

        fun openIntent(context: Context, account: String, household: Long, requestCode: Int): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN)
                .putExtra(EXTRA_ACCOUNT, account)
                .putExtra(EXTRA_HOUSEHOLD, household)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        /** The household a notification tap asks for; only for the account signed in now and a household it knows (the activity is exported). */
        suspend fun household(intent: Intent?, signedIn: String?, known: suspend (Long) -> Boolean): Long? {
            if (intent?.action != ACTION_OPEN || signedIn == null) return null
            if (intent.getStringExtra(EXTRA_ACCOUNT) != signedIn) return null
            return intent.getLongExtra(EXTRA_HOUSEHOLD, 0).takeIf { it > 0 && known(it) }
        }
    }
}
