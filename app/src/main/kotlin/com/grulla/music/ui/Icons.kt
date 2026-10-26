package com.grulla.music.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The app's icons: 24dp, mostly 2px rounded strokes (Crane & Ink style); the
 * transport glyphs are filled. Tinted by Icon() like any vector.
 */
object AppIcons {
    val ArrowBack = stroke("ArrowBack", "M19 12H5M11 6l-6 6 6 6")
    val ChevronLeft = stroke("ChevronLeft", "M15 6l-6 6 6 6")
    val ChevronRight = stroke("ChevronRight", "M9 6l6 6-6 6")
    val Close = stroke("Close", "M6 6l12 12M18 6L6 18")
    val Settings = stroke(
        "Settings",
        "M4 7h8M16 7h4M4 17h4M12 17h8M14 5a2 2 0 1 1 0 4a2 2 0 1 1 0-4zM10 15a2 2 0 1 1 0 4a2 2 0 1 1 0-4z",
    )
    val Parts = stroke(
        "Parts",
        "M6 4v8M6 16v4M12 4v2M12 10v10M18 4v9M18 17v3" +
            "M6 12a2 2 0 1 1 0 4a2 2 0 1 1 0-4zM12 6a2 2 0 1 1 0 4a2 2 0 1 1 0-4zM18 13a2 2 0 1 1 0 4a2 2 0 1 1 0-4z",
    )
    val Mic = stroke("Mic", "M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3zM5 11a7 7 0 0 0 14 0M12 18v3")
    val Metronome = stroke("Metronome", "M9 3h6l3 18H6zM12 15l5-9")
    val FolderOpen = stroke("FolderOpen", "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
    val Warning = stroke("Warning", "M12 4l9 16H3zM12 10v4M12 17v0.5")
    val Delete = stroke("Delete", "M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3")
    val ZoomIn = stroke("ZoomIn", "M11 4a7 7 0 1 1 0 14a7 7 0 1 1 0-14zM20 20l-4-4M8 11h6M11 8v6")
    val ZoomOut = stroke("ZoomOut", "M11 4a7 7 0 1 1 0 14a7 7 0 1 1 0-14zM20 20l-4-4M8 11h6")
    val ViewPages = stroke("ViewPages", "M4 5h7v14H4zM13 5h7v14h-7z")
    val ViewScroll = stroke("ViewScroll", "M5 4h14v6H5zM5 14h14v6H5z")
    val Check = stroke("Check", "M5 12l4 4 10-10")

    val Play = fill("Play", "M8 5v14l11-7z")
    val Pause = fill("Pause", "M7 5h4v14H7zM13 5h4v14h-4z")
    val Stop = fill("Stop", "M8 6h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z")
    val More = fill(
        "More",
        "M12 3.2a1.8 1.8 0 1 1 0 3.6a1.8 1.8 0 1 1 0-3.6zM12 10.2a1.8 1.8 0 1 1 0 3.6a1.8 1.8 0 1 1 0-3.6z" +
            "M12 17.2a1.8 1.8 0 1 1 0 3.6a1.8 1.8 0 1 1 0-3.6z",
    )

    private fun builder(name: String) =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)

    private fun stroke(name: String, pathData: String): ImageVector =
        builder(name)
            .addPath(
                pathData = addPathNodes(pathData),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()

    private fun fill(name: String, pathData: String): ImageVector =
        builder(name).addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black)).build()
}
