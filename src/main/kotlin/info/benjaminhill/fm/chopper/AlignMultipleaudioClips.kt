package info.benjaminhill.fm.chopper

import java.io.File
import java.time.Duration


fun main() {
    val allDreamFiles = File("/Users/benhill/Downloads/Aerosmith/").walk().filter {
        it.isFile && "pcm".equals(
            it.extension, ignoreCase = true
        ) && it.name.contains("Dream_On") && it.length() > 1024 * 1024 * 2 && it.canRead()
    }.toMutableSet()

    val primaryDream = allDreamFiles.random().also {
        allDreamFiles.remove(it)
    }

    val waveform = primaryDream.readWaveform()

    val spectrograph = waveformToFfts(
        waveform = waveform, useFade = true, overlap = 0.5, minWindowSize = Duration.ofMillis(500)
    ).toSortedMap()

    fftsToImage(spectrograph, File("spect.png"))

    Timer.report()
}




