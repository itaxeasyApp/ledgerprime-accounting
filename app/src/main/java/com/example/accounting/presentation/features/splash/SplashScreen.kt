package com.example.accounting.presentation.features.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.accounting.presentation.theme.Brand
import com.example.ui.theme.DeepPurpleBackground
import com.example.ui.theme.OffWhiteOnDark
import com.example.ui.theme.RoyalPurple
import com.example.ui.theme.RoyalPurpleDark
import com.example.ui.theme.RoyalPurpleLight
import kotlinx.coroutines.delay

/**
 * Custom animated launch intro (Week 1, Play Store update plan - "glossy 3d splash screen with
 * intro of the company") - shown once per cold start, before [com.example.accounting.presentation.MainAppScreen].
 * The real Android 12+ system splash (icon-only, brief, unavoidable) still shows first; this is the
 * richer in-app follow-up. Sequence: publisher line fades in -> the brand mark does a 3D flip/scale
 * entrance with a looping glossy light sweep -> the wordmark + tagline settle in -> a short hold ->
 * [onFinished]. Tappable to skip immediately (never trap a repeat user/tester behind an animation).
 * Fixed dark Royal-Purple background regardless of system light/dark mode - a brand moment, not a
 * themed content screen (the mark's own artwork is a fixed opaque dark-navy square card, same
 * reasoning as its own doc comment).
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val density = LocalDensity.current

    var publisherVisible by remember { mutableFloatStateOf(0f) }
    var logoVisible by remember { mutableFloatStateOf(0f) }
    var wordmarkVisible by remember { mutableFloatStateOf(0f) }
    val logoRotationY = remember { Animatable(-85f) }
    val logoScale = remember { Animatable(0.4f) }

    LaunchedEffect(Unit) {
        delay(150)
        publisherVisible = 1f
        delay(450)
        logoVisible = 1f
        logoRotationY.animateTo(0f, animationSpec = tween(650, easing = EaseOutBack))
        logoScale.animateTo(1f, animationSpec = tween(650, easing = EaseOutBack))
        delay(150)
        wordmarkVisible = 1f
        delay(900)
        onFinished()
    }

    val publisherAlpha by animateFloatAsState(publisherVisible, tween(400), label = "publisherAlpha")
    val logoAlpha by animateFloatAsState(logoVisible, tween(200), label = "logoAlpha")
    val wordmarkAlpha by animateFloatAsState(wordmarkVisible, tween(500), label = "wordmarkAlpha")
    val wordmarkOffset by animateFloatAsState(if (wordmarkVisible > 0f) 0f else 24f, tween(500, easing = EaseOutBack), label = "wordmarkOffset")

    // Continuous glossy light sweep across the brand mark, looping the whole time this screen shows.
    val shimmer = rememberInfiniteTransition(label = "shimmer")
    val sweepProgress by shimmer.animateFloat(
        initialValue = -0.6f, targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "sweepProgress"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(colors = listOf(RoyalPurple, RoyalPurpleDark, DeepPurpleBackground)))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onFinished() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${Brand.PUBLISHER_LEGAL_NAME.uppercase()} PRESENTS",
                color = OffWhiteOnDark.copy(alpha = 0.6f * publisherAlpha),
                fontSize = 12.sp,
                letterSpacing = 3.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(28.dp))

            // Soft ambient glow behind the mark, plus the mark itself with a 3D flip-in and a
            // looping glossy sweep clipped to its own rounded-square silhouette.
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .blur(60.dp)
                        .background(Brush.radialGradient(listOf(RoyalPurpleLight.copy(alpha = 0.55f), Color.Transparent)))
                )
                Box(
                    modifier = Modifier
                        .size(132.dp)
                        .graphicsLayer {
                            alpha = logoAlpha
                            rotationY = logoRotationY.value
                            scaleX = logoScale.value
                            scaleY = logoScale.value
                            cameraDistance = 16f * density.density
                            transformOrigin = TransformOrigin.Center
                        }
                        .clip(RoundedCornerShape(28.dp))
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            // Additive translucent band = a glossy reflection sweeping across the mark.
                            val bandWidth = size.width * 0.5f
                            val centerX = size.width * sweepProgress
                            drawRect(
                                brush = Brush.linearGradient(
                                    colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent),
                                    start = Offset(centerX - bandWidth, 0f),
                                    end = Offset(centerX + bandWidth, size.height)
                                ),
                                blendMode = BlendMode.Plus
                            )
                        }
                ) {
                    Image(
                        painter = painterResource(id = com.example.R.drawable.ic_ledgerprime_brandmark),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer {
                    alpha = wordmarkAlpha
                    translationY = wordmarkOffset
                }
            ) {
                Text(Brand.APP_NAME, color = OffWhiteOnDark, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Text(Brand.TAGLINE, color = OffWhiteOnDark.copy(alpha = 0.7f), fontSize = 14.sp, textAlign = TextAlign.Center)
            }
        }
    }
}
