package info.benjaminhill.fm.chopper

import org.apache.commons.lang3.StringUtils
import java.io.*
import java.time.Instant


class SongMetadata private constructor(
    val artist: String,
    val title: String,
    private val metaFile: File,
) : Serializable {

    fun save() {
        FileOutputStream(metaFile).use { fos ->
            ObjectOutputStream(fos).use { oos ->
                oos.writeObject(this)
                oos.flush()
            }
        }
    }

    var start: Instant? = null
    var end: Instant? = null
    var startBuffered: Instant? = null
    var endBuffered: Instant? = null
    var frequency: Double? = null
    var channel: Int? = null
    var bufferSeconds: Long = 0

    val imageFile: File
        get() = File(metaFile.parentFile, "${escape(title)}.jpg")

    val nextWavFile: File
        get() = (0..9999).asSequence().map { File(metaFile.parentFile, "${escape(title)}.%04d.WAV".format(it)) }
            .firstOrNull { !it.exists() }!!

    companion object {
        private const val OUTPUT_DIR = "output"

        private fun escape(value: String) = StringUtils.stripAccents(value).replace(Regex("[^A-Za-z0-9-]+"), "_")

        fun create(artist: String, title: String): SongMetadata {
            val artistFolder = File(OUTPUT_DIR, escape(artist)).also { it.mkdirs() }
            val metaFile = File(artistFolder, "${escape(title)}.meta.prop")

            if (metaFile.canRead() && metaFile.length() > 0) {
                FileInputStream(metaFile).use { fis ->
                    ObjectInputStream(fis).use { ois ->
                        return ois.readObject() as SongMetadata
                    }
                }
            }
            return SongMetadata(artist, title, metaFile)
        }
    }

    private val serialVersionUID = 1L
}