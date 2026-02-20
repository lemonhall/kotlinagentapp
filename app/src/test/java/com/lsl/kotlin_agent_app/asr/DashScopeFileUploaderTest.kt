package com.lsl.kotlin_agent_app.asr

import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashScopeFileUploaderTest {

    @Test
    fun uploadFileAndGetOssUrl_requestsPolicy_thenUploadsMultipart_andReturnsOssUrl() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                val policyJson =
                    """
                    {
                      "request_id": "r1",
                      "data": {
                        "upload_host": "${server.url("/upload")}",
                        "upload_dir": "dashscope-instant/abc/2026-02-20/xyz",
                        "oss_access_key_id": "ak",
                        "policy": "p",
                        "signature": "sig",
                        "x_oss_object_acl": "private",
                        "x_oss_forbid_overwrite": "true"
                      }
                    }
                    """.trimIndent()
                server.enqueue(MockResponse().setResponseCode(200).setBody(policyJson))
                // OkHttp may retry a POST on certain connection-level failures (multipart body is replayable).
                // Enqueue a few success responses to make this test stable under parallel test load.
                repeat(3) { server.enqueue(MockResponse().setResponseCode(200).setBody("")) }

                val baseUrl = server.url("/api/v1").toString().trimEnd('/')
                val uploader = DashScopeFileUploader(baseUrl = baseUrl, apiKey = "k_test")

                val f = File.createTempFile("chunk_001", ".ogg")
                f.writeBytes(byteArrayOf(0x01, 0x02, 0x03))

                val ossUrl = uploader.uploadFileAndGetOssUrl(modelName = "qwen3-asr-flash-filetrans", file = f)
                assertEquals("oss://dashscope-instant/abc/2026-02-20/xyz/${f.name}", ossUrl)

                val req1 = server.takeRequest()
                assertEquals("GET", req1.method)
                assertTrue(req1.path!!.startsWith("/api/v1/uploads?action=getPolicy"))

                var req2 = server.takeRequest()
                var safety = 0
                while ((req2.method != "POST" || req2.path != "/upload") && safety < 4) {
                    req2 = server.takeRequest()
                    safety += 1
                }
                assertEquals("POST", req2.method)
                assertEquals("/upload", req2.path)
                assertTrue("multipart should include key field", req2.body.readUtf8().contains("name=\"key\""))
            } finally {
                server.shutdown()
            }
        }
}
