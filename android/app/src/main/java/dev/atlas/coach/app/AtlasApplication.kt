package dev.atlas.coach.app

import android.app.Application
import java.io.File

/** One process-wide controller shared by the HOME and coach activities. */
class AtlasApplication : Application() {
    @Volatile
    private var sharedController: AtlasController? = null

    val controller: AtlasController
        get() = sharedController ?: synchronized(this) {
            sharedController ?: AtlasController(applicationContext).also { sharedController = it }
        }

    /** Local day-plan owner; one atomic document per local date under filesDir/plans. */
    val planController: PlanController by lazy {
        PlanController(directory = File(filesDir, "plans"))
    }

    /** Instrumentation setup only: creates a fresh graph after a test clears private storage. */
    fun resetControllerForTesting() {
        synchronized(this) { sharedController = null }
    }
}
