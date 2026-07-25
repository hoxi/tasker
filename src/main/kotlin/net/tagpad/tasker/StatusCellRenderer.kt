package net.tagpad.tasker

import java.awt.Component
import java.awt.Font
import javax.swing.JTable
import javax.swing.table.TableCellRenderer

/**
 * Renders the Status column as a [StatusBadge], tracking the table's font and selection colors so the
 * pill sits on the correct cell background. The pill itself is drawn by the shared component.
 */
class StatusCellRenderer : StatusBadge(), TableCellRenderer {

    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int,
    ): Component {
        setStatus(value?.toString().orEmpty())
        backdrop = if (isSelected) table.selectionBackground else table.background
        font = table.font.deriveFont(Font.BOLD, table.font.size2D - 1f)
        return this
    }
}
