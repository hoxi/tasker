package net.tagpad.tasker.sort

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SortSpecCodecTest {

    @Test
    fun `a spec survives saving and reading back`() {
        val spec = SortSpec(
            listOf(
                AssigneeSortRule(
                    listOf(PersonMatcher.Me, PersonMatcher.Named("Anna Berg"), PersonMatcher.Unassigned),
                    OthersPlacement.AFTER,
                ),
                FieldSortRule(SortFields.PRIORITY, SortDirection.ASCENDING),
                FieldSortRule(SortFields.UPDATED, SortDirection.DESCENDING),
            ),
        )
        assertEquals(spec, SortSpecCodec.decode(SortSpecCodec.encode(spec)))
    }

    @Test
    fun `nothing saved, or something unreadable, is no sort`() {
        assertEquals(SortSpec(), SortSpecCodec.decode(null))
        assertEquals(SortSpec(), SortSpecCodec.decode("not json"))
    }

    @Test
    fun `rules it can't read are dropped one by one`() {
        val saved = """[{"type":"field","field":"gone","direction":"ASCENDING"},
            {"type":"future"},
            {"type":"field","field":"created","direction":"DESCENDING"}]"""
        assertEquals(
            SortSpec(listOf(FieldSortRule(SortFields.CREATED, SortDirection.DESCENDING))),
            SortSpecCodec.decode(saved),
        )
    }
}
