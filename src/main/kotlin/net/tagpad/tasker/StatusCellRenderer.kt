package net.tagpad.tasker

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.TableCellRenderer

/**
 * Renders the Status column as a Jira-style colored "lozenge": a rounded, tinted pill with the
 * state text, colored per state so the list reads at a glance. Theme-aware via [JBColor].
 */
class StatusCellRenderer : JPanel(), TableCellRenderer {

    private var text: String = ""
    private var chipBackground: Color = NEUTRAL.first
    private var chipForeground: Color = NEUTRAL.second
    private var cellBackground: Color = JBColor.background()

    init {
        isOpaque = true
    }

    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int,
    ): Component {
        val raw = value?.toString().orEmpty()
        text = raw.uppercase()
        val (bg, fg) = statusColors(raw)
        chipBackground = bg
        chipForeground = fg
        cellBackground = if (isSelected) table.selectionBackground else table.background
        font = table.font.deriveFont(Font.BOLD, table.font.size2D - 1f)
        return this
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.color = cellBackground
            g2.fillRect(0, 0, width, height)
            if (text.isEmpty()) return

            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.font = font
            val fm = g2.fontMetrics

            val padH = JBUI.scale(8)
            val padV = JBUI.scale(2)
            val arc = JBUI.scale(10)
            val marginX = JBUI.scale(4)

            val chipW = fm.stringWidth(text) + padH * 2
            val chipH = fm.height + padV * 2
            val chipY = (height - chipH) / 2

            g2.color = chipBackground
            g2.fillRoundRect(marginX, chipY, chipW, chipH, arc, arc)

            g2.color = chipForeground
            g2.drawString(text, marginX + padH, chipY + padV + fm.ascent)
        } finally {
            g2.dispose()
        }
    }

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
