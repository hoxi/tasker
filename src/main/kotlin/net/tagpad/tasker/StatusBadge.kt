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
    private var chipBackground: Color = NEUTRAL.first
    private var chipForeground: Color = NEUTRAL.second

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

    companion object {
        // bg / fg pairs, each theme-aware (light, dark).
        private val NEUTRAL = chip(0xDFE1E6, 0x42526E, 0x3C4147, 0xC7CDD6)   // To Do / Open
        private val BLUE = chip(0xDEEBFF, 0x0747A6, 0x1C3A5E, 0xA6C8FF)      // In Progress
        private val GREEN = chip(0xE3FCEF, 0x006644, 0x1B3A2B, 0x84D6A8)     // Done / Resolved
        private val YELLOW = chip(0xFFF0B3, 0x574F16, 0x4A3F14, 0xF2D06B)    // Reopened

        private fun statusColors(status: String): Pair<Color, Color> {
            val s = status.lowercase()
            return when {
                "progress" in s -> BLUE
                "reopen" in s -> YELLOW
                // Finished states are de-emphasized (neutral grey).
                "resolv" in s || "done" in s || "closed" in s || "fixed" in s || "complete" in s -> NEUTRAL
                // Actionable/available states are green.
                "open" in s || "to do" in s || "todo" in s || "new" in s || "submitted" in s -> GREEN
                else -> NEUTRAL
            }
        }

        private fun chip(lightBg: Int, lightFg: Int, darkBg: Int, darkFg: Int): Pair<Color, Color> =
            JBColor(Color(lightBg), Color(darkBg)) to JBColor(Color(lightFg), Color(darkFg))
    }
}
