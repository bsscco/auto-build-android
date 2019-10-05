package processors.issues

import Config
import io.ktor.application.ApplicationCall
import io.ktor.application.call
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.response.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.response.respondText
import io.ktor.routing.Route
import io.ktor.routing.get
import kotlinx.coroutines.runBlocking
import processors.issues.IssuesStatusChangeProcessor.ReadyForQaIssuesResponse.Issue
import utils.HttpClientCreator
import utils.JiraAuthenticationCookieGetter

fun Route.issuesStatusChange() {
    get("/issues/status/change") { IssuesStatusChangeProcessor(call).process() }
}

class IssuesStatusChangeProcessor(private val call: ApplicationCall) {

    private val appVersion: String
    private val httpClient = HttpClientCreator.create()
    private val jiraAuthCookie: String

    init {
        appVersion = getAppVersion().also { println(it) }
        jiraAuthCookie = runBlocking { JiraAuthenticationCookieGetter.get(httpClient) }
    }

    private fun getAppVersion(): String {
        return call.request.queryParameters["app_version"] ?: throw Exception("app_version이 필요해요!")
    }

    suspend fun process() {
        getReadyForQaIssues()
            .also { issues -> changeIssuesStatus(issues) }
            .let { issues -> createResponseText(issues) }
            .let { responseText -> respond(responseText) }
    }

    private suspend fun getReadyForQaIssues(): List<Issue> {
        return httpClient.use { client ->
            client.get<ReadyForQaIssuesResponse>(Config.getJiraReadyForQaIssuesUrl(appVersion)) {
                header("cookie", jiraAuthCookie)
            }
        }.issues
    }

    private suspend fun changeIssuesStatus(issues: List<Issue>) {
        issues.forEach { issue ->
            httpClient.use { client ->
                val response = client.post<HttpResponse>(Config.getJiraIssueQaInProgressTransitionUrl(issue.key)) {
                    header("cookie", jiraAuthCookie)
                    body = TextContent(
                        contentType = ContentType.Application.Json,
                        text = Config.JIRA_ISSUE_QA_IN_PROGRESS_TRANSITION_POST_BODY
                    )
                }
                println(response)
            }
        }
    }

    private fun createResponseText(issues: List<Issue>): String {
        return issues.fold("", { text, issue ->
            text + "(${Config.getJiraIssueUrl(issue.key)}) ${issue.fields.summary}\n"
        })
    }

    private suspend fun respond(text: String) {
        call.respondText(
            text = text,
            contentType = ContentType.Text.Plain
        )
    }

    private data class ReadyForQaIssuesResponse(val issues: List<Issue>) {
        data class Issue(val key: String, val fields: Fields) {
            data class Fields(val summary: String)
        }
    }
}