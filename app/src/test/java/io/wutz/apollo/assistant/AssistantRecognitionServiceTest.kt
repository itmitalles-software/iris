package io.wutz.apollo.assistant

import android.content.Intent
import android.os.RemoteException
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Test

class AssistantRecognitionServiceTest {
    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `start listening is refused at once so callers never hang`() {
        val callback = mockk<RecognitionService.Callback>(relaxed = true)

        startListening(callback)

        verify(exactly = 1) { callback.error(SpeechRecognizer.ERROR_CLIENT) }
    }

    @Test
    fun `a dead caller does not crash the refusal`() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>(), any()) } returns 0
        val callback = mockk<RecognitionService.Callback>()
        every { callback.error(any()) } throws RemoteException("gone")

        startListening(callback)

        verify(exactly = 1) { callback.error(SpeechRecognizer.ERROR_CLIENT) }
    }

    private fun startListening(callback: RecognitionService.Callback) {
        val service = AssistantRecognitionService()
        AssistantRecognitionService::class.java
            .getDeclaredMethod("onStartListening", Intent::class.java, RecognitionService.Callback::class.java)
            .apply { isAccessible = true }
            .invoke(service, mockk<Intent>(), callback)
    }
}
