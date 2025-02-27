package test

import com.apollographql.apollo3.ApolloClient
import com.apollographql.apollo3.annotations.ApolloExperimental
import com.apollographql.apollo3.cache.http.HttpFetchPolicy
import com.apollographql.apollo3.cache.http.httpCache
import com.apollographql.apollo3.cache.http.httpFetchPolicy
import com.apollographql.apollo3.mockserver.ChunkedResponse
import com.apollographql.apollo3.mockserver.MockServer
import com.apollographql.apollo3.mpp.currentTimeMillis
import com.apollographql.apollo3.testing.runTest
import defer.WithFragmentSpreadsQuery
import defer.fragment.ComputerFields
import defer.fragment.ScreenFields
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ApolloExperimental::class)
class DeferJvmTest {
  private lateinit var mockServer: MockServer
  private lateinit var apolloClient: ApolloClient

  private suspend fun setUp() {
    val dir = File("build/httpCache")
    dir.deleteRecursively()
    mockServer = MockServer()
    apolloClient = ApolloClient.Builder()
        .serverUrl(mockServer.url())
        .httpCache(dir, 4_000)
        .build()
  }

  private suspend fun tearDown() {
    mockServer.stop()
  }

  @Test
  fun payloadsAreReceivedIncrementallyWithHttpCache() = runTest(before = { setUp() }, after = { tearDown() }) {
    val delayMillis = 200L
    val chunkedResponse = ChunkedResponse(chunksDelayMillis = delayMillis)
    mockServer.enqueue(chunkedResponse.response)

    val syncChannel = Channel<Unit>()
    val job = launch {
      apolloClient.query(WithFragmentSpreadsQuery()).toFlow().collect {
        syncChannel.send(Unit)
      }
    }

    val jsonList = listOf(
        """{"data":{"computers":[{"__typename":"Computer","id":"Computer1"},{"__typename":"Computer","id":"Computer2"}]},"hasNext":true}""",
        """{"data":{"cpu":"386","year":1993,"screen":{"__typename":"Screen","resolution":"640x480"}},"path":["computers",0],"hasNext":true}""",
        """{"data":{"cpu":"486","year":1996,"screen":{"__typename":"Screen","resolution":"800x600"}},"path":["computers",1],"hasNext":true}""",
        """{"data":{"isColor":false},"path":["computers",0,"screen"],"hasNext":true,"label":"a"}""",
        """{"data":{"isColor":true},"path":["computers",1,"screen"],"hasNext":false,"label":"a"}""",
    )

    for ((index, json) in jsonList.withIndex()) {
      val isLast = index == jsonList.lastIndex
      chunkedResponse.send(
          content = json,
          isFirst = index == 0,
          isLast = isLast,
      )
      val timeBeforeReceive = currentTimeMillis()
      syncChannel.receive()
      assertTrue(currentTimeMillis() - timeBeforeReceive >= delayMillis)
    }
    job.cancel()

    // Also check that caching worked
    val actual = apolloClient.query(WithFragmentSpreadsQuery()).httpFetchPolicy(HttpFetchPolicy.CacheOnly).toFlow().last().dataAssertNoErrors
    val expected = WithFragmentSpreadsQuery.Data(
        listOf(
            WithFragmentSpreadsQuery.Computer("Computer", "Computer1", ComputerFields("386", 1993,
                ComputerFields.Screen("Screen", "640x480",
                    ScreenFields(false)))),
            WithFragmentSpreadsQuery.Computer("Computer", "Computer2", ComputerFields("486", 1996,
                ComputerFields.Screen("Screen", "800x600",
                    ScreenFields(true)))),
        )
    )

    assertEquals(expected, actual)
  }
}
