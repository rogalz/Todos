package com.example.ai.ui.nav

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.OverlayClip
import androidx.compose.animation.SharedTransitionScope.ResizeMode
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ai.ui.theme.EmphasizedEasing
import com.example.ai.ui.theme.OPEN_TODO_MILLIS

/**
 * The real [SharedElements]: the bounds of the card (or FAB) morph into the detail screen's container
 * (a "container transform") and the title morphs with it, in [OPEN_TODO_MILLIS] ms with emphasized easing.
 * Only the way *in* uses it: the detail screen stops applying these modifiers once it starts closing, so
 * there is no match to morph back into (see `AppNavHost` for why).
 *
 * The scopes come from the `SharedTransitionLayout` around the NavHost and from the destination being
 * composed; see `AppNavHost`.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
internal class NavSharedElements(
    private val transitionScope: SharedTransitionScope,
    private val visibilityScope: AnimatedVisibilityScope,
) : SharedElements {

    private val boundsTransform = BoundsTransform { _, _ -> tween(OPEN_TODO_MILLIS, easing = EmphasizedEasing) }

    @Composable
    override fun container(key: String): Modifier = with(transitionScope) {
        Modifier.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = visibilityScope,
            // Cross-fade what is inside the bounds: a small card and a whole screen look nothing alike.
            enter = fadeIn(tween(OPEN_TODO_MILLIS, easing = EmphasizedEasing)),
            exit = fadeOut(tween(OPEN_TODO_MILLIS, easing = EmphasizedEasing)),
            boundsTransform = boundsTransform,
            // Re-lays the content out at the animated size instead of stretching it.
            resizeMode = ResizeMode.RemeasureToBounds,
            // The card's corners while it travels; without a clip the screen would show square corners mid-flight.
            clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(CARD_CORNER)),
        )
    }

    @Composable
    override fun title(key: String): Modifier = with(transitionScope) {
        Modifier.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = visibilityScope,
            enter = fadeIn(tween(OPEN_TODO_MILLIS, easing = EmphasizedEasing)),
            exit = fadeOut(tween(OPEN_TODO_MILLIS, easing = EmphasizedEasing)),
            boundsTransform = boundsTransform,
            // NOT scaleToBounds: the card's title is a small line of text and the detail's title is a wide text
            // field, and scaling one to the other's bounds blows the text up to several times its size for
            // the length of the transition (seen in a frame capture). Re-measuring keeps the text its own size.
            resizeMode = ResizeMode.RemeasureToBounds,
        )
    }

    private companion object {
        val CARD_CORNER = 16.dp
    }
}
