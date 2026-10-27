package dev.atlas.coach.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.atlas.coach.memory.FileMemoryStore
import dev.atlas.coach.memory.MemoryCategory
import dev.atlas.coach.memory.MemoryClock
import dev.atlas.coach.memory.MemoryFact
import dev.atlas.coach.memory.MemoryProvenance
import dev.atlas.coach.memory.MemoryQuarantineState
import dev.atlas.coach.memory.MemoryRetentionPolicy
import dev.atlas.coach.memory.MemorySensitivity
import dev.atlas.coach.memory.SystemMemoryClock
import java.io.File
import java.util.UUID

/** Local-only actions and observable state for the memory screen. */
class MemoryController(
    directory: File,
    private val clock: MemoryClock = SystemMemoryClock,
    retentionPolicy: MemoryRetentionPolicy = MemoryRetentionPolicy(),
) {
    private val store = FileMemoryStore(
        file = File(directory, MEMORY_FILE_NAME),
        clock = clock,
        retentionPolicy = retentionPolicy,
    )

    var facts by mutableStateOf<List<MemoryFact>>(emptyList())
        private set
    var quarantineState by mutableStateOf<MemoryQuarantineState?>(store.quarantineState)
        private set
    var storageProblem by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var revision by mutableStateOf(0)
        private set

    init {
        refresh()
    }

    fun refresh() {
        quarantineState = store.quarantineState
        if (quarantineState != null) {
            facts = emptyList()
            storageProblem = null
        } else {
            try {
                facts = store.list(includeRevoked = true)
                storageProblem = null
            } catch (_: Exception) {
                facts = emptyList()
                storageProblem = "La mémoire locale ne peut pas être lue pour le moment."
            }
        }
        revision++
    }

    /** The consent checkbox is an explicit precondition to saving a preference. */
    fun addPreference(
        statement: String,
        sensitivity: MemorySensitivity,
        consentGiven: Boolean,
    ): Boolean {
        if (!consentGiven) {
            message = "Confirmez votre accord avant d'enregistrer cette préférence."
            return false
        }
        val cleanStatement = statement.trim()
        if (cleanStatement.isEmpty() || cleanStatement.length > MemoryFact.MAX_STATEMENT_LENGTH) {
            message = "Le texte doit contenir de 1 à ${MemoryFact.MAX_STATEMENT_LENGTH} caractères."
            return false
        }
        val now = clock.now()
        return mutate {
            store.add(
                MemoryFact(
                    factId = UUID.randomUUID().toString(),
                    category = MemoryCategory.PREFERENCE,
                    statement = cleanStatement,
                    provenance = MemoryProvenance(
                        source = USER_SOURCE,
                        observedAt = now,
                        confidence = 1.0,
                        userConfirmed = true,
                    ),
                    sensitivity = sensitivity,
                    createdAt = now,
                ),
            )
        }
    }

    /** Explicitly promotes a hypothesis after the user selects its resulting category. */
    fun confirmHypothesis(factId: String, targetCategory: MemoryCategory): Boolean {
        if (targetCategory !in CONFIRMABLE_CATEGORIES) {
            message = "Choisissez une préférence ou une habitude confirmée."
            return false
        }
        val candidate = runCatching { store.get(factId) }.getOrNull()
        if (candidate?.category != MemoryCategory.HYPOTHESIS) {
            message = "Cette hypothèse n'est plus disponible."
            return false
        }
        return mutate { store.confirm(factId, targetCategory) }
    }

    fun correct(factId: String, statement: String): Boolean {
        val cleanStatement = statement.trim()
        if (cleanStatement.isEmpty() || cleanStatement.length > MemoryFact.MAX_STATEMENT_LENGTH) {
            message = "Le texte doit contenir de 1 à ${MemoryFact.MAX_STATEMENT_LENGTH} caractères."
            return false
        }
        return mutate { store.correct(factId, cleanStatement) }
    }

    fun delete(factId: String): Boolean = mutate { store.delete(factId) }

    fun revoke(factId: String): Boolean = mutate { store.revoke(factId) }

    /** Returns the number purged, or null if the operation could not be completed. */
    fun purgeRetention(): Int? {
        var removed: Int? = null
        val succeeded = mutate { removed = store.purgeExpired() }
        if (succeeded) {
            message = if (removed == 0) {
                "Aucun fait arrivé à échéance."
            } else {
                "${removed} fait(s) arrivé(s) à échéance supprimé(s)."
            }
        }
        return removed.takeIf { succeeded }
    }

    /** Called only after the screen's explicit confirmation dialog. */
    fun recoverByStartingEmpty(): Boolean {
        return try {
            val recovered = store.recoverByStartingEmpty()
            if (recovered) {
                message = "La mémoire est réinitialisée. La copie mise en quarantaine est conservée."
            } else {
                message = "La récupération est indisponible tant qu'une copie ne peut pas être conservée."
            }
            refresh()
            recovered
        } catch (_: Exception) {
            message = "La récupération a échoué. La copie en quarantaine reste conservée."
            refresh()
            false
        }
    }

    private fun mutate(action: () -> Any?): Boolean {
        return try {
            val result = action()
            if (result == false) {
                message = "Aucune modification n'a été effectuée."
                refresh()
                return false
            }
            message = null
            refresh()
            true
        } catch (_: Exception) {
            message = "Cette action n'a pas pu être enregistrée dans la mémoire locale."
            refresh()
            false
        }
    }

    private companion object {
        private const val MEMORY_FILE_NAME = "memory-facts.json"
        private const val USER_SOURCE = "user"
        private val CONFIRMABLE_CATEGORIES = setOf(
            MemoryCategory.PREFERENCE,
            MemoryCategory.CONFIRMED_HABIT,
        )
    }
}
