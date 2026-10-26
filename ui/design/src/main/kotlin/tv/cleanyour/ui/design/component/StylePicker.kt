package tv.cleanyour.ui.design.component

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.spring
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import tv.cleanyour.ui.design.icon.CytIcons
import tv.cleanyour.ui.design.theme.CytTheme

// One style of the Style strip: its key, name and one line (TalkBack reads "{name}. {description}"
// and the selected state once), the mini preview of this TV in that style with this picture (null
// while it is drawn), and whether this TV can show it (`note` says why not, "Needs the home screen
// update").
@Immutable
data class StyleChoice(val key: String, val name: String, val description: String, val look: TvLook?, val enabled: Boolean = true,
    val note: String? = null)

// The strip's test tag (LookPagesTest scrolls it).
const val STYLE_STRIP_TAG = "cyt_style_strip"

private val CARD_WIDTH = 150.dp
private val CARD_GAP = 10.dp

// look2 (owner, round 2): the five styles as a sideways strip like the Picture strip, cards about
// 150 dp wide that snap to the start edge after a fling, each a mini TvPreview with the name under
// it. The chosen card has the white rim and the check badge; a card this TV cannot show is dimmed
// and says why. One selectableGroup, each card a radio button. A tap only chooses.
@Composable
fun StylePicker(options: List<StyleChoice>, selectedKey: String?, onSelect: (StyleChoice) -> Unit, modifier: Modifier = Modifier) {
    val c = CytTheme.colors
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val step = with(density) { (CARD_WIDTH + CARD_GAP).toPx() }
    val decay = rememberSplineBasedDecay<Float>()
    val fling = remember(scroll, step, decay) { SnapToCards(scroll, step, decay) }
    Row(
        modifier.fillMaxWidth().testTag(STYLE_STRIP_TAG).horizontalScroll(scroll, flingBehavior = fling).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
    ) {
        for (o in options) {
            val selected = o.key == selectedKey
            val shape = CytTheme.shapes.small
            val label = listOfNotNull("${o.name}. ${o.description}", o.note).joinToString(" ")
            Column(
                Modifier.width(CARD_WIDTH).clip(shape)
                    .selectable(selected, enabled = o.enabled, role = Role.RadioButton) { onSelect(o) }
                    .semantics(mergeDescendants = true) { contentDescription = label }
                    .padding(4.dp),
            ) {
                Box(
                    Modifier.fillMaxWidth().clip(shape).background(c.surface1)
                        .border(if (selected) 2.dp else 1.dp, if (selected) c.textPrimary else c.outline, shape)
                        .padding(if (selected) 2.dp else 1.dp),
                ) {
                    val miniWidth = CARD_WIDTH - 14.dp
                    Box(Modifier.alpha(if (o.enabled) 1f else 0.45f).clearAndSetSemantics {}) {
                        if (o.look != null) TvPreview(o.look, "", Modifier.width(miniWidth))
                        else Box(Modifier.size(miniWidth, miniWidth * 9f / 16f).background(Color.Black, shape))
                    }
                    if (selected) {
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(5.dp).size(22.dp).background(c.textPrimary, CytTheme.shapes.pill),
                            contentAlignment = Alignment.Center,
                        ) { CytIcon(CytIcons.Check, null, tint = c.bg, size = 14.dp) }
                    }
                }
                Text(
                    o.name, Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp).clearAndSetSemantics {},
                    color = if (selected) c.textPrimary else c.textSecondary, style = CytTheme.type.bodyMedium, maxLines = 2,
                )
                if (o.note != null) {
                    Text(o.note, Modifier.padding(start = 2.dp, end = 2.dp).clearAndSetSemantics {}, color = c.textSecondary, style = CytTheme.type.bodySmall)
                }
            }
        }
    }
}

// After a fling the strip settles with a card's start at the start edge.
private class SnapToCards(private val scroll: ScrollState, private val step: Float, private val decay: DecayAnimationSpec<Float>) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        val from = scroll.value.toFloat()
        val coasted = decay.calculateTargetValue(from, initialVelocity)
        val target = ((coasted / step).roundToInt() * step).coerceIn(0f, scroll.maxValue.toFloat())
        var last = from
        animate(from, target, initialVelocity, spring(stiffness = 400f)) { v, _ ->
            scrollBy(v - last)
            last = v
        }
        return 0f
    }
}
