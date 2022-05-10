package info.benjaminhill.fm.chopper

import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*
import java.io.File

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
        val audioFile = File("examples/audiocheck.net_sin_1000Hz_-3dBFS_3s.wav")
        val tsToAmplitudes = waveformToFfts(
            waveform = audioFile.readWaveform(),
        )
        val keysToAmps = tsToAmplitudes.entries.first().value
        val keyWithMaxAmplitude = keysToAmps.indices.maxByOrNull { keysToAmps[it] }!!
        assert( keyWithMaxAmplitude in 62..64)
        val freqWithMaxAmplitude = PIANO_KEYS_TO_FREQ[keyWithMaxAmplitude]!!
        assertEquals(freqWithMaxAmplitude, 1000.0, 100.0)
    }
}