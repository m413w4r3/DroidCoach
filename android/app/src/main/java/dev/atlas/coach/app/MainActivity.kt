package dev.atlas.coach.app

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import dev.atlas.coach.app.ui.AtlasTheme

class MainActivity : ComponentActivity() {
    private var atlasController: AtlasController? = null
    private val homeRoleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        atlasController?.reportMessage(
            if (result.resultCode == Activity.RESULT_OK) "ATLAS a été choisi comme application d'accueil." else "Demande refusée ou annulée; l'application d'accueil du système reste disponible.",
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            AtlasTheme {
                val controller = remember { AtlasController(applicationContext).also { it.handleNotificationIntent(intent) } }
                atlasController = controller
                AtlasApp(
                    controller = controller,
                    context = this,
                    requestHomeRole = ::requestHomeRoleExplicitly,
                    openSystemHome = ::openSystemHome,
                    openSystemLauncher = ::openSavedSystemLauncher,
                    homeLaunch = intent.action == Intent.ACTION_MAIN && intent.categories?.contains(Intent.CATEGORY_HOME) == true,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        atlasController?.handleNotificationIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.categories?.contains(Intent.CATEGORY_HOME) == true) {
            if (atlasController?.capabilities?.get(AtlasController.CAP_LAUNCHER_HOME) == true) {
                atlasController?.navigationState?.selectTab(AtlasScreenId.LAUNCHER)
            } else {
                atlasController?.navigationState?.replace(AtlasScreenId.HOME)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val home = resolvedHomeActivity()
        val isAtlas = home?.packageName == packageName
        if (!isAtlas && home != null) {
            getSharedPreferences("atlas_launcher_exit", MODE_PRIVATE).edit()
                .putString("system_home_package", home.packageName)
                .putString("system_home_class", home.className)
                .apply()
        }
        atlasController?.launcherState?.refreshHomeRole(isAtlas)
    }

    private fun resolvedHomeActivity(): ComponentName? {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        val activity = resolved?.activityInfo ?: return null
        return ComponentName(activity.packageName, activity.name)
    }

    private fun openSavedSystemLauncher() {
        val preferences = getSharedPreferences("atlas_launcher_exit", MODE_PRIVATE)
        val pkg = preferences.getString("system_home_package", null)
        val cls = preferences.getString("system_home_class", null)
        val component = if (pkg != null && cls != null && pkg != packageName) ComponentName(pkg, cls) else null
        val intent = component?.let {
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = intent?.let { runCatching { startActivity(it) }.isSuccess } == true
        if (!launched) openSystemHome()
    }

    private fun requestHomeRoleExplicitly() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            atlasController?.reportMessage("Le rôle Accueil requiert Android 10 ou ultérieur.")
            return
        }
        val manager = getSystemService(RoleManager::class.java)
        if (manager == null || !manager.isRoleAvailable(RoleManager.ROLE_HOME)) {
            atlasController?.reportMessage("Le rôle Accueil n'est pas disponible sur cet appareil.")
            return
        }
        resolvedHomeActivity()?.takeIf { it.packageName != packageName }?.let { home ->
            getSharedPreferences("atlas_launcher_exit", MODE_PRIVATE).edit()
                .putString("system_home_package", home.packageName)
                .putString("system_home_class", home.className)
                .apply()
        }
        runCatching { homeRoleLauncher.launch(manager.createRequestRoleIntent(RoleManager.ROLE_HOME)) }
            .onFailure { atlasController?.reportMessage("Impossible d'ouvrir la demande de rôle Accueil.") }
    }

    private fun openSystemHome() {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Intent(Settings.ACTION_HOME_SETTINGS))
            add(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            add(Intent(Settings.ACTION_SETTINGS))
        }
        val destination = candidates.firstOrNull { packageManager.resolveActivity(it, 0) != null }
        if (destination == null) {
            atlasController?.reportMessage("Les réglages de l'accueil ne sont pas disponibles sur cet appareil.")
            return
        }
        runCatching { startActivity(destination) }
            .onFailure { atlasController?.reportMessage("Impossible d'ouvrir les réglages de l'application d'accueil.") }
    }

}
