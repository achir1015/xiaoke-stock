import WidgetKit
import SwiftUI
import AppIntents

/// 桌面小工具：目前自選群組前幾檔的報價（同 Android QuoteWidget）；按 ⟳ 更新、按 ▶ 換群組、按個股開個股頁。
struct RefreshIntent: AppIntent {
    static var title: LocalizedStringResource = "更新報價"
    func perform() async throws -> some IntentResult { .result() }   // 執行完 WidgetKit 會自動重抓
}

struct NextGroupIntent: AppIntent {
    static var title: LocalizedStringResource = "換下一個群組"
    func perform() async throws -> some IntentResult {
        let store = Store()
        Store.defaults.set((store.current + 1) % max(1, store.groups.count), forKey: "group")
        return .result()
    }
}
struct WidgetRow: Identifiable {
    var id: String { code }
    let code: String, name: String, price: Double, change: Double, pct: Double
}

struct QuoteEntry: TimelineEntry {
    let date: Date
    let group: String
    let rows: [WidgetRow]
    let failed: Bool
}

struct QuoteProvider: TimelineProvider {
    func placeholder(in context: Context) -> QuoteEntry {
        QuoteEntry(date: .now, group: "自選1", rows: [WidgetRow(code: "2330", name: "台積電", price: 1000, change: 10, pct: 1)], failed: false)
    }

    func getSnapshot(in context: Context, completion: @escaping (QuoteEntry) -> Void) {
        if context.isPreview { completion(placeholder(in: context)); return }
        Task { completion(await load(context.family)) }
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<QuoteEntry>) -> Void) {
        Task {
            let e = await load(context.family)
            completion(Timeline(entries: [e], policy: .after(.now.addingTimeInterval(15 * 60))))
        }
    }

    private func load(_ family: WidgetFamily) async -> QuoteEntry {
        let store = Store()
        let g = store.currentGroup
        let n = family == .systemLarge ? 8 : family == .systemMedium ? 4 : 2
        let codes = Array(g.codes.prefix(n))
        let qs = await Quotes.fetch(codes)
        let rows = codes.map { c -> WidgetRow in
            if let q = qs[c] { return WidgetRow(code: c, name: q.name, price: q.price, change: q.change, pct: q.pct) }
            return WidgetRow(code: c, name: Market.shared.get(c)?.name ?? c, price: .nan, change: 0, pct: .nan)
        }
        return QuoteEntry(date: .now, group: g.name, rows: rows, failed: qs.isEmpty && !codes.isEmpty)
    }
}

struct QuoteWidgetView: View {
    var entry: QuoteEntry
    @Environment(\.widgetFamily) var family

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text("小柯股市 · \(entry.group)").font(.system(size: 13, weight: .bold)).foregroundColor(Theme.accent).lineLimit(1)
                Spacer(minLength: 2)
                if family != .systemSmall {
                    Text(entry.failed ? "連線失敗" : entry.date.formatted(date: .omitted, time: .shortened))
                        .font(.system(size: 10)).foregroundColor(Theme.sub)
                }
                Button(intent: RefreshIntent()) { Text("⟳").font(.system(size: 15, weight: .bold)) }
                    .buttonStyle(.plain).foregroundColor(Theme.text)
                Button(intent: NextGroupIntent()) { Text("▶").font(.system(size: 13)) }
                    .buttonStyle(.plain).foregroundColor(Theme.text)
            }
            if entry.rows.isEmpty {
                Text("這個群組還沒有股票").font(.system(size: 12)).foregroundColor(Theme.sub)
            }
            ForEach(entry.rows) { r in
                Link(destination: URL(string: "xiaokestock://stock/\(r.code)")!) {
                    HStack(spacing: 4) {
                        Text(r.name).font(.system(size: 13)).foregroundColor(Theme.text).lineLimit(1)
                        if family != .systemSmall {
                            Text(r.code).font(.system(size: 10)).foregroundColor(Theme.sub)
                        }
                        Spacer(minLength: 2)
                        Text(Fmt.price(r.price)).font(.system(size: 13, weight: .bold)).foregroundColor(Theme.color(r.change))
                            .lineLimit(1).minimumScaleFactor(0.6)
                        if family != .systemSmall {
                            Text(Fmt.pct(r.pct)).font(.system(size: 12)).foregroundColor(Theme.color(r.change))
                                .frame(width: 62, alignment: .trailing).lineLimit(1).minimumScaleFactor(0.6)
                        }
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .containerBackground(Theme.bg, for: .widget)
    }
}

@main
struct XiaoKeStockWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "QuoteWidget", provider: QuoteProvider()) { e in
            QuoteWidgetView(entry: e)
        }
        .configurationDisplayName("小柯自選報價")
        .description("顯示目前自選群組的報價；按 ⟳ 更新、按 ▶ 換群組。")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}
