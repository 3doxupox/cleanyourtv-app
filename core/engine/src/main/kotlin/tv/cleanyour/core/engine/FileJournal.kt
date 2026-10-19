package tv.cleanyour.core.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.cleanyour.core.model.PlanId
import tv.cleanyour.core.model.TvId
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

// Write-ahead journal on disk: `<root>/tvs/<tvId>/journal/<planId>.jsonl`, one CRC32-suffixed
// record per line, appended and forced (FileChannel.force) before `append` returns
// (ARCHITECTURE 2.4). Pure java.nio, so the crash matrix runs on the JVM.
class FileJournal(
    private val root: File,
    private val io: CoroutineContext = Dispatchers.IO,
) : Journal {
    private val lock = Mutex()
    private val tvOf = ConcurrentHashMap<PlanId, TvId>()

    private fun dir(tvId: TvId) = File(root, "tvs/${tvId.value}/journal")
    private fun file(tvId: TvId, planId: PlanId) = File(dir(tvId), planId.value + ".jsonl")

    private fun locate(planId: PlanId): File? {
        tvOf[planId]?.let { return file(it, planId) }
        val tvs = File(root, "tvs").listFiles().orEmpty()
        for (tv in tvs) {
            val f = File(tv, "journal/${planId.value}.jsonl")
            if (f.isFile) {
                runCatching { TvId(tv.name) }.getOrNull()?.let { tvOf[planId] = it }
                return f
            }
        }
        return null
    }

    override suspend fun append(record: JournalRecord) = lock.withLock {
        withContext(io) {
            val f = if (record is JournalRecord.PlanStart) {
                tvOf[record.planId] = record.tvId
                file(record.tvId, record.planId)
            } else {
                locate(record.planId) ?: throw IllegalStateException("no journal for plan ${record.planId.value}; PlanStart must come first")
            }
            f.parentFile.mkdirs()
            val bytes = (JournalJson.line(record) + "\n").toByteArray(StandardCharsets.UTF_8)
            FileChannel.open(f.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { ch ->
                val buf = ByteBuffer.wrap(bytes)
                while (buf.hasRemaining()) ch.write(buf)
                ch.force(false)
            }
        }
    }

    override suspend fun read(planId: PlanId): List<JournalRecord> = withContext(io) {
        val f = locate(planId) ?: return@withContext emptyList()
        parse(f.readBytes())
    }

    override suspend fun openRuns(tvId: TvId): List<PlanId> = withContext(io) {
        dir(tvId).listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".jsonl") }
            .sortedBy { it.name }
            .mapNotNull { f ->
                val records = parse(f.readBytes())
                val start = records.firstOrNull() as? JournalRecord.PlanStart ?: return@mapNotNull null
                if (records.any { it is JournalRecord.End }) null else start.planId.also { tvOf[it] = tvId }
            }
    }

    companion object {
        // Records up to the first torn line; a torn last line is dropped, and nothing after a torn
        // line is trusted (ARCHITECTURE 2.4).
        fun parse(bytes: ByteArray): List<JournalRecord> {
            val text = String(bytes, StandardCharsets.UTF_8)
            val out = mutableListOf<JournalRecord>()
            var start = 0
            while (start < text.length) {
                val nl = text.indexOf('\n', start)
                if (nl < 0) break   // no newline: the last line was torn mid-write
                val record = JournalJson.parseLine(text.substring(start, nl)) ?: break
                out += record
                start = nl + 1
            }
            return out
        }
    }
}
