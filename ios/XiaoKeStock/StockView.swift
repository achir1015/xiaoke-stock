import SwiftUI
import WidgetKit

/// 個股頁：即時報價、基本資料、最佳五檔、分時走勢、日/週/月 K。
struct StockView: View {
    @EnvironmentObject var store: Store
    let code: String

    @State private var quote: Quote?
    @State private var daily: MarketRow?
    @State private var symbol: String?
    @State private var mode = UserDefaults.standard.integer(forKey: "chartMode")   // 0 走勢、1 日K、2 週K、3 月K（測試可用啟動參數 -chartMode）
    @State private var kd = true
    @State private var chart: ChartData?
    @State private var chartError = false
    @State private var showGroups = false
    @State private var showAlert = false

    private let specs = [("1d", "1m"), ("2y", "1d"), ("10y", "1wk"), ("max", "1mo")]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                header
                HStack(spacing: 6) {
                    action("＋ 加入自選") { showGroups = true }
                    action("🔔 到價提醒") { showAlert = true }
                    action("🤖 小柯唸") { if let q = quote { Speaker.say(Speaker.describe(q)) } }
                }
                HStack(spacing: 4) {
                    Segments(items: ["走勢", "日K", "週K", "月K"], selected: $mode)
                    if mode > 0 {
                        Segments(items: ["KD", "MACD"], selected: Binding(get: { kd ? 0 : 1 }, set: { kd = $0 == 0 }))
                            .frame(width: 120)
                    }
                }
                ZStack {
                    ChartView(data: chart, kd: kd).frame(height: 380)
                    if chartError { Text("圖表資料載入失敗").foregroundColor(Theme.sub) }
                }
                infoCard
                bookCard
                Text("資料來源：證交所 MIS、證交所/櫃買中心 OpenAPI、Yahoo Finance。僅供參考，非投資建議。")
                    .font(.system(size: 11)).foregroundColor(Theme.sub)
            }
            .padding(10)
        }
        .background(Theme.bg)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: store.refresh) {
            var first = true
            while !Task.isCancelled {
                await loadQuote(first: first)
                first = false
                try? await Task.sleep(for: .seconds(store.refresh))
            }
        }
        .task(id: "\(mode)-\(symbol ?? "")") { await loadChart() }
        .sheet(isPresented: $showGroups) { GroupPicker(code: code).presentationDetents([.medium]) }
        .sheet(isPresented: $showAlert) { AlertEditor(code: code, name: quote?.name ?? code, current: quote).presentationDetents([.medium, .large]) }
    }

    private var title: String {
        if let q = quote {
            let mk = q.market == "tse" ? "上市" : q.market == "otc" ? "上櫃" : q.market == "idx" ? "指數" : "海外"
            return "\(q.name)  \(q.code == q.name ? "" : q.code)  \(mk)"
        }
        if let d = daily { return "\(d.name)  \(d.code)" }
        return code
    }

    private var header: some View {
        let price = quote?.price ?? daily?.close ?? .nan
        let ch = quote?.change ?? daily?.change ?? .nan
        let pct = quote?.pct ?? daily?.pct ?? .nan
        let col = Theme.color(ch)
        return HStack(alignment: .center, spacing: 14) {
            Text(Fmt.price(price)).font(.system(size: 34, weight: .bold)).foregroundColor(col)
                .lineLimit(1).minimumScaleFactor(0.5)
            VStack(alignment: .leading, spacing: 2) {
                Text("\(Fmt.change(ch))  \(Fmt.pct(pct))").font(.system(size: 16, weight: .bold)).foregroundColor(col)
                Text(quote.map { $0.time.isEmpty ? "" : "更新 \($0.time)" } ?? daily.map { "收盤 \(Market.roc($0.date))" } ?? "")
                    .font(.system(size: 12)).foregroundColor(Theme.sub)
            }
        }
    }

    private func action(_ t: String, _ f: @escaping () -> Void) -> some View {
        Button(action: f) {
            Text(t).font(.system(size: 14, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.7)
                .frame(maxWidth: .infinity).padding(.vertical, 9)
                .foregroundColor(Theme.text).background(RoundedRectangle(cornerRadius: 8).fill(Theme.line))
        }
        .buttonStyle(.plain)
    }

    private var infoCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("行情資訊").font(.system(size: 15, weight: .bold)).foregroundColor(Theme.accent)
            if let q = quote {
                let amp = q.prev > 0 ? (q.high - q.low) / q.prev * 100 : .nan
                pair("開盤", Fmt.price(q.open), Theme.color(q.open - q.prev), "昨收", Fmt.price(q.prev), Theme.text)
                pair("最高", Fmt.price(q.high), Theme.color(q.high - q.prev), "最低", Fmt.price(q.low), Theme.color(q.low - q.prev))
                pair(q.isTw ? "總量(張)" : "成交量", Fmt.vol(q.volume), Theme.text, "振幅", amp.isNaN ? "--" : String(format: "%.2f%%", amp), Theme.text)
                if q.isTw {
                    pair("漲停", Fmt.price(q.limitUp), Theme.up, "跌停", Fmt.price(q.limitDown), Theme.down)
                }
            }
            if let r = daily, quote?.isTw ?? true {
                pair("本益比", Fmt.fix(r.pe), Theme.text, "殖利率", r.yld.isNaN ? "--" : Fmt.fix(r.yld) + "%", Theme.text)
                pair("股價淨值比", Fmt.fix(r.pb), Theme.text, "資料日", Market.roc(r.date), Theme.sub)
            }
        }
        .padding(12).background(RoundedRectangle(cornerRadius: 12).fill(Theme.card))
    }

    private func pair(_ k1: String, _ v1: String, _ c1: Color, _ k2: String, _ v2: String, _ c2: Color) -> some View {
        HStack {
            Text(k1).font(.system(size: 13)).foregroundColor(Theme.sub)
            Spacer()
            Text(v1).font(.system(size: 14, weight: .bold)).foregroundColor(c1).lineLimit(1).minimumScaleFactor(0.6)
            Spacer().frame(width: 20)
            Text(k2).font(.system(size: 13)).foregroundColor(Theme.sub)
            Spacer()
            Text(v2).font(.system(size: 14, weight: .bold)).foregroundColor(c2).lineLimit(1).minimumScaleFactor(0.6)
        }
    }

    private var bookCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("最佳五檔").font(.system(size: 15, weight: .bold)).foregroundColor(Theme.accent)
            if let q = quote, !(q.bid.isEmpty && q.ask.isEmpty) {
                Grid(horizontalSpacing: 6, verticalSpacing: 6) {
                    GridRow {
                        ForEach(["委買量", "買價", "賣價", "委賣量"], id: \.self) {
                            Text($0).font(.system(size: 12)).foregroundColor(Theme.sub).frame(maxWidth: .infinity)
                        }
                    }
                    ForEach(0..<5, id: \.self) { i in
                        GridRow {
                            cell(i < q.bidVol.count ? Fmt.vol(q.bidVol[i]) : "", Theme.text)
                            cell(i < q.bid.count ? Fmt.price(q.bid[i]) : "", i < q.bid.count ? Theme.color(q.bid[i] - q.prev) : Theme.text)
                            cell(i < q.ask.count ? Fmt.price(q.ask[i]) : "", i < q.ask.count ? Theme.color(q.ask[i] - q.prev) : Theme.text)
                            cell(i < q.askVol.count ? Fmt.vol(q.askVol[i]) : "", Theme.text)
                        }
                    }
                }
            } else {
                Text(quote?.isTw ?? true ? "目前沒有委買委賣資料" : "海外商品不提供五檔").font(.system(size: 13)).foregroundColor(Theme.sub)
            }
        }
        .padding(12).background(RoundedRectangle(cornerRadius: 12).fill(Theme.card))
    }

    private func cell(_ s: String, _ c: Color) -> some View {
        Text(s).font(.system(size: 14)).foregroundColor(c).frame(maxWidth: .infinity)
    }

    private func loadQuote(first: Bool) async {
        if first { daily = Market.shared.get(code) }
        if let q = await Quotes.fetch([code])[code] {
            quote = q
            if symbol == nil {
                symbol = q.market == "tse" ? code + ".TW" : q.market == "otc" ? code + ".TWO" : Quotes.yahooSymbol(code)
            }
        } else if first, symbol == nil {
            // 沒有即時報價（例如 MIS 連不上）時，用最近一天的收盤資料
            symbol = Quotes.yahooSymbol(code, market: daily?.market)
        }
    }

    private func loadChart() async {
        guard let sym = symbol else { return }
        let m = mode
        chartError = false
        do {
            var b = try await Quotes.bars(sym, specs[m].0, specs[m].1)
            if m == 0 && b.count == 0 { b = try await Quotes.bars(sym, "5d", "5m") }   // 休市日：顯示最近幾天
            guard m == mode else { return }
            chart = m == 0 ? .intraday(b, prev: quote?.prev ?? b.prev) : .kline(b, fmt: m == 3 ? "yyyy/MM" : "yy/MM/dd")
        } catch {
            if m == mode { chart = nil; chartError = true }
        }
    }
}

/// 加入自選：勾選要放進哪些群組
struct GroupPicker: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    let code: String

    var body: some View {
        NavigationStack {
            List {
                ForEach(store.groups.indices, id: \.self) { i in
                    let has = store.groups[i].codes.contains(code)
                    Button {
                        if has { store.groups[i].codes.removeAll { $0 == code } }
                        else if store.groups[i].codes.count < Store.maxPerGroup { store.groups[i].codes.append(code) }
                        WidgetCenter.shared.reloadAllTimelines()
                    } label: {
                        HStack {
                            Text("\(store.groups[i].name)（\(store.groups[i].codes.count)）").foregroundColor(Theme.text)
                            Spacer()
                            Image(systemName: has ? "checkmark.circle.fill" : "circle").foregroundColor(has ? Theme.accent : Theme.sub)
                        }
                    }
                }
            }
            .navigationTitle("加入自選群組").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
        }
    }
}

/// 到價提醒設定
struct AlertEditor: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    let code: String
    let name: String
    let current: Quote?
    @State private var above = ""
    @State private var below = ""
    @State private var pct = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("漲到（≥ 價格）", text: $above).keyboardType(.decimalPad)
                    TextField("跌到（≤ 價格）", text: $below).keyboardType(.decimalPad)
                    TextField("漲跌幅超過（%）", text: $pct).keyboardType(.decimalPad)
                } footer: {
                    VStack(alignment: .leading, spacing: 4) {
                        if let q = current { Text("目前 \(Fmt.price(q.price))（\(Fmt.pct(q.pct))）") }
                        Text("App 開著時每分鐘檢查；在背景時由 iOS 決定檢查時間，不保證準時。台股只在平日 09:00–13:35 檢查，同一檔同一天只提醒一次。")
                    }
                }
                if store.alerts.contains(where: { $0.code == code }) {
                    Button("刪除這個提醒", role: .destructive) {
                        store.alerts.removeAll { $0.code == code }
                        dismiss()
                    }
                }
            }
            .navigationTitle("到價提醒：\(name)").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("儲存") { save() } }
            }
            .onAppear {
                if let a = store.alerts.first(where: { $0.code == code }) {
                    above = a.above.map { String($0) } ?? ""
                    below = a.below.map { String($0) } ?? ""
                    pct = a.pct.map { String($0) } ?? ""
                }
            }
        }
    }

    private func save() {
        func opt(_ s: String) -> Double? { let v = num(s); return v.isNaN ? nil : v }
        var list = store.alerts.filter { $0.code != code }
        let a = PriceAlert(code: code, name: name, above: opt(above), below: opt(below), pct: opt(pct))
        if a.above != nil || a.below != nil || a.pct != nil { list.append(a) }
        store.alerts = list
        AlertChecker.requestPermission()
        AlertChecker.schedule()
        dismiss()
    }
}
