import LocalAuthentication
import Foundation

/// Biometrics helper — convenience unlock only.
///
/// Rule: biometrics are NEVER the encryption key for save files.
/// They only gate access to the UI (e.g. the Reset screen).
/// Encryption uses Argon2id / scrypt / HKDF from the StellarTimeLock
/// seed or a user password; biometrics just unlock the UI session.
enum BiometricGate {

    /// Whether Face ID / Touch ID is enrolled and available.
    static var available: Bool {
        let ctx = LAContext()
        var error: NSError?
        return ctx.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error)
    }

    /// Prompt for biometrics.
    /// - Parameter reason: Message shown in the biometric prompt.
    /// - Parameter completion: Called on the main queue with `true` on success.
    static func authenticate(reason: String = "Confirm your identity", completion: @escaping (Bool) -> Void) {
        guard available else {
            DispatchQueue.main.async { completion(true) }
            return
        }
        let ctx = LAContext()
        ctx.evaluatePolicy(
            .deviceOwnerAuthenticationWithBiometrics,
            localizedReason: reason
        ) { success, _ in
            DispatchQueue.main.async { completion(success) }
        }
    }
}
