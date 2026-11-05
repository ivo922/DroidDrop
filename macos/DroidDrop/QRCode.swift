import AppKit
import CoreImage
import CoreImage.CIFilterBuiltins

enum QRCode {
    static func image(for string: String) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)) else { return nil }
        let extent = output.extent.integral
        guard let cgImage = CIContext().createCGImage(output, from: extent) else { return nil }
        return NSImage(cgImage: cgImage, size: NSSize(width: extent.width, height: extent.height))
    }
}
