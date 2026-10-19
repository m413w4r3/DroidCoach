package dev.atlas.coach.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProposalActionLabelsTest {
    @Test
    fun knownActionIdsHaveUserFacingFrenchLabels() {
        assertEquals("Ouvrir le Coach", ProposalActionLabels.labelFor("open_coach"))
        assertEquals("Démarrer un focus", ProposalActionLabels.labelFor("focus.start"))
        assertEquals("Aucune action", ProposalActionLabels.labelFor("none"))
    }

    @Test
    fun editedLabelMapsBackToTheExistingContractId() {
        assertEquals("open_coach", ProposalActionLabels.contractIdFor("Ouvrir le Coach", "open_coach"))
        assertEquals("focus.start", ProposalActionLabels.contractIdFor("Démarrer un focus", "open_coach"))
        assertEquals("none", ProposalActionLabels.contractIdFor("Aucune action", "open_coach"))
    }

    @Test
    fun unknownIdsAreHiddenAndRemainUnchangedWhenNoEditWasMade() {
        val privateId = "future.internal_action"
        val shown = ProposalActionLabels.labelFor(privateId)
        assertEquals("Action non reconnue", shown)
        assertEquals(privateId, ProposalActionLabels.contractIdFor(shown, privateId))
    }

    @Test
    fun unknownUserLabelsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ProposalActionLabels.contractIdFor("Action inventée", "open_coach")
        }
    }
}
