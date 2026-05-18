package com.example.navipilot.ui.theme

import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Professional Animation System
 * Standard durations and easing curves for consistent UI animations
 */

// Standard animation durations (milliseconds)
object AnimationDuration {
    const val FAST = 150        // Quick interactions (button press, toggle)
    const val NORMAL = 300      // Standard transitions (page change, dialog)
    const val SLOW = 500        // Complex animations (loading, transitions)
}

// Standard easing curves
object AnimationEasing {
    val FastOutSlowIn = FastOutSlowInEasing
    val EaseInOut = EaseInOutCubic
    val EaseOut = EaseOut
    val EaseIn = EaseIn
}

/**
 * Button press scale animation
 * Scales button down slightly on press for tactile feedback
 */
@Composable
fun Modifier.animatedClickable(
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    var isPressed by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(
            durationMillis = AnimationDuration.FAST,
            easing = AnimationEasing.FastOutSlowIn
        ),
        label = "button_scale"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
}

/**
 * Fade in animation for content
 */
@Composable
fun rememberFadeInAnimation(delayMillis: Int = 0): State<Float> {
    return animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(
            durationMillis = AnimationDuration.NORMAL,
            delayMillis = delayMillis,
            easing = AnimationEasing.EaseOut
        ),
        label = "fade_in"
    )
}

/**
 * Slide up animation for content
 */
@Composable
fun rememberSlideUpAnimation(delayMillis: Int = 0): State<Float> {
    return animateFloatAsState(
        targetValue = 0f,
        animationSpec = tween(
            durationMillis = AnimationDuration.NORMAL,
            delayMillis = delayMillis,
            easing = AnimationEasing.EaseOut
        ),
        label = "slide_up"
    )
}

/**
 * Pulsing animation for attention-grabbing elements
 */
@Composable
fun rememberPulseAnimation(): State<Float> {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    return infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1000,
                easing = AnimationEasing.EaseInOut
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )
}
