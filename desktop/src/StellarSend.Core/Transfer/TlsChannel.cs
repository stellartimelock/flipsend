using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using StellarSend.Core.Protocol;
using StellarSend.Core.Security;

namespace StellarSend.Core.Transfer;

public sealed class TlsConnection : IAsyncDisposable
{
    private readonly IDisposable? _owner;
    public SslStream Stream { get; }
    public string RemoteFingerprint { get; }
    public byte[] RemoteSpki { get; }
    public bool IsRelay { get; set; }
    public string? RelaySeen { get; set; }

    public TlsConnection(TcpClient tcp, SslStream stream, string remoteFingerprint, byte[] remoteSpki)
        : this(stream, remoteFingerprint, remoteSpki, tcp)
    {
    }

    public TlsConnection(SslStream stream, string remoteFingerprint, byte[] remoteSpki, IDisposable? owner)
    {
        Stream = stream;
        RemoteFingerprint = remoteFingerprint;
        RemoteSpki = remoteSpki;
        _owner = owner;
    }

    public async ValueTask DisposeAsync()
    {
        await Stream.DisposeAsync().ConfigureAwait(false);
        _owner?.Dispose();
    }
}

public static class TlsChannel
{
    public static Task<TlsConnection> ConnectAsync(
        IPEndPoint endpoint,
        X509Certificate2 certificate,
        string expectedFingerprint,
        CancellationToken ct) =>
        ConnectAsync(endpoint, certificate, expectedFingerprint, ct, localAddress: null);

    public static async Task<TlsConnection> ConnectAsync(
        IPEndPoint endpoint,
        X509Certificate2 certificate,
        string expectedFingerprint,
        CancellationToken ct,
        IPAddress? localAddress)
    {
        if (!PinFormat.IsWellFormed(expectedFingerprint))
        {
            throw new ProtocolException("Missing peer pin.");
        }

        var tcp = new TcpClient();
        Widen(tcp);
        try
        {
            if (localAddress is not null)
            {
                tcp.Client.Bind(new IPEndPoint(localAddress, 0));
            }

            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(2000);
            using (timeout.Token.Register(() =>
            {
                try
                {
                    tcp.Dispose();
                }
                catch
                {
                }
            }))
            {
                await tcp.ConnectAsync(endpoint, timeout.Token).ConfigureAwait(false);
            }

            return await ConnectAsync(tcp, certificate, expectedFingerprint, endpoint.Address.ToString(), ct).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            tcp.Dispose();
            throw new TimeoutException("Direct connect timed out.");
        }
        catch
        {
            tcp.Dispose();
            throw;
        }
    }

    public static Task<TlsConnection> ConnectAsync(
        TcpClient tcp,
        X509Certificate2 certificate,
        string expectedFingerprint,
        CancellationToken ct) =>
        ConnectAsync(tcp, certificate, expectedFingerprint, "stellarsend", ct);

    private static async Task<TlsConnection> ConnectAsync(
        TcpClient tcp,
        X509Certificate2 certificate,
        string expectedFingerprint,
        string targetHost,
        CancellationToken ct)
    {
        Widen(tcp);
        var seen = new PinCapture();
        RemoteCertificateValidationCallback callback = (sender, cert, chain, errors) =>
            Validate(cert, expectedFingerprint, allowNew: false, seen);
        SslStream? ssl = new SslStream(tcp.GetStream(), leaveInnerStreamOpen: false, callback);
        try
        {
            await ssl.AuthenticateAsClientAsync(ClientOptions(targetHost, certificate, callback), ct).ConfigureAwait(false);
            if (!Pin.FixedEquals(seen.Fingerprint, expectedFingerprint) || seen.Spki is null)
            {
                KeyChangedLog.Write(KeyChangedLog.LocalCheck, seen.Fingerprint, expectedFingerprint, seen.DeviceId);
                throw PeerRejectedException.KeyChanged();
            }

            var connection = new TlsConnection(tcp, ssl, seen.Fingerprint!, seen.Spki);
            ssl = null;
            return connection;
        }
        catch (AuthenticationException ex)
        {
            throw ExplainAlert(ex, seen, expectedFingerprint, Pin.Fingerprint(certificate));
        }
        finally
        {
            if (ssl is not null)
            {
                await ssl.DisposeAsync().ConfigureAwait(false);
            }
        }
    }

    public static async Task<TlsConnection> ConnectPairingAsync(
        IPEndPoint endpoint,
        X509Certificate2 certificate,
        CancellationToken ct)
    {
        var tcp = new TcpClient();
        Widen(tcp);
        try
        {
            await tcp.ConnectAsync(endpoint, ct).ConfigureAwait(false);
            var seen = new PinCapture();
            RemoteCertificateValidationCallback callback = (sender, cert, chain, errors) =>
                Validate(cert, expectedFingerprint: null, allowNew: true, seen);
            var ssl = new SslStream(tcp.GetStream(), leaveInnerStreamOpen: false, callback);
            await ssl.AuthenticateAsClientAsync(ClientOptions(endpoint.Address.ToString(), certificate, callback), ct).ConfigureAwait(false);
            if (seen.Fingerprint is null || seen.Spki is null)
            {
                await ssl.DisposeAsync().ConfigureAwait(false);
                throw PeerRejectedException.Unknown();
            }

            return new TlsConnection(tcp, ssl, seen.Fingerprint, seen.Spki);
        }
        catch
        {
            tcp.Dispose();
            throw;
        }
    }

    public static async Task<TlsConnection> AcceptAsync(
        TcpClient tcp,
        X509Certificate2 certificate,
        string? expectedFingerprint,
        bool allowNew,
        CancellationToken ct)
    {
        Widen(tcp);
        RejectDeviceIdAsPin(expectedFingerprint);
        var seen = new PinCapture();
        RemoteCertificateValidationCallback callback = (sender, cert, chain, errors) =>
            Validate(cert, expectedFingerprint, allowNew, seen);
        var ssl = new SslStream(tcp.GetStream(), leaveInnerStreamOpen: false, callback);
        try
        {
            await ssl.AuthenticateAsServerAsync(ServerOptions(certificate, callback), ct).ConfigureAwait(false);
            FillFromRemote(ssl, seen);
        }
        catch (AuthenticationException ex)
        {
            FillFromRemote(ssl, seen);
            await ssl.DisposeAsync().ConfigureAwait(false);
            throw ExplainAlert(ex, seen, expectedFingerprint, Pin.Fingerprint(certificate));
        }
        catch
        {
            await ssl.DisposeAsync().ConfigureAwait(false);
            throw;
        }

        if (DeliveryFault.IsSelf(seen.Fingerprint, Pin.Fingerprint(certificate)))
        {
            await ssl.DisposeAsync().ConfigureAwait(false);
            throw new SelfDialException();
        }

        if (seen.Fingerprint is null || seen.Spki is null)
        {
            await ssl.DisposeAsync().ConfigureAwait(false);
            throw PeerRejectedException.Unknown();
        }

        if (expectedFingerprint is not null && !Pin.FixedEquals(seen.Fingerprint, expectedFingerprint))
        {
            await ssl.DisposeAsync().ConfigureAwait(false);
            KeyChangedLog.Write(KeyChangedLog.LocalCheck, seen.Fingerprint, expectedFingerprint, seen.DeviceId);
            throw PeerRejectedException.KeyChanged();
        }

        if (expectedFingerprint is null && !allowNew)
        {
            await ssl.DisposeAsync().ConfigureAwait(false);
            throw PeerRejectedException.Unknown();
        }

        return new TlsConnection(tcp, ssl, seen.Fingerprint, seen.Spki);
    }

    public static Task<TlsConnection> ConnectStreamAsync(
        Stream stream,
        X509Certificate2 certificate,
        string expectedFingerprint,
        CancellationToken ct) =>
        AuthenticateClientAsync(stream, certificate, expectedFingerprint, allowNew: false, "stellarsend", ct);

    public static Task<TlsConnection> AcceptStreamAsync(
        Stream stream,
        X509Certificate2 certificate,
        string? expectedFingerprint,
        bool allowNew,
        CancellationToken ct) =>
        AuthenticateServerAsync(stream, certificate, expectedFingerprint, allowNew, ct);

    private static async Task<TlsConnection> AuthenticateClientAsync(
        Stream stream,
        X509Certificate2 certificate,
        string? expectedFingerprint,
        bool allowNew,
        string targetHost,
        CancellationToken ct)
    {
        RejectDeviceIdAsPin(expectedFingerprint);
        var seen = new PinCapture();
        RemoteCertificateValidationCallback callback = (sender, cert, chain, errors) =>
            Validate(cert, expectedFingerprint, allowNew, seen);
        var ssl = new SslStream(stream, leaveInnerStreamOpen: false, callback);
        try
        {
            await ssl.AuthenticateAsClientAsync(ClientOptions(targetHost, certificate, callback), ct).ConfigureAwait(false);
        }
        catch (AuthenticationException ex)
        {
            await ssl.DisposeAsync().ConfigureAwait(false);
            throw ExplainAlert(ex, seen, expectedFingerprint, Pin.Fingerprint(certificate));
        }
        catch
        {
            await ssl.DisposeAsync().ConfigureAwait(false);
            throw;
        }

        return Finish(ssl, seen, expectedFingerprint, allowNew, Pin.Fingerprint(certificate));
    }

    private static async Task<TlsConnection> AuthenticateServerAsync(
        Stream stream,
        X509Certificate2 certificate,
        string? expectedFingerprint,
        bool allowNew,
        CancellationToken ct)
    {
        RejectDeviceIdAsPin(expectedFingerprint);
        var seen = new PinCapture();
        RemoteCertificateValidationCallback callback = (sender, cert, chain, errors) =>
            Validate(cert, expectedFingerprint, allowNew, seen);
        var ssl = new SslStream(stream, leaveInnerStreamOpen: false, callback);
        try
        {
            await ssl.AuthenticateAsServerAsync(ServerOptions(certificate, callback), ct).ConfigureAwait(false);
            FillFromRemote(ssl, seen);
        }
        catch (AuthenticationException ex)
        {
            FillFromRemote(ssl, seen);
            await ssl.DisposeAsync().ConfigureAwait(false);
            throw ExplainAlert(ex, seen, expectedFingerprint, Pin.Fingerprint(certificate));
        }
        catch
        {
            await ssl.DisposeAsync().ConfigureAwait(false);
            throw;
        }

        return Finish(ssl, seen, expectedFingerprint, allowNew, Pin.Fingerprint(certificate));
    }

    private static void Widen(TcpClient tcp)
    {
        tcp.NoDelay = true;
        try
        {
            tcp.SendBufferSize = 256 * 1024;
            tcp.ReceiveBufferSize = 256 * 1024;
        }
        catch (SocketException)
        {
        }
    }

    /// <summary>
    /// A failed handshake is not proof the pairing file is wrong. A finished
    /// handshake whose pin does not match still raises <see cref="PeerRejectedException.KeyChanged"/>.
    /// </summary>
    private static IOException ExplainAlert(Exception ex, PinCapture seen, string? expectedFingerprint, string? localFingerprint)
    {
        if (DeliveryFault.IsSelf(seen.Fingerprint, localFingerprint))
        {
            return new SelfDialException();
        }

        var different = expectedFingerprint is not null &&
            PinFormat.IsWellFormed(expectedFingerprint) &&
            seen.Fingerprint is not null &&
            !Pin.FixedEquals(seen.Fingerprint, expectedFingerprint);
        var message = different
            ? "Secure connection closed before the handshake finished. The certificate on this socket did not match the saved pin. The pairing file was not changed."
            : "The secure connection closed. FlipSend will try again.";
        return new IOException(message, ex);
    }

    private static void RejectDeviceIdAsPin(string? expectedFingerprint)
    {
        if (expectedFingerprint is not null && !PinFormat.IsWellFormed(expectedFingerprint))
        {
            throw new IOException("Refusing to compare a device id to a certificate pin. The secure connection closed. FlipSend will try again.");
        }
    }

    /// <summary>
    /// Schannel sometimes finishes AuthenticateAsServer before the validation
    /// callback sees the client cert. Read it from the stream so handshake is
    /// not asked to compare a blank pin ("none").
    /// </summary>
    private static void FillFromRemote(SslStream ssl, PinCapture seen)
    {
        if (!string.IsNullOrWhiteSpace(seen.Fingerprint) && seen.Spki is not null)
        {
            return;
        }

        try
        {
            Validate(ssl.RemoteCertificate, expectedFingerprint: null, allowNew: true, seen);
        }
        catch (Exception)
        {
        }
    }

    private static TlsConnection Finish(SslStream ssl, PinCapture seen, string? expectedFingerprint, bool allowNew, string? localFingerprint)
    {
        FillFromRemote(ssl, seen);
        if (DeliveryFault.IsSelf(seen.Fingerprint, localFingerprint))
        {
            ssl.Dispose();
            throw new SelfDialException();
        }

        if (seen.Fingerprint is null || seen.Spki is null)
        {
            ssl.Dispose();
            throw PeerRejectedException.Unknown();
        }

        if (expectedFingerprint is not null && !Pin.FixedEquals(seen.Fingerprint, expectedFingerprint))
        {
            ssl.Dispose();
            KeyChangedLog.Write(KeyChangedLog.LocalCheck, seen.Fingerprint, expectedFingerprint, seen.DeviceId);
            throw PeerRejectedException.KeyChanged();
        }

        if (expectedFingerprint is null && !allowNew)
        {
            ssl.Dispose();
            throw PeerRejectedException.Unknown();
        }

        return new TlsConnection(ssl, seen.Fingerprint, seen.Spki, owner: null);
    }

    private static SslClientAuthenticationOptions ClientOptions(
        string targetHost,
        X509Certificate2 certificate,
        RemoteCertificateValidationCallback callback) =>
        new()
        {
            TargetHost = targetHost,
            ClientCertificates = new X509CertificateCollection { certificate },
            EnabledSslProtocols = SslProtocols.Tls12,
            CertificateRevocationCheckMode = X509RevocationMode.NoCheck,
            RemoteCertificateValidationCallback = callback,
            LocalCertificateSelectionCallback = (_, _, _, _, _) => certificate
        };

    /// <summary>
    /// Always present the pairing identity. On Windows, Schannel can substitute
    /// a machine cert if only ServerCertificate is set; the selection callback
    /// pins this identity. Linux OpenSSL uses ServerCertificate instead.
    /// </summary>
    private static SslServerAuthenticationOptions ServerOptions(
        X509Certificate2 certificate,
        RemoteCertificateValidationCallback callback)
    {
        var options = new SslServerAuthenticationOptions
        {
            ClientCertificateRequired = true,
            EnabledSslProtocols = SslProtocols.Tls12,
            CertificateRevocationCheckMode = X509RevocationMode.NoCheck,
            RemoteCertificateValidationCallback = callback
        };
        if (OperatingSystem.IsWindows())
        {
            options.ServerCertificateSelectionCallback = (_, _) => certificate;
        }
        else
        {
            options.ServerCertificate = certificate;
        }

        return options;
    }

    private static bool Validate(X509Certificate? certificate, string? expectedFingerprint, bool allowNew, PinCapture seen)
    {
        if (certificate is null)
        {
            return false;
        }

        byte[] der;
        try
        {
            der = certificate.Export(X509ContentType.Cert);
        }
        catch (Exception)
        {
            return false;
        }

        using var parsed = X509CertificateLoader.LoadCertificate(der);
        var fingerprint = Pin.Fingerprint(parsed);
        seen.Fingerprint = fingerprint;
        seen.Spki = parsed.PublicKey.ExportSubjectPublicKeyInfo();
        seen.DeviceId = parsed.GetNameInfo(X509NameType.SimpleName, false);
        if (expectedFingerprint is null)
        {
            return allowNew;
        }

        return Pin.FixedEquals(fingerprint, expectedFingerprint);
    }

    private sealed class PinCapture
    {
        public string? Fingerprint;
        public byte[]? Spki;
        public string? DeviceId;
    }
}
