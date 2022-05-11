package info.benjaminhill.fm.chopper

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import java.time.Duration
import java.time.Instant

/** For serializing stuff with GSON */
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

/** For serializing stuff with GSON */
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
 * From https://github.com/Kotlin/kotlinx.coroutines/issues/1147
 */
@OptIn(FlowPreview::class)
fun <T, R> Flow<T>.concurrentMap(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    concurrencyLevel: Int = DEFAULT_CONCURRENCY,
    transform: suspend (T) -> R
): Flow<R> {
    return flatMapMerge(concurrencyLevel) { value ->
        flow { emit(transform(value)) }
    }.flowOn(dispatcher)
}
