package dev.atlas.coach.app

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
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
        setContent {
            AtlasTheme {
                val controller = remember { AtlasController(applicationContext) }
                atlasController = controller
                AtlasApp(
                    controller = controller,
                    context = this,
                    requestHomeRole = ::requestHomeRoleExplicitly,
                    openSystemHome = ::openSystemHome,
                    homeLaunch = intent.action == Intent.ACTION_MAIN && intent.categories?.contains(Intent.CATEGORY_HOME) == true,
                )
            }
        }
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
