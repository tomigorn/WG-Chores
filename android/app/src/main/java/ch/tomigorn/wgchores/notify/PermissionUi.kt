package ch.tomigorn.wgchores.notify

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

class NotifyPermissionUi(val state: OsNotify, val request: (onGranted: () -> Unit) -> Unit, val openSettings: () -> Unit)

/** The phone's notification state, read again on every resume (the user may come back from the system settings). */
@Composable
fun rememberNotifyPermission(store: NotifyStore): NotifyPermissionUi {
    val context = LocalContext.current
    val asked by store.asked.collectAsStateWithLifecycle(initialValue = true)
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose {}
    }
    val state = remember(tick, asked) { osNotify(context, asked) }
    var onGranted by remember { mutableStateOf<(() -> Unit)?>(null) }
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        tick++
        if (granted) onGranted?.invoke()
        onGranted = null
    }
    val open = { openNotificationSettings(context) }
    return NotifyPermissionUi(
        state,
        request = { cb ->
            if (Build.VERSION.SDK_INT >= NotifyPermission.ASK_FROM_SDK) {
                onGranted = cb
                scope.launch { store.markAsked() }
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                open()
            }
        },
        openSettings = open,
    )
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

fun osNotify(context: Context, asked: Boolean): OsNotify {
    val sdk = Build.VERSION.SDK_INT
    val granted = sdk < NotifyPermission.ASK_FROM_SDK ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val rationale = sdk >= NotifyPermission.ASK_FROM_SDK &&
        context.activity()?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.POST_NOTIFICATIONS) } == true
    return NotifyPermission.state(sdk, NotificationManagerCompat.from(context).areNotificationsEnabled(), granted, rationale, asked)
}

fun openNotificationSettings(context: Context) {
    val app = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    for (i in listOf(app, details)) {
        if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}
