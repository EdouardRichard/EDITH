package com.sheen.rokid.transport

import com.rokid.cxr.session.*
import org.junit.Assert.*
import org.junit.Test

class SdkSignatureTest {
    @Test fun exactBinaryUsesSynchronousWholeArrayAudio() {
        assertEquals(SessionResult::class.java, CxrSession::class.java.getMethod("startAudioStream").returnType)
        assertEquals(SessionResult::class.java, CxrSession::class.java.getMethod("stopAudioStream").returnType)
        assertEquals(Void.TYPE, IAudioCallback::class.java.getMethod("onAudioReceived", ByteArray::class.java).returnType)
    }
    @Test fun authorizationUsesMicrophoneAndExplicitSupportedMediaOnly() {
        assertEquals(listOf(GlassPermission.MICROPHONE), CxrBackend.authorizationPermissions(false))
        assertEquals(listOf(GlassPermission.MICROPHONE, GlassPermission.MEDIA), CxrBackend.authorizationPermissions(true))
    }
}
