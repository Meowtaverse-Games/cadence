import Foundation
#if canImport(Darwin)
import Darwin
#else
import Glibc
#endif

/// A separate lock file survives atomic replacement of the data file.
public enum BoundedFileLock {
    public struct Timeout: Error {
        public let waitedMilliseconds: Int
    }

    public static func withLock<T>(at url: URL, timeout: TimeInterval, body: () throws -> T) throws -> T {
        let fd = open(url.path, O_CREAT | O_RDWR, S_IRUSR | S_IWUSR)
        guard fd >= 0 else { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
        defer { close(fd) }
        let started = DispatchTime.now().uptimeNanoseconds
        let budget = UInt64(max(0, timeout) * 1_000_000_000)
        while flock(fd, LOCK_EX | LOCK_NB) != 0 {
            let code = errno
            guard code == EWOULDBLOCK || code == EAGAIN || code == EINTR else {
                throw POSIXError(POSIXErrorCode(rawValue: code) ?? .EIO)
            }
            let elapsed = DispatchTime.now().uptimeNanoseconds - started
            guard elapsed < budget else {
                throw Timeout(waitedMilliseconds: Int(elapsed / 1_000_000))
            }
            // Monotonic deadline; short sleeps avoid busy waiting or a full extra poll at the deadline.
            Thread.sleep(forTimeInterval: Double(min(5_000_000, budget - elapsed)) / 1_000_000_000)
        }
        defer { _ = flock(fd, LOCK_UN) }
        return try body()
    }
}
