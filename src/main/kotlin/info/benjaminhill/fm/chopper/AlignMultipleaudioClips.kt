package info.benjaminhill.fm.chopper

import info.benjaminhill.utils.r
import java.io.File
import java.time.Duration
import kotlin.math.hypot


fun main() {
    val allDreamFiles = File("/Users/benhill/Downloads/Aerosmith/").walk().filter {
        it.isFile && "pcm".equals(
            it.extension, ignoreCase = true
        ) && it.name.contains("Dream_On") && it.length() > 1024 * 1024 * 2 && it.canRead()
    }.map { AudioFile(
        audioFile = it,
        overlap = 0.5,
        minWindowSize = Duration.ofMillis(250),
    ) }
        .toMutableSet()

    val primaryDream = allDreamFiles.random().also {
        allDreamFiles.remove(it)
    }

    val secondDream = allDreamFiles.first()

    val sliceOfSecond = secondDream.frames
        .filter {
            it.ts in Duration.ofSeconds(30)..Duration.ofSeconds(32)
        }

    println("Frames of First: ${primaryDream.frames.size}")
    println("First frame starts at ${primaryDream.frames.first().ts.toSeconds()}s")
    println("Frames of Second: ${sliceOfSecond.size}")
    val secondSongFirstFrame = sliceOfSecond.first()
    println("Second song first frame starts at ${secondSongFirstFrame.ts.toSeconds()}s frame ${secondSongFirstFrame.startAtSample}")

    val diffs = (0..primaryDream.frames.indexOfFirst { it.ts > Duration.ofSeconds(120) }).map {offset->
        val diffAtOffset = sliceOfSecond.mapIndexed { idx, frameFromSecond ->
            frameFromSecond.pianoKeys.dist(primaryDream.frames[idx + offset].pianoKeys)
        }.average()
        offset to diffAtOffset
    }.sortedBy { it.second }

    println("Least diffs:")
    diffs.take(10).forEach { (offset, diff)->
        val timeShift = secondSongFirstFrame.ts - primaryDream.frames[offset].ts
        println("Offset $offset, diff: $diff, timeshift: ${(timeShift.toMillis()/1_000.0).r}sec")
    }

    // ImageIO.write(primaryDream.toBufferedImage(), "png", File("spect.png"))

    // find the best location for that small window of the second song
    //(0 until Duration.ofSeconds(60).toSampleCount()).associateBy { offset->

    Timer.report()
}




