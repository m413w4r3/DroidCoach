package dev.atlas.coach.test

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runners.BlockJUnit4ClassRunner
import org.junit.runners.model.FrameworkMethod

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresBackendArguments(
    val keys: Array<String>,
    val reason: String,
)

/** Filters script-only backend tests out unless their dedicated harness supplies arguments. */
class BackendArgumentsAwareRunner(testClass: Class<*>) : BlockJUnit4ClassRunner(testClass) {
    override fun getChildren(): MutableList<FrameworkMethod> {
        val arguments = InstrumentationRegistry.getArguments()
        return super.getChildren().filterTo(mutableListOf()) { method ->
            val requirement = method.getAnnotation(RequiresBackendArguments::class.java)
            if (requirement == null) {
                true
            } else {
                val supplied = requirement.keys.count(arguments::containsKey)
                if (supplied == 0) {
                    Log.i("ATLAS_TEST_FILTER", "Filtered ${describeChild(method).displayName}: ${requirement.reason}")
                }
                supplied > 0
            }
        }
    }
}
