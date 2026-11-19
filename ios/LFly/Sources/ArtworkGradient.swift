import SwiftUI
import UIKit

/// Two colours sampled from the top and bottom of the artwork, shaded so white controls stay
/// legible: the phone's ArtworkGradient.kt, line by line.
struct ArtworkGradient: Equatable {
    var top: Color
    var bottom: Color

    static let fallback = ArtworkGradient(
        top: Color(rgb: shade(0x3A, 0x3A, 0x40, maxValue: 0.34, scale: 0.75)),
        bottom: Color(rgb: shade(0x0B, 0x0B, 0x0F, maxValue: 0.10, scale: 1))
    )

    /// The top and bottom 15% of a 64×64 copy, averaged, then shaded.
    static func from(_ image: UIImage) -> ArtworkGradient {
        let side = 64
        guard let cg = image.cgImage,
              let ctx = CGContext(data: nil, width: side, height: side, bitsPerComponent: 8, bytesPerRow: side * 4,
                                  space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
        else { return .fallback }
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: side, height: side))
        guard let data = ctx.data?.assumingMemoryBound(to: UInt8.self) else { return .fallback }
        // CGContext rows run bottom-up in memory order here: row 0 is the image's top.
        func band(_ from: Int, _ to: Int) -> (Int, Int, Int) {
            var r = 0, g = 0, b = 0, n = 0
            for y in from..<to {
                for x in 0..<side {
                    let p = (y * side + x) * 4
                    if data[p + 3] < 32 { continue }
                    r += Int(data[p]); g += Int(data[p + 1]); b += Int(data[p + 2]); n += 1
                }
            }
            return n == 0 ? (0x20, 0x20, 0x24) : (r / n, g / n, b / n)
        }
        let h = max(Int(Double(side) * 0.15), 1)
        let t = band(0, h)
        let bt = band(side - h, side)
        return ArtworkGradient(
            top: Color(rgb: shade(t.0, t.1, t.2, maxValue: 0.47, scale: 0.80)),
            bottom: Color(rgb: shade(bt.0, bt.1, bt.2, maxValue: 0.28, scale: 0.50))
        )
    }

    /// Keeps saturated artwork colourful while capping luminance (shadeRgb).
    static func shade(_ r0: Int, _ g0: Int, _ b0: Int, maxValue: Double, scale: Double) -> (Int, Int, Int) {
        let high = Double(max(r0, g0, b0)), low = Double(min(r0, g0, b0))
        let saturation = high <= 0 ? 0 : min((high - low) / high * 1.15, 1)
        let value = min(high * scale, maxValue * 255)
        func channel(_ c: Int) -> Int {
            let hue = high == low ? 0 : (Double(c) - low) / (high - low)
            return max(0, min(255, Int((value * (1 - saturation + saturation * hue)).rounded())))
        }
        var r = channel(r0), g = channel(g0), b = channel(b0)
        func linear(_ c: Int) -> Double {
            let n = Double(c) / 255
            return n <= 0.04045 ? n / 12.92 : pow((n + 0.055) / 1.055, 2.4)
        }
        while 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b) > 0.183 {
            r = Int((Double(r) * 0.98).rounded()); g = Int((Double(g) * 0.98).rounded()); b = Int((Double(b) * 0.98).rounded())
        }
        return (r, g, b)
    }
}

extension Color {
    init(rgb: (Int, Int, Int)) {
        self.init(red: Double(rgb.0) / 255, green: Double(rgb.1) / 255, blue: Double(rgb.2) / 255)
    }

    /// LFly's accent (the phone's default, emerald).
    static let lfly = Color(red: 0x30 / 255, green: 0xD1 / 255, blue: 0x58 / 255)
}

/// Covers' gradients and images, by URL, so a song change never samples the same picture twice.
@MainActor
final class ArtworkCache {
    static let shared = ArtworkCache()
    private var gradients: [String: ArtworkGradient] = [:]
    private var images: [String: UIImage] = [:]

    func gradient(_ url: String?) async -> ArtworkGradient {
        guard let url, let u = URL(string: url) else { return .fallback }
        if let g = gradients[url] { return g }
        guard let image = await image(url, u) else { return .fallback }
        let g = ArtworkGradient.from(image)
        gradients[url] = g
        return g
    }

    func clear() {
        gradients.removeAll()
        images.removeAll()
    }

    func image(_ url: String, _ u: URL) async -> UIImage? {
        if let i = images[url] { return i }
        guard let (data, _) = try? await URLSession.shared.data(from: u), let i = UIImage(data: data) else { return nil }
        if images.count > 40 { images.removeAll() }
        images[url] = i
        return i
    }
}
