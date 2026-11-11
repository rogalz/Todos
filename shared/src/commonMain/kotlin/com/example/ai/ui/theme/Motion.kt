package com.example.ai.ui.theme

import androidx.compose.animation.core.CubicBezierEasing

/** Material 3 "emphasized" easing (decelerates hard at the end): the curve for a surface that opens. */
val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** Material 3 "emphasized accelerate": starts slowly and speeds up, the curve for a surface that leaves. */
val EmphasizedAccelerateEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/** How long opening a todo takes. Long enough to follow, short enough not to be waited for. */
const val OPEN_TODO_MILLIS = 350

/**
 * How long leaving a todo takes. Shorter than opening on purpose: the user has already decided to go
 * back (and the save has already taken its time), so the screen should get out of the way.
 */
const val CLOSE_TODO_MILLIS = 200
