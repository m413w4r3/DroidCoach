package dev.atlas.coach.usage.android

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Opened only by an explicit notification tap. It never auto-launches or covers another app. */
class IntentionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        val appLabel = runCatching {
            packageManager.getApplicationInfo(packageName, 0).let { packageManager.getApplicationLabel(it).toString() }
        }.getOrDefault(packageName)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }
        content.addView(TextView(this).apply {
            text = "Est-ce bien ton intention d'ouvrir $appLabel ?\n\nPrends un instant. Tu peux continuer ou fermer cet écran."
            textSize = 22f
            gravity = Gravity.CENTER
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(Button(this).apply {
            text = "Continuer vers $appLabel"
            setOnClickListener {
                packageManager.getLaunchIntentForPackage(packageName)?.let(::startActivity)
                finish()
            }
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(Button(this).apply {
            text = "Fermer"
            setOnClickListener { finish() }
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(content)
    }

    companion object {
        const val EXTRA_PACKAGE_NAME = "dev.atlas.coach.usage.extra.PACKAGE_NAME"
        const val EXTRA_INTERVENTION_ID = "dev.atlas.coach.usage.extra.INTERVENTION_ID"
    }
}
