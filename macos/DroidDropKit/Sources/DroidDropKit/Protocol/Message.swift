import Foundation

public enum Platform: String, Codable, Sendable {
    case android
    case macos
}

public struct ProtocolError: Error, Equatable {
    public let reason: String
    public init(_ reason: String) { self.reason = reason }
}

public enum Message: Equatable, Sendable {
    case hello(v: Int, name: String, platform: Platform)
    case pairRequest(proof: String)
    case pairResult(ok: Bool, proof: String?, reason: String?)
    case ping(id: Int)
    case pong(id: Int)
    case clip(id: String, mime: String, text: String)
    case fileOffer(transferId: String, name: String, size: Int, sha256: String, mime: String)
    case fileAccept(transferId: String, offset: Int)
    case fileReject(transferId: String, reason: String)
    case fileDone(transferId: String)
    case fileResult(transferId: String, ok: Bool, reason: String?)
    case fileCancel(transferId: String)
    case unknown(type: String)
}

public enum MessageCodec {
    public static func decode(_ data: Data) throws -> Message {
        guard String(data: data, encoding: .utf8) != nil else { throw ProtocolError("payload is not UTF-8") }
        try StrictJSON.validate(Array(data))
        let json: Any
        do {
            json = try JSONSerialization.jsonObject(with: data)
        } catch {
            throw ProtocolError("payload is not JSON")
        }
        guard let object = json as? [String: Any] else { throw ProtocolError("payload is not a JSON object") }
        return try Message(Members(object: object))
    }

    public static func encode(_ message: Message) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return try encoder.encode(message)
    }
}

private extension Message {
    init(_ m: Members) throws {
        let type = try m.string("type")
        switch type {
        case "hello":
            let v = try m.integer("v")
            guard v >= 1 else { throw ProtocolError("v must be >= 1") }
            guard let platform = Platform(rawValue: try m.string("platform")) else { throw ProtocolError("unknown platform") }
            self = .hello(v: v, name: try m.nonEmpty("name"), platform: platform)
        case "pair_request":
            self = .pairRequest(proof: try m.b64url32("proof"))
        case "pair_result":
            let ok = try m.boolean("ok")
            let proof = m.isPresent("proof") ? try m.b64url32("proof") : nil
            guard !ok || proof != nil else { throw ProtocolError("proof required when ok") }
            self = .pairResult(ok: ok, proof: proof, reason: m.isPresent("reason") ? try m.string("reason") : nil)
        case "ping":
            self = .ping(id: try m.integer("id"))
        case "pong":
            self = .pong(id: try m.integer("id"))
        case "clip":
            self = .clip(id: try m.uuid("id"), mime: try m.nonEmpty("mime"), text: try m.string("text"))
        case "file_offer":
            self = .fileOffer(transferId: try m.uuid("transferId"), name: try m.nonEmpty("name"), size: try m.integer("size"),
                              sha256: try m.sha256hex("sha256"), mime: try m.nonEmpty("mime"))
        case "file_accept":
            self = .fileAccept(transferId: try m.uuid("transferId"), offset: try m.integer("offset"))
        case "file_reject":
            self = .fileReject(transferId: try m.uuid("transferId"), reason: try m.string("reason"))
        case "file_done":
            self = .fileDone(transferId: try m.uuid("transferId"))
        case "file_result":
            self = .fileResult(transferId: try m.uuid("transferId"), ok: try m.boolean("ok"),
                               reason: m.isPresent("reason") ? try m.string("reason") : nil)
        case "file_cancel":
            self = .fileCancel(transferId: try m.uuid("transferId"))
        default:
            self = .unknown(type: type)
        }
    }
}

extension Message: Encodable {
    enum Key: String, CodingKey {
        case type, v, name, platform, proof, ok, reason, id, mime, text, transferId, size, sha256, offset
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: Key.self)
        switch self {
        case let .hello(v, name, platform):
            try c.encode("hello", forKey: .type)
            try c.encode(v, forKey: .v)
            try c.encode(name, forKey: .name)
            try c.encode(platform, forKey: .platform)
        case let .pairRequest(proof):
            try c.encode("pair_request", forKey: .type)
            try c.encode(proof, forKey: .proof)
        case let .pairResult(ok, proof, reason):
            try c.encode("pair_result", forKey: .type)
            try c.encode(ok, forKey: .ok)
            try c.encodeIfPresent(proof, forKey: .proof)
            try c.encodeIfPresent(reason, forKey: .reason)
        case let .ping(id):
            try c.encode("ping", forKey: .type)
            try c.encode(id, forKey: .id)
        case let .pong(id):
            try c.encode("pong", forKey: .type)
            try c.encode(id, forKey: .id)
        case let .clip(id, mime, text):
            try c.encode("clip", forKey: .type)
            try c.encode(id, forKey: .id)
            try c.encode(mime, forKey: .mime)
            try c.encode(text, forKey: .text)
        case let .fileOffer(transferId, name, size, sha256, mime):
            try c.encode("file_offer", forKey: .type)
            try c.encode(transferId, forKey: .transferId)
            try c.encode(name, forKey: .name)
            try c.encode(size, forKey: .size)
            try c.encode(sha256, forKey: .sha256)
            try c.encode(mime, forKey: .mime)
        case let .fileAccept(transferId, offset):
            try c.encode("file_accept", forKey: .type)
            try c.encode(transferId, forKey: .transferId)
            try c.encode(offset, forKey: .offset)
        case let .fileReject(transferId, reason):
            try c.encode("file_reject", forKey: .type)
            try c.encode(transferId, forKey: .transferId)
            try c.encode(reason, forKey: .reason)
        case let .fileDone(transferId):
            try c.encode("file_done", forKey: .type)
            try c.encode(transferId, forKey: .transferId)
        case let .fileResult(transferId, ok, reason):
            try c.encode("file_result", forKey: .type)
            try c.encode(transferId, forKey: .transferId)
            try c.encode(ok, forKey: .ok)
            try c.encodeIfPresent(reason, forKey: .reason)
        case let .fileCancel(transferId):
            try c.encode("file_cancel", forKey: .type)
            try c.encode(transferId, forKey: .transferId)
        case let .unknown(type):
            throw EncodingError.invalidValue(type, .init(codingPath: [], debugDescription: "unknown messages cannot be encoded"))
        }
    }
}

/// Typed access to a `JSONSerialization` object, where `NSNumber` bridges booleans, integers and floats.
private struct Members {
    let object: [String: Any]

    func isPresent(_ key: String) -> Bool {
        object[key].map { !($0 is NSNull) } ?? false
    }

    func string(_ key: String) throws -> String {
        guard let value = object[key] as? String else { throw ProtocolError("'\(key)' must be a string") }
        return value
    }

    func nonEmpty(_ key: String) throws -> String {
        let value = try string(key)
        guard !value.isEmpty else { throw ProtocolError("'\(key)' must not be empty") }
        return value
    }

    func boolean(_ key: String) throws -> Bool {
        guard let number = object[key] as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else {
            throw ProtocolError("'\(key)' must be a boolean")
        }
        return number.boolValue
    }

    func integer(_ key: String) throws -> Int {
        guard let number = object[key] as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(), !CFNumberIsFloatType(number),
              (0...Int64(WireFormat.maxInteger)).contains(number.int64Value) else {
            throw ProtocolError("'\(key)' must be an integer in 0...2^53-1")
        }
        return Int(number.int64Value)
    }

    func uuid(_ key: String) throws -> String {
        let value = try string(key)
        guard WireFormat.isUUID(value) else { throw ProtocolError("'\(key)' must be a lowercase uuid") }
        return value
    }

    func sha256hex(_ key: String) throws -> String {
        let value = try string(key)
        guard WireFormat.isSHA256Hex(value) else { throw ProtocolError("'\(key)' must be sha256 hex") }
        return value
    }

    func b64url32(_ key: String) throws -> String {
        let value = try string(key)
        guard Base64URL.decode32(value) != nil else { throw ProtocolError("'\(key)' must be b64url32") }
        return value
    }
}
