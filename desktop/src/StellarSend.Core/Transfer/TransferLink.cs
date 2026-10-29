using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using StellarSend.Core.Companion;
using StellarSend.Core.Terminal;
using StellarSend.Core.Pairing;
using StellarSend.Core.Protocol;
using StellarSend.Core.Security;

namespace StellarSend.Core.Transfer;

public static class TransferLink
{
    private static readonly TimeSpan HandshakeTimeout = TimeSpan.FromSeconds(15);
    private static readonly TimeSpan AcceptTimeout = TimeSpan.FromMinutes(3);
    private static readonly TimeSpan ChunkTimeout = TimeSpan.FromSeconds(60);

    public static async Task<HelloBody> HandshakeAsync(
        Stream stream,
        DeviceIdentity local,
        string? expectedFingerprint,
        string? expectedDeviceId,
        string certificateFingerprint,
        bool allowNewPeer,
        Func<HelloBody, CancellationToken, Task<bool>>? confirmPeer,
        CancellationToken ct,
        Action<HelloBody>? onHello = null,
        DirectOffer? direct = null)
    {
        var hello = new HelloBody(
            ProtocolLimits.Version,
            local.DeviceId.ToString("D"),
            Validators.TrimName(local.Name),
            local.Fingerprint,
            Direct: direct,
            Resume: true);
        await FrameCodec.WriteJsonAsync(stream, MessageType.Hello, hello, ct).ConfigureAwait(false);
        var remoteFrame = await FrameCodec.ReadAsync(stream, ct, HandshakeTimeout).ConfigureAwait(false);
        if (remoteFrame.Type == MessageType.Error)
        {
            throw ErrorOf(remoteFrame, expectedFingerprint, expectedDeviceId);
        }

        if (remoteFrame.Type != MessageType.Hello)
        {
            throw new ProtocolException("Expected a hello message.");
        }

        var remote = ProtocolJson.Read<HelloBody>(remoteFrame.Payload);
        Validators.ValidateHello(remote);
        if (!Pin.FixedEquals(remote.Fingerprint, certificateFingerprint))
        {
            await WriteErrorAsync(stream, null, ErrorCodes.BadRequest, "Certificate does not match the hello message.", ct).ConfigureAwait(false);
            throw new ProtocolException("Certificate does not match the hello message.");
        }

        var known = expectedFingerprint is not null;
        var sameKey = known && Pin.FixedEquals(certificateFingerprint, expectedFingerprint);
        // A blank TLS pin ("none") is a closed socket, not a new key.
        if (TrustNewKey.ShouldRaiseKeyChanged(certificateFingerprint, expectedFingerprint))
        {
            KeyChangedLog.Write(KeyChangedLog.LocalCheck, remote.Fingerprint, expectedFingerprint, remote.DeviceId);
            await WriteErrorAsync(stream, null, ErrorCodes.KeyChanged, PeerRejectedException.KeyChanged().Message, ct).ConfigureAwait(false);
            throw PeerRejectedException.KeyChanged();
        }

        if (!sameKey && known && PinFormat.IsWellFormed(certificateFingerprint) && expectedDeviceId is not null && !string.Equals(remote.DeviceId, expectedDeviceId, StringComparison.OrdinalIgnoreCase))
        {
            KeyChangedLog.Write(KeyChangedLog.LocalCheck, remote.Fingerprint, expectedFingerprint, remote.DeviceId);
            await WriteErrorAsync(stream, null, ErrorCodes.KeyChanged, PeerRejectedException.KeyChanged().Message, ct).ConfigureAwait(false);
            throw PeerRejectedException.KeyChanged();
        }

        if (!known)
        {
            if (!allowNewPeer || confirmPeer is null)
            {
                await WriteErrorAsync(stream, null, ErrorCodes.UnknownPeer, PeerRejectedException.Unknown().Message, ct).ConfigureAwait(false);
                throw PeerRejectedException.Unknown();
            }

            var accepted = await confirmPeer(remote, ct).ConfigureAwait(false);
            if (!accepted)
            {
                await WriteErrorAsync(stream, null, ErrorCodes.Declined, "Pairing was declined.", ct).ConfigureAwait(false);
                throw new PeerRejectedException(ErrorCodes.Declined, "Pairing was declined.");
            }
        }

        var ack = new HelloAckBody(local.DeviceId.ToString("D"), local.Fingerprint);
        await FrameCodec.WriteJsonAsync(stream, MessageType.HelloAck, ack, ct).ConfigureAwait(false);
        var ackFrame = await FrameCodec.ReadAsync(stream, ct, HandshakeTimeout).ConfigureAwait(false);
        if (ackFrame.Type == MessageType.Error)
        {
            throw ErrorOf(ackFrame, expectedFingerprint, expectedDeviceId);
        }

        if (ackFrame.Type != MessageType.HelloAck)
        {
            throw new ProtocolException("Expected a hello acknowledgement.");
        }

        var remoteAck = ProtocolJson.Read<HelloAckBody>(ackFrame.Payload);
        if (!string.Equals(remoteAck.DeviceId, remote.DeviceId, StringComparison.OrdinalIgnoreCase)
            || !Pin.FixedEquals(remoteAck.Fingerprint, remote.Fingerprint))
        {
            throw new ProtocolException("Hello acknowledgement did not match.");
        }

        onHello?.Invoke(remote);
        return remote;
    }

    public static async Task<TransferResult> SendAsync(
        Stream stream,
        DeviceIdentity local,
        PeerRecord peer,
        string certificateFingerprint,
        OutgoingPayload payload,
        IProgress<TransferProgress>? progress,
        CancellationToken ct,
        Action<HelloBody>? onHello = null,
        Guid? transferId = null,
        DirectAttempt? punch = null,
        Action? onDirect = null,
        Action? onMissed = null)
    {
        var id = transferId ?? Guid.NewGuid();
        Stream io = stream;
        TlsConnection? promoted = null;
        try
        {
            var remoteHello = await HandshakeAsync(
                io,
                local,
                peer.Fingerprint,
                peer.DeviceId.ToString("D"),
                certificateFingerprint,
                allowNewPeer: false,
                confirmPeer: null,
                ct,
                onHello,
                punch?.Offer).ConfigureAwait(false);
            var wantAcks = remoteHello.Resume;
            if (punch is not null && remoteHello.Direct is not null)
            {
                promoted = await punch.FinishAsync(local, remoteHello, ct).ConfigureAwait(false);
                if (promoted is not null)
                {
                    io = promoted.Stream;
                    await stream.DisposeAsync().ConfigureAwait(false);
                    onDirect?.Invoke();
                }
                else
                {
                    onMissed?.Invoke();
                }
            }

            int? batchIndex = payload.BatchCount > 1 && payload.BatchIndex > 0 ? payload.BatchIndex : null;
            int? batchCount = payload.BatchCount > 1 ? payload.BatchCount : null;
            var offer = new OfferBody(id.ToString("D"), payload.Kind, payload.Name, payload.Size, payload.Sha256, payload.Mime, batchIndex, batchCount);
            Validators.ValidateOffer(offer);
            Report(progress, id, payload.Name, TransferState.AwaitingConsent, 0, payload.Size, null, null, null);
            await FrameCodec.WriteJsonAsync(io, MessageType.Offer, offer, ct).ConfigureAwait(false);
            var reply = await FrameCodec.ReadAsync(io, ct, AcceptTimeout).ConfigureAwait(false);
            if (reply.Type == MessageType.Reject)
            {
                var reject = ProtocolJson.Read<RejectBody>(reply.Payload);
                return Finish(progress, id, payload.Name, payload.Size, TransferState.Failed, ErrorCodes.Declined, reject.Reason, 0);
            }

            if (reply.Type == MessageType.Error)
            {
                var error = ProtocolJson.Read<ErrorBody>(reply.Payload);
                return Finish(progress, id, payload.Name, payload.Size, TransferState.Failed, error.Code, error.Message, 0);
            }

            if (reply.Type != MessageType.Accept)
            {
                return Finish(progress, id, payload.Name, payload.Size, TransferState.Failed, ErrorCodes.BadRequest, "The other device did not accept the transfer.", 0);
            }

            long sent = 0;
            if (reply.Payload.Length > 0)
            {
                using var acceptDoc = JsonDocument.Parse(reply.Payload);
                if (acceptDoc.RootElement.TryGetProperty("offset", out var offsetElement)
                    && offsetElement.TryGetInt64(out var resumeOffset)
                    && resumeOffset > 0)
                {
                    if (resumeOffset > payload.Size || !payload.Content.CanSeek)
                    {
                        return Finish(progress, id, payload.Name, payload.Size, TransferState.Failed, ErrorCodes.Io, "Could not resume the transfer.", 0);
                    }

                    payload.Content.Seek(resumeOffset, SeekOrigin.Begin);
                    sent = resumeOffset;
                }
            }

            var lastAck = sent;
            var clock = Stopwatch.StartNew();
            var chunks = 0;
            var buffer = new byte[ProtocolLimits.MaxChunkData];
            while (sent < payload.Size)
            {
                ct.ThrowIfCancellationRequested();
                var remaining = (int)Math.Min(buffer.Length, payload.Size - sent);
                var read = 0;
                while (read < remaining)
                {
                    var n = await payload.Content.ReadAsync(buffer.AsMemory(read, remaining - read), ct).ConfigureAwait(false);
                    if (n == 0)
                    {
                        break;
                    }

                    read += n;
                }

                if (read == 0)
                {
                    return Finish(progress, id, payload.Name, payload.Size, TransferState.Failed, ErrorCodes.Io, "The file ended before the promised size.", chunks);
                }

                var chunk = ChunkCodec.Encode(id, (ulong)sent, buffer.AsSpan(0, read));
                await FrameCodec.WriteAsync(io, MessageType.Chunk, chunk, ct).ConfigureAwait(false);
                sent += read;
                chunks++;
                if (wantAcks && sent - lastAck >= ResumeAck.IntervalBytes)
                {
                    lastAck = await ReadAckAsync(io, id, lastAck, sent, ct).ConfigureAwait(false);
                }

                var seconds = clock.Elapsed.TotalSeconds;
                double? rate = seconds > 0.2 ? sent / seconds : null;
                double? eta = rate is > 0 ? (payload.Size - sent) / rate.Value : null;
                Report(progress, id, payload.Name, TransferState.InProgress, sent, payload.Size, rate, eta, null);
            }

            await FrameCodec.WriteJsonAsync(io, MessageType.Complete, new CompleteBody(id.ToString("D"), payload.Sha256), ct).ConfigureAwait(false);
            var done = await FrameCodec.ReadAsync(io, ct, ChunkTimeout).ConfigureAwait(false);
            if (done.Type == MessageType.Error)
            {
                var error = ProtocolJson.Read<ErrorBody>(done.Payload);
                var state = error.Code == ErrorCodes.Cancelled ? TransferState.Cancelled : TransferState.Failed;
                return Finish(progress, id, payload.Name, payload.Size, state, error.Code, error.Message, chunks);
            }

            if (done.Type != MessageType.Complete)
            {
                return Finish(progress, id, payload.Name, payload.Size, TransferState.Failed, ErrorCodes.BadRequest, "The other device did not confirm the transfer.", chunks);
            }

            var complete = ProtocolJson.Read<CompleteBody>(done.Payload);
            if (!Pin.FixedEquals(complete.Sha256, payload.Sha256))
            {
                return Finish(progress, id, payload.Name, payload.Size, TransferState.Failed, ErrorCodes.HashMismatch, "Integrity check failed.", chunks);
            }

            return Finish(progress, id, payload.Name, payload.Size, TransferState.Succeeded, null, null, chunks);
        }
        catch (RelayQuotaException ex)
        {
            return Finish(progress, id, payload.Name, payload.Size, TransferState.Failed, RelayPlans.Quota, ex.Message, 0);
        }
        catch (OperationCanceledException)
        {
            await TryCancelAsync(io, id).ConfigureAwait(false);
            return Finish(progress, id, payload.Name, payload.Size, TransferState.Cancelled, ErrorCodes.Cancelled, "Cancelled", 0);
        }
        finally
        {
            if (promoted is not null)
            {
                await promoted.DisposeAsync().ConfigureAwait(false);
            }
        }
    }

    public static async Task<TransferResult> ReceiveOneAsync(
        Stream stream,
        DeviceIdentity local,
        PeerRecord? peer,
        string certificateFingerprint,
        bool allowNewPeer,
        Func<HelloBody, CancellationToken, Task<bool>>? confirmPeer,
        Func<OfferBody, CancellationToken, Task<bool>> confirmOffer,
        Func<OfferBody, IReceiveSink> openFile,
        Func<string, Task> onText,
        IProgress<TransferProgress>? progress,
        CancellationToken ct,
        Func<ServiceRequest, CancellationToken, Task<ServiceResult>>? onService = null,
        Action<HelloBody>? onHello = null,
        Func<ServiceRequest, Func<TerminalChunk, CancellationToken, Task>, CancellationToken, Task<ServiceResult>>? onTerminal = null,
        Func<CancellationToken, Task>? onPaired = null,
        Action? onPhoneScreenStarted = null,
        Action<byte[]>? onPhoneScreenFrame = null,
        Action? onPhoneScreenStopped = null,
        Func<RemoteInput, bool>? onRemoteInput = null,
        Func<byte[]?>? nextRemoteFrame = null,
        DirectAttempt? punch = null,
        Action? onDirect = null,
        Action? onMissed = null,
        Action<string>? note = null)
    {
        Stream io = stream;
        TlsConnection? promoted = null;
        try
        {
        var remoteHello = await HandshakeAsync(
            io,
            local,
            peer?.Fingerprint,
            peer?.DeviceId.ToString("D"),
            certificateFingerprint,
            allowNewPeer,
            confirmPeer,
            ct,
            onHello,
            punch?.Offer).ConfigureAwait(false);
        var wantAcks = remoteHello.Resume;
        if (punch is not null && remoteHello.Direct is not null)
        {
            promoted = await punch.FinishAsync(local, remoteHello, ct).ConfigureAwait(false);
            if (promoted is not null)
            {
                io = promoted.Stream;
                await stream.DisposeAsync().ConfigureAwait(false);
                onDirect?.Invoke();
            }
            else
            {
                onMissed?.Invoke();
            }
        }
        if (onPaired is not null)
        {
            await onPaired(ct).ConfigureAwait(false);
        }

        Frame frame;
        try
        {
            frame = await FrameCodec.ReadAsync(io, ct, HandshakeTimeout).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is EndOfStreamException or IOException)
        {
            return new TransferResult(TransferState.Succeeded, null, "Paired", null, null, 0, true);
        }

        if (frame.Type == MessageType.Error)
        {
            var error = ProtocolJson.Read<ErrorBody>(frame.Payload);
            return new TransferResult(TransferState.Failed, error.Code, error.Message, null, null, 0, false);
        }

        if (frame.Type == MessageType.Service)
        {
            return await HandleServiceAsync(
                io,
                frame,
                onService,
                onTerminal,
                ct,
                onPhoneScreenStarted,
                onPhoneScreenFrame,
                onPhoneScreenStopped,
                onRemoteInput,
                nextRemoteFrame,
                note).ConfigureAwait(false);
        }

        if (frame.Type != MessageType.Offer)
        {
            throw new ProtocolException("Expected an offer.");
        }

        var offer = ProtocolJson.Read<OfferBody>(frame.Payload);
        var transferId = Guid.TryParse(offer.TransferId, out var parsed) ? parsed : Guid.Empty;
        try
        {
            Validators.ValidateOffer(offer);
        }
        catch (ProtocolException ex)
        {
            await FrameCodec.WriteJsonAsync(io, MessageType.Reject, new RejectBody(offer.TransferId, ex.Message), ct).ConfigureAwait(false);
            return new TransferResult(TransferState.Failed, ex.Code, ex.Message, null, null, 0, false);
        }

        Report(progress, transferId, offer.Name, TransferState.AwaitingConsent, 0, offer.Size, null, null, null);
        var accepted = await confirmOffer(offer, ct).ConfigureAwait(false);
        if (!accepted)
        {
            await FrameCodec.WriteJsonAsync(io, MessageType.Reject, new RejectBody(offer.TransferId, "Declined"), ct).ConfigureAwait(false);
            return Finish(progress, transferId, offer.Name, offer.Size, TransferState.Cancelled, ErrorCodes.Declined, "Declined", 0);
        }

        await using var sink = offer.Kind == Kinds.File ? openFile(offer) : new TextReceiveSink();
        using var hasher = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        if (sink.StartOffset > 0)
        {
            await sink.HashExistingAsync(hasher, ct).ConfigureAwait(false);
            await FrameCodec.WriteJsonAsync(io, MessageType.Accept, new AcceptBody(offer.TransferId, sink.StartOffset), ct).ConfigureAwait(false);
        }
        else
        {
            await FrameCodec.WriteJsonAsync(io, MessageType.Accept, new TransferIdBody(offer.TransferId), ct).ConfigureAwait(false);
        }

        var clock = Stopwatch.StartNew();
        long got = sink.StartOffset;
        var lastAck = sink.StartOffset;
        var chunks = 0;
        try
        {
            while (got < offer.Size)
            {
                var incoming = await FrameCodec.ReadAsync(io, ct, ChunkTimeout).ConfigureAwait(false);
                if (incoming.Type == MessageType.Cancel)
                {
                    await sink.DiscardAsync().ConfigureAwait(false);
                    return Finish(progress, transferId, offer.Name, offer.Size, TransferState.Cancelled, ErrorCodes.Cancelled, "Cancelled", chunks);
                }

                if (incoming.Type == MessageType.Error)
                {
                    await sink.DiscardAsync().ConfigureAwait(false);
                    var error = ProtocolJson.Read<ErrorBody>(incoming.Payload);
                    return Finish(progress, transferId, offer.Name, offer.Size, TransferState.Failed, error.Code, error.Message, chunks);
                }

                if (incoming.Type != MessageType.Chunk)
                {
                    throw new ProtocolException("Expected a chunk.");
                }

                var chunk = ChunkCodec.Decode(incoming.Payload);
                if (chunk.TransferId != transferId || chunk.Offset != (ulong)got)
                {
                    throw new ProtocolException("Chunk was out of order.");
                }

                if (got + chunk.Data.Length > offer.Size)
                {
                    throw new ProtocolException("Chunk exceeds the offered size.", ErrorCodes.Limit);
                }

                hasher.AppendData(chunk.Data);
                await sink.WriteAsync(chunk.Data, ct).ConfigureAwait(false);
                got += chunk.Data.Length;
                chunks++;
                if (got - lastAck >= ResumeAck.IntervalBytes)
                {
                    await sink.CheckpointAsync(ct).ConfigureAwait(false);
                    if (wantAcks)
                    {
                        await FrameCodec.WriteJsonAsync(io, MessageType.Ack, new AckBody(offer.TransferId, got), ct).ConfigureAwait(false);
                    }

                    lastAck = got;
                }

                var seconds = clock.Elapsed.TotalSeconds;
                double? rate = seconds > 0.2 ? got / seconds : null;
                double? eta = rate is > 0 ? (offer.Size - got) / rate.Value : null;
                Report(progress, transferId, offer.Name, TransferState.InProgress, got, offer.Size, rate, eta, null);
            }

            var end = await FrameCodec.ReadAsync(io, ct, ChunkTimeout).ConfigureAwait(false);
            if (end.Type == MessageType.Cancel)
            {
                await sink.DiscardAsync().ConfigureAwait(false);
                return Finish(progress, transferId, offer.Name, offer.Size, TransferState.Cancelled, ErrorCodes.Cancelled, "Cancelled", chunks);
            }

            if (end.Type != MessageType.Complete)
            {
                throw new ProtocolException("Expected a completed transfer.");
            }

            var complete = ProtocolJson.Read<CompleteBody>(end.Payload);
            var actual = Convert.ToHexString(hasher.GetHashAndReset()).ToLowerInvariant();
            if (!Pin.FixedEquals(actual, offer.Sha256) || !Pin.FixedEquals(actual, complete.Sha256))
            {
                await sink.DiscardAsync().ConfigureAwait(false);
                await WriteErrorAsync(io, offer.TransferId, ErrorCodes.HashMismatch, "Integrity check failed.", ct).ConfigureAwait(false);
                return Finish(progress, transferId, offer.Name, offer.Size, TransferState.Failed, ErrorCodes.HashMismatch, "Integrity check failed.", chunks);
            }

            var committed = await sink.CommitAsync(ct).ConfigureAwait(false);
            await FrameCodec.WriteJsonAsync(io, MessageType.Complete, new CompleteBody(offer.TransferId, actual), ct).ConfigureAwait(false);
            if (offer.Kind == Kinds.Text && committed is not null)
            {
                await onText(committed).ConfigureAwait(false);
                Report(progress, transferId, offer.Name, TransferState.Succeeded, offer.Size, offer.Size, null, null, null);
                return new TransferResult(TransferState.Succeeded, null, null, null, committed, chunks, false);
            }

            return Finish(progress, transferId, offer.Name, offer.Size, TransferState.Succeeded, null, committed, chunks) with { SavedPath = committed };
        }
        catch (OperationCanceledException)
        {
            await sink.DiscardAsync().ConfigureAwait(false);
            await TryCancelAsync(io, transferId).ConfigureAwait(false);
            return Finish(progress, transferId, offer.Name, offer.Size, TransferState.Cancelled, ErrorCodes.Cancelled, "Cancelled", chunks);
        }
        catch (ProtocolException ex) when (ex.Code == ErrorCodes.HashMismatch)
        {
            await sink.DiscardAsync().ConfigureAwait(false);
            throw;
        }
        catch
        {
            await sink.PreserveAsync().ConfigureAwait(false);
            throw;
        }
        }
        finally
        {
            if (promoted is not null)
            {
                await promoted.DisposeAsync().ConfigureAwait(false);
            }
        }
    }

    private static async Task<long> ReadAckAsync(Stream stream, Guid transferId, long lastAck, long sent, CancellationToken ct)
    {
        var frame = await FrameCodec.ReadAsync(stream, ct, ChunkTimeout).ConfigureAwait(false);
        if (frame.Type == MessageType.Error)
        {
            throw ErrorOf(frame);
        }

        if (frame.Type != MessageType.Ack)
        {
            throw new ProtocolException("Expected an ack.");
        }

        var ack = ProtocolJson.Read<AckBody>(frame.Payload);
        if (!Guid.TryParse(ack.TransferId, out var id)
            || id != transferId
            || ack.Offset < lastAck
            || ack.Offset > sent)
        {
            throw new ProtocolException("Ack was out of order.");
        }

        return ack.Offset;
    }

    private static async Task TryCancelAsync(Stream stream, Guid transferId)
    {
        try
        {
            await FrameCodec.WriteJsonAsync(stream, MessageType.Cancel, new TransferIdBody(transferId.ToString("D")), CancellationToken.None).ConfigureAwait(false);
        }
        catch
        {
            // The peer may already be gone.
        }
    }

    private static async Task WriteErrorAsync(Stream stream, string? transferId, string code, string message, CancellationToken ct)
    {
        await FrameCodec.WriteJsonAsync(stream, MessageType.Error, new ErrorBody(transferId, code, message), ct).ConfigureAwait(false);
    }

    private static Exception ErrorOf(Frame frame, string? pinnedFingerprint = null, string? deviceId = null)
    {
        var error = ProtocolJson.Read<ErrorBody>(frame.Payload);
        if (error.Code is ErrorCodes.KeyChanged or ErrorCodes.UnknownPeer or ErrorCodes.Declined)
        {
            if (error.Code == ErrorCodes.KeyChanged)
            {
                KeyChangedLog.Write(KeyChangedLog.PhoneSent, presented: null, pinnedFingerprint, deviceId);
            }

            return new PeerRejectedException(error.Code, error.Message);
        }

        return new ProtocolException(error.Message, error.Code);
    }

    private static void Report(
        IProgress<TransferProgress>? progress,
        Guid id,
        string name,
        TransferState state,
        long done,
        long total,
        double? rate,
        double? eta,
        string? detail)
    {
        progress?.Report(new TransferProgress(id, name, state, done, total, rate, eta, detail));
    }

    private static TransferResult Finish(
        IProgress<TransferProgress>? progress,
        Guid id,
        string name,
        long total,
        TransferState state,
        string? code,
        string? message,
        int chunks)
    {
        Report(progress, id, name, state, state == TransferState.Succeeded ? total : 0, total, null, null, message);
        return new TransferResult(state, code, message, null, null, chunks, false);
    }

    public static async Task<ServiceResult> CallStreamingAsync(
        Stream stream,
        DeviceIdentity local,
        PeerRecord peer,
        string certificateFingerprint,
        string id,
        string service,
        string op,
        JsonElement body,
        Func<TerminalChunk, CancellationToken, Task> onOutput,
        CancellationToken ct)
    {
        await HandshakeAsync(
            stream,
            local,
            peer.Fingerprint,
            peer.DeviceId.ToString("D"),
            certificateFingerprint,
            allowNewPeer: false,
            confirmPeer: null,
            ct).ConfigureAwait(false);

        using var document = JsonDocument.Parse($"{{\"id\":{JsonSerializer.Serialize(id)},\"service\":{JsonSerializer.Serialize(service)},\"op\":{JsonSerializer.Serialize(op)},\"body\":{body.GetRawText()}}}");
        await FrameCodec.WriteAsync(stream, MessageType.Service, Encoding.UTF8.GetBytes(document.RootElement.GetRawText()), ct).ConfigureAwait(false);
        while (true)
        {
            var frame = await FrameCodec.ReadAsync(stream, ct).ConfigureAwait(false);
            if (frame.Type == MessageType.TerminalData)
            {
                var chunk = ProtocolJson.Read<TerminalChunk>(frame.Payload);
                if (string.Equals(chunk.Id, id, StringComparison.Ordinal))
                {
                    await onOutput(chunk, ct).ConfigureAwait(false);
                }

                continue;
            }

            if (frame.Type == MessageType.ServiceResult)
            {
                return ProtocolJson.Read<ServiceResult>(frame.Payload);
            }

            if (frame.Type == MessageType.Error)
            {
                throw ErrorOf(frame);
            }

            throw new ProtocolException("Expected terminal output.");
        }
    }

    private static async Task<TransferResult> HandleServiceAsync(
        Stream stream,
        Frame frame,
        Func<ServiceRequest, CancellationToken, Task<ServiceResult>>? onService,
        Func<ServiceRequest, Func<TerminalChunk, CancellationToken, Task>, CancellationToken, Task<ServiceResult>>? onTerminal,
        CancellationToken ct,
        Action? onPhoneScreenStarted = null,
        Action<byte[]>? onPhoneScreenFrame = null,
        Action? onPhoneScreenStopped = null,
        Func<RemoteInput, bool>? onRemoteInput = null,
        Func<byte[]?>? nextRemoteFrame = null,
        Action<string>? note = null)
    {
        ServiceResult result;
        try
        {
            var request = ServiceCodec.ParseRequest(frame.Payload);
            if (request.Service == CompanionServices.Terminal && onTerminal is not null)
            {
                result = await onTerminal(
                    request,
                    async (chunk, token) => await FrameCodec.WriteJsonAsync(stream, MessageType.TerminalData, chunk, token).ConfigureAwait(false),
                    ct).ConfigureAwait(false);
            }
            else if (request.Service == CompanionServices.ScreenShare && request.Op == "start")
            {
                result = await ReadPhoneScreenAsync(
                    stream,
                    request,
                    ct,
                    onPhoneScreenStarted,
                    onPhoneScreenFrame,
                    onPhoneScreenStopped).ConfigureAwait(false);
            }
            else if (request.Service == CompanionServices.RemoteControl && request.Op == RemoteControl.OpDrive)
            {
                return await RunRemoteDriveAsync(stream, request, onService, onRemoteInput, ct).ConfigureAwait(false);
            }
            else if (request.Service == CompanionServices.RemoteControl && request.Op == RemoteControl.OpRequest)
            {
                return await RunRemoteRequestAsync(stream, request, onService, onRemoteInput, nextRemoteFrame, ct, note).ConfigureAwait(false);
            }
            else if (request.Service == CompanionServices.RemoteControl && request.Op == RemoteControl.OpWatch)
            {
                return await RunRemoteWatchAsync(stream, request, onService, onRemoteInput, nextRemoteFrame, ct, note).ConfigureAwait(false);
            }
            else
            {
                result = onService is null
                    ? ServiceResult.Fail(request.Id, ErrorCodes.NotImplemented, "This device does not accept companion calls.")
                    : await onService(request, ct).ConfigureAwait(false);
            }
        }
        catch (ProtocolException ex)
        {
            result = ServiceResult.Fail("", ex.Code, ex.Message);
        }
        catch (Exception)
        {
            result = ServiceResult.Fail("", ErrorCodes.BadRequest, "Companion call failed.");
        }

        await FrameCodec.WriteJsonAsync(stream, MessageType.ServiceResult, result, ct).ConfigureAwait(false);
        var state = result.Ok ? TransferState.Succeeded : TransferState.Failed;
        return new TransferResult(state, result.Code, result.Message, null, null, 0, false);
    }

    private static async Task<ServiceResult> ReadPhoneScreenAsync(
        Stream stream,
        ServiceRequest request,
        CancellationToken ct,
        Action? onStarted,
        Action<byte[]>? onFrame,
        Action? onStopped)
    {
        onStarted?.Invoke();
        try
        {
            while (!ct.IsCancellationRequested)
            {
                Frame next;
                try
                {
                    next = await FrameCodec.ReadAsync(stream, ct).ConfigureAwait(false);
                }
                catch (Exception ex) when (ex is EndOfStreamException or IOException or OperationCanceledException)
                {
                    return ServiceResult.Done(request.Id, "Screen share stopped.");
                }

                if (next.Type == MessageType.ScreenFrame)
                {
                    if (ScreenShare.TryJpeg(next.Payload, out var jpeg))
                    {
                        onFrame?.Invoke(jpeg);
                    }

                    continue;
                }

                if (next.Type == MessageType.Service)
                {
                    var inner = ServiceCodec.ParseRequest(next.Payload);
                    if (inner.Service == CompanionServices.ScreenShare && inner.Op == "stop")
                    {
                        return ServiceResult.Done(request.Id, "Screen share stopped.");
                    }
                }

                if (next.Type == MessageType.Cancel)
                {
                    return ServiceResult.Done(request.Id, "Screen share stopped.");
                }

                if (next.Type == MessageType.Error)
                {
                    throw ErrorOf(next);
                }

                throw new ProtocolException("Expected a screen frame.");
            }

            return ServiceResult.Done(request.Id, "Screen share stopped.");
        }
        finally
        {
            onStopped?.Invoke();
        }
    }

    public static async Task<ServiceResult> CallAsync(
        Stream stream,
        DeviceIdentity local,
        PeerRecord peer,
        string certificateFingerprint,
        ServiceRequest request,
        CancellationToken ct)
    {
        await HandshakeAsync(
            stream,
            local,
            peer.Fingerprint,
            peer.DeviceId.ToString("D"),
            certificateFingerprint,
            allowNewPeer: false,
            confirmPeer: null,
            ct).ConfigureAwait(false);

        using var document = JsonDocument.Parse(
            $"{{\"id\":{JsonSerializer.Serialize(request.Id)},\"service\":{JsonSerializer.Serialize(request.Service)},\"op\":{JsonSerializer.Serialize(request.Op)},\"body\":{request.Body.GetRawText()}}}");
        await FrameCodec.WriteAsync(stream, MessageType.Service, Encoding.UTF8.GetBytes(document.RootElement.GetRawText()), ct).ConfigureAwait(false);
        var frame = await FrameCodec.ReadAsync(stream, ct).ConfigureAwait(false);
        if (frame.Type == MessageType.Error)
        {
            throw ErrorOf(frame);
        }

        if (frame.Type != MessageType.ServiceResult)
        {
            throw new ProtocolException("Expected a companion result.");
        }

        return ProtocolJson.Read<ServiceResult>(frame.Payload);
    }

    public static async Task<ServiceResult> DriveRemoteAsync(
        Stream stream,
        DeviceIdentity local,
        PeerRecord peer,
        string certificateFingerprint,
        string sessionId,
        Func<RemoteInput?> nextInput,
        CancellationToken ct)
    {
        await HandshakeAsync(
            stream,
            local,
            peer.Fingerprint,
            peer.DeviceId.ToString("D"),
            certificateFingerprint,
            allowNewPeer: false,
            confirmPeer: null,
            ct).ConfigureAwait(false);

        var id = Guid.NewGuid().ToString("D");
        using var startDoc = JsonDocument.Parse($"{{\"session\":{JsonSerializer.Serialize(sessionId)}}}");
        using var envelope = JsonDocument.Parse(
            $"{{\"id\":{JsonSerializer.Serialize(id)},\"service\":{JsonSerializer.Serialize(CompanionServices.RemoteControl)},\"op\":{JsonSerializer.Serialize(RemoteControl.OpDrive)},\"body\":{startDoc.RootElement.GetRawText()}}}");
        await FrameCodec.WriteAsync(stream, MessageType.Service, Encoding.UTF8.GetBytes(envelope.RootElement.GetRawText()), ct).ConfigureAwait(false);
        var armed = await FrameCodec.ReadAsync(stream, ct).ConfigureAwait(false);
        if (armed.Type == MessageType.Error)
        {
            throw ErrorOf(armed);
        }

        if (armed.Type != MessageType.ServiceResult)
        {
            throw new ProtocolException("Expected a companion result.");
        }

        var ready = ProtocolJson.Read<ServiceResult>(armed.Payload);
        if (!ready.Ok)
        {
            return ready;
        }

        while (!ct.IsCancellationRequested)
        {
            var input = nextInput();
            if (input is not null)
            {
                await FrameCodec.WriteAsync(stream, MessageType.RemoteInput, RemoteInputCodec.Encode(input), ct).ConfigureAwait(false);
            }

            try
            {
                await Task.Delay(RemoteControl.MinInputGapMs, ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }

        using var stopDoc = JsonDocument.Parse($"{{\"session\":{JsonSerializer.Serialize(sessionId)}}}");
        using var stopEnvelope = JsonDocument.Parse(
            $"{{\"id\":{JsonSerializer.Serialize(id)},\"service\":{JsonSerializer.Serialize(CompanionServices.RemoteControl)},\"op\":{JsonSerializer.Serialize(RemoteControl.OpStop)},\"body\":{stopDoc.RootElement.GetRawText()}}}");
        try
        {
            await FrameCodec.WriteAsync(stream, MessageType.Service, Encoding.UTF8.GetBytes(stopEnvelope.RootElement.GetRawText()), CancellationToken.None).ConfigureAwait(false);
            var done = await FrameCodec.ReadAsync(stream, CancellationToken.None).ConfigureAwait(false);
            if (done.Type == MessageType.ServiceResult)
            {
                return ProtocolJson.Read<ServiceResult>(done.Payload);
            }
        }
        catch (Exception)
        {
            // The other side already closed.
        }

        return ServiceResult.Done(id, RemoteControl.Stopped);
    }

    public static async Task<ServiceResult> WatchRemoteAsync(
        Stream stream,
        DeviceIdentity local,
        PeerRecord peer,
        string certificateFingerprint,
        string sessionId,
        Func<RemoteInput?> nextInput,
        Action<byte[]> onFrame,
        CancellationToken ct)
    {
        await HandshakeAsync(
            stream,
            local,
            peer.Fingerprint,
            peer.DeviceId.ToString("D"),
            certificateFingerprint,
            allowNewPeer: false,
            confirmPeer: null,
            ct).ConfigureAwait(false);

        var id = Guid.NewGuid().ToString("D");
        using var startDoc = JsonDocument.Parse($"{{\"session\":{JsonSerializer.Serialize(sessionId)}}}");
        using var envelope = JsonDocument.Parse(
            $"{{\"id\":{JsonSerializer.Serialize(id)},\"service\":{JsonSerializer.Serialize(CompanionServices.RemoteControl)},\"op\":{JsonSerializer.Serialize(RemoteControl.OpWatch)},\"body\":{startDoc.RootElement.GetRawText()}}}");
        await FrameCodec.WriteAsync(stream, MessageType.Service, Encoding.UTF8.GetBytes(envelope.RootElement.GetRawText()), ct).ConfigureAwait(false);
        var writeLock = new SemaphoreSlim(1, 1);
        var writer = Task.Run(async () =>
        {
            try
            {
                while (!ct.IsCancellationRequested)
                {
                    var input = nextInput();
                    if (input is not null)
                    {
                        await writeLock.WaitAsync(ct).ConfigureAwait(false);
                        try
                        {
                            await FrameCodec.WriteAsync(stream, MessageType.RemoteInput, RemoteInputCodec.Encode(input), ct).ConfigureAwait(false);
                        }
                        finally
                        {
                            writeLock.Release();
                        }
                    }

                    await Task.Delay(RemoteControl.MinInputGapMs, ct).ConfigureAwait(false);
                }
            }
            catch (Exception)
            {
                // Reader ends the session.
            }
        }, ct);

        try
        {
            var started = false;
            while (!ct.IsCancellationRequested)
            {
                var next = await FrameCodec.ReadAsync(stream, ct).ConfigureAwait(false);
                if (next.Type == MessageType.ServiceResult)
                {
                    var result = ProtocolJson.Read<ServiceResult>(next.Payload);
                    if (!result.Ok)
                    {
                        return result;
                    }

                    if (!started)
                    {
                        started = true;
                        continue;
                    }

                    return result;
                }

                if (next.Type == MessageType.ScreenFrame)
                {
                    if (ScreenShare.TryJpeg(next.Payload, out var jpeg))
                    {
                        onFrame(jpeg);
                    }

                    continue;
                }

                if (next.Type == MessageType.Error)
                {
                    throw ErrorOf(next);
                }

                if (next.Type == MessageType.Service)
                {
                    var inner = ServiceCodec.ParseRequest(next.Payload);
                    if (inner.Op == RemoteControl.OpStop)
                    {
                        return ServiceResult.Done(id, RemoteControl.Stopped);
                    }
                }
            }

            return ServiceResult.Done(id, RemoteControl.Stopped);
        }
        catch (Exception) when (ct.IsCancellationRequested)
        {
            return ServiceResult.Done(id, RemoteControl.Stopped);
        }
        finally
        {
            try
            {
                await writer.ConfigureAwait(false);
            }
            catch (Exception)
            {
                // Writer already stopped.
            }
        }
    }

    private static async Task<TransferResult> RunRemoteDriveAsync(
        Stream stream,
        ServiceRequest request,
        Func<ServiceRequest, CancellationToken, Task<ServiceResult>>? onService,
        Func<RemoteInput, bool>? onRemoteInput,
        CancellationToken ct)
    {
        var ready = onService is null
            ? ServiceResult.Fail(request.Id, ErrorCodes.NotImplemented, "This device does not accept companion calls.")
            : await onService(request, ct).ConfigureAwait(false);
        await FrameCodec.WriteJsonAsync(stream, MessageType.ServiceResult, ready, ct).ConfigureAwait(false);
        if (!ready.Ok)
        {
            return new TransferResult(TransferState.Failed, ready.Code, ready.Message, null, null, 0, false);
        }

        try
        {
            while (!ct.IsCancellationRequested)
            {
                Frame next;
                try
                {
                    next = await FrameCodec.ReadAsync(stream, ct).ConfigureAwait(false);
                }
                catch (Exception ex) when (ex is EndOfStreamException or IOException or OperationCanceledException)
                {
                    return new TransferResult(TransferState.Succeeded, "ok", RemoteControl.Stopped, null, null, 0, false);
                }

                if (next.Type == MessageType.RemoteInput)
                {
                    var input = RemoteInputCodec.Parse(next.Payload);
                    if (input is not null)
                    {
                        onRemoteInput?.Invoke(input);
                    }

                    continue;
                }

                if (next.Type == MessageType.Service)
                {
                    var inner = ServiceCodec.ParseRequest(next.Payload);
                    var result = onService is null
                        ? ServiceResult.Fail(inner.Id, ErrorCodes.NotImplemented, "This device does not accept companion calls.")
                        : await onService(inner, ct).ConfigureAwait(false);
                    await FrameCodec.WriteJsonAsync(stream, MessageType.ServiceResult, result, ct).ConfigureAwait(false);
                    var state = result.Ok ? TransferState.Succeeded : TransferState.Failed;
                    return new TransferResult(state, result.Code, result.Message, null, null, 0, false);
                }

                if (next.Type is MessageType.Cancel or MessageType.Error)
                {
                    return new TransferResult(TransferState.Succeeded, "ok", RemoteControl.Stopped, null, null, 0, false);
                }
            }

            return new TransferResult(TransferState.Succeeded, "ok", RemoteControl.Stopped, null, null, 0, false);
        }
        catch (Exception)
        {
            return new TransferResult(TransferState.Succeeded, "ok", RemoteControl.Stopped, null, null, 0, false);
        }
    }

    private static async Task<TransferResult> RunRemoteRequestAsync(
        Stream stream,
        ServiceRequest request,
        Func<ServiceRequest, CancellationToken, Task<ServiceResult>>? onService,
        Func<RemoteInput, bool>? onRemoteInput,
        Func<byte[]?>? nextRemoteFrame,
        CancellationToken ct,
        Action<string>? note)
    {
        var ready = onService is null
            ? ServiceResult.Fail(request.Id, ErrorCodes.NotImplemented, "This device does not accept companion calls.")
            : await onService(request, ct).ConfigureAwait(false);
        var from = ServiceCodec.ReadString(request.Body, "from") ?? "";
        if (ready.Ok && RemoteDesktopSession.KeepsLink(request.Op, from, ready.Ok) && nextRemoteFrame is null)
        {
            ready = ServiceResult.Fail(request.Id, ErrorCodes.NotImplemented, RemoteControl.UpdatePhone);
            note?.Invoke("Remote desktop capture is not available. " + RemoteControl.UpdatePhone);
        }

        await FrameCodec.WriteJsonAsync(stream, MessageType.ServiceResult, ready, ct).ConfigureAwait(false);
        if (!ready.Ok || !RemoteDesktopSession.KeepsLink(request.Op, from, ready.Ok))
        {
            var state = ready.Ok ? TransferState.Succeeded : TransferState.Failed;
            var message = ready.Ok ? "Remote desktop request answered." : "Remote desktop request refused. " + ready.Message;
            note?.Invoke(message);
            return new TransferResult(state, RemoteDesktop.SessionCode, message, null, null, 0, false);
        }

        note?.Invoke("Remote desktop capture starting.");
        return await StreamRemoteAsync(stream, request, onService, onRemoteInput, nextRemoteFrame, ct, note, parkWhenEmpty: true).ConfigureAwait(false);
    }

    private static async Task<TransferResult> RunRemoteWatchAsync(
        Stream stream,
        ServiceRequest request,
        Func<ServiceRequest, CancellationToken, Task<ServiceResult>>? onService,
        Func<RemoteInput, bool>? onRemoteInput,
        Func<byte[]?>? nextRemoteFrame,
        CancellationToken ct,
        Action<string>? note)
    {
        var ready = onService is null
            ? ServiceResult.Fail(request.Id, ErrorCodes.NotImplemented, "This device does not accept companion calls.")
            : await onService(request, ct).ConfigureAwait(false);
        await FrameCodec.WriteJsonAsync(stream, MessageType.ServiceResult, ready, ct).ConfigureAwait(false);
        if (!ready.Ok)
        {
            note?.Invoke("Remote desktop watch refused. " + ready.Message);
            return new TransferResult(TransferState.Failed, RemoteDesktop.SessionCode, "Remote desktop watch refused. " + ready.Message, null, null, 0, false);
        }

        note?.Invoke("Remote desktop capture starting.");
        return await StreamRemoteAsync(stream, request, onService, onRemoteInput, nextRemoteFrame, ct, note, parkWhenEmpty: false).ConfigureAwait(false);
    }

    private static async Task<TransferResult> StreamRemoteAsync(
        Stream stream,
        ServiceRequest request,
        Func<ServiceRequest, CancellationToken, Task<ServiceResult>>? onService,
        Func<RemoteInput, bool>? onRemoteInput,
        Func<byte[]?>? nextRemoteFrame,
        CancellationToken ct,
        Action<string>? note,
        bool parkWhenEmpty)
    {
        if (DesktopIo.CanTurn(stream))
        {
            return await StreamRemoteTurnAsync(stream, request, onService, onRemoteInput, nextRemoteFrame, ct, note, parkWhenEmpty).ConfigureAwait(false);
        }

        var pump = new FramePump();
        var writeLock = new SemaphoreSlim(1, 1);
        var loggedMove = false;
        var peerClosed = false;
        var startedAt = Environment.TickCount64;
        using var sessionStop = CancellationTokenSource.CreateLinkedTokenSource(ct);
        var sessionCt = sessionStop.Token;
        var writer = Task.Run(async () =>
        {
            try
            {
                while (!sessionCt.IsCancellationRequested)
                {
                    byte[]? jpeg = null;
                    try
                    {
                        jpeg = nextRemoteFrame?.Invoke();
                    }
                    catch (Exception ex)
                    {
                        note?.Invoke($"Remote desktop capture failed. {ex.GetType().Name}: {ex.Message}");
                    }

                    if (jpeg is not null)
                    {
                        pump.Offer(jpeg);
                    }

                    var newest = pump.Take();
                    if (ScreenShare.TryJpeg(newest, out var frame))
                    {
                        await writeLock.WaitAsync(sessionCt).ConfigureAwait(false);
                        try
                        {
                            await FrameCodec.WriteAsync(stream, MessageType.ScreenFrame, frame, sessionCt).ConfigureAwait(false);
                            pump.NoteSent(frame.Length);
                            if (pump.SentFrames == 1 || pump.SentFrames % 20 == 0)
                            {
                                note?.Invoke($"Remote desktop frames {pump.SentFrames}, {pump.SentBytes} bytes, dropped {pump.Dropped}.");
                            }
                        }
                        finally
                        {
                            writeLock.Release();
                        }
                    }

                    await Task.Delay(RemoteDesktop.ActiveIntervalMs, sessionCt).ConfigureAwait(false);
                }
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                note?.Invoke($"Remote desktop writer stopped. {ex.GetType().Name}: {ex.Message}");
            }
        }, ct);

        try
        {
            while (!ct.IsCancellationRequested)
            {
                Frame next;
                try
                {
                    next = await FrameCodec.ReadAsync(stream, ct).ConfigureAwait(false);
                }
                catch (Exception ex) when (ex is EndOfStreamException or IOException or OperationCanceledException)
                {
                    peerClosed = true;
                    if (ex is not (EndOfStreamException or OperationCanceledException))
                    {
                        note?.Invoke($"Remote desktop link closed. {ex.GetType().Name}: {ex.Message}");
                    }

                    return SessionEnded(false);
                }

                if (next.Type == MessageType.RemoteInput)
                {
                    var input = RemoteInputCodec.Parse(next.Payload);
                    if (input is not null)
                    {
                        if (input.Kind != "move" || !loggedMove)
                        {
                            loggedMove = loggedMove || input.Kind == "move";
                            note?.Invoke($"Remote desktop input {input.Kind}.");
                        }

                        onRemoteInput?.Invoke(input);
                    }

                    continue;
                }

                if (next.Type == MessageType.Service)
                {
                    var inner = ServiceCodec.ParseRequest(next.Payload);
                    note?.Invoke($"Remote desktop {inner.Op}.");
                    var result = onService is null
                        ? ServiceResult.Fail(inner.Id, ErrorCodes.NotImplemented, "This device does not accept companion calls.")
                        : await onService(inner, ct).ConfigureAwait(false);
                    await writeLock.WaitAsync(ct).ConfigureAwait(false);
                    try
                    {
                        await FrameCodec.WriteJsonAsync(stream, MessageType.ServiceResult, result, ct).ConfigureAwait(false);
                    }
                    finally
                    {
                        writeLock.Release();
                    }

                    var state = result.Ok ? TransferState.Succeeded : TransferState.Failed;
                    return new TransferResult(state, RemoteDesktop.SessionCode, "Remote desktop session ended.", null, null, 0, false);
                }

                if (next.Type is MessageType.Cancel or MessageType.Error)
                {
                    return SessionEnded(false);
                }
            }

            return SessionEnded(false);
        }
        finally
        {
            sessionStop.Cancel();
            try
            {
                await writer.WaitAsync(TimeSpan.FromSeconds(2)).ConfigureAwait(false);
            }
            catch (Exception)
            {
                // Writer already stopped.
            }

            NoteRemoteClosed(parkWhenEmpty, peerClosed, pump.SentFrames, startedAt, note);
        }
    }

    /// <summary>
    /// One thread reads and writes. SslStream drops the link when a frame write
    /// overlaps ReadAsync, which closed the request after the first frame.
    /// </summary>
    private static async Task<TransferResult> StreamRemoteTurnAsync(
        Stream stream,
        ServiceRequest request,
        Func<ServiceRequest, CancellationToken, Task<ServiceResult>>? onService,
        Func<RemoteInput, bool>? onRemoteInput,
        Func<byte[]?>? nextRemoteFrame,
        CancellationToken ct,
        Action<string>? note,
        bool parkWhenEmpty)
    {
        var pump = new FramePump();
        var loggedMove = false;
        var peerClosed = false;
        var startedAt = Environment.TickCount64;
        var nextFrameAt = 0L;
        try
        {
            while (!ct.IsCancellationRequested)
            {
                if (DesktopIo.Pending(stream))
                {
                    Frame next;
                    try
                    {
                        next = await FrameCodec.ReadAsync(stream, ct).ConfigureAwait(false);
                    }
                    catch (Exception ex) when (ex is EndOfStreamException or IOException or OperationCanceledException)
                    {
                        peerClosed = ex is not OperationCanceledException;
                        if (ex is IOException and not EndOfStreamException)
                        {
                            note?.Invoke($"Remote desktop link closed. {ex.GetType().Name}: {ex.Message}");
                        }

                        return SessionEnded(false);
                    }

                    if (next.Type == MessageType.RemoteInput)
                    {
                        var input = RemoteInputCodec.Parse(next.Payload);
                        if (input is not null)
                        {
                            if (input.Kind != "move" || !loggedMove)
                            {
                                loggedMove = loggedMove || input.Kind == "move";
                                note?.Invoke($"Remote desktop input {input.Kind}.");
                            }

                            onRemoteInput?.Invoke(input);
                        }

                        continue;
                    }

                    if (next.Type == MessageType.Service)
                    {
                        var inner = ServiceCodec.ParseRequest(next.Payload);
                        note?.Invoke($"Remote desktop {inner.Op}.");
                        var result = onService is null
                            ? ServiceResult.Fail(inner.Id, ErrorCodes.NotImplemented, "This device does not accept companion calls.")
                            : await onService(inner, ct).ConfigureAwait(false);
                        await FrameCodec.WriteJsonAsync(stream, MessageType.ServiceResult, result, ct).ConfigureAwait(false);
                        var state = result.Ok ? TransferState.Succeeded : TransferState.Failed;
                        return new TransferResult(state, RemoteDesktop.SessionCode, "Remote desktop session ended.", null, null, 0, false);
                    }

                    if (next.Type is MessageType.Cancel or MessageType.Error)
                    {
                        return SessionEnded(false);
                    }

                    continue;
                }

                if (Environment.TickCount64 >= nextFrameAt)
                {
                    byte[]? jpeg = null;
                    try
                    {
                        jpeg = nextRemoteFrame?.Invoke();
                    }
                    catch (Exception ex)
                    {
                        note?.Invoke($"Remote desktop capture failed. {ex.GetType().Name}: {ex.Message}");
                    }

                    if (jpeg is not null)
                    {
                        pump.Offer(jpeg);
                    }

                    var newest = pump.Take();
                    if (ScreenShare.TryJpeg(newest, out var frame))
                    {
                        try
                        {
                            await FrameCodec.WriteAsync(stream, MessageType.ScreenFrame, frame, ct).ConfigureAwait(false);
                            pump.NoteSent(frame.Length);
                            if (pump.SentFrames == 1 || pump.SentFrames % 20 == 0)
                            {
                                note?.Invoke($"Remote desktop frames {pump.SentFrames}, {pump.SentBytes} bytes, dropped {pump.Dropped}.");
                            }
                        }
                        catch (Exception ex) when (ex is not OperationCanceledException)
                        {
                            peerClosed = true;
                            note?.Invoke($"Remote desktop writer stopped. {ex.GetType().Name}: {ex.Message}");
                            return SessionEnded(false);
                        }
                    }

                    nextFrameAt = Environment.TickCount64 + RemoteDesktop.ActiveIntervalMs;
                }

                var waitMs = (int)Math.Min(20, Math.Max(1, nextFrameAt - Environment.TickCount64));
                await Task.Delay(waitMs, ct).ConfigureAwait(false);
            }

            return SessionEnded(false);
        }
        catch (OperationCanceledException)
        {
            return SessionEnded(false);
        }
        finally
        {
            NoteRemoteClosed(parkWhenEmpty, peerClosed, pump.SentFrames, startedAt, note);
        }
    }

    private static void NoteRemoteClosed(bool parkWhenEmpty, bool peerClosed, int sentFrames, long startedAt, Action<string>? note)
    {
        // A 1.5.34 phone reads the allow result and closes, then opens watch.
        // A frame can already be on the wire. A live session stays open.
        var brief = Environment.TickCount64 - startedAt < 3_000;
        if (parkWhenEmpty && peerClosed && (sentFrames == 0 || brief))
        {
            note?.Invoke("Remote desktop request link closed. Waiting for the phone's watch connection.");
        }
        else
        {
            note?.Invoke("Remote desktop watch ended.");
        }
    }

    private static TransferResult SessionEnded(bool parked) =>
        new(TransferState.Succeeded, RemoteDesktop.SessionCode,
            parked
                ? "Remote desktop request link closed. Waiting for the phone's watch connection."
                : "Remote desktop session ended.",
            null, null, 0, false);
}
