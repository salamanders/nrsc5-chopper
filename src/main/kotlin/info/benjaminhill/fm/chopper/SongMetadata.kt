package info.benjaminhill.fm.chopper

import mu.KLoggable
import org.apache.commons.lang3.StringUtils
import java.io.File
import java.io.FileWriter
import java.io.Serializable
import java.time.Instant

data class SongMetadata(
    val artist: String,
    val title: String,
    val album:String,
    val genre:String,
    val count: Long,
    val start: Instant,
    val end: Instant,
    val frequency: Double,
    val channel: Int,
    val startBuffered: Instant,
    val endBuffered: Instant,
    val bufferSeconds: Long,
    val bitrate: Double,
    val topImages: List<DurationState<String>>,
) : Serializable {

    fun imageFile(): File = File(File(parsedArgs.output, escape(artist)), "${escape(title)}.%05d.jpg".format(count))

    fun wavFile(): File = File(File(parsedArgs.output, escape(artist)), "${escape(title)}.%05d.wav".format(count))

    private fun metaFile(): File = potentialMetaFile(artist, title, count)

    fun save() {
        FileWriter(metaFile()).use { fw ->
            GSON.toJson(this, fw)
            fw.flush()
        }
    }

    companion object : Any(), KLoggable {
        override val logger = logger()

        private fun potentialMetaFile(artist: String, title: String, count: Long): File {
            val artistFolder = File(parsedArgs.output, escape(artist)).also { it.mkdirs() }
            return File(artistFolder, "${escape(title)}.%05d.json".format(count))
        }

        private fun escape(value: String) = StringUtils.stripAccents(value).replace(Regex("[^A-Za-z0-9-]+"), "_")

        /**
         * Next free song slot (max 99999)
         */
        fun getFirstUnusedCount(artist: String, title: String): Long = (0L..99999L).firstOrNull { count ->
            !potentialMetaFile(artist, title, count).exists()
        } ?: 99999L.also {
            logger.error("Unable to find a slot for `$artist:$title`")
        }
    }
}
