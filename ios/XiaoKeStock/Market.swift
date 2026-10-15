import Foundation

/// 全市場日資料（上市＋上櫃）：股票清單、收盤、漲跌、成交量值、本益比、殖利率、淨值比。
/// 來源是證交所與櫃買中心 OpenAPI；快取 6 小時，存在 App Group 的 market_v2.json（小工具也讀得到）。
struct MarketRow: Identifiable {
    var id: String { code }
    var code = "", name = "", market = "", date = ""
    var close = Double.nan, change = Double.nan, open = Double.nan, high = Double.nan, low = Double.nan
    var volume = Double.nan, value = Double.nan, pe = Double.nan, yld = Double.nan, pb = Double.nan

    var pct: Double {
        let prev = close - change
        return prev > 0 ? change / prev * 100 : .nan
    }
    var isEtf: Bool { code.hasPrefix("00") }
}

final class Market: @unchecked Sendable {
    static let shared = Market()

    private let lock = NSLock()
    private var rows: [MarketRow] = []
    private var byCode: [String: MarketRow] = [:]
    private var loaded = false

    /// 只收股票與 ETF：4 碼（含特別股 2881A）或 00 開頭的 ETF；權證、牛熊證不收
    private static let stockPattern = "^(\\d{4}[A-Z]?|00\\d{2,4}[A-Z]?)$"

    private var fileURL: URL {
        let base = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: Store.group)
            ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base.appendingPathComponent("market_v2.json")
    }

    func all() -> [MarketRow] {
        lock.lock(); defer { lock.unlock() }
        loadLocked()
        return rows
    }

    func get(_ code: String) -> MarketRow? {
        lock.lock(); defer { lock.unlock() }
        loadLocked()
        return byCode[code]
    }

    func marketOf(_ code: String) -> String? { get(code)?.market }

    /// 資料日期，民國 1151014 → 115/10/14；上市與上櫃日期不同時兩個都列
    func date() -> String {
        let l = all()
        let tse = l.first { $0.market == "tse" }?.date ?? ""
        let otc = l.first { $0.market == "otc" }?.date ?? ""
        if tse == otc || otc.isEmpty { return Market.roc(tse) }
        if tse.isEmpty { return Market.roc(otc) }
        return "上市 \(Market.roc(tse))、上櫃 \(Market.roc(otc))"
    }

    static func roc(_ d: String) -> String {
        guard d.count >= 7 else { return d }
        let n = d.count
        let a = d.prefix(n - 4), b = d.dropFirst(n - 4).prefix(2), c = d.suffix(2)
        return "\(a)/\(b)/\(c)"
    }

    /// 代號或名稱搜尋（最多 60 筆）：完全符合 → 開頭符合 → 名稱包含
    func search(_ q: String) -> [MarketRow] {
        let q = q.trimmingCharacters(in: .whitespaces).uppercased()
        if q.isEmpty { return [] }
        var exact: [MarketRow] = [], starts: [MarketRow] = [], contains: [MarketRow] = []
        for r in all() {
            if r.code == q || r.name == q { exact.append(r) }
            else if r.code.hasPrefix(q) || r.name.hasPrefix(q) { starts.append(r) }
            else if r.name.uppercased().contains(q) { contains.append(r) }
        }
        return Array((exact + starts + contains).prefix(60))
    }

    /// 快取超過 6 小時就重抓
    @discardableResult
    func refresh(force: Bool = false) async -> Bool {
        if !force, let attr = try? FileManager.default.attributesOfItem(atPath: fileURL.path),
           let m = attr[.modificationDate] as? Date, Date().timeIntervalSince(m) < 6 * 3600 {
            _ = all()
            return true
        }
        do {
            var list: [MarketRow] = []
            var map: [String: MarketRow] = [:]
            func ok(_ code: String) -> Bool { code.range(of: Market.stockPattern, options: .regularExpression) != nil }

            let a = try await Net.json("https://openapi.twse.com.tw/v1/exchangeReport/STOCK_DAY_ALL") as? [[String: Any]] ?? []
            for o in a {
                var r = MarketRow()
                r.market = "tse"
                r.code = (o["Code"] as? String ?? "").trimmingCharacters(in: .whitespaces)
                r.name = (o["Name"] as? String ?? "").trimmingCharacters(in: .whitespaces)
                r.date = o["Date"] as? String ?? ""
                r.close = num(o["ClosingPrice"]); r.change = num(o["Change"])
                r.open = num(o["OpeningPrice"]); r.high = num(o["HighestPrice"]); r.low = num(o["LowestPrice"])
                r.volume = num(o["TradeVolume"]) / 1000            // 股 → 張
                r.value = num(o["TradeValue"])
                if !ok(r.code) || r.close.isNaN { continue }
                if r.change.isNaN { r.change = 0 }
                list.append(r); map[r.code] = r
            }
            let b = try await Net.json("https://www.tpex.org.tw/openapi/v1/tpex_mainboard_daily_close_quotes") as? [[String: Any]] ?? []
            for o in b {
                var r = MarketRow()
                r.market = "otc"
                r.code = (o["SecuritiesCompanyCode"] as? String ?? "").trimmingCharacters(in: .whitespaces)
                r.name = (o["CompanyName"] as? String ?? "").trimmingCharacters(in: .whitespaces)
                r.date = o["Date"] as? String ?? ""
                r.close = num(o["Close"]); r.change = num(o["Change"])
                r.open = num(o["Open"]); r.high = num(o["High"]); r.low = num(o["Low"])
                r.volume = num(o["TradingShares"]) / 1000
                r.value = num(o["TransactionAmount"])
                if !ok(r.code) || r.close.isNaN { continue }
                if r.change.isNaN { r.change = 0 }
                list.append(r); map[r.code] = r
            }
            // 本益比、殖利率、淨值比（抓不到不影響行情）
            if let p = try? await Net.json("https://openapi.twse.com.tw/v1/exchangeReport/BWIBBU_ALL") as? [[String: Any]] {
                for o in p {
                    let code = (o["Code"] as? String ?? "").trimmingCharacters(in: .whitespaces)
                    guard var r = map[code] else { continue }
                    r.pe = num(o["PEratio"]); r.yld = num(o["DividendYield"]); r.pb = num(o["PBratio"])
                    map[code] = r
                }
            }
            if let p = try? await Net.json("https://www.tpex.org.tw/openapi/v1/tpex_mainboard_peratio_analysis") as? [[String: Any]] {
                for o in p {
                    let code = (o["SecuritiesCompanyCode"] as? String ?? "").trimmingCharacters(in: .whitespaces)
                    guard var r = map[code] else { continue }
                    r.pe = num(o["PriceEarningRatio"]); r.yld = num(o["YieldRatio"]); r.pb = num(o["PriceBookRatio"])
                    map[code] = r
                }
            }
            list = list.map { map[$0.code] ?? $0 }
            if list.isEmpty { return false }
            save(list)
            lock.lock()
            rows = list; byCode = map; loaded = true
            lock.unlock()
            return true
        } catch {
            _ = all()
            return false
        }
    }

    private func save(_ list: [MarketRow]) {
        func n(_ d: Double) -> Any { d.isNaN || d.isInfinite ? NSNull() : d }
        let arr: [[Any]] = list.map {
            [$0.code, $0.name, $0.market, $0.date, n($0.close), n($0.change), n($0.open), n($0.high), n($0.low),
             n($0.volume), n($0.value), n($0.pe), n($0.yld), n($0.pb)]
        }
        if let d = try? JSONSerialization.data(withJSONObject: arr) { try? d.write(to: fileURL, options: .atomic) }
    }

    /// 呼叫前要先持有 lock
    private func loadLocked() {
        if loaded { return }
        loaded = true
        guard let d = try? Data(contentsOf: fileURL), let arr = try? JSONSerialization.jsonObject(with: d) as? [[Any]] else { return }
        var list: [MarketRow] = []
        for x in arr where x.count >= 14 {
            var r = MarketRow()
            r.code = x[0] as? String ?? ""; r.name = x[1] as? String ?? ""; r.market = x[2] as? String ?? ""; r.date = x[3] as? String ?? ""
            r.close = num(x[4]); r.change = num(x[5]); r.open = num(x[6]); r.high = num(x[7]); r.low = num(x[8])
            r.volume = num(x[9]); r.value = num(x[10]); r.pe = num(x[11]); r.yld = num(x[12]); r.pb = num(x[13])
            list.append(r)
        }
        rows = list
        byCode = Dictionary(list.map { ($0.code, $0) }, uniquingKeysWith: { a, _ in a })
    }
}
