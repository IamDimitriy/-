package sonnik.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// One dark look: the app is used in a dark bedroom.
object Palette {
    val ink = Color(0xFF0D0F17)
    val panel = Color(0xFF161926)
    val panelHigh = Color(0xFF1E2232)
    val line = Color(0xFF2A2F42)
    val text = Color(0xFFE6E2D6)
    val muted = Color(0xFF8E8C9C)
    val amber = Color(0xFFE0A458)
    val amberInk = Color(0xFF1B1309)
    val danger = Color(0xFFE57A6E)
    val ok = Color(0xFF8FBF8A)
    val snore = Color(0xFF7F9CF5)

    /** One colour per kind of sound, used for dots in lists and on the night graph. */
    fun kind(k: sonnik.core.SoundKind): Color = when (k) {
        sonnik.core.SoundKind.SPEECH -> amber
        sonnik.core.SoundKind.SNORE -> snore
        sonnik.core.SoundKind.COUGH -> Color(0xFFE57A9A)
        sonnik.core.SoundKind.MOVEMENT -> Color(0xFFB7A27C)
        sonnik.core.SoundKind.STREET -> Color(0xFF6FBFA8)
        sonnik.core.SoundKind.OTHER -> muted
    }
}

@Composable
fun SonnikTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.amber,
            onPrimary = Palette.amberInk,
            secondary = Palette.amber,
            onSecondary = Palette.amberInk,
            secondaryContainer = Palette.panelHigh,
            onSecondaryContainer = Palette.amber,
            background = Palette.ink,
            onBackground = Palette.text,
            surface = Palette.ink,
            onSurface = Palette.text,
            surfaceVariant = Palette.panel,
            onSurfaceVariant = Palette.muted,
            surfaceContainer = Palette.panel,
            surfaceContainerHigh = Palette.panelHigh,
            surfaceContainerHighest = Palette.panelHigh,
            outline = Palette.line,
            outlineVariant = Palette.line,
            error = Palette.danger,
        ),
        content = content,
    )
}

/** Small glyphs drawn here so the app does not need the large extended icon set. */
object Glyphs {
    private fun icon(name: String, block: androidx.compose.ui.graphics.vector.ImageVector.Builder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    val Moon = icon("moon") {
        path(fill = SolidColor(Color.White)) {
            moveTo(14f, 3f)
            arcTo(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = false, x1 = 20.5f, y1 = 17.5f)
            arcTo(10f, 10f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 14f, y1 = 3f)
            close()
        }
    }

    val Waves = icon("waves") {
        path(stroke = SolidColor(Color.White), strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round) {
            moveTo(5f, 9f); lineTo(5f, 15f)
            moveTo(9.5f, 5f); lineTo(9.5f, 19f)
            moveTo(14f, 8f); lineTo(14f, 16f)
            moveTo(18.5f, 10.5f); lineTo(18.5f, 13.5f)
        }
    }

    val Play = icon("play") {
        path(fill = SolidColor(Color.White)) {
            moveTo(8f, 5f); lineTo(19f, 12f); lineTo(8f, 19f); close()
        }
    }

    val Pause = icon("pause") {
        path(fill = SolidColor(Color.White)) {
            moveTo(7f, 5f); lineTo(10.5f, 5f); lineTo(10.5f, 19f); lineTo(7f, 19f); close()
            moveTo(13.5f, 5f); lineTo(17f, 5f); lineTo(17f, 19f); lineTo(13.5f, 19f); close()
        }
    }
}
