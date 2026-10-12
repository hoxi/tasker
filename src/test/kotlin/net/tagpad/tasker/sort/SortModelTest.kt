package net.tagpad.tasker.sort

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SortModelTest {

    private val careful = SortSpec(
        listOf(AssigneeSortRule(listOf(PersonMatcher.Me)), FieldSortRule(SortFields.UPDATED, SortDirection.DESCENDING)),
    )

    private val stray = SortSpec(listOf(FieldSortRule(SortFields.SUMMARY, SortDirection.ASCENDING)))

    @Test
    fun `restore brings back the sort last saved, after a stray change`() {
        val kept = mutableListOf<SortSpec>()
        val model = DefaultSortModel(initial = careful, persistSaved = { kept += it })
        model.save()
        model.update(stray)
        model.restore()
        assertEquals(careful, model.spec)
        assertEquals(listOf(careful), kept)
    }

    @Test
    fun `with nothing saved, restore leaves the sort alone`() {
        val model = DefaultSortModel(initial = stray)
        assertNull(model.saved)
        model.restore()
        assertEquals(stray, model.spec)
    }

    @Test
    fun `a sort saved in an earlier session can be restored`() {
        val model = DefaultSortModel(initial = stray, saved = careful)
        model.restore()
        assertEquals(careful, model.spec)
    }
}
