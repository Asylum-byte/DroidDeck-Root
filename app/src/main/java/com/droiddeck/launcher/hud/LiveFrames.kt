package com.droiddeck.launcher.hud

/** The HUD's live frame statistics, published for the agent bridge while the HUD counts frames. */
internal object LiveFrames {
    @Volatile var stats: FrameStats? = null
    @Volatile var engine: String = ""
    @Volatile var updatedNs: Long = 0L
}
