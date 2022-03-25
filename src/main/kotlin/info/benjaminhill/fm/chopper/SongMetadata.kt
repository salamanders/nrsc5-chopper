package info.benjaminhill.fm.chopper

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import mu.KLoggable
import org.apache.commons.lang3.StringUtils
import java.io.File
import java.io.FileWriter
import java.io.Serializable
import java.time.Instant


data class SongMetadata(
    val artist: String,
    val title: String,
    val count: Long,
    val start: Instant,
    val end: Instant,
    val frequency: Double,
    val channel: Int,
    val startBuffered: Instant,
    val endBuffered: Instant,
    val bufferSeconds: Long,
    val bitrate: Double,
) : Serializable {

    fun imageFile(): File = File(File(OUTPUT_DIR, escape(artist)), "${escape(title)}.%05d.jpg".format(count))

    fun wavFile(): File = File(File(OUTPUT_DIR, escape(artist)), "${escape(title)}.%05d.wav".format(count))

    private fun metaFile(): File = potentialMetaFile(artist, title, count)

    fun save() {
        FileWriter(metaFile()).use { fw ->
            GSON.toJson(this, fw)
            fw.flush()
        }
    }

    companion object : Any(), KLoggable {
        override val logger = logger()
        private const val OUTPUT_DIR = "output"

        private fun potentialMetaFile(artist: String, title: String, count: Long): File {
            val artistFolder = File(OUTPUT_DIR, escape(artist)).also { it.mkdirs() }
            return File(artistFolder, "${escape(title)}.%05d.json".format(count))
        }

        private val GSON: Gson =
            GsonBuilder().registerTypeAdapter(Instant::class.java, object : TypeAdapter<Instant>() {
                override fun write(out: JsonWriter, value: Instant?) {
                    if (value == null) {
                        out.nullValue()
                    } else {
                        out.value(value.toEpochMilli())
                    }
                }

                override fun read(jr: JsonReader): Instant? {
                    val token = jr.peek()
                    if (token == JsonToken.NULL) {
                        jr.nextNull()
                        return null
                    }
                    val instantLong = jr.nextLong()
                    return Instant.ofEpochMilli(instantLong)
                }
            }).setPrettyPrinting().create()

        private fun escape(value: String) = StringUtils.stripAccents(value).replace(Regex("[^A-Za-z0-9-]+"), "_")

        /**
         * Next free song slot
         */
        fun getFirstUnusedCount(artist: String, title: String): Long = (0L..99999L).firstOrNull { count ->
            !potentialMetaFile(artist, title, count).exists()
        } ?: 0L.also {
            logger.error("Unable to find a slot for `$artist:$title`")
        }
    }
}