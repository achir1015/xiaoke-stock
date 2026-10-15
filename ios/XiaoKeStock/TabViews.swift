import SwiftUI
import WidgetKit

// ═════════════ 自選 ═════════════

struct WatchView: View {
    @EnvironmentObject var store: Store
    @State private var quotes: [String: Quote] = [:]
    @State private var renaming: Int?          // -1 = 新增
    @State private var newName = ""
    @State private var toast: String?

    var body: some View {
        VStack(spacing: 0) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(store.groups.indices, id: \.self) { i in
                        Button {
                            store.current = i
                            WidgetCenter.shared.reloadAllTimelines()
                        } label: {
                            Text(store.groups[i].name).font(.system(size: 14, weight: .semibold))
                                .padding(.horizontal, 14).padding(.vertical, 7)
                                .foregroundColor(i == store.current ? Theme.bg : Theme.text)
                                .background(RoundedRectangle(cornerRadius: 8).fill(i == store.current ? Theme.accent : Theme.line))
                        }
                        .buttonStyle(.plain)
                        .contextMenu {
                            Button("改名") { newName = store.groups[i].name; renaming = i }
                            Button("刪除群組", role: .destructive) {
                                if store.groups.count > 1 {
                                    store.groups.remove(at: i)
                                    store.current = 0
                                } else { toast = "至少要保留一個群組" }
                            }
                        }
                    }
                    Button {
                        if store.groups.count >= Store.maxGroups { toast = "最多 \(Store.maxGroups) 個群組" }
                        else { newName = "自選\(store.groups.count + 1)"; renaming = -1 }
                    } label: {
                        Image(systemName: "plus").padding(.horizontal, 14).padding(.vertical, 9)
                            .background(RoundedRectangle(cornerRadius: 8).fill(Theme.line))
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 10).padding(.vertical, 6)
            }
            HStack {
                Text("長按股票可排序/刪除，長按群組可改名").font(.system(size: 11)).foregroundColor(Theme.sub)
                Spacer()
                Button("🤖 小柯唸自選") { speak() }.font(.system(size: 13, weight: .semibold))
            }
            .padding(.horizontal, 12).padding(.bottom, 4)

            List {
                if store.currentGroup.codes.isEmpty {
                    Text("這個群組還沒有股票。\n按右上角 🔍 輸入代號或名稱加入。")
                        .foregroundColor(Theme.sub).frame(maxWidth: .infinity).multilineTextAlignment(.center)
                        .listRowBackground(Theme.bg)
                }
                ForEach(store.currentGroup.codes, id: \.self) { code in
                    NavigationLink(value: code) { row(code) }
                        .listRowBackground(Theme.bg)
                        .contextMenu {
                            Button("置頂") { store.move(code, 0) }
                            Button("上移") { store.move(code, -1) }
                            Button("下移") { store.move(code, 1) }
                            Button("從群組刪除", role: .destructive) { store.remove(code) }
                        }
                }
                .onMove { from, to in store.groups[store.current].codes.move(fromOffsets: from, toOffset: to) }
                .onDelete { idx in store.groups[store.current].codes.remove(atOffsets: idx) }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .refreshable { await load() }
        }
        .task(id: "\(store.current)-\(store.currentGroup.codes)-\(store.refresh)") {
            while !Task.isCancelled {
                await load()
                try? await Task.sleep(for: .seconds(store.refresh))
            }
        }
        .alert(renaming == -1 ? "新增群組" : "群組改名", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("名稱", text: $newName)
            Button("確定") {
                let n = newName.trimmingCharacters(in: .whitespaces)
                if !n.isEmpty, let r = renaming {
                    if r < 0 { store.groups.append(StockGroup(name: n, codes: [])); store.current = store.groups.count - 1 }
                    else { store.groups[r].name = n }
                }
                renaming = nil
            }
            Button("取消", role: .cancel) { renaming = nil }
        }
        .alert(toast ?? "", isPresented: Binding(get: { toast != nil }, set: { if !$0 { toast = nil } })) { Button("好") {} }
    }

    @ViewBuilder private func row(_ code: String) -> some View {
        if let q = quotes[code] {
            StockRow(code: code, name: q.name, price: q.price, change: q.change, pct: q.pct, extra: "量 " + Fmt.vol(q.volume))
        } else if let r = Market.shared.get(code) {
            StockRow(code: code, name: r.name, price: r.close, change: r.change, pct: r.pct, extra: "收盤")
        } else {
            StockRow(code: code, name: Quotes.indexName(code) ?? code, price: .nan, change: 0, pct: .nan)
        }
    }

    private func load() async {
        let codes = store.currentGroup.codes
        if codes.isEmpty { return }
        let m = await Quotes.fetch(codes)
        quotes.merge(m) { $1 }
    }

    private func speak() {
        var s = "小柯報告\(store.currentGroup.name)。"
        var n = 0
        for c in store.currentGroup.codes {
            guard let q = quotes[c] else { continue }
            s += Speaker.describe(q)
            n += 1
            if n >= 10 { break }
        }
        if n == 0 { s += "目前還沒有報價。" }
        Speaker.say(s)
    }
}

// ═════════════ 大盤 ═════════════

struct MarketView: View {
    @EnvironmentObject var store: Store
    @State private var quotes: [String: Quote] = [:]
    @State private var chart: ChartData?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                VStack(alignment: .leading) {
                    Text("加權指數 今日走勢").font(.system(size: 15, weight: .bold)).foregroundColor(Theme.accent)
                    ChartView(data: chart).frame(height: 220)
                }
                .padding(12).background(RoundedRectangle(cornerRadius: 12).fill(Theme.card))

                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                    ForEach(Quotes.indexes, id: \.0) { code, name in
                        NavigationLink(value: code) {
                            let q = quotes[code]
                            let col = q.map { Theme.color($0.change) } ?? Theme.text
                            VStack(alignment: .leading, spacing: 3) {
                                Text(name).font(.system(size: 13)).foregroundColor(Theme.sub)
                                Text(q.map { Fmt.price($0.price) } ?? "--").font(.system(size: 19, weight: .bold))
                                    .foregroundColor(col).lineLimit(1).minimumScaleFactor(0.6)
                                Text(q.map { Fmt.change($0.change) + " " + Fmt.pct($0.pct) } ?? " ")
                                    .font(.system(size: 12)).foregroundColor(col).lineLimit(1).minimumScaleFactor(0.6)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(12).background(RoundedRectangle(cornerRadius: 12).fill(Theme.card))
                        }
                        .buttonStyle(.plain)
                    }
                }
                Text("台股指數來自證交所 MIS；國際指數、匯率、期貨來自 Yahoo Finance，可能延遲。")
                    .font(.system(size: 11)).foregroundColor(Theme.sub)
            }
            .padding(10)
        }
        .task {
            var b = (try? await Quotes.bars("^TWII", "1d", "1m")) ?? Bars()
            if b.count == 0 { b = (try? await Quotes.bars("^TWII", "5d", "5m")) ?? Bars() }
            let q = await Quotes.fetch(["t00"])["t00"]
            chart = .intraday(b, prev: q?.prev ?? b.prev)
        }
        .task(id: store.refresh) {
            while !Task.isCancelled {
                let m = await Quotes.fetch(Quotes.indexes.map(\.0))
                quotes.merge(m) { $1 }
                try? await Task.sleep(for: .seconds(max(10, store.refresh)))
            }
        }
    }
}

// ═════════════ 排行 ═════════════

struct RankView: View {
    @State private var kind = 0
    @State private var scope = 0
    @State private var rows: [MarketRow] = Market.shared.all()

    var body: some View {
        VStack(spacing: 6) {
            Segments(items: ["漲幅", "跌幅", "成交量", "成交值", "殖利率"], selected: $kind)
            Segments(items: ["全部", "上市", "上櫃", "ETF"], selected: $scope)
            Text(rows.isEmpty ? "正在下載全市場資料…" : "資料日期 \(Market.shared.date())（收盤後更新）· 前 100 名")
                .font(.system(size: 12)).foregroundColor(Theme.sub).frame(maxWidth: .infinity, alignment: .leading)
            List {
                ForEach(Array(ranked.enumerated()), id: \.element.code) { n, r in
                    NavigationLink(value: r.code) {
                        StockRow(code: r.code, name: "\(n + 1). \(r.name)", price: r.close, change: r.change, pct: r.pct, extra: extra(r))
                    }
                    .listRowBackground(Theme.bg)
                }
            }
            .listStyle(.plain).scrollContentBackground(.hidden)
        }
        .padding(.horizontal, 8).padding(.top, 4)
        .task {
            if rows.isEmpty { await Market.shared.refresh(); rows = Market.shared.all() }
        }
    }

    private func extra(_ r: MarketRow) -> String {
        switch kind {
        case 3: return "值 " + Fmt.vol(r.value)
        case 4: return String(format: "殖利率 %.2f%%", r.yld)
        default: return "量 " + Fmt.vol(r.volume) + "張"
        }
    }

    private var ranked: [MarketRow] {
        let l = rows.filter { r in
            if scope == 1 && r.market != "tse" { return false }
            if scope == 2 && r.market != "otc" { return false }
            if scope == 3 && !r.isEtf { return false }
            if kind <= 1 && (r.volume < 100 || r.pct.isNaN) { return false }   // 排除冷門股
            if kind == 4 && (r.yld.isNaN || r.volume < 100) { return false }
            return true
        }
        let s: [MarketRow]
        switch kind {
        case 0: s = l.sorted { $0.pct > $1.pct }
        case 1: s = l.sorted { $0.pct < $1.pct }
        case 2: s = l.sorted { $0.volume > $1.volume }
        case 3: s = l.sorted { $0.value > $1.value }
        default: s = l.sorted { $0.yld > $1.yld }
        }
        return Array(s.prefix(100))
    }
}

// ═════════════ 選股 ═════════════

struct ScreenerView: View {
    @State private var f = Array(repeating: "", count: 7)   // PE≤、殖≥、PB≤、價≥、價≤、量≥、漲≥
    @State private var noEtf = true
    @State private var results: [MarketRow]?
    @State private var info = ""

    private let hints = ["本益比 ≤", "殖利率 ≥ %", "淨值比 ≤", "股價 ≥", "股價 ≤", "成交量 ≥ 張", "漲跌幅 ≥ %"]
    private let presets: [(String, [String])] = [
        ("高殖利率", ["15", "5", "", "", "", "500", ""]),
        ("低本益比", ["10", "", "1.5", "", "", "300", ""]),
        ("價值股", ["", "4", "1", "", "", "200", ""]),
        ("強勢股", ["", "", "", "", "", "1000", "5"]),
        ("銅板股", ["", "", "", "", "10", "500", ""]),
    ]

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Text("條件選股（以最近一個交易日收盤資料）").font(.system(size: 14, weight: .bold)).foregroundColor(Theme.accent)
                    HStack { field(0); field(1); field(2) }
                    HStack { field(3); field(4) }
                    HStack { field(5); field(6) }
                    Toggle("排除 ETF", isOn: $noEtf)
                    HStack(spacing: 4) {
                        ForEach(presets, id: \.0) { p in
                            Button { f = p.1 } label: {
                                Text(p.0).font(.system(size: 12, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.7)
                                    .frame(maxWidth: .infinity).padding(.vertical, 7)
                                    .background(RoundedRectangle(cornerRadius: 8).fill(Theme.line))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    Button { run() } label: {
                        Text("開始選股").font(.system(size: 15, weight: .bold)).foregroundColor(Theme.bg)
                            .frame(maxWidth: .infinity).padding(.vertical, 9)
                            .background(RoundedRectangle(cornerRadius: 8).fill(Theme.accent))
                    }
                    .buttonStyle(.plain)
                }
            }
            .listRowBackground(Theme.card)
            if let results {
                Section(info) {
                    ForEach(results.prefix(200)) { r in
                        NavigationLink(value: r.code) {
                            StockRow(code: r.code, name: r.name, price: r.close, change: r.change, pct: r.pct,
                                     extra: "PE \(f1(r.pe)) 殖 \(f1(r.yld))% PB \(f1(r.pb))")
                        }
                        .listRowBackground(Theme.bg)
                    }
                }
            }
        }
        .listStyle(.plain).scrollContentBackground(.hidden)
        .scrollDismissesKeyboard(.immediately)
    }

    private func field(_ i: Int) -> some View {
        TextField(hints[i], text: $f[i]).keyboardType(.decimalPad).textFieldStyle(.roundedBorder).font(.system(size: 14))
    }

    private func f1(_ v: Double) -> String { v.isNaN ? "-" : String(format: "%.1f", v) }

    private func run() {
        let v = f.map { num($0) }
        let all = Market.shared.all()
        results = all.filter { r in
            if noEtf && r.isEtf { return false }
            if !v[0].isNaN && (r.pe.isNaN || r.pe <= 0 || r.pe > v[0]) { return false }
            if !v[1].isNaN && (r.yld.isNaN || r.yld < v[1]) { return false }
            if !v[2].isNaN && (r.pb.isNaN || r.pb > v[2]) { return false }
            if !v[3].isNaN && r.close < v[3] { return false }
            if !v[4].isNaN && r.close > v[4] { return false }
            if !v[5].isNaN && r.volume < v[5] { return false }
            if !v[6].isNaN && (r.pct.isNaN || r.pct < v[6]) { return false }
            return true
        }.sorted { $0.volume > $1.volume }
        info = all.isEmpty ? "全市場資料還在下載，請稍候再試"
            : "符合 \(results?.count ?? 0) 檔（依成交量排序，最多 200 檔）· 資料日 \(Market.shared.date())"
    }
}

// ═════════════ 新聞 ═════════════

struct NewsView: View {
    @State private var cat = 0
    @State private var items: [News]?
    @Environment(\.openURL) private var openURL
    private let cats = ["tw-market", "intl-markets"]

    var body: some View {
        VStack(spacing: 6) {
            Segments(items: ["台股新聞", "國際財經"], selected: $cat).padding(.horizontal, 8)
            List {
                if items == nil { Text("載入中…").foregroundColor(Theme.sub).listRowBackground(Theme.bg) }
                else if items!.isEmpty { Text("新聞載入失敗，請檢查網路").foregroundColor(Theme.sub).listRowBackground(Theme.bg) }
                ForEach(items ?? []) { n in
                    Button {
                        if let u = URL(string: n.link) { openURL(u) }
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(n.title).font(.system(size: 15)).foregroundColor(Theme.text)
                            if let d = n.date {
                                Text(d.formatted(.dateTime.month(.twoDigits).day(.twoDigits).hour().minute()))
                                    .font(.system(size: 11)).foregroundColor(Theme.sub)
                            }
                        }
                    }
                    .listRowBackground(Theme.bg)
                }
            }
            .listStyle(.plain).scrollContentBackground(.hidden)
            .refreshable { await load() }
        }
        .padding(.top, 4)
        .task(id: cat) { items = nil; await load() }
    }

    private func load() async {
        items = (try? await Quotes.news(cats[cat])) ?? []
    }
}
