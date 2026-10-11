package net.tagpad.tasker

import com.intellij.tasks.Comment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Date

class ThreadCommentsTest {

    private class TestComment(override val commentId: String, override val parentId: String? = null) :
        Comment(), ThreadedComment {
        override fun getText(): String = commentId
        override fun getAuthor(): String? = null
        override fun getDate(): Date? = null
    }

    private fun order(comments: List<Comment>): List<Pair<String, Int>> =
        threadComments(comments).map { (comment, depth) -> comment.text to depth }

    @Test
    fun `replies follow the comment they answer, indented`() {
        val comments = listOf(
            TestComment("1"), TestComment("2"), TestComment("3", parentId = "1"), TestComment("4", parentId = "3"),
        )
        assertEquals(listOf("1" to 0, "3" to 1, "4" to 2, "2" to 0), order(comments))
    }

    @Test
    fun `a reply to a missing comment is top-level`() {
        assertEquals(listOf("1" to 0, "2" to 0), order(listOf(TestComment("1"), TestComment("2", parentId = "9"))))
    }

    @Test
    fun `a comment replying to itself is top-level`() {
        assertEquals(listOf("1" to 0), order(listOf(TestComment("1", parentId = "1"))))
    }

    @Test
    fun `a loop of replies is kept rather than dropped`() {
        val comments = listOf(TestComment("1", parentId = "2"), TestComment("2", parentId = "1"))
        assertEquals(setOf("1", "2"), order(comments).map { it.first }.toSet())
    }
}
