package info.benjaminhill.fm.chopper

import info.benjaminhill.fm.chopper.AudioFile.Companion.hzToPianoKey
import info.benjaminhill.fm.chopper.AudioFile.Companion.pianoKeyToHz
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.time.Duration
import javax.imageio.ImageIO

internal class AudioHelpersKtTest {

    @Test
    fun pianoKeyToHzTest() {
        assertEquals(440.0, pianoKeyToHz(49), 0.1)
        assertEquals(27.5, pianoKeyToHz(1), 0.1)
        assertEquals(4186.0, pianoKeyToHz(88), 0.1)
    }

    @Test
    fun hzToPianoKeyTest() {
        assertEquals(49, hzToPianoKey(440.0))
    }

    @Test
    fun oneHundredHzTest() {
        val audioFile = AudioFile(File("examples/audiocheck.net_sin_1000Hz_-3dBFS_3s.wav"))
        val keyWithMaxAmplitude =
            audioFile.frames.first().pianoKeys.mapIndexed { key, amp -> key to amp }.maxBy { it.second }

        assert(keyWithMaxAmplitude.first in 62..64)
        val freqWithMaxAmplitude = pianoKeyToHz(keyWithMaxAmplitude.first)
        assertEquals(freqWithMaxAmplitude, 1000.0, 100.0)

        ImageIO.write(audioFile.toBufferedImage(), "png", File("oneHundredHzTest.png").also { it.deleteOnExit() })
    }

    @Test
    fun multiToneTest() {
        val audioFile = AudioFile(audioFile = File("examples/multi.wav"), minWindowSize = Duration.ofMillis(100))
        assertEquals(44100, audioFile.waveform.size)

        val keyWithMaxAmplitude =
            audioFile.frames.first().pianoKeys.mapIndexed { key, amp -> key to amp }.maxBy { it.second }

        assert(keyWithMaxAmplitude.first in 47..51) { "Failed: ${keyWithMaxAmplitude.first}"}
        val freqWithMaxAmplitude = pianoKeyToHz(keyWithMaxAmplitude.first)
        assertEquals(440.0, freqWithMaxAmplitude, 100.0)
    }
}