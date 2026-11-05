import Foundation

public enum Frame: Equatable, Sendable {
    /// UTF-8 JSON control payload, not yet parsed.
    case control(Data)
    /// `transferId` is the lowercase UUID string.
    case chunk(transferId: String, offset: UInt64, data: Data)
}

public enum FrameLimits {
    public static let maxControlPayload = 524_288
    public static let maxChunkData = 1_048_576
    static let chunkHeaderLength = 16 + 8
    public static let maxFrameLength = 1 + chunkHeaderLength + maxChunkData
}

public enum FrameError: String, Error {
    case emptyFrame = "empty_frame"
    case frameTooLarge = "frame_too_large"
    case unknownKind = "unknown_kind"
    case chunkTooShort = "chunk_too_short"
}

enum FrameKind {
    static let control: UInt8 = 0x01
    static let chunk: UInt8 = 0x02
}

public enum FrameEncoder {
    public static func encode(_ frame: Frame) -> Data {
        var payload = Data()
        let kind: UInt8
        switch frame {
        case .control(let json):
            kind = FrameKind.control
            payload = json
        case .chunk(let transferId, let offset, let data):
            kind = FrameKind.chunk
            payload.append(uuidBytes(transferId))
            payload.append(bigEndian: offset)
            payload.append(data)
        }
        var out = Data(capacity: 5 + payload.count)
        out.append(bigEndian: UInt32(1 + payload.count))
        out.append(kind)
        out.append(payload)
        return out
    }

    static func uuidBytes(_ transferId: String) -> Data {
        guard let uuid = UUID(uuidString: transferId) else {
            preconditionFailure("chunk transferId is not a UUID: \(transferId)")
        }
        return withUnsafeBytes(of: uuid.uuid) { Data($0) }
    }
}

public struct FrameDecoder {
    private var buffer: [UInt8] = []
    private var start = 0

    public init() {}

    /// Appends received bytes and returns every frame that is now complete.
    /// Throws on the first framing error; the connection must then be closed.
    public mutating func append(_ bytes: Data) throws -> [Frame] {
        buffer.append(contentsOf: bytes)
        var frames: [Frame] = []
        while buffer.count - start >= 4 {
            let length = Int(readUInt32(at: start))
            if length == 0 { throw FrameError.emptyFrame }
            if length > FrameLimits.maxFrameLength { throw FrameError.frameTooLarge }
            guard buffer.count - start >= 5 else { break }
            let kind = buffer[start + 4]
            switch kind {
            case FrameKind.control:
                if length - 1 > FrameLimits.maxControlPayload { throw FrameError.frameTooLarge }
            case FrameKind.chunk:
                if length - 1 < FrameLimits.chunkHeaderLength + 1 { throw FrameError.chunkTooShort }
            default:
                throw FrameError.unknownKind
            }
            guard buffer.count - start >= 4 + length else { break }
            let payload = Data(buffer[(start + 5)..<(start + 4 + length)])
            frames.append(makeFrame(kind: kind, payload: payload))
            start += 4 + length
        }
        compact()
        return frames
    }

    private func readUInt32(at index: Int) -> UInt32 {
        buffer[index..<(index + 4)].reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
    }

    private func makeFrame(kind: UInt8, payload: Data) -> Frame {
        guard kind == FrameKind.chunk else { return .control(payload) }
        let bytes = [UInt8](payload)
        let uuid = UUID(uuid: (bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
                               bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]))
        let offset = bytes[16..<24].reduce(UInt64(0)) { $0 << 8 | UInt64($1) }
        return .chunk(transferId: uuid.uuidString.lowercased(), offset: offset, data: Data(bytes[24...]))
    }

    private mutating func compact() {
        if start == buffer.count {
            buffer.removeAll(keepingCapacity: true)
            start = 0
        } else if start > 1 << 20 {
            buffer.removeFirst(start)
            start = 0
        }
    }
}

extension Data {
    mutating func append<T: FixedWidthInteger>(bigEndian value: T) {
        Swift.withUnsafeBytes(of: value.bigEndian) { append(contentsOf: $0) }
    }
}
