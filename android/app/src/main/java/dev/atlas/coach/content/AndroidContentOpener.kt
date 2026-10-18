package dev.atlas.coach.content

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager

/** Android-only adapter; URL validation and request construction remain JVM-testable. */
class AndroidContentOpener private constructor(private val fallback: ContentOpener) : ContentOpener {
    constructor(context: Context) : this(FallbackContentOpener(AndroidContentLaunchGateway(context.applicationContext)))

    /** Injectable JVM boundary; tests can exercise this adapter without Android services. */
    internal constructor(gateway: ContentLaunchGateway) : this(FallbackContentOpener(gateway))

    override fun open(request: ContentOpenRequest): ContentOpenResult = fallback.open(request)
}

private class AndroidContentLaunchGateway(private val context: Context) : ContentLaunchGateway {
    override fun hasCompatibleHandler(request: ContentOpenRequest): Boolean {
        val intent = viewIntent(request)
        return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty() ||
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
    }

    override fun openChooser(request: ContentOpenRequest): Boolean = launch(request, chooser = true)

    override fun openDirect(request: ContentOpenRequest): Boolean = launch(request, chooser = false)

    private fun launch(request: ContentOpenRequest, chooser: Boolean): Boolean = try {
        val viewIntent = viewIntent(request)
        val intent = if (chooser) Intent.createChooser(viewIntent, request.chooserTitle) else viewIntent
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun viewIntent(request: ContentOpenRequest) = Intent(request.action, Uri.parse(request.uri.toASCIIString()))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
