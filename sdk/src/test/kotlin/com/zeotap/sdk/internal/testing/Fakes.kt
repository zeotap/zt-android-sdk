package com.zeotap.sdk.internal.testing

import com.zeotap.sdk.Logger
import com.zeotap.sdk.Storage
import com.zeotap.sdk.internal.consent.tcf.TcData
import com.zeotap.sdk.internal.consent.tcf.TcfDefaults
import com.zeotap.sdk.internal.util.Clock
import com.zeotap.sdk.internal.util.IdGenerator
import java.util.Locale

/** Settable wall clock. */
internal class FakeClock(var nowMs: Long) : Clock {
    override fun nowMs(): Long = nowMs

    fun advance(ms: Long) {
        nowMs += ms
    }
}

/** Deterministic v4-shaped UUIDs `00000000-0000-4000-8000-000000000001`, `…002`, … */
internal class SequenceIdGenerator : IdGenerator {
    private var n = 0

    override fun uuid(): String = String.format(Locale.ROOT, "00000000-0000-4000-8000-%012d", ++n)

    fun reset() {
        n = 0
    }
}

/** Insertion-ordered in-memory [Storage] with write/remove counters. */
internal class InMemoryStorage : Storage {
    val data = LinkedHashMap<String, String>()
    var writes = 0
    var removes = 0

    override fun get(key: String): String? = data[key]

    override fun set(key: String, value: String) {
        writes++
        data[key] = value
    }

    override fun remove(key: String) {
        removes++
        data.remove(key)
    }

    fun snapshot(): Map<String, String> = LinkedHashMap(data)

    fun resetCounters() {
        writes = 0
        removes = 0
    }
}

/** Scriptable `IABTCF_*` source; [set] fires observers synchronously unless `notify = false`. */
internal class FakeTcfDefaults(initial: TcData = TcData.EMPTY) : TcfDefaults {
    var data: TcData = initial
        private set
    private val observers = mutableListOf<() -> Unit>()
    val observerCount: Int get() = observers.size

    override fun read(): TcData = data

    override fun observe(onChange: () -> Unit): AutoCloseable {
        observers.add(onChange)
        return AutoCloseable { observers.remove(onChange) }
    }

    fun set(data: TcData, notify: Boolean = true) {
        this.data = data
        if (notify) observers.toList().forEach { it() }
    }
}

/** Captures log lines for assertions. */
internal class RecordingLogger : Logger {
    val debugs = mutableListOf<String>()
    val warns = mutableListOf<String>()
    val errors = mutableListOf<Pair<String, Throwable?>>()

    override fun debug(message: String) {
        debugs.add(message)
    }

    override fun warn(message: String) {
        warns.add(message)
    }

    override fun error(message: String, error: Throwable?) {
        errors.add(message to error)
    }
}
