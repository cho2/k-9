package com.fsck.k9.backend.jmap

import com.fsck.k9.backend.api.FolderInfo
import com.fsck.k9.mail.AuthenticationFailedException
import com.fsck.k9.mail.FolderType
import com.fsck.k9.mail.internet.BinaryTempFileBody
import java.io.File
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import rs.ltt.jmap.client.JmapClient
import rs.ltt.jmap.client.http.BasicAuthHttpAuthentication

class CommandSyncTest {
    private val backendStorage = InMemoryBackendStorage()
    private val okHttpClient = OkHttpClient.Builder().build()
    private val syncListener = LoggingSyncListener()

    @Before
    fun setUp() {
        BinaryTempFileBody.setTempDirectory(File(System.getProperty("java.io.tmpdir")))
        createFolderInBackendStorage()
    }

    @Test
    fun sessionResourceWithAuthenticationError() {
        val command = createCommandSync(
            MockResponse().setResponseCode(401)
        )

        command.sync(FOLDER_SERVER_ID, syncListener)

        assertEquals(SyncListenerEvent.SyncStarted(FOLDER_SERVER_ID), syncListener.getNextEvent())
        val failedEvent = syncListener.getNextEvent() as SyncListenerEvent.SyncFailed
        assertEquals(AuthenticationFailedException::class.java, failedEvent.exception!!.javaClass)
    }

    @Test
    fun fullSyncStartingWithEmptyLocalMailbox() {
        val server = createMockWebServer(
            responseBodyFromResource("/jmap_responses/session/valid_session.json"),
            responseBodyFromResource("/jmap_responses/email/email_query_M001_and_M002.json"),
            responseBodyFromResource("/jmap_responses/email/email_get_ids_M001_and_M002.json"),
            responseBodyFromResource("/jmap_responses/blob/email/email_1.eml"),
            responseBodyFromResource("/jmap_responses/blob/email/email_2.eml")
        )
        val baseUrl = server.url("/jmap/")
        val command = createCommandSync(baseUrl)

        command.sync(FOLDER_SERVER_ID, syncListener)

        val backendFolder = backendStorage.getFolder(FOLDER_SERVER_ID)
        backendFolder.assertMessages(
            "M001" to "/jmap_responses/blob/email/email_1.eml",
            "M002" to "/jmap_responses/blob/email/email_2.eml"
        )
        syncListener.assertSyncEvents(
            SyncListenerEvent.SyncStarted(FOLDER_SERVER_ID),
            SyncListenerEvent.SyncProgress(FOLDER_SERVER_ID, completed = 1, total = 2),
            SyncListenerEvent.SyncProgress(FOLDER_SERVER_ID, completed = 2, total = 2),
            SyncListenerEvent.SyncFinished(FOLDER_SERVER_ID)
        )
        server.skipRequests(3)
        server.assertRequestUrlPath("/jmap/download/test%40example.com/B001/B001?accept=application%2Foctet-stream")
        server.assertRequestUrlPath("/jmap/download/test%40example.com/B002/B002?accept=application%2Foctet-stream")
    }

    @Test
    fun fullSyncExceedingMaxObjectsInGet() {
        val command = createCommandSync(
            responseBodyFromResource("/jmap_responses/session/session_with_maxObjectsInGet_2.json"),
            responseBodyFromResource("/jmap_responses/email/email_query_M001_to_M005.json"),
            responseBodyFromResource("/jmap_responses/email/email_get_ids_M001_and_M002.json"),
            responseBodyFromResource("/jmap_responses/email/email_get_ids_M003_and_M004.json"),
            responseBodyFromResource("/jmap_responses/email/email_get_ids_M005.json"),
            responseBodyFromResource("/jmap_responses/blob/email/email_1.eml"),
            responseBodyFromResource("/jmap_responses/blob/email/email_2.eml"),
            responseBodyFromResource("/jmap_responses/blob/email/email_3.eml"),
            responseBodyFromResource("/jmap_responses/blob/email/email_3.eml"),
            responseBodyFromResource("/jmap_responses/blob/email/email_3.eml")
        )

        command.sync(FOLDER_SERVER_ID, syncListener)

        val backendFolder = backendStorage.getFolder(FOLDER_SERVER_ID)
        assertEquals(setOf("M001", "M002", "M003", "M004", "M005"), backendFolder.getMessageServerIds())
    }

    @Test
    fun fullSyncWithLocalMessagesAndDifferentMessagesInRemoteMailbox() {
        val backendFolder = backendStorage.getFolder(FOLDER_SERVER_ID)
        backendFolder.createMessages(
            "M001" to "/jmap_responses/blob/email/email_1.eml",
            "M002" to "/jmap_responses/blob/email/email_2.eml"
        )
        val command = createCommandSync(
            responseBodyFromResource("/jmap_responses/session/valid_session.json"),
            responseBodyFromResource("/jmap_responses/email/email_query_M002_and_M003.json"),
            responseBodyFromResource("/jmap_responses/email/email_get_ids_M003.json"),
            responseBodyFromResource("/jmap_responses/blob/email/email_3.eml")
        )

        command.sync(FOLDER_SERVER_ID, syncListener)

        backendFolder.assertMessages(
            "M002" to "/jmap_responses/blob/email/email_2.eml",
            "M003" to "/jmap_responses/blob/email/email_3.eml"
        )
    }

    @Test
    fun fullSyncWithLocalMessagesAndEmptyRemoteMailbox() {
        val backendFolder = backendStorage.getFolder(FOLDER_SERVER_ID)
        backendFolder.createMessages(
            "M001" to "/jmap_responses/blob/email/email_1.eml",
            "M002" to "/jmap_responses/blob/email/email_2.eml"
        )
        val command = createCommandSync(
            responseBodyFromResource("/jmap_responses/session/valid_session.json"),
            responseBodyFromResource("/jmap_responses/email/email_query_empty_result.json")
        )

        command.sync(FOLDER_SERVER_ID, syncListener)

        assertEquals(emptySet<String>(), backendFolder.getMessageServerIds())
        syncListener.assertSyncEvents(
            SyncListenerEvent.SyncStarted(FOLDER_SERVER_ID),
            SyncListenerEvent.SyncFinished(FOLDER_SERVER_ID)
        )
    }

    private fun createCommandSync(vararg mockResponses: MockResponse): CommandSync {
        val server = createMockWebServer(*mockResponses)
        return createCommandSync(server.url("/jmap/"))
    }

    private fun createCommandSync(baseUrl: HttpUrl): CommandSync {
        val httpAuthentication = BasicAuthHttpAuthentication(USERNAME, PASSWORD)
        val jmapClient = JmapClient(httpAuthentication, baseUrl)
        return CommandSync(backendStorage, jmapClient, okHttpClient, ACCOUNT_ID, httpAuthentication)
    }

    private fun createFolderInBackendStorage() {
        backendStorage.createFolders(listOf(FolderInfo(FOLDER_SERVER_ID, "Regular folder", FolderType.REGULAR)))
    }

    private fun MockWebServer.assertRequestUrlPath(expected: String) {
        val request = takeRequest()
        val requestUrl = request.requestUrl ?: error("No request URL")
        val requestUrlPath = requestUrl.encodedPath + "?" + requestUrl.encodedQuery
        assertEquals(expected, requestUrlPath)
    }

    companion object {
        private const val FOLDER_SERVER_ID = "id_folder"
        private const val USERNAME = "username"
        private const val PASSWORD = "password"
        private const val ACCOUNT_ID = "test@example.com"
    }
}
