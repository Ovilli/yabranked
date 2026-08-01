package dev.yabranked.backend.store

import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Object
import java.net.URI
import java.util.UUID

/**
 * Replay packets on S3-compatible object storage — Cloudflare R2, MinIO, S3.
 *
 * This exists because the two places packets could otherwise live are both wrong
 * for a hosted deployment: the heap loses them on every restart and will OOM a
 * small instance long before that, and a local directory needs a persistent disk,
 * which the free tier of every PaaS worth using does not have.
 *
 * ## Object per chunk, because object storage cannot append
 *
 * S3 has no append. Rewriting a growing 40 MB object on every 512 KB chunk would
 * move gigabytes to store megabytes, and multipart uploads cannot be *read* until
 * they are completed — which rules them out, because a checkpointed recording has
 * to be watchable while the match is still being played.
 *
 * So each append is its own immutable object, keyed by the offset it starts at:
 *
 * ```
 * <prefix>/<matchId>/<index>/<offset padded to 20 digits>
 * ```
 *
 * Zero-padded because S3 lists lexicographically and `1000000` must sort after
 * `999999`; padded keys make a listing and a byte-ordered walk the same thing.
 * The stream's length is then the last key's offset plus that object's size, and
 * a read is a range over however many chunks the window covers.
 *
 * The offset-addressed [append] contract survives unchanged: a chunk offered at
 * the wrong offset writes nothing and is answered with the real length, so an
 * agent that cannot tell whether its timed-out request landed simply re-asks.
 * Here that also makes a duplicate harmless in a second way — the same chunk
 * writes the same key with the same bytes.
 */
class S3ReplayBlobStore(
    private val client: S3Client,
    private val bucket: String,
    /** Key prefix, so a bucket can hold more than replays. */
    private val prefix: String = "replays",
) : ReplayBlobStore {
    private val log = LoggerFactory.getLogger("replays")

    /**
     * Report a failed storage call as one line, with the trace only at DEBUG.
     *
     * Every call here can fail for a reason that is about the endpoint and not
     * about this code, and each failure used to be logged with its full stack —
     * an AWS SDK trace is roughly ninety frames. When a MinIO went away and left
     * its endpoint configured, that turned into 5816 exceptions in ten minutes,
     * about 559 000 lines, which pushed the entire day's journal out of the ring
     * buffer. The evidence needed to debug the *matches* played that day was
     * destroyed by the logging of an unrelated fault, so a routine failure now
     * costs one line and the trace stays available behind a log level.
     */
    private fun failed(what: String, cause: Throwable) {
        log.warn("could not {}: {}", what, cause.rootMessage())
        log.debug("could not {}", what, cause)
    }

    /** The innermost cause's type and message — "why", without the ninety frames. */
    private fun Throwable.rootMessage(): String {
        var root: Throwable = this
        // Guarded against a cycle: a self-referencing cause is rare and hanging
        // the logger is a worse outcome than an imprecise message.
        var hops = 0
        while (root.cause != null && root.cause !== root && hops++ < 10) root = root.cause!!
        return "${root.javaClass.simpleName}: ${root.message ?: "no message"}"
    }

    private fun streamPrefix(matchId: UUID, index: Int) = "$prefix/$matchId/$index/"

    private fun matchPrefix(matchId: UUID) = "$prefix/$matchId/"

    /** `0000000000000123456` — sortable, because S3 orders keys as text. */
    private fun keyFor(matchId: UUID, index: Int, offset: Long) =
        streamPrefix(matchId, index) + offset.toString().padStart(OFFSET_DIGITS, '0')

    private fun offsetOf(key: String): Long = key.substringAfterLast('/').toLongOrNull() ?: 0

    private fun chunksOf(matchId: UUID, index: Int): List<S3Object> = runCatching {
        buildList {
            var token: String? = null
            do {
                val response = client.listObjectsV2(
                    ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .prefix(streamPrefix(matchId, index))
                        .continuationToken(token)
                        .build()
                )
                addAll(response.contents())
                token = response.nextContinuationToken()
            } while (response.isTruncated == true)
        }.sortedBy { it.key() }
    }.onFailure { failed("list replay chunks for $matchId/$index", it) }
        .getOrDefault(emptyList())

    override fun length(matchId: UUID, index: Int): Long {
        val last = chunksOf(matchId, index).lastOrNull() ?: return 0
        return offsetOf(last.key()) + last.size()
    }

    override fun append(matchId: UUID, index: Int, offset: Long, bytes: ByteArray): Long {
        val current = length(matchId, index)
        // The caller is allowed to be wrong about where it is; being told the
        // truth is the whole protocol.
        if (offset != current) return current
        return runCatching {
            client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(keyFor(matchId, index, offset)).build(),
                RequestBody.fromBytes(bytes),
            )
            current + bytes.size
        }.onFailure {
            failed("store a replay chunk for $matchId/$index at $offset", it)
        }.getOrDefault(current)
    }

    override fun read(matchId: UUID, index: Int, offset: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val end = offset + length
        val out = java.io.ByteArrayOutputStream(minOf(length, MAX_BUFFER))

        for (chunk in chunksOf(matchId, index)) {
            val chunkStart = offsetOf(chunk.key())
            val chunkEnd = chunkStart + chunk.size()
            if (chunkEnd <= offset) continue
            if (chunkStart >= end) break

            // Only the overlapping slice, asked for as an HTTP range so a 4 MB
            // read of a 40 MB stream does not move 40 MB.
            val from = maxOf(offset, chunkStart) - chunkStart
            val to = minOf(end, chunkEnd) - chunkStart - 1
            runCatching {
                client.getObjectAsBytes(
                    GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(chunk.key())
                        .range("bytes=$from-$to")
                        .build()
                ).asByteArray()
            }.onSuccess { out.write(it) }
                .onFailure { failed("read replay chunk ${chunk.key()}", it) }
        }
        return out.toByteArray()
    }

    override fun totalBytes(matchId: UUID): Long = runCatching {
        var total = 0L
        var token: String? = null
        do {
            val response = client.listObjectsV2(
                ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(matchPrefix(matchId))
                    .continuationToken(token)
                    .build()
            )
            total += response.contents().sumOf { it.size() }
            token = response.nextContinuationToken()
        } while (response.isTruncated == true)
        total
    }.onFailure { failed("size the replay for $matchId", it) }.getOrDefault(0)

    override fun delete(matchId: UUID) {
        runCatching {
            var token: String? = null
            do {
                val response = client.listObjectsV2(
                    ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .prefix(matchPrefix(matchId))
                        .continuationToken(token)
                        .build()
                )
                val keys = response.contents().map { ObjectIdentifier.builder().key(it.key()).build() }
                if (keys.isNotEmpty()) {
                    // In batches, because a long recording is hundreds of chunks
                    // and one delete-per-object is one round trip per object.
                    keys.chunked(DELETE_BATCH).forEach { batch ->
                        client.deleteObjects(
                            DeleteObjectsRequest.builder()
                                .bucket(bucket)
                                .delete(Delete.builder().objects(batch).build())
                                .build()
                        )
                    }
                }
                token = response.nextContinuationToken()
            } while (response.isTruncated == true)
        }.onFailure { failed("delete the replay for $matchId", it) }
    }

    companion object {
        /** Enough for any stream this will ever hold; see the class doc on sorting. */
        private const val OFFSET_DIGITS = 20

        /** S3's own limit on a single delete request. */
        private const val DELETE_BATCH = 1000

        private const val MAX_BUFFER = 8 * 1024 * 1024

        /**
         * Build a client for [endpoint], or null when the settings are incomplete.
         *
         * Path-style access is forced because R2 and MinIO do not do
         * virtual-host-style buckets, and the SDK defaults to the latter.
         */
        fun create(
            endpoint: String?,
            bucket: String?,
            accessKey: String?,
            secretKey: String?,
            region: String,
        ): S3ReplayBlobStore? {
            if (bucket.isNullOrBlank() || accessKey.isNullOrBlank() || secretKey.isNullOrBlank()) return null
            val builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
                )
                .forcePathStyle(true)
                .httpClientBuilder(software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient.builder())
            if (!endpoint.isNullOrBlank()) builder.endpointOverride(URI.create(endpoint))
            return S3ReplayBlobStore(builder.build(), bucket)
        }
    }
}
