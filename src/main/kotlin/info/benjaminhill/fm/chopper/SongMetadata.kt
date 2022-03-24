package info.benjaminhill.fm.chopper

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import org.apache.commons.lang3.StringUtils
import java.io.File
import java.io.FileWriter
import java.io.Serializable
import java.time.Instant


data class SongMetadata private constructor(
    val artist: String,
    val title: String,
    val count: Long,
) : Serializable {

    var start: Instant? = null
    var end: Instant? = null
    var startBuffered: Instant? = null
    var endBuffered: Instant? = null
    var frequency: Double? = null
    var channel: Int? = null
    var bufferSeconds: Long = 0
    var bitrate:Double = 0.0

    fun imageFile(): File = File(File(OUTPUT_DIR, escape(artist)), "${escape(title)}.%05d.jpg".format(count))

    fun wavFile(): File = File(File(OUTPUT_DIR, escape(artist)), "${escape(title)}.%05d.wav".format(count))

    private fun metaFile(): File = potentialMetaFile(artist, title, count)

    fun save() {
        FileWriter(metaFile()).use { fw ->
            GSON.toJson(this, fw)
            fw.flush()
        }

        /*
        FileOutputStream(metaFile(artist, title, count)).use { fos ->
            ObjectOutputStream(fos).use { oos ->
                oos.writeObject(this)
                oos.flush()
            }
        }
        */
    }

    companion object {
        private const val OUTPUT_DIR = "output"


        private val GSON: Gson =
            GsonBuilder().registerTypeAdapter(Instant::class.java, object : TypeAdapter<Instant>() {
                    override fun write(out: JsonWriter, value: Instant?) {
                        if (value == null) {
                            out.nullValue()
                        } else {
                            out.value(value.toEpochMilli())
                        }
                    }

                    override fun read(`in`: JsonReader): Instant? {
                        val token = `in`.peek()
                        if (token == JsonToken.NULL) {
                            `in`.nextNull()
                            return null
                        }
                        val instantLong = `in`.nextLong()
                        return Instant.ofEpochMilli(instantLong)
                    }
                }).setPrettyPrinting().create()

        private fun potentialMetaFile(artist: String, title: String, count: Long): File {
            val artistFolder = File(OUTPUT_DIR, escape(artist)).also { it.mkdirs() }
            return File(artistFolder, "${escape(title)}.%05d.json".format(count))
        }

        private fun escape(value: String) = StringUtils.stripAccents(value).replace(Regex("[^A-Za-z0-9-]+"), "_")

        /**
         * Next free song slot
         */
        fun create(artist: String, title: String): SongMetadata {
            val count = (0L..99999L).firstOrNull { count ->
                !potentialMetaFile(artist, title, count).exists()
            } ?: error("Unable to find a slot for `$artist:$title`")
            return SongMetadata(artist, title, count)
        }
    }
}