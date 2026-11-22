import XCTest
import CadenceCore
#if canImport(Darwin)
import Darwin
#else
import Glibc
#endif

final class BoundedFileLockTests: XCTestCase {
    private func withLockFile(_ body: (URL) throws -> Void) throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        try body(directory.appendingPathComponent("test.lock"))
    }

    func testContentionTimesOutNearDeadlineWithoutRunningBody() throws {
        try withLockFile { url in
            let fd = open(url.path, O_CREAT | O_RDWR, S_IRUSR | S_IWUSR)
            XCTAssertGreaterThanOrEqual(fd, 0)
            defer { close(fd) }
            XCTAssertEqual(flock(fd, LOCK_EX | LOCK_NB), 0)
            defer { _ = flock(fd, LOCK_UN) }
            let timeout = 0.25
            let start = DispatchTime.now().uptimeNanoseconds
            var calls = 0
            XCTAssertThrowsError(try BoundedFileLock.withLock(at: url, timeout: timeout) { calls += 1 }) { error in
                XCTAssertTrue(error is BoundedFileLock.Timeout)
            }
            let elapsed = Double(DispatchTime.now().uptimeNanoseconds - start) / 1_000_000_000
            XCTAssertGreaterThanOrEqual(elapsed, timeout * 0.9)
            XCTAssertLessThanOrEqual(elapsed, timeout * 2)
            XCTAssertEqual(calls, 0)
        }
    }

    func testAcquiresAfterOtherDescriptorReleases() throws {
        try withLockFile { url in
            let fd = open(url.path, O_CREAT | O_RDWR, S_IRUSR | S_IWUSR)
            XCTAssertGreaterThanOrEqual(fd, 0)
            defer { close(fd) }
            XCTAssertEqual(flock(fd, LOCK_EX | LOCK_NB), 0)
            XCTAssertThrowsError(try BoundedFileLock.withLock(at: url, timeout: 0.02) {})
            XCTAssertEqual(flock(fd, LOCK_UN), 0)
            XCTAssertEqual(try BoundedFileLock.withLock(at: url, timeout: 0.25) { 42 }, 42)
        }
    }

    func testSuccessfulBodyRunsOnceWithExclusiveLockAndReleases() throws {
        try withLockFile { url in
            var calls = 0
            try BoundedFileLock.withLock(at: url, timeout: 0.25) {
                calls += 1
                XCTAssertThrowsError(try BoundedFileLock.withLock(at: url, timeout: 0) {})
            }
            XCTAssertEqual(calls, 1)
            XCTAssertNoThrow(try BoundedFileLock.withLock(at: url, timeout: 0) {})
        }
    }

    func testThrowingBodyReleasesLock() throws {
        enum Failure: Error { case expected }
        try withLockFile { url in
            XCTAssertThrowsError(try BoundedFileLock.withLock(at: url, timeout: 0.25) { throw Failure.expected }) { error in
                XCTAssertTrue(error is Failure)
            }
            XCTAssertNoThrow(try BoundedFileLock.withLock(at: url, timeout: 0) {})
        }
    }
}
