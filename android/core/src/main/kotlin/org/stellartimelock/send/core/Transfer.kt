package org.stellartimelock.send.core

import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

enum class TransferState { AWAITING_CONSENT, IN_PROGRESS, SUCCEEDED, FAILED, CANCELLED }

data class TransferUpdate(
    val id: String,
    val name: String,
    val state: TransferState,
    val bytes: Long,
    val total: Long,
    val bytesPerSecond: Double?,
    val secondsRemaining: Double?,
    val detail: String?,
    val batchIndex: Int = 0,
    val batchCount: Int = 0
)

data class TransferOutcome(
    val state: TransferState,
    val code: String?,
    val message: String?,
    val savedPath: String?,
    val text: String?,
    val chunks: Int
)

object TransferSession {
    fun send(
        output: OutputStream,
        input: InputStream,
        local: DeviceMaterial,
        peerId: String,
        peerFingerprint: String,
        certificateFingerprint: String,
        kind: String,
        name: String,
        mime: String,
        size: Long,
        sha256: String,
        content: InputStream,
        cancelled: () -> Boolean = { false },
        onProgress: (TransferUpdate) -> Unit = {},
        announcement: RelayAnnouncement? = null,
        transferId: String = UUID.randomUUID().toString(),
        direct: DirectAttempt? = null,
        onDirect: (Boolean) -> Unit = {}
    ): TransferOutcome {
        val out = ByteOut(output)
        val inp = ByteIn(input)
        var promoted: javax.net.ssl.SSLSocket? = null
        try {
        val remote = handshake(out, inp, local, peerFingerprint, peerId, certificateFingerprint, allowNew = false, confirmPeer = null, announcement = announcement, direct = direct?.offer)
        val wantAcks = remote.optBoolean("resume")
        promoted = direct?.open(local, remote)
        if (promoted != null) {
            out.inner = promoted.outputStream
            inp.inner = promoted.inputStream
            onDirect(true)
        } else if (direct != null && remote.optJSONObject("direct") != null) {
            onDirect(false)
        }
        val offer = JSONObject()
            .put("transferId", transferId)
            .put("kind", kind)
            .put("name", name)
            .put("size", size)
            .put("sha256", sha256)
            .put("mime", mime)
        onProgress(TransferUpdate(transferId, name, TransferState.AWAITING_CONSENT, 0, size, null, null, null))
        FrameCodec.writeJson(out, MessageType.OFFER, offer)
        val reply = FrameCodec.read(inp)
        if (reply.type == MessageType.REJECT) {
            val json = JSONObject(reply.payload.toString(Charsets.UTF_8))
            return TransferOutcome(TransferState.FAILED, ErrorCodes.DECLINED, json.optString("reason", "Declined"), null, null, 0)
        }
        if (reply.type != MessageType.ACCEPT) {
            return TransferOutcome(TransferState.FAILED, ErrorCodes.BAD_REQUEST, "The other device did not accept the transfer.", null, null, 0)
        }
        val acceptJson = JSONObject(reply.payload.toString(Charsets.UTF_8))
        var sent = acceptJson.optLong("offset", 0).coerceIn(0, size)
        if (sent > 0) {
            var left = sent
            val skip = ByteArray(8192)
            while (left > 0) {
                val n = content.read(skip, 0, minOf(skip.size.toLong(), left).toInt())
                if (n < 0) {
                    return TransferOutcome(TransferState.FAILED, ErrorCodes.IO, "Could not resume the transfer.", null, null, 0)
                }
                left -= n
            }
        }
        var lastAck = sent
        val started = System.nanoTime()
        var chunks = 0
        val buffer = ByteArray(ProtocolLimits.MAX_CHUNK_DATA)
        while (sent < size) {
            if (cancelled()) {
                FrameCodec.writeJson(out, MessageType.CANCEL, JSONObject().put("transferId", transferId))
                return TransferOutcome(TransferState.CANCELLED, ErrorCodes.CANCELLED, "Cancelled", null, null, chunks)
            }
            val want = minOf(buffer.size.toLong(), size - sent).toInt()
            var read = 0
            while (read < want) {
                val n = content.read(buffer, read, want - read)
                if (n < 0) break
                read += n
            }
            if (read == 0) return TransferOutcome(TransferState.FAILED, ErrorCodes.IO, "The file ended before the promised size.", null, null, chunks)
            FrameCodec.write(out, MessageType.CHUNK, ChunkCodec.encode(UUID.fromString(transferId), sent, buffer.copyOf(read)))
            sent += read
            chunks++
            if (wantAcks && sent - lastAck >= ResumeAck.INTERVAL_BYTES) {
                lastAck = readAck(inp, transferId, lastAck, sent)
            }
            val seconds = (System.nanoTime() - started) / 1_000_000_000.0
            val rate = if (seconds > 0.2) sent / seconds else null
            val eta = if (rate != null && rate > 0) (size - sent) / rate else null
            onProgress(TransferUpdate(transferId, name, TransferState.IN_PROGRESS, sent, size, rate, eta, null))
        }
        FrameCodec.writeJson(out, MessageType.COMPLETE, JSONObject().put("transferId", transferId).put("sha256", sha256))
        val done = FrameCodec.read(inp)
        if (done.type == MessageType.ERROR) {
            val json = JSONObject(done.payload.toString(Charsets.UTF_8))
            return TransferOutcome(TransferState.FAILED, json.optString("code", ErrorCodes.BAD_REQUEST), json.optString("message"), null, null, chunks)
        }
        if (done.type != MessageType.COMPLETE) {
            return TransferOutcome(TransferState.FAILED, ErrorCodes.BAD_REQUEST, "The other device did not confirm the transfer.", null, null, chunks)
        }
        return TransferOutcome(TransferState.SUCCEEDED, null, null, null, null, chunks)
        } finally {
            runCatching { promoted?.close() }
        }
    }

    fun receive(
        output: OutputStream,
        input: InputStream,
        local: DeviceMaterial,
        peerFingerprint: String?,
        peerId: String?,
        certificateFingerprint: String,
        allowNew: Boolean,
        confirmPeer: ((String, String, String) -> Boolean)?,
        confirmOffer: (String, Long) -> Boolean,
        openFile: (String) -> File,
        onProgress: (TransferUpdate) -> Unit = {},
        onService: ((ServiceRequest) -> ServiceResult)? = null,
        announcement: RelayAnnouncement? = null,
        onPaired: (() -> Unit)? = null,
        onRemoteInput: ((RemoteInput) -> Boolean)? = null,
        nextRemoteFrame: (() -> ByteArray?)? = null,
        remoteCancelled: (() -> Boolean)? = null,
        direct: DirectAttempt? = null,
        onDirect: (Boolean) -> Unit = {}
    ): TransferOutcome {
        val out = ByteOut(output)
        val inp = ByteIn(input)
        var promoted: javax.net.ssl.SSLSocket? = null
        try {
        val remote = handshake(out, inp, local, peerFingerprint, peerId, certificateFingerprint, allowNew, confirmPeer, announcement, direct = direct?.offer)
        val wantAcks = remote.optBoolean("resume")
        promoted = direct?.open(local, remote)
        if (promoted != null) {
            out.inner = promoted.outputStream
            inp.inner = promoted.inputStream
            onDirect(true)
        } else if (direct != null && remote.optJSONObject("direct") != null) {
            onDirect(false)
        }
        onPaired?.invoke()
        val frame = try {
            FrameCodec.read(inp)
        } catch (_: EOFException) {
            return TransferOutcome(TransferState.SUCCEEDED, null, "Paired", null, null, 0)
        }
        if (frame.type == MessageType.SERVICE) {
            return handleService(out, inp, frame, onService, onRemoteInput, nextRemoteFrame, remoteCancelled)
        }
        if (frame.type != MessageType.OFFER) throw ProtocolException("Expected an offer.")
        val offer = JSONObject(frame.payload.toString(Charsets.UTF_8))
        val id = offer.getString("transferId")
        val kind = offer.getString("kind")
        val name = offer.getString("name")
        val size = offer.getLong("size")
        val sha = offer.getString("sha256")
        val batchIndex = offer.optInt("index", 0)
        val batchCount = offer.optInt("count", 0)
        val max = if (kind == "text") ProtocolLimits.MAX_TEXT_BYTES.toLong() else ProtocolLimits.MAX_FILE_BYTES
        if (size < 0 || size > max || !isFingerprint(sha) || kind !in setOf("file", "text")) {
            FrameCodec.writeJson(out, MessageType.REJECT, JSONObject().put("transferId", id).put("reason", "Content is not allowed."))
            return TransferOutcome(TransferState.FAILED, ErrorCodes.LIMIT, "Content is not allowed.", null, null, 0)
        }
        if (!confirmOffer(name, size)) {
            FrameCodec.writeJson(out, MessageType.REJECT, JSONObject().put("transferId", id).put("reason", "Declined"))
            return TransferOutcome(TransferState.CANCELLED, ErrorCodes.DECLINED, "Declined", null, null, 0)
        }
        val prepared = try {
            prepareSink(kind, name, sha, size, id, openFile)
        } catch (ex: Exception) {
            val reason = sinkReason(ex)
            FrameCodec.writeJson(out, MessageType.REJECT, JSONObject().put("transferId", id).put("reason", reason))
            return TransferOutcome(TransferState.FAILED, ErrorCodes.IO, reason, null, null, 0)
        }
        val file = prepared.file
        val partial = prepared.partial
        val resume = prepared.resume
        val sink = prepared.sink
        val accept = JSONObject().put("transferId", id)
        if (resume > 0) accept.put("offset", resume)
        FrameCodec.writeJson(out, MessageType.ACCEPT, accept)
        onProgress(TransferUpdate(id, name, TransferState.IN_PROGRESS, resume, size, null, null, if (resume > 0) "Resuming" else null, batchIndex, batchCount))
        val textOut = if (kind == "text") java.io.ByteArrayOutputStream() else null
        var got = resume
        var lastAck = resume
        try {
            var chunks = 0
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            if (resume > 0 && partial != null) {
                partial.inputStream().use { existing ->
                    val buffer = ByteArray(8192)
                    var left = resume
                    while (left > 0) {
                        val n = existing.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                        if (n < 0) throw ProtocolException("The saved partial file is shorter than its resume marker.", ErrorCodes.IO)
                        digest.update(buffer, 0, n)
                        left -= n
                    }
                }
            }
            while (got < size) {
                val incoming = FrameCodec.read(inp)
                if (incoming.type == MessageType.CANCEL) {
                    sink?.close()
                    if (partial != null) ResumeFiles.discard(partial)
                    return TransferOutcome(TransferState.CANCELLED, ErrorCodes.CANCELLED, "Cancelled", null, null, chunks)
                }
                if (incoming.type != MessageType.CHUNK) throw ProtocolException("Expected a chunk.")
                val chunk = ChunkCodec.decode(incoming.payload)
                if (chunk.transferId.toString() != id || chunk.offset != got) throw ProtocolException("Chunk was out of order.")
                if (got + chunk.data.size > size) throw ProtocolException("Chunk passed the promised size.", ErrorCodes.LIMIT)
                digest.update(chunk.data)
                sink?.write(chunk.data)
                textOut?.write(chunk.data)
                got += chunk.data.size
                chunks++
                if (got - lastAck >= ResumeAck.INTERVAL_BYTES) {
                    checkpoint(sink, partial, sha, got, id, size)
                    if (wantAcks) {
                        FrameCodec.writeJson(out, MessageType.ACK, JSONObject().put("transferId", id).put("offset", got))
                    }
                    lastAck = got
                }
                onProgress(TransferUpdate(id, name, TransferState.IN_PROGRESS, got, size, null, null, null, batchIndex, batchCount))
            }
            val end = FrameCodec.read(inp)
            if (end.type != MessageType.COMPLETE) throw ProtocolException("Expected a completed transfer.")
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            val remoteHash = JSONObject(end.payload.toString(Charsets.UTF_8)).getString("sha256")
            if (!pinsEqual(actual, sha) || !pinsEqual(actual, remoteHash)) {
                sink?.close()
                if (partial != null) ResumeFiles.discard(partial)
                FrameCodec.writeJson(out, MessageType.ERROR, JSONObject().put("transferId", id).put("code", ErrorCodes.HASH_MISMATCH).put("message", "Integrity check failed."))
                return TransferOutcome(TransferState.FAILED, ErrorCodes.HASH_MISMATCH, "Integrity check failed.", null, null, chunks)
            }
            sink?.close()
            if (partial != null) File(partial.parentFile, partial.name + ".meta").delete()
            if (file != null && partial != null && !partial.renameTo(file)) {
                partial.copyTo(file, overwrite = true)
                partial.delete()
            }
            FrameCodec.writeJson(out, MessageType.COMPLETE, JSONObject().put("transferId", id).put("sha256", actual))
            val text = textOut?.toByteArray()?.toString(Charsets.UTF_8)
            return TransferOutcome(TransferState.SUCCEEDED, null, null, file?.absolutePath, text, chunks)
        } catch (ex: Exception) {
            sink?.close()
            if (ex is ProtocolException && ex.code == ErrorCodes.HASH_MISMATCH) {
                if (partial != null) ResumeFiles.discard(partial)
            } else if (partial != null && got in 1 until size) {
                ResumeFiles.preserve(partial, sha, got, id, size)
            } else if (partial != null) {
                ResumeFiles.discard(partial)
            }
            val code = if (ex is ProtocolException) ex.code else ErrorCodes.IO
            val message = sinkReason(ex)
            runCatching {
                FrameCodec.writeJson(
                    out,
                    MessageType.ERROR,
                    JSONObject().put("transferId", id).put("code", code).put("message", message)
                )
            }
            throw ex
        }
        } finally {
            runCatching { promoted?.close() }
        }
    }

    private data class PreparedSink(
        val file: File?,
        val partial: File?,
        val resume: Long,
        val sink: FileOutputStream?
    )

    private fun prepareSink(
        kind: String,
        name: String,
        sha: String,
        size: Long,
        transferId: String,
        openFile: (String) -> File
    ): PreparedSink {
        val file = if (kind == "file") openFile(name) else null
        val dir = file?.parentFile
        val byId = if (dir != null && transferId.isNotEmpty()) File(dir, "$transferId.partial") else null
        val byName = file?.let { File(it.parentFile, it.name + ".partial") }
        val partial = when {
            byId != null && ResumeFiles.match(byId, sha, size, transferId) > 0 -> byId
            byName != null && ResumeFiles.match(byName, sha, size, transferId) > 0 -> byName
            else -> byId ?: byName
        }
        val resume = if (partial != null) ResumeFiles.match(partial, sha, size, transferId) else 0L
        partial?.parentFile?.mkdirs()
        if (resume == 0L && partial != null) ResumeFiles.discard(partial)
        val sink = partial?.let { FileOutputStream(it, resume > 0) }
        return PreparedSink(file, partial, resume, sink)
    }

    private fun sinkReason(ex: Exception): String =
        ex.message?.trim()?.takeIf { it.isNotEmpty() } ?: "Could not save the file."

    fun call(
        output: OutputStream,
        input: InputStream,
        local: DeviceMaterial,
        expectedDeviceId: String,
        expectedFingerprint: String,
        certificateFingerprint: String,
        request: ServiceRequest,
        announcement: RelayAnnouncement? = null
    ): ServiceResult {
        handshake(output, input, local, expectedFingerprint, expectedDeviceId, certificateFingerprint, allowNew = false, confirmPeer = null, announcement = announcement)
        FrameCodec.writeJson(output, MessageType.SERVICE, request.toJson())
        val frame = FrameCodec.read(input)
        if (frame.type == MessageType.ERROR) {
            val error = errorOf(frame.payload)
            return ServiceResult.fail(request.id, error.code, error.message ?: "Companion call failed.")
        }
        if (frame.type != MessageType.SERVICE_RESULT) throw ProtocolException("Expected a companion result.")
        return ServiceCodec.parseResult(frame.payload)
    }

    fun callStreaming(
        output: OutputStream,
        input: InputStream,
        local: DeviceMaterial,
        expectedDeviceId: String,
        expectedFingerprint: String,
        certificateFingerprint: String,
        request: ServiceRequest,
        onOutput: (String, String) -> Unit,
        announcement: RelayAnnouncement? = null
    ): ServiceResult {
        handshake(output, input, local, expectedFingerprint, expectedDeviceId, certificateFingerprint, allowNew = false, confirmPeer = null, announcement = announcement)
        FrameCodec.writeJson(output, MessageType.SERVICE, request.toJson())
        while (true) {
            val frame = FrameCodec.read(input)
            when (frame.type) {
                MessageType.TERMINAL_DATA -> {
                    val chunk = TerminalCodec.parseChunk(frame.payload)
                    if (chunk.id == request.id && (chunk.stream == "out" || chunk.stream == "err")) {
                        onOutput(chunk.stream, chunk.text)
                    }
                }
                MessageType.SERVICE_RESULT -> return ServiceCodec.parseResult(frame.payload)
                MessageType.ERROR -> {
                    val error = errorOf(frame.payload)
                    return ServiceResult.fail(request.id, error.code, error.message ?: "Companion call failed.")
                }
                else -> throw ProtocolException("Expected terminal output.")
            }
        }
    }

    fun shareScreen(
        output: OutputStream,
        input: InputStream,
        local: DeviceMaterial,
        expectedDeviceId: String,
        expectedFingerprint: String,
        certificateFingerprint: String,
        nextFrame: () -> ByteArray?,
        cancelled: () -> Boolean,
        announcement: RelayAnnouncement? = null
    ): ServiceResult {
        val id = java.util.UUID.randomUUID().toString()
        handshake(output, input, local, expectedFingerprint, expectedDeviceId, certificateFingerprint, allowNew = false, confirmPeer = null, announcement = announcement)
        val start = ServiceRequest(id, CompanionServices.SCREEN_SHARE, "start", JSONObject())
        FrameCodec.writeJson(output, MessageType.SERVICE, start.toJson())
        while (!cancelled()) {
            val jpeg = nextFrame()
            if (ScreenShare.acceptJpeg(jpeg)) {
                FrameCodec.write(output, MessageType.SCREEN_FRAME, jpeg!!)
            }
            Thread.sleep(ScreenShare.INTERVAL_MS)
        }
        FrameCodec.writeJson(output, MessageType.SERVICE, ServiceRequest(id, CompanionServices.SCREEN_SHARE, "stop", JSONObject()).toJson())
        val frame = FrameCodec.read(input)
        if (frame.type == MessageType.ERROR) {
            val error = errorOf(frame.payload)
            return ServiceResult.fail(id, error.code, error.message ?: "Screen share failed.")
        }
        if (frame.type != MessageType.SERVICE_RESULT) throw ProtocolException("Expected a companion result.")
        return ServiceCodec.parseResult(frame.payload)
    }

    fun requestRemote(
        output: OutputStream,
        input: InputStream,
        local: DeviceMaterial,
        expectedDeviceId: String,
        expectedFingerprint: String,
        certificateFingerprint: String,
        sessionId: String,
        from: String,
        announcement: RelayAnnouncement? = null
    ): ServiceResult {
        val id = java.util.UUID.randomUUID().toString()
        val body = JSONObject().put("session", sessionId).put("from", from)
        return call(
            output, input, local, expectedDeviceId, expectedFingerprint, certificateFingerprint,
            ServiceRequest(id, CompanionServices.REMOTE_CONTROL, RemoteControl.OP_REQUEST, body),
            announcement
        )
    }

    fun driveRemote(
        output: OutputStream,
        input: InputStream,
        local: DeviceMaterial,
        expectedDeviceId: String,
        expectedFingerprint: String,
        certificateFingerprint: String,
        sessionId: String,
        nextInput: () -> RemoteInput?,
        cancelled: () -> Boolean,
        announcement: RelayAnnouncement? = null
    ): ServiceResult {
        val id = java.util.UUID.randomUUID().toString()
        handshake(output, input, local, expectedFingerprint, expectedDeviceId, certificateFingerprint, allowNew = false, confirmPeer = null, announcement = announcement)
        val start = ServiceRequest(id, CompanionServices.REMOTE_CONTROL, RemoteControl.OP_DRIVE, JSONObject().put("session", sessionId))
        FrameCodec.writeJson(output, MessageType.SERVICE, start.toJson())
        val armed = FrameCodec.read(input)
        if (armed.type == MessageType.ERROR) {
            val error = errorOf(armed.payload)
            return ServiceResult.fail(id, error.code, error.message ?: "Remote control failed.")
        }
        if (armed.type != MessageType.SERVICE_RESULT) throw ProtocolException("Expected a companion result.")
        val ready = ServiceCodec.parseResult(armed.payload)
        if (!ready.ok) return ready
        while (!cancelled()) {
            val event = nextInput()
            if (event != null) {
                FrameCodec.write(output, MessageType.REMOTE_INPUT, RemoteInputCodec.encode(event))
            }
            Thread.sleep(RemoteControl.MIN_INPUT_GAP_MS)
        }
        FrameCodec.writeJson(output, MessageType.SERVICE, ServiceRequest(id, CompanionServices.REMOTE_CONTROL, RemoteControl.OP_STOP, JSONObject().put("session", sessionId)).toJson())
        val frame = try {
            FrameCodec.read(input)
        } catch (_: Exception) {
            return ServiceResult.done(id, RemoteControl.STOPPED)
        }
        if (frame.type == MessageType.ERROR) {
            val error = errorOf(frame.payload)
            return ServiceResult.fail(id, error.code, error.message ?: RemoteControl.STOPPED)
        }
        if (frame.type != MessageType.SERVICE_RESULT) return ServiceResult.done(id, RemoteControl.STOPPED)
        return ServiceCodec.parseResult(frame.payload)
    }

    fun watchRemote(
        output: OutputStream,
        input: InputStream,
        local: DeviceMaterial,
        expectedDeviceId: String,
        expectedFingerprint: String,
        certificateFingerprint: String,
        sessionId: String,
        nextInput: () -> RemoteInput?,
        onFrame: (ByteArray) -> Unit,
        cancelled: () -> Boolean,
        announcement: RelayAnnouncement? = null,
        direct: DirectAttempt? = null,
        onDirect: (Boolean) -> Unit = {}
    ): ServiceResult {
        val id = java.util.UUID.randomUUID().toString()
        val out = ByteOut(output)
        val inp = ByteIn(input)
        var promoted: javax.net.ssl.SSLSocket? = null
        val writer = Thread {
            try {
                while (!cancelled()) {
                    val event = nextInput()
                    if (event != null) {
                        FrameCodec.write(out, MessageType.REMOTE_INPUT, RemoteInputCodec.encode(event))
                    }
                    Thread.sleep(RemoteControl.MIN_INPUT_GAP_MS)
                }
            } catch (_: Exception) {
                // The reader loop ends the session.
            }
        }
        writer.isDaemon = true
        var started = false
        try {
            val remoteHello = handshake(
                out, inp, local, expectedFingerprint, expectedDeviceId, certificateFingerprint,
                allowNew = false, confirmPeer = null, announcement = announcement, direct = direct?.offer
            )
            promoted = direct?.open(local, remoteHello)
            if (promoted != null) {
                out.inner = promoted.outputStream
                inp.inner = promoted.inputStream
                onDirect(true)
            } else if (direct != null && remoteHello.optJSONObject("direct") != null) {
                onDirect(false)
            }
            val start = ServiceRequest(id, CompanionServices.REMOTE_CONTROL, RemoteControl.OP_WATCH, JSONObject().put("session", sessionId))
            FrameCodec.writeJson(out, MessageType.SERVICE, start.toJson())
            while (!cancelled()) {
                val next = FrameCodec.read(inp)
                if (next.type == MessageType.SERVICE_RESULT) {
                    val result = ServiceCodec.parseResult(next.payload)
                    if (!result.ok) return result
                    if (!started) {
                        started = true
                        writer.start()
                        continue
                    }
                    return result
                }
                if (next.type == MessageType.SCREEN_FRAME) {
                    if (ScreenShare.acceptJpeg(next.payload)) onFrame(next.payload)
                    continue
                }
                if (next.type == MessageType.ERROR) {
                    val error = errorOf(next.payload)
                    return ServiceResult.fail(id, error.code, error.message ?: RemotePhrases.current.failedFallback)
                }
                if (next.type == MessageType.SERVICE) {
                    val inner = ServiceCodec.parseRequest(next.payload)
                    if (inner.op == RemoteControl.OP_STOP) return ServiceResult.done(id, RemoteControl.STOPPED)
                }
            }
            return ServiceResult.done(id, RemoteControl.STOPPED)
        } catch (ex: Exception) {
            if (cancelled()) return ServiceResult.done(id, RemoteControl.STOPPED)
            return ServiceResult.fail(id, ErrorCodes.IO, RemoteDesktop.explain(ErrorCodes.IO, ex.message ?: RemoteDesktop.LINK))
        } finally {
            if (started) runCatching { writer.join(500) }
            runCatching { promoted?.close() }
        }
    }

    /**
     * One connection: ask for Allow, then read SCREEN_FRAME and write REMOTE_INPUT.
     * A computer that closes after Allow returns [RemoteDesktop.TRY_WATCH] even when
     * a frame already arrived, so the caller can open the watch connection.
     */
    fun openRemote(
        output: OutputStream,
        input: InputStream,
        local: DeviceMaterial,
        expectedDeviceId: String,
        expectedFingerprint: String,
        certificateFingerprint: String,
        sessionId: String,
        nextInput: () -> RemoteInput?,
        onAllowed: () -> Unit,
        onFrame: (ByteArray) -> Unit,
        cancelled: () -> Boolean,
        announcement: RelayAnnouncement? = null,
        direct: DirectAttempt? = null,
        onDirect: (Boolean) -> Unit = {}
    ): ServiceResult {
        val id = java.util.UUID.randomUUID().toString()
        val out = ByteOut(output)
        val inp = ByteIn(input)
        var promoted: javax.net.ssl.SSLSocket? = null
        val writer = Thread {
            try {
                while (!cancelled()) {
                    val event = nextInput()
                    if (event != null) {
                        FrameCodec.write(out, MessageType.REMOTE_INPUT, RemoteInputCodec.encode(event))
                    }
                    Thread.sleep(RemoteControl.MIN_INPUT_GAP_MS)
                }
                FrameCodec.writeJson(
                    out,
                    MessageType.SERVICE,
                    ServiceRequest(id, CompanionServices.REMOTE_CONTROL, RemoteControl.OP_STOP, JSONObject().put("session", sessionId)).toJson()
                )
            } catch (_: Exception) {
                // The reader loop ends the session.
            }
        }
        writer.isDaemon = true
        var started = false
        try {
            val remoteHello = handshake(
                out, inp, local, expectedFingerprint, expectedDeviceId, certificateFingerprint,
                allowNew = false, confirmPeer = null, announcement = announcement, direct = direct?.offer
            )
            promoted = direct?.open(local, remoteHello)
            if (promoted != null) {
                out.inner = promoted.outputStream
                inp.inner = promoted.inputStream
                onDirect(true)
            } else if (direct != null && remoteHello.optJSONObject("direct") != null) {
                onDirect(false)
            }
            val start = ServiceRequest(
                id,
                CompanionServices.REMOTE_CONTROL,
                RemoteControl.OP_REQUEST,
                JSONObject().put("session", sessionId).put("from", RemoteControl.FROM_PHONE)
            )
            FrameCodec.writeJson(out, MessageType.SERVICE, start.toJson())
            while (!cancelled()) {
                val next = try {
                    FrameCodec.read(inp)
                } catch (ex: Exception) {
                    if (cancelled()) return ServiceResult.done(id, RemoteControl.STOPPED)
                    if (RemoteDesktop.opensWatch(started, userStopped = false)) {
                        return ServiceResult.fail(id, RemoteDesktop.TRY_WATCH, "The computer closed the request. Opening the watch connection.")
                    }
                    return ServiceResult.fail(id, ErrorCodes.IO, RemoteDesktop.explain(ErrorCodes.IO, ex.message ?: RemoteDesktop.LINK))
                }
                if (next.type == MessageType.SERVICE_RESULT) {
                    val result = ServiceCodec.parseResult(next.payload)
                    if (!result.ok) return result
                    if (!started) {
                        started = true
                        onAllowed()
                        writer.start()
                        continue
                    }
                    return result
                }
                if (next.type == MessageType.SCREEN_FRAME) {
                    if (ScreenShare.acceptJpeg(next.payload)) onFrame(next.payload)
                    continue
                }
                if (next.type == MessageType.ERROR) {
                    val error = errorOf(next.payload)
                    return ServiceResult.fail(id, error.code, error.message ?: RemotePhrases.current.failedFallback)
                }
                if (next.type == MessageType.SERVICE) {
                    val inner = ServiceCodec.parseRequest(next.payload)
                    if (inner.op == RemoteControl.OP_STOP) return ServiceResult.done(id, RemoteControl.STOPPED)
                }
            }
            return ServiceResult.done(id, RemoteControl.STOPPED)
        } catch (ex: Exception) {
            if (cancelled()) return ServiceResult.done(id, RemoteControl.STOPPED)
            if (RemoteDesktop.opensWatch(started, userStopped = false)) {
                return ServiceResult.fail(id, RemoteDesktop.TRY_WATCH, "The computer closed the request. Opening the watch connection.")
            }
            return ServiceResult.fail(id, ErrorCodes.IO, RemoteDesktop.explain(ErrorCodes.IO, ex.message ?: RemoteDesktop.LINK))
        } finally {
            if (started) runCatching { writer.join(500) }
            runCatching { promoted?.close() }
        }
    }

    private fun handleService(
        output: OutputStream,
        input: InputStream,
        frame: Frame,
        onService: ((ServiceRequest) -> ServiceResult)?,
        onRemoteInput: ((RemoteInput) -> Boolean)?,
        nextRemoteFrame: (() -> ByteArray?)?,
        remoteCancelled: (() -> Boolean)?
    ): TransferOutcome {
        val result = try {
            val request = ServiceCodec.parseRequest(frame.payload)
            if (request.service == CompanionServices.REMOTE_CONTROL && request.op == RemoteControl.OP_DRIVE) {
                return runRemoteDrive(output, input, request, onService, onRemoteInput, remoteCancelled)
            }
            if (request.service == CompanionServices.REMOTE_CONTROL && request.op == RemoteControl.OP_WATCH) {
                return runRemoteWatch(output, input, request, onService, onRemoteInput, nextRemoteFrame, remoteCancelled)
            }
            onService?.invoke(request) ?: ServiceResult.fail(request.id, ErrorCodes.NOT_IMPLEMENTED, "This device does not accept companion calls.")
        } catch (ex: ProtocolException) {
            ServiceResult.fail("", ex.code, ex.message ?: "Companion call failed.")
        } catch (_: Exception) {
            ServiceResult.fail("", ErrorCodes.BAD_REQUEST, "Companion call failed.")
        }
        FrameCodec.writeJson(output, MessageType.SERVICE_RESULT, result.toJson())
        val state = if (result.ok) TransferState.SUCCEEDED else TransferState.FAILED
        return TransferOutcome(state, result.code, result.message, null, null, 0)
    }

    private fun runRemoteDrive(
        output: OutputStream,
        input: InputStream,
        request: ServiceRequest,
        onService: ((ServiceRequest) -> ServiceResult)?,
        onRemoteInput: ((RemoteInput) -> Boolean)?,
        remoteCancelled: (() -> Boolean)?
    ): TransferOutcome {
        val ready = onService?.invoke(request)
            ?: ServiceResult.fail(request.id, ErrorCodes.NOT_IMPLEMENTED, "This device does not accept companion calls.")
        FrameCodec.writeJson(output, MessageType.SERVICE_RESULT, ready.toJson())
        if (!ready.ok) {
            return TransferOutcome(TransferState.FAILED, ready.code, ready.message, null, null, 0)
        }
        try {
            while (remoteCancelled?.invoke() != true) {
                val next = FrameCodec.read(input)
                if (next.type == MessageType.REMOTE_INPUT) {
                    val event = RemoteInputCodec.parse(next.payload) ?: continue
                    onRemoteInput?.invoke(event)
                    continue
                }
                if (next.type == MessageType.SERVICE) {
                    val inner = ServiceCodec.parseRequest(next.payload)
                    val result = onService?.invoke(inner)
                        ?: ServiceResult.fail(inner.id, ErrorCodes.NOT_IMPLEMENTED, "This device does not accept companion calls.")
                    FrameCodec.writeJson(output, MessageType.SERVICE_RESULT, result.toJson())
                    return TransferOutcome(
                        if (result.ok) TransferState.SUCCEEDED else TransferState.FAILED,
                        result.code, result.message, null, null, 0
                    )
                }
                if (next.type == MessageType.CANCEL || next.type == MessageType.ERROR) {
                    return TransferOutcome(TransferState.SUCCEEDED, "ok", RemoteControl.STOPPED, null, null, 0)
                }
            }
            return TransferOutcome(TransferState.SUCCEEDED, "ok", RemoteControl.STOPPED, null, null, 0)
        } catch (_: Exception) {
            return TransferOutcome(TransferState.SUCCEEDED, "ok", RemoteControl.STOPPED, null, null, 0)
        }
    }

    private fun runRemoteWatch(
        output: OutputStream,
        input: InputStream,
        request: ServiceRequest,
        onService: ((ServiceRequest) -> ServiceResult)?,
        onRemoteInput: ((RemoteInput) -> Boolean)?,
        nextRemoteFrame: (() -> ByteArray?)?,
        remoteCancelled: (() -> Boolean)?
    ): TransferOutcome {
        val ready = onService?.invoke(request)
            ?: ServiceResult.fail(request.id, ErrorCodes.NOT_IMPLEMENTED, "This device does not accept companion calls.")
        FrameCodec.writeJson(output, MessageType.SERVICE_RESULT, ready.toJson())
        if (!ready.ok) {
            return TransferOutcome(TransferState.FAILED, ready.code, ready.message, null, null, 0)
        }
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val writer = Thread {
            try {
                while (!stop.get() && remoteCancelled?.invoke() != true) {
                    val jpeg = nextRemoteFrame?.invoke()
                    if (ScreenShare.acceptJpeg(jpeg)) {
                        FrameCodec.write(output, MessageType.SCREEN_FRAME, jpeg!!)
                    }
                    Thread.sleep(RemoteControl.INTERVAL_MS)
                }
            } catch (_: Exception) {
                // Reader ends the session.
            }
        }
        writer.isDaemon = true
        writer.start()
        try {
            while (remoteCancelled?.invoke() != true) {
                val next = FrameCodec.read(input)
                if (next.type == MessageType.REMOTE_INPUT) {
                    val event = RemoteInputCodec.parse(next.payload) ?: continue
                    onRemoteInput?.invoke(event)
                    continue
                }
                if (next.type == MessageType.SERVICE) {
                    val inner = ServiceCodec.parseRequest(next.payload)
                    val result = onService?.invoke(inner)
                        ?: ServiceResult.fail(inner.id, ErrorCodes.NOT_IMPLEMENTED, "This device does not accept companion calls.")
                    FrameCodec.writeJson(output, MessageType.SERVICE_RESULT, result.toJson())
                    return TransferOutcome(
                        if (result.ok) TransferState.SUCCEEDED else TransferState.FAILED,
                        result.code, result.message, null, null, 0
                    )
                }
                if (next.type == MessageType.CANCEL || next.type == MessageType.ERROR) {
                    return TransferOutcome(TransferState.SUCCEEDED, "ok", RemoteControl.STOPPED, null, null, 0)
                }
            }
            return TransferOutcome(TransferState.SUCCEEDED, "ok", RemoteControl.STOPPED, null, null, 0)
        } catch (_: Exception) {
            return TransferOutcome(TransferState.SUCCEEDED, "ok", RemoteControl.STOPPED, null, null, 0)
        } finally {
            stop.set(true)
            runCatching { writer.join(500) }
        }
    }

    fun handshake(
        output: OutputStream,
        input: InputStream,
        local: DeviceMaterial,
        expectedFingerprint: String?,
        expectedDeviceId: String?,
        certificateFingerprint: String,
        allowNew: Boolean,
        confirmPeer: ((String, String, String) -> Boolean)?,
        announcement: RelayAnnouncement? = null,
        direct: JSONObject? = null
    ): JSONObject {
        val hello = JSONObject()
            .put("v", 1)
            .put("deviceId", local.deviceId)
            .put("name", trimDeviceName(local.name))
            .put("fingerprint", local.fingerprint)
            .put("resume", true)
        direct?.let { hello.put("direct", it) }
        announcement?.plan?.takeIf { it.isNotBlank() }?.let { hello.put("plan", it) }
        announcement?.quotaKey?.takeIf { it.isNotBlank() }?.let { hello.put("quotaKey", it) }
        announcement?.relayUsed?.let { hello.put("relayUsed", it) }
        announcement?.relayLimit?.let { hello.put("relayLimit", it) }
        FrameCodec.writeJson(output, MessageType.HELLO, hello)
        val remoteFrame = FrameCodec.read(input)
        if (remoteFrame.type == MessageType.ERROR) throw errorOf(remoteFrame.payload)
        if (remoteFrame.type != MessageType.HELLO) throw ProtocolException("Expected a hello message.")
        val remote = JSONObject(remoteFrame.payload.toString(Charsets.UTF_8))
        val remoteFp = remote.getString("fingerprint")
        val remoteId = remote.getString("deviceId")
        if (!pinsEqual(remoteFp, certificateFingerprint)) {
            val message = "Certificate does not match the hello message."
            FrameCodec.writeJson(output, MessageType.ERROR, JSONObject().put("code", ErrorCodes.BAD_REQUEST).put("message", message))
            throw ProtocolException(message, ErrorCodes.BAD_REQUEST)
        }
        val sameKey = expectedFingerprint != null && pinsEqual(certificateFingerprint, expectedFingerprint)
        // A blank TLS pin ("none") is a closed socket, not a new key.
        if (TrustNewKey.shouldRaiseKeyChanged(certificateFingerprint, expectedFingerprint)) {
            val message = "The paired device key changed. Unpair both sides and pair again."
            FrameCodec.writeJson(output, MessageType.ERROR, JSONObject().put("code", ErrorCodes.KEY_CHANGED).put("message", message))
            throw ProtocolException(message, ErrorCodes.KEY_CHANGED)
        }
        if (!sameKey && expectedFingerprint != null && expectedDeviceId != null && isFingerprint(certificateFingerprint) && !remoteId.equals(expectedDeviceId, true)) {
            val message = "The paired device key changed. Unpair both sides and pair again."
            FrameCodec.writeJson(output, MessageType.ERROR, JSONObject().put("code", ErrorCodes.KEY_CHANGED).put("message", message))
            throw ProtocolException(message, ErrorCodes.KEY_CHANGED)
        }
        if (expectedFingerprint == null) {
            val accepted = allowNew && confirmPeer != null && confirmPeer(remote.optString("name"), remoteId, remoteFp)
            if (!accepted) {
                val declined = allowNew && confirmPeer != null
                val code = if (declined) ErrorCodes.DECLINED else ErrorCodes.UNKNOWN_PEER
                val message = if (declined) "Pairing was declined." else "This device is not paired. Pair again from the QR code."
                FrameCodec.writeJson(output, MessageType.ERROR, JSONObject().put("code", code).put("message", message))
                throw ProtocolException(message, code)
            }
        }
        FrameCodec.writeJson(output, MessageType.HELLO_ACK, JSONObject().put("deviceId", local.deviceId).put("fingerprint", local.fingerprint))
        val ack = FrameCodec.read(input)
        if (ack.type == MessageType.ERROR) throw errorOf(ack.payload)
        if (ack.type != MessageType.HELLO_ACK) throw ProtocolException("Expected a hello acknowledgement.")
        val ackJson = JSONObject(ack.payload.toString(Charsets.UTF_8))
        if (!ackJson.optString("deviceId").equals(remoteId, true) || !pinsEqual(ackJson.optString("fingerprint"), remoteFp)) {
            throw ProtocolException("Hello acknowledgement did not match.")
        }
        return remote
    }

    private fun checkpoint(
        sink: FileOutputStream?,
        partial: File?,
        sha: String,
        got: Long,
        transferId: String,
        size: Long
    ) {
        sink?.flush()
        if (partial != null && got in 1 until size) {
            ResumeFiles.preserve(partial, sha, got, transferId, size)
        }
    }

    private fun readAck(input: InputStream, transferId: String, lastAck: Long, sent: Long): Long {
        val frame = FrameCodec.read(input)
        if (frame.type == MessageType.ERROR) throw errorOf(frame.payload)
        if (frame.type != MessageType.ACK) throw ProtocolException("Expected an ack.")
        val json = JSONObject(frame.payload.toString(Charsets.UTF_8))
        val offset = json.optLong("offset")
        if (json.optString("transferId") != transferId || offset < lastAck || offset > sent) {
            throw ProtocolException("Ack was out of order.")
        }
        return offset
    }

    private fun errorOf(payload: ByteArray): ProtocolException {
        val json = JSONObject(payload.toString(Charsets.UTF_8))
        return ProtocolException(json.optString("message", "Transfer failed."), json.optString("code", ErrorCodes.BAD_REQUEST))
    }
}
