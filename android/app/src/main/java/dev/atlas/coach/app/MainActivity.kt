package dev.atlas.coach.app

import android.app.Activity
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import dev.atlas.coach.app.ui.AtlasTheme
import dev.atlas.coach.app.ui.ThemePreferenceStore
import java.util.UUID

/** Coach dashboard and management screens. Android HOME is implemented by [LauncherActivity]. */
class MainActivity : ComponentActivity() {
    private val controller: AtlasController
        get() = (application as AtlasApplication).controller

    private val homeRoleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        controller.reportMessage(
            if (result.resultCode == Activity.RESULT_OK) "ATLAS a été choisi comme application d'accueil."
            else "Demande refusée ou annulée; l'application d'accueil du système reste disponible.",
        )
        syncHomeRole()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        handleNotificationIntent(intent)
        setContent {
            val themePreferenceStore = remember { ThemePreferenceStore.get(applicationContext) }
            AtlasTheme(preference = themePreferenceStore.currentPreference) {
                AtlasApp(
                    controller = controller,
                    context = this,
                    requestHomeRole = ::requestHomeRoleExplicitly,
                    openSystemHome = ::openSystemHomeSettings,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        syncHomeRole()
        controller.refreshProactiveCoachWork()
    }

    private fun handleNotificationIntent(incoming: Intent?) {
        if (incoming?.action != ProactiveCoachNotificationPublisher.ACTION_OPEN_PROPOSAL) {
            controller.handleNotificationIntent(incoming)
            return
        }
        if (incoming.getStringExtra(ProactiveCoachNotificationPublisher.EXTRA_DESTINATION) !=
            ProactiveCoachNotificationPublisher.DESTINATION_COACH
        ) return
        val proposalId = incoming.getStringExtra(ProactiveCoachNotificationPublisher.EXTRA_LOCAL_PROPOSAL_ID)
        if (proposalId == null) {
            controller.openCoachFromProactiveNotification()
            return
        }
        val canonicalId = runCatching { UUID.fromString(proposalId).toString() }.getOrNull()
        if (canonicalId != proposalId) return
        if (controller.agentInbox.none { it.id == proposalId }) {
            controller.openUnavailableProactiveProposalFromNotification()
            return
        }
        controller.openProactiveProposalFromNotification(proposalId)
    }

    private fun syncHomeRole() {
        val home = resolvedHomeActivity()
        val isAtlas = home?.packageName == packageName
        if (!isAtlas && home != null) {
            getSharedPreferences("atlas_launcher_exit", MODE_PRIVATE).edit {
                putString("system_home_package", home.packageName)
                putString("system_home_class", home.className)
            }
        }
        controller.syncLauncherHomeRole(isAtlas)
    }

    private fun resolvedHomeActivity(): ComponentName? {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        val activity = resolved?.activityInfo ?: return null
        return ComponentName(activity.packageName, activity.name)
    }

    private fun requestHomeRoleExplicitly() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            controller.reportMessage("Le rôle Accueil requiert Android 10 ou ultérieur.")
            return
        }
        val manager = getSystemService(RoleManager::class.java)
        if (manager == null || !manager.isRoleAvailable(RoleManager.ROLE_HOME)) {
            controller.reportMessage("Le rôle Accueil n'est pas disponible sur cet appareil.")
            return
        }
        resolvedHomeActivity()?.takeIf { it.packageName != packageName }?.let { home ->
            getSharedPreferences("atlas_launcher_exit", MODE_PRIVATE).edit {
                putString("system_home_package", home.packageName)
                putString("system_home_class", home.className)
            }
        }
        runCatching { homeRoleLauncher.launch(manager.createRequestRoleIntent(RoleManager.ROLE_HOME)) }
            .onFailure { controller.reportMessage("Impossible d'ouvrir la demande de rôle Accueil.") }
    }

    private fun openSystemHomeSettings() {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Intent(Settings.ACTION_HOME_SETTINGS))
            add(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            add(Intent(Settings.ACTION_SETTINGS))
        }
        candidates.forEach { destination ->
            if (runCatching { startActivity(destination) }.isSuccess) return
        }
        controller.reportMessage("Les réglages de l'accueil ne sont pas disponibles sur cet appareil.")
    }
}
