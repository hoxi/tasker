package net.tagpad.tasker

import com.intellij.ide.BrowserUtil
import com.intellij.ide.browsers.UrlOpener
import com.intellij.ide.browsers.WebBrowserManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.project.Project
import java.awt.Component

/** Registered by the bundled GitLab plugin; opens its Merge Requests tool window. */
internal const val GITLAB_MERGE_REQUESTS_ACTION = "GitLab.Merge.Request.Show.List"

/**
 * Opens a link from a task, trying in turn:
 * 1. a plugin that opens such links in the IDE, as JetLab does for the GitLab repositories it knows;
 * 2. for a GitLab merge request, the bundled GitLab plugin's Merge Requests tool window, where it is installed;
 * 3. the browser.
 *
 * [component] is where the link was clicked, which the GitLab plugin's action takes its context from.
 */
internal fun openLink(project: Project, url: String, component: Component) {
    if (openInIde(project, url)) return
    if (isMergeRequestUrl(url) && showGitLabMergeRequests(component)) return
    BrowserUtil.browse(url, project)
}

/**
 * Offers [url] to the plugins that open links in the IDE, through the platform's [UrlOpener] hook, and returns
 * whether one did. Asked directly because [BrowserUtil.browse] asks them only when the IDE's default browser is
 * set to "First listed", and the default is "System default".
 */
private fun openInIde(project: Project, url: String): Boolean {
    val browser = WebBrowserManager.getInstance().firstActiveBrowser ?: return false
    return UrlOpener.EP_NAME.extensionList.any { it.openUrl(browser, url, project) }
}

/** Opens the GitLab plugin's Merge Requests tool window, if the plugin is there. */
private fun showGitLabMergeRequests(component: Component): Boolean {
    val action = ActionManager.getInstance().getAction(GITLAB_MERGE_REQUESTS_ACTION) ?: return false
    ActionManager.getInstance().tryToExecute(action, null, component, ActionPlaces.UNKNOWN, true)
    return true
}

/** Whether [url] is a GitLab merge request's page, which GitLab marks with "/-/merge_requests/<number>". */
internal fun isMergeRequestUrl(url: String): Boolean = MERGE_REQUEST_PATH.containsMatchIn(url)

private val MERGE_REQUEST_PATH = Regex("""/-/merge_requests/\d+""")
