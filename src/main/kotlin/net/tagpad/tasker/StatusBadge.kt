package net.tagpad.tasker

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent

/**
 * A Jira-style status lozenge: a rounded, tinted pill holding the state text, colored per state so a
 * list reads at a glance. Theme-aware via [JBColor].
 *
 * Both the Status column ([StatusCellRenderer]) and the details pane paint through this one component,
 * so a given state looks identical wherever it appears.
 */
open class StatusBadge : JComponent() {

    private var text: String = ""
    private var chipBackground: Color = GREY.first
    private var chipForeground: Color = GREY.second

    /** Filled behind the chip before it is drawn; null leaves the background to the parent. */
    protected var backdrop: Color? = null

    init {
        isOpaque = false
        font = JBFont.small().asBold()
    }

    /** Sets the state to display. Empty text paints nothing, rather than an empty pill. */
    fun setStatus(status: String) {
        text = status.uppercase()
        val (bg, fg) = statusColors(status)
        chipBackground = bg
        chipForeground = fg
    }

    override fun getPreferredSize(): Dimension {
        if (text.isEmpty()) return Dimension(0, 0)
        val fm = getFontMetrics(font)
        return Dimension(fm.stringWidth(text) + (padH() + marginX()) * 2, fm.height + padV() * 2)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            backdrop?.let {
                g2.color = it
                g2.fillRect(0, 0, width, height)
            }
            if (text.isEmpty()) return

            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.font = font
            val fm = g2.fontMetrics

            val chipW = fm.stringWidth(text) + padH() * 2
            val chipH = fm.height + padV() * 2
            val chipY = (height - chipH) / 2

            g2.color = chipBackground
            g2.fillRoundRect(marginX(), chipY, chipW, chipH, arc(), arc())

            g2.color = chipForeground
            g2.drawString(text, marginX() + padH(), chipY + padV() + fm.ascent)
        } finally {
            g2.dispose()
        }
    }

    // Geometry is shared by measuring and painting so the two can't drift apart. Scaled on each call
    // rather than cached, so the badge follows a runtime change to the IDE's zoom level.
    private fun padH() = JBUI.scale(8)
    private fun padV() = JBUI.scale(2)
    private fun arc() = JBUI.scale(10)
    private fun marginX() = JBUI.scale(4)

    /**
     * What a state means, independent of the words a given tracker picks for it.
     *
     * Trackers don't agree on vocabulary and most let users invent their own, so this is a best-effort
     * reading of the name — the only thing available everywhere. It has to be, because the popup knows
     * a state only as a [com.intellij.tasks.CustomTaskState] with a display name, no type information.
     */
    private enum class StatusKind { TODO, IN_PROGRESS, REOPENED, DONE, CUSTOM }

    companion object {
        // bg / fg pairs, each theme-aware (light, dark).
        private val GREY = chip(0xDFE1E6, 0x42526E, 0x3C4147, 0xC7CDD6)
        private val BLUE = chip(0xDEEBFF, 0x0747A6, 0x1C3A5E, 0xA6C8FF)
        private val GREEN = chip(0xE3FCEF, 0x006644, 0x1B3A2B, 0x84D6A8)
        private val YELLOW = chip(0xFFF0B3, 0x574F16, 0x4A3F14, 0xF2D06B)
        private val PURPLE = chip(0xEAE6FF, 0x403294, 0x322A52, 0xB8ACF6)

        private fun statusColors(status: String): Pair<Color, Color> = when (classify(status)) {
            StatusKind.TODO -> GREEN          // actionable
            StatusKind.IN_PROGRESS -> BLUE
            StatusKind.REOPENED -> YELLOW
            StatusKind.DONE -> GREY           // terminal states are de-emphasized
            // A state we don't recognise gets its own color rather than falling in with Done: grey would
            // read as "finished", which is exactly the wrong thing to say about an unknown state.
            StatusKind.CUSTOM -> PURPLE
        }

        private fun classify(status: String): StatusKind {
            // YouTrack's command syntax braces multi-word values, so states can arrive as "{Won't fix}".
            val s = status.lowercase().trim().trim('{', '}').trim()
            return when {
                "progress" in s || "started" in s || "doing" in s || "review" in s ||
                    "testing" in s || "implement" in s -> StatusKind.IN_PROGRESS

                // Before the TODO branch: "reopened" contains "open".
                "reopen" in s -> StatusKind.REOPENED

                "resolv" in s || "done" in s || "closed" in s || "fixed" in s || "complete" in s ||
                    "verified" in s || "duplicate" in s || "obsolete" in s || "declined" in s ||
                    "rejected" in s || "invalid" in s || "won't" in s || "wont" in s ||
                    "can't" in s || "cannot" in s -> StatusKind.DONE

                "open" in s || "to do" in s || "todo" in s || "new" in s || "submitted" in s ||
                    "backlog" in s || "discuss" in s || "triage" in s || "pending" in s -> StatusKind.TODO

                else -> StatusKind.CUSTOM
            }
        }

        private fun chip(lightBg: Int, lightFg: Int, darkBg: Int, darkFg: Int): Pair<Color, Color> =
            JBColor(Color(lightBg), Color(darkBg)) to JBColor(Color(lightFg), Color(darkFg))
    }
}
