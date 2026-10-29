package dev.atlas.coach.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun CoachTabScreen(
    controller: AtlasController,
    planController: PlanController,
    revision: Int = controller.revision,
    onOpenContext: () -> Unit = {},
) {
    var selectedTab by rememberSaveable { mutableStateOf(CoachTab.CONVERSATION) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CoachSegmentChoice(
                label = "Conversation",
                selected = selectedTab == CoachTab.CONVERSATION,
                testTag = "coach_conversation_tab",
                modifier = Modifier.weight(1f),
                onClick = { selectedTab = CoachTab.CONVERSATION },
            )
            CoachSegmentChoice(
                label = "Synthèses",
                selected = selectedTab == CoachTab.INSIGHTS,
                testTag = "coach_insights_tab",
                modifier = Modifier.weight(1f),
                onClick = { selectedTab = CoachTab.INSIGHTS },
            )
        }

        when (selectedTab) {
            CoachTab.CONVERSATION -> AgentCoachScreen(
                controller = controller,
                revision = revision,
                onOpenContext = onOpenContext,
            )
            CoachTab.INSIGHTS -> CoachInsightsScreen(
                controller = controller,
                planController = planController,
                revision = revision,
            )
        }
    }
}

@Composable
internal fun CoachSegmentChoice(
    label: String,
    selected: Boolean,
    testTag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private enum class CoachTab { CONVERSATION, INSIGHTS }
