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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import dev.atlas.coach.app.ui.AtlasTheme
import dev.atlas.coach.app.ui.ThemePreferenceStore

/** Minimal, text-first Android HOME. It shares the process controller with [MainActivity]. */
class LauncherActivity : ComponentActivity() {
    private val controller: AtlasController
        get() = (application as AtlasApplication).controller

    private var isAtlasHomeRole by mutableStateOf(false)

    private val homeRoleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshHomeRole()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        refreshHomeRole()
        setContent {
            val themePreferenceStore = remember { ThemePreferenceStore.get(applicationContext) }
            AtlasTheme(preference = themePreferenceStore.currentPreference) {
                LauncherHomeScreen(
                    controller = controller,
                    context = this,
                    isHomeRoleHeld = isAtlasHomeRole,
                    onRequestHomeRole = ::requestHomeRoleExplicitly,
                    openCoach = ::openCoach,
                    openFocus = ::openFocus,
                    openLibrary = ::openLibrary,
                    openPreviousLauncher = ::openSavedSystemLauncher,
                    openHomeSettings = ::openSystemHomeSettings,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHomeRole()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        refreshHomeRole()
    }

    private fun refreshHomeRole() {
        val held = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true
        } else {
            resolvedHomeActivity()?.packageName == packageName
        }
        isAtlasHomeRole = held
        controller.syncLauncherHomeRole(held)
        val currentHome = resolvedHomeActivity()
        if (!held && currentHome != null) savePreviousLauncher(currentHome)
    }

    private fun resolvedHomeActivity(): ComponentName? {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        val activity = resolved?.activityInfo ?: return null
        return ComponentName(activity.packageName, activity.name)
    }

    private fun savePreviousLauncher(home: ComponentName) {
        if (home.packageName == packageName) return
        getSharedPreferences("atlas_launcher_exit", MODE_PRIVATE).edit {
            putString("system_home_package", home.packageName)
            putString("system_home_class", home.className)
        }
    }

    private fun openCoach() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun openFocus() {
        controller.navigationState.selectTab(AtlasScreenId.FOCUS)
        openCoach()
    }

    private fun openLibrary() {
        controller.navigationState.navigate(AtlasScreenId.LIBRARY)
        openCoach()
    }

    private fun openSavedSystemLauncher() {
        val preferences = getSharedPreferences("atlas_launcher_exit", MODE_PRIVATE)
        val pkg = preferences.getString("system_home_package", null)
        val cls = preferences.getString("system_home_class", null)
        val component = if (pkg != null && cls != null && pkg != packageName) ComponentName(pkg, cls) else null
        val intent = component?.let {
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(it)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent?.let { runCatching { startActivity(it) }.isSuccess } != true) openSystemHomeSettings()
    }

    private fun requestHomeRoleExplicitly() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return openSystemHomeSettings()
        val manager = getSystemService(RoleManager::class.java)
        if (manager == null || !manager.isRoleAvailable(RoleManager.ROLE_HOME)) return openSystemHomeSettings()
        resolvedHomeActivity()?.takeIf { it.packageName != packageName }?.let(::savePreviousLauncher)
        runCatching { homeRoleLauncher.launch(manager.createRequestRoleIntent(RoleManager.ROLE_HOME)) }
            .onFailure { openSystemHomeSettings() }
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
    }
}
