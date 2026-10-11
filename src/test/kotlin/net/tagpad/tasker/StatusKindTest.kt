package net.tagpad.tasker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StatusKindTest {

    @Test
    fun `reads common state names`() {
        assertEquals(StatusKind.TODO, statusKind("Open"))
        assertEquals(StatusKind.TODO, statusKind("To Do"))
        assertEquals(StatusKind.IN_PROGRESS, statusKind("In Progress"))
        assertEquals(StatusKind.IN_PROGRESS, statusKind("In Review"))
        assertEquals(StatusKind.DONE, statusKind("Done"))
        assertEquals(StatusKind.DONE, statusKind("Won't fix"))
    }

    @Test
    fun `reopened is not mistaken for open`() {
        assertEquals(StatusKind.REOPENED, statusKind("Reopened"))
    }

    @Test
    fun `strips YouTrack's braces around multi-word values`() {
        assertEquals(StatusKind.DONE, statusKind("{Won't fix}"))
    }

    @Test
    fun `an unknown state is its own kind, not done`() {
        assertEquals(StatusKind.CUSTOM, statusKind("Selected for Development"))
    }
}
