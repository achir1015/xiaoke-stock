import SwiftUI
import UserNotifications
import WidgetKit

@main
struct XiaoKeStockApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var delegate
    @StateObject private var store = Store.shared
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
                .preferredColorScheme(.dark)
                .tint(Theme.accent)
                .task { await Market.shared.refresh() }
                .task {   // 前景時每分鐘檢查到價提醒
                    while !Task.isCancelled {
                        await AlertChecker.check()
                        try? await Task.sleep(for: .seconds(60))
                    }
                }
        }
        .onChange(of: phase) { _, p in
            if p == .active {   // 小工具按 ▶ 可能換過群組
                let g = min(Store.defaults.integer(forKey: "group"), store.groups.count - 1)
                if g != store.current { store.current = g }
            }
            if p == .background {
                AlertChecker.schedule()
                WidgetCenter.shared.reloadAllTimelines()
            }
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        AlertChecker.register()
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    /// App 在前景時也顯示通知
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }

    /// 點通知打開個股頁
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        if let code = response.notification.request.content.userInfo["code"] as? String {
            await MainActor.run { Router.shared.open = code }
        }
    }
}

/// 從通知或小工具開啟的個股
final class Router: ObservableObject {
    static let shared = Router()
    @Published var open: String?
}

struct ContentView: View {
    @EnvironmentObject var store: Store
    @StateObject private var router = Router.shared
    @AppStorage("tab") private var tab = 0
    @State private var showSearch = false
    @State private var showSettings = false

    var body: some View {
        TabView(selection: $tab) {
            page(WatchView()).tabItem { Label("自選", systemImage: "star.fill") }.tag(0)
            page(MarketView()).tabItem { Label("大盤", systemImage: "chart.xyaxis.line") }.tag(1)
            page(RankView()).tabItem { Label("排行", systemImage: "trophy.fill") }.tag(2)
            page(ScreenerView()).tabItem { Label("選股", systemImage: "line.3.horizontal.decrease.circle") }.tag(3)
            page(NewsView()).tabItem { Label("新聞", systemImage: "newspaper.fill") }.tag(4)
        }
        .sheet(isPresented: $showSearch) { SearchView() }
        .sheet(isPresented: $showSettings) { SettingsView() }
        .sheet(item: Binding(get: { router.open.map { OpenCode(code: $0) } }, set: { router.open = $0?.code })) { o in
            NavigationStack {
                StockView(code: o.code)
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("關閉") { router.open = nil } } }
            }
            .environmentObject(store)
        }
        .onAppear {   // 測試用：啟動參數 -openCode 2330 直接開個股頁（-tab 1 則由 @AppStorage 讀到）
            if let c = UserDefaults.standard.string(forKey: "openCode"), !c.isEmpty { router.open = c }
        }
        .onOpenURL { url in   // 小工具：xiaokestock://stock/2330
            if url.host == "stock", let c = url.pathComponents.last, c != "/" { router.open = c }
        }
    }

    private func page<V: View>(_ v: V) -> some View {
        NavigationStack {
            v.background(Theme.bg)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .principal) {
                        HStack(spacing: 4) {
                            Text("◠‿◠").foregroundColor(Theme.pink)
                            Text("小柯股市助手").font(.headline).foregroundColor(Theme.text)
                        }
                    }
                    ToolbarItemGroup(placement: .topBarTrailing) {
                        Button { showSearch = true } label: { Image(systemName: "magnifyingglass") }
                        Button { showSettings = true } label: { Image(systemName: "gearshape") }
                    }
                }
                .toolbarBackground(Theme.bg, for: .navigationBar)
                .toolbarBackground(.visible, for: .navigationBar)
                .navigationDestination(for: String.self) { StockView(code: $0) }
        }
    }
}

private struct OpenCode: Identifiable {
    let code: String
    var id: String { code }
}

/// 一列股票：名稱/代號、價格、漲跌幅色塊
struct StockRow: View {
    @EnvironmentObject var store: Store
    let code: String
    let name: String
    let price: Double
    let change: Double
    let pct: Double
    var extra: String?

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(name).font(.system(size: 16, weight: .bold)).foregroundColor(Theme.text).lineLimit(1)
                Text(extra.map { "\(code)  \($0)" } ?? code).font(.system(size: 12)).foregroundColor(Theme.sub).lineLimit(1)
            }
            Spacer(minLength: 6)
            Text(Fmt.price(price)).font(.system(size: 17, weight: .bold)).foregroundColor(Theme.color(change))
                .lineLimit(1).minimumScaleFactor(0.7)
            Text(store.showPct ? Fmt.pct(pct) : Fmt.change(change))
                .font(.system(size: 14, weight: .bold)).foregroundColor(Theme.text)
                .lineLimit(1).minimumScaleFactor(0.7)
                .frame(width: 84, height: 30)
                .background(RoundedRectangle(cornerRadius: 6).fill(change > 0 ? Theme.up : change < 0 ? Theme.down : Theme.line))
        }
        .padding(.vertical, 6)
        .contentShape(Rectangle())
    }
}

/// 分段按鈕
struct Segments: View {
    let items: [String]
    @Binding var selected: Int

    var body: some View {
        HStack(spacing: 4) {
            ForEach(items.indices, id: \.self) { i in
                Button { selected = i } label: {
                    Text(items[i]).font(.system(size: 13, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.7)
                        .frame(maxWidth: .infinity).padding(.vertical, 7)
                        .foregroundColor(i == selected ? Theme.bg : Theme.text)
                        .background(RoundedRectangle(cornerRadius: 8).fill(i == selected ? Theme.accent : Theme.line))
                }
                .buttonStyle(.plain)
            }
        }
    }
}
