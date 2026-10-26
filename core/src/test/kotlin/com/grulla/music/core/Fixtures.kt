package com.grulla.music.core

/** Test fixtures; the .timemap.json and .mid files were produced by Verovio 6.3.0 from the .mxl. */
object Fixtures {
    const val CHRONO = "chrono-trigger-04-peaceful-days"

    fun bytes(name: String): ByteArray =
        requireNotNull(Fixtures::class.java.getResourceAsStream("/fixtures/$name")) { "Missing fixture $name" }
            .use { it.readBytes() }

    fun text(name: String): String = bytes(name).toString(Charsets.UTF_8)
}
