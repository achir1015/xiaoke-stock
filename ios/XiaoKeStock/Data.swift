import Foundation

/// 所有公開資料來源：證交所 MIS 即時、Yahoo 圖表、Yahoo 新聞 RSS（同 Android Data.java）。
enum Net {
    static let ua = "Mozilla/5.0 (iPhone) XiaoKeStock/1.0"

    static func get(_ url: String) async throws -> Data {
        guard let u = URL(string: url) else { throw URLError(.badURL) }
        var r = URLRequest(url: u, timeoutInterval: 20)
        r.setValue(ua, forHTTPHeaderField: "User-Agent")
        r.setValue("zh-TW", forHTTPHeaderField: "Accept-Language")
        let (d, resp) = try await URLSession.shared.data(for: r)
        if let h = resp as? HTTPURLResponse, h.statusCode >= 400 { throw URLError(.badServerResponse) }
        return d
    }

    static func json(_ url: String) async throws -> Any {
        try JSONSerialization.jsonObject(with: try await get(url))
    }

    /// 路徑或參數用：只保留英數與 -._
    static func enc(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: .alphanumerics.union(CharacterSet(charactersIn: "-._"))) ?? s
    }
}

struct Quote {
    var code = "", name = "", market = ""     // market: tse / otc / idx / us
    var price = Double.nan, prev = Double.nan, open = Double.nan, high = Double.nan, low = Double.nan
    var volume = Double.nan                   // 台股：張；其他：股
    var limitUp = Double.nan, limitDown = Double.nan
    var time = ""
    var bid: [Double] = [], bidVol: [Double] = [], ask: [Double] = [], askVol: [Double] = []

    var change: Double { price - prev }
    var pct: Double { prev > 0 ? (price - prev) / prev * 100 : .nan }
    var isTw: Bool { market == "tse" || market == "otc" }
}

struct Bars {
    var t: [Int] = []
    var o: [Double] = [], h: [Double] = [], l: [Double] = [], c: [Double] = [], v: [Double] = []
    var prev = Double.nan
    var count: Int { t.count }
}

struct News: Identifiable {
    let id = UUID()
    var title: String, link: String, date: Date?
}

enum Quotes {
    /// 指數與國際行情：代號、顯示名稱
    static let indexes: [(String, String)] = [
        ("t00", "加權指數"), ("o00", "櫃買指數"), ("^DJI", "道瓊工業"), ("^IXIC", "那斯達克"),
        ("^GSPC", "S&P 500"), ("^SOX", "費城半導體"), ("^N225", "日經 225"), ("^HSI", "恆生指數"),
        ("^KS11", "韓國綜合"), ("000001.SS", "上證指數"), ("TWD=X", "美元/台幣"), ("JPYTWD=X", "日圓/台幣"),
        ("GC=F", "黃金期貨"), ("CL=F", "原油期貨"), ("BTC-USD", "比特幣"),
    ]

    static func indexName(_ code: String) -> String? { indexes.first { $0.0 == code }?.1 }

    static func isTw(_ code: String) -> Bool {
        code.range(of: "^\\d{4,6}[A-Z]?$", options: .regularExpression) != nil
    }

    /// 抓一批代號的報價：台股走 MIS（一次最多 50 個 key），其他走 Yahoo。回傳 code → Quote
    static func fetch(_ codes: [String]) async -> [String: Quote] {
        var keys: [String] = [], others: [String] = []
        for code in codes {
            if code == "t00" { keys.append("tse_t00.tw") }
            else if code == "o00" { keys.append("otc_o00.tw") }
            else if isTw(code) {
                let m = Market.shared.marketOf(code)
                if m == nil || m == "tse" { keys.append("tse_\(code).tw") }
                if m == nil || m == "otc" { keys.append("otc_\(code).tw") }
            } else { others.append(code) }
        }
        var out: [String: Quote] = [:]
        var i = 0
        while i < keys.count {
            let part = keys[i..<min(keys.count, i + 50)].joined(separator: "|")
            if let m = try? await mis(part) { out.merge(m) { $1 } }
            i += 50
        }
        await withTaskGroup(of: Quote?.self) { g in
            for s in others { g.addTask { try? await yahooQuote(s) } }
            for await q in g { if let q { out[q.code] = q } }
        }
        return out
    }

    private static func mis(_ exCh: String) async throws -> [String: Quote] {
        let url = "https://mis.twse.com.tw/stock/api/getStockInfo.jsp?json=1&delay=0&_=\(Int(Date().timeIntervalSince1970 * 1000))&ex_ch="
            + exCh.replacingOccurrences(of: "|", with: "%7C")
        guard let root = try await Net.json(url) as? [String: Any],
              let arr = root["msgArray"] as? [[String: Any]] else { return [:] }
        var out: [String: Quote] = [:]
        for o in arr {
            var q = Quote()
            q.code = o["c"] as? String ?? ""
            if q.code.isEmpty { continue }        // 無效的 key（上市/上櫃猜錯）
            q.name = o["n"] as? String ?? q.code
            q.market = (q.code == "t00" || q.code == "o00") ? "idx" : (o["ex"] as? String ?? "")
            q.prev = num(o["y"]); q.open = num(o["o"]); q.high = num(o["h"]); q.low = num(o["l"])
            q.volume = num(o["v"]); q.limitUp = num(o["u"]); q.limitDown = num(o["w"])
            q.time = o["t"] as? String ?? ""
            q.bid = split(o["b"]); q.bidVol = split(o["g"]); q.ask = split(o["a"]); q.askVol = split(o["f"])
            var z = num(o["z"])
            if z.isNaN { z = num(o["pz"]) }                       // 這一筆沒成交：用上一筆
            if z.isNaN, let b = q.bid.first { z = b }
            if z.isNaN { z = q.prev }                             // 開盤前
            q.price = z
            if q.code == "t00" { q.name = "加權指數" }
            if q.code == "o00" { q.name = "櫃買指數" }
            out[q.code] = q
        }
        return out
    }

    private static func split(_ any: Any?) -> [Double] {
        guard let s = any as? String, !s.isEmpty, s != "-" else { return [] }
        return s.split(separator: "_").map { num(String($0)) }.filter { !$0.isNaN }
    }

    /// Yahoo 代號：上市 .TW、上櫃 .TWO、指數 ^TWII / ^TWOII，其他原樣
    static func yahooSymbol(_ code: String, market: String? = nil) -> String {
        if code == "t00" { return "^TWII" }
        if code == "o00" { return "^TWOII" }
        if isTw(code) {
            let m = market ?? Market.shared.marketOf(code)
            return code + (m == "otc" ? ".TWO" : ".TW")
        }
        return code
    }

    private static func chart(_ symbol: String, _ range: String, _ interval: String) async throws -> [String: Any] {
        let url = "https://query1.finance.yahoo.com/v8/finance/chart/\(Net.enc(symbol))?range=\(range)&interval=\(interval)&includePrePost=false"
        guard let r = try await Net.json(url) as? [String: Any], let c = r["chart"] as? [String: Any],
              let res = c["result"] as? [[String: Any]], let f = res.first else { throw URLError(.cannotParseResponse) }
        return f
    }

    static func yahooQuote(_ symbol: String) async throws -> Quote {
        guard let meta = try await chart(symbol, "1d", "1d")["meta"] as? [String: Any] else { throw URLError(.cannotParseResponse) }
        var q = Quote()
        q.code = symbol
        q.name = indexName(symbol) ?? (meta["shortName"] as? String ?? meta["longName"] as? String ?? symbol)
        q.market = "us"
        q.price = num(meta["regularMarketPrice"])
        q.prev = num(meta["previousClose"])
        if q.prev.isNaN { q.prev = num(meta["chartPreviousClose"]) }
        q.high = num(meta["regularMarketDayHigh"])
        q.low = num(meta["regularMarketDayLow"])
        q.volume = num(meta["regularMarketVolume"])
        if let t = meta["regularMarketTime"] as? NSNumber {
            let f = DateFormatter()
            f.dateFormat = "MM/dd HH:mm"
            q.time = f.string(from: Date(timeIntervalSince1970: t.doubleValue))
        }
        return q
    }

    /// range/interval 例：1d/1m 分時、2y/1d 日K、10y/1wk 週K、max/1mo 月K
    static func bars(_ symbol: String, _ range: String, _ interval: String) async throws -> Bars {
        let r = try await chart(symbol, range, interval)
        var b = Bars()
        if let meta = r["meta"] as? [String: Any] { b.prev = num(meta["chartPreviousClose"]) }
        guard let ts = r["timestamp"] as? [Any],
              let ind = r["indicators"] as? [String: Any], let qs = ind["quote"] as? [[String: Any]], let q = qs.first
        else { return b }
        let o = q["open"] as? [Any] ?? [], h = q["high"] as? [Any] ?? [], l = q["low"] as? [Any] ?? [],
            c = q["close"] as? [Any] ?? [], v = q["volume"] as? [Any] ?? []
        func at(_ a: [Any], _ i: Int) -> Double { i < a.count ? num(a[i]) : .nan }
        for i in 0..<ts.count {
            let close = at(c, i)
            if close.isNaN { continue }           // 沒成交的那一分鐘
            b.t.append((ts[i] as? NSNumber)?.intValue ?? 0)
            b.c.append(close)
            let oo = at(o, i), hh = at(h, i), ll = at(l, i), vv = at(v, i)
            b.o.append(oo.isNaN ? close : oo)
            b.h.append(hh.isNaN ? close : hh)
            b.l.append(ll.isNaN ? close : ll)
            b.v.append(vv.isNaN ? 0 : vv)
        }
        if symbol.hasSuffix(".TW") || symbol.hasSuffix(".TWO") {   // 台股成交量：股 → 張
            b.v = b.v.map { $0 / 1000 }
        }
        return b
    }

    // ───────── 新聞 ─────────

    static func news(_ category: String) async throws -> [News] {
        let xml = String(decoding: try await Net.get("https://tw.stock.yahoo.com/rss?category=\(category)"), as: UTF8.self)
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        var out: [News] = []
        for part in xml.components(separatedBy: "<item>").dropFirst() {
            let item = part.components(separatedBy: "</item>").first ?? part
            guard let t = tag(item, "title"), let l = tag(item, "link") else { continue }
            out.append(News(title: t, link: l, date: tag(item, "pubDate").flatMap { f.date(from: $0) }))
        }
        return out
    }

    private static func tag(_ s: String, _ t: String) -> String? {
        guard let a = s.range(of: "<\(t)>"), let b = s.range(of: "</\(t)>"), a.upperBound <= b.lowerBound else { return nil }
        var v = String(s[a.upperBound..<b.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)
        if v.hasPrefix("<![CDATA["), v.hasSuffix("]]>") { v = String(v.dropFirst(9).dropLast(3)) }
        return v.replacingOccurrences(of: "&amp;", with: "&").replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&#39;", with: "'").replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">").trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
