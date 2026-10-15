import Foundation
import Combine

/// 自選群組、到價提醒、設定，存在 App Group 的 UserDefaults（格式同 Android 版）。
struct StockGroup: Codable, Equatable {
    var name: String
    var codes: [String]
}

struct PriceAlert: Codable, Equatable, Identifiable {
    var id: String { code }
    var code: String
    var name: String
    var above: Double?          // 漲到 ≥
    var below: Double?          // 跌到 ≤
    var pct: Double?            // 漲跌幅絕對值 ≥ %
    var on: Bool = true
    var fired: String = ""      // 同一天只提醒一次（yyyyMMdd）

    var summary: String {
        var s: [String] = []
        if let a = above { s.append("≥" + Fmt.price(a)) }
        if let b = below { s.append("≤" + Fmt.price(b)) }
        if let p = pct { s.append("±" + Fmt.fix(p, 1) + "%") }
        return s.joined(separator: " ")
    }
}

final class Store: ObservableObject {
    static let group = "group.com.achir.xiaokestock"
    static let maxGroups = 10
    static let maxPerGroup = 60
    static let shared = Store()

    static var defaults: UserDefaults { UserDefaults(suiteName: group) ?? .standard }

    @Published var groups: [StockGroup] { didSet { save("groups", groups) } }
    @Published var current: Int { didSet { Store.defaults.set(current, forKey: "group") } }
    @Published var alerts: [PriceAlert] { didSet { save("alerts", alerts) } }
    @Published var refresh: Int { didSet { Store.defaults.set(refresh, forKey: "refresh") } }
    @Published var showPct: Bool { didSet { Store.defaults.set(showPct, forKey: "showPct") } }

    init() {
        let d = Store.defaults
        var gs = Store.load([StockGroup].self, "groups") ?? []
        if gs.isEmpty {
            gs = [StockGroup(name: "自選1", codes: ["2330", "2317", "2454", "2308", "0050", "0056", "00878", "NVDA"]),
                  StockGroup(name: "自選2", codes: [])]
        }
        groups = gs
        current = min(d.integer(forKey: "group"), gs.count - 1)
        alerts = Store.load([PriceAlert].self, "alerts") ?? []
        refresh = d.object(forKey: "refresh") as? Int ?? 10
        showPct = d.object(forKey: "showPct") as? Bool ?? true
        save("groups", groups)
    }

    var currentGroup: StockGroup { groups[max(0, min(current, groups.count - 1))] }

    /// Android 版存的是 JSON 字串，這裡也一樣
    private static func load<T: Decodable>(_ t: T.Type, _ key: String) -> T? {
        guard let s = defaults.string(forKey: key), let d = s.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(t, from: d)
    }

    private func save<T: Encodable>(_ key: String, _ v: T) {
        if let d = try? JSONEncoder().encode(v), let s = String(data: d, encoding: .utf8) {
            Store.defaults.set(s, forKey: key)
        }
    }

    // ───────── 自選操作 ─────────

    /// 加到目前群組；回傳提示文字
    func add(_ code: String, to gi: Int? = nil) -> String {
        let i = gi ?? max(0, min(current, groups.count - 1))
        if groups[i].codes.contains(code) { return "已在「\(groups[i].name)」" }
        if groups[i].codes.count >= Store.maxPerGroup { return "\(groups[i].name) 已滿 \(Store.maxPerGroup) 檔" }
        groups[i].codes.append(code)
        return "已加入「\(groups[i].name)」"
    }

    func move(_ code: String, _ how: Int) {   // 0 置頂、-1 上移、1 下移
        var cs = groups[current].codes
        guard let i = cs.firstIndex(of: code) else { return }
        if how == 0 { cs.remove(at: i); cs.insert(code, at: 0) }
        else if how == -1, i > 0 { cs.swapAt(i, i - 1) }
        else if how == 1, i < cs.count - 1 { cs.swapAt(i, i + 1) }
        groups[current].codes = cs
    }

    func remove(_ code: String) { groups[current].codes.removeAll { $0 == code } }
}
