package xtdb.indexer

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.ipc.ArrowStreamReader
import xtdb.api.log.Log
import xtdb.api.log.Log.Message
import xtdb.api.log.LogOffset
import xtdb.api.log.Watchers
import xtdb.arrow.asChannel
import xtdb.trie.TrieCatalog
import java.time.Duration
import java.time.Instant

class LogProcessor(
    allocator: BufferAllocator,
    private val indexer: IIndexer,
    private val log: Log,
    private val trieCatalog: TrieCatalog,
    meterRegistry: MeterRegistry,
    flushTimeout: Duration
) : Log.Subscriber, AutoCloseable {

    private val watchers = Watchers(indexer.latestCompletedTx()?.txId ?: -1)

    val ingestionError get() = watchers.exception

    data class Flusher(
        val flushTimeout: Duration,
        var lastFlushCheck: Instant,
        var previousChunkTxId: Long,
        var flushedTxId: Long
    ) {
        constructor(flushTimeout: Duration, indexer: IIndexer) : this(
            flushTimeout, Instant.now(),
            previousChunkTxId = indexer.latestCompletedChunkTx()?.txId ?: -1,
            flushedTxId = -1
        )

        fun checkChunkTimeout(now: Instant, currentChunkTxId: Long, latestCompletedTxId: Long): Message? =
            when {
                lastFlushCheck + flushTimeout >= now || flushedTxId == latestCompletedTxId -> null
                currentChunkTxId != previousChunkTxId -> {
                    lastFlushCheck = now
                    previousChunkTxId = currentChunkTxId
                    null
                }

                else -> {
                    lastFlushCheck = now
                    Message.FlushChunk(currentChunkTxId)
                }
            }

        fun checkChunkTimeout(indexer: IIndexer) =
            checkChunkTimeout(
                Instant.now(),
                currentChunkTxId = indexer.latestCompletedChunkTx()?.txId ?: -1,
                latestCompletedTxId = indexer.latestCompletedTx()?.txId ?: -1
            )
    }

    private val allocator =
        allocator.newChildAllocator("log-processor", 0, Long.MAX_VALUE)
            .also {
                Gauge.builder("watcher.allocator.allocated_memory", allocator) { it.allocatedMemory.toDouble() }
                    .baseUnit("bytes")
                    .register(meterRegistry)
            }

    private val flusher = Flusher(flushTimeout, indexer)

    private val subscription = log.subscribe(this)

    override fun close() {
        subscription.close()
        allocator.close()
    }

    override val latestCompletedOffset: LogOffset
        get() = indexer.latestCompletedTx()?.txId ?: -1

    override fun processRecords(records: List<Log.Record>) = runBlocking {
        flusher.checkChunkTimeout(indexer)?.let { flushMsg ->
            flusher.flushedTxId = log.appendMessage(flushMsg).await()
        }

        records.forEach { record ->
            val offset = record.logOffset

            try {
                val res = when (val msg = record.message) {
                    is Message.Tx -> {
                        msg.payload.asChannel.use { txOpsCh ->
                            ArrowStreamReader(txOpsCh, allocator).use { reader ->
                                reader.vectorSchemaRoot.use { root ->
                                    reader.loadNextBatch()

                                    indexer.indexTx(offset, record.logTimestamp, root)
                                }
                            }
                        }
                    }

                    is Message.FlushChunk -> {
                        indexer.forceFlush(record)
                        null
                    }

                    is Message.TriesAdded -> {
                        msg.tries.forEach { trieCatalog.addTrie(it.tableName, it.trieKey) }
                        null
                    }
                }

                watchers.notify(offset, res)
            } catch (e: Throwable) {
                watchers.notify(offset, e)
            }
        }
    }

    @JvmOverloads
    fun awaitAsync(offset: LogOffset = log.latestSubmittedOffset) = watchers.awaitAsync(offset)
}
