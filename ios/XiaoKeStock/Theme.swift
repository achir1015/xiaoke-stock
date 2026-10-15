import SwiftUI

/// 共用顏色與數字格式（同 Android Ui.java）。台股慣例：漲紅跌綠。
enum Theme {
    static let bg = Color(hex: 0x101522)
    static let card = Color(hex: 0x1A2133)
    static let line = Color(hex: 0x2A3247)
    static let text = Color(hex: 0xE8ECF4)
    static let sub = Color(hex: 0x8A93A8)
    static let up = Color(hex: 0xFF4D4F)
    static let down = Color(hex: 0x22C55E)
    static let flat = Color(hex: 0xE8ECF4)
    static let accent = Color(hex: 0xFFB020)
    static let pink = Color(hex: 0xFF6F91)
    static let blue = Color(hex: 0x5EA8FF)

    static func color(_ change: Double) -> Color {
        change > 0 ? up : change < 0 ? down : flat
    }
}

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255, opacity: alpha)
    }
}

enum Fmt {
    /// 股價 1000 以上一位小數，一萬以上加千分位
    static func price(_ p: Double) -> String {
        if p.isNaN { return "--" }
        if abs(p) >= 10000 {
            let f = NumberFormatter()
            f.numberStyle = .decimal
            f.minimumFractionDigits = 2
            f.maximumFractionDigits = 2
            return f.string(from: NSNumber(value: p)) ?? String(format: "%.2f", p)
        }
        if abs(p) >= 1000 { return String(format: "%.1f", p) }
        return String(format: "%.2f", p)
    }

    static func change(_ c: Double) -> String {
        if c.isNaN { return "--" }
        let s = price(abs(c))
        return c > 0 ? "▲" + s : c < 0 ? "▼" + s : s
    }

    static func pct(_ p: Double) -> String {
        p.isNaN ? "--" : String(format: "%+.2f%%", p)
    }

    static func vol(_ v: Double) -> String {
        if v.isNaN { return "--" }
        if v >= 1e8 { return String(format: "%.2f億", v / 1e8) }
        if v >= 1e4 { return String(format: "%.1f萬", v / 1e4) }
        return String(format: "%.0f", v)
    }

    static func fix(_ v: Double, _ digits: Int = 2) -> String {
        v.isNaN ? "--" : String(format: "%.\(digits)f", v)
    }
}

/// 字串或數字轉 Double；"-"、空字串、null 一律 NaN
func num(_ any: Any?) -> Double {
    if let n = any as? NSNumber { return n.doubleValue }
    guard var s = any as? String else { return .nan }
    s = s.replacingOccurrences(of: ",", with: "").replacingOccurrences(of: "+", with: "")
        .trimmingCharacters(in: .whitespaces)
    if s.isEmpty || s == "-" || s == "--" { return .nan }
    return Double(s) ?? .nan
}
