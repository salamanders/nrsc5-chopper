package info.benjaminhill.fm.chopper

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Semaphore
import java.time.Duration
import java.time.Instant

private val instantTypeAdapter = object : TypeAdapter<Instant>() {
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
}

private val durationTypeAdapter = object : TypeAdapter<Duration>() {
    override fun write(out: JsonWriter, value: Duration?) {
        if (value == null) {
            out.nullValue()
        } else {
            out.value(value.toMillis())
        }
    }

    override fun read(jr: JsonReader): Duration? {
        val token = jr.peek()
        if (token == JsonToken.NULL) {
            jr.nextNull()
            return null
        }
        val durationLong = jr.nextLong()
        return Duration.ofMillis(durationLong)
    }
}

internal val GSON: Gson =
    GsonBuilder()
        .registerTypeAdapter(Instant::class.java, instantTypeAdapter)
        .registerTypeAdapter(Duration::class.java, durationTypeAdapter)
        .setPrettyPrinting().create()


/**
 * Parallel map from https://github.com/Kotlin/kotlinx.coroutines/issues/1147#issuecomment-846439876
 */
inline fun <T, R> Flow<T>.mapInOrder(concurrencyLevel: Int, crossinline map: suspend (T) -> R): Flow<R> =
    Semaphore(permits = concurrencyLevel).let { semaphore ->
        channelFlow {
            collect {
                semaphore.acquire()
                send(async { map(it) })
            }
        }
            .map { it.await() }
            .onEach { semaphore.release() }
    }