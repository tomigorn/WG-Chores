package ch.tomigorn.wgchores

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import ch.tomigorn.wgchores.auth.SsoCallback

/** Target of the `.SsoCallback` alias, in its own task (disabling the alias removes the task it roots) (Custom Tab fallback only): hands the callback to the app, closes the alias, opens the app, finishes. */
class SsoCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SsoCallback.fromIntent(intent?.component?.className, packageName, intent?.dataString)?.let { (application as WgcApp).container.deliverSso(it) }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        setFallback(this, false)
        finish()
    }

    companion object {
        /** The `wgchores://auth` intent filter exists only while a fallback Custom Tab sign-in is open. */
        fun setFallback(context: Context, enabled: Boolean) {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, "${context.packageName}.${SsoCallback.FALLBACK_ALIAS}"),
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
