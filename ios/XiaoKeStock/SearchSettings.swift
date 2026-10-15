import SwiftUI
import WidgetKit

// ═════════════ 搜尋 ═════════════

struct SearchView: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var q = ""
    @State private var toast: String?

    var body: some View {
        NavigationStack {
            List {
                ForEach(Market.shared.search(q)) { r in
                    row(r.code, "\(r.name)  \(r.market == "tse" ? "上市" : "上櫃")")
                }
                let up = q.trimmingCharacters(in: .whitespaces).uppercased()
                if up.range(of: "^[A-Z0-9.^=\\-]{1,12}$", options: .regularExpression) != nil {
                    row(up, "查詢 \(up)（海外 / Yahoo 代號）")
                }
                if Market.shared.all().isEmpty && !q.isEmpty {
                    Text("全市場清單下載中，可直接輸入完整代號").font(.footnote).foregroundColor(Theme.sub)
                }
            }
            .searchable(text: $q, placement: .navigationBarDrawer(displayMode: .always), prompt: "代號或名稱，例：2330、台積、0050、AAPL")
            .navigationTitle("搜尋股票").navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: String.self) { StockView(code: $0) }
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("關閉") { dismiss() } } }
            .overlay(alignment: .bottom) {
                if let t = toast {
                    Text(t).padding(10).background(Capsule().fill(Theme.card)).padding(.bottom, 20)
                        .task { try? await Task.sleep(for: .seconds(1.5)); toast = nil }
                }
            }
        }
    }

    private func row(_ code: String, _ label: String) -> some View {
        HStack {
            NavigationLink(value: code) {
                Text("\(code)  \(label)").foregroundColor(Theme.text)
            }
            Button("＋自選") {
                toast = store.add(code)
                WidgetCenter.shared.reloadAllTimelines()
            }
            .buttonStyle(.bordered)
        }
    }
}

// ═════════════ 設定 ═════════════

struct SettingsView: View {
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var message: String?
    @State private var busy = false
    @State private var release: Updater.Release?

    var body: some View {
        NavigationStack {
            Form {
                Section("報價") {
                    Picker("自動更新", selection: $store.refresh) {
                        ForEach([5, 10, 30, 60], id: \.self) { Text("每 \($0) 秒").tag($0) }
                    }
                    Picker("漲跌顯示", selection: $store.showPct) {
                        Text("漲跌幅 %").tag(true)
                        Text("漲跌點數").tag(false)
                    }
                    Button(busy ? "下載中…" : "立即更新全市場資料") {
                        busy = true
                        Task {
                            let ok = await Market.shared.refresh(force: true)
                            busy = false
                            message = ok ? "已更新：\(Market.shared.all().count) 檔，資料日 \(Market.shared.date())" : "更新失敗，請檢查網路"
                        }
                    }
                    .disabled(busy)
                }
                Section {
                    if store.alerts.isEmpty {
                        Text("還沒有設定。到個股頁按「🔔 到價提醒」新增。").foregroundColor(Theme.sub)
                    }
                    ForEach($store.alerts) { $a in
                        Toggle(isOn: $a.on) {
                            VStack(alignment: .leading) {
                                Text("\(a.name) \(a.code)")
                                Text(a.summary).font(.caption).foregroundColor(Theme.sub)
                            }
                        }
                    }
                    .onDelete { store.alerts.remove(atOffsets: $0) }
                    Button("立即檢查") {
                        AlertChecker.requestPermission()
                        Task {
                            let n = await AlertChecker.check()
                            message = AlertChecker.twOpen() ? "已檢查，發出 \(n) 則提醒" : "已檢查（台股目前非交易時段，只檢查海外商品），發出 \(n) 則提醒"
                        }
                    }
                } header: {
                    Text("到價提醒（\(store.alerts.count)）")
                } footer: {
                    Text("App 開著時每分鐘檢查；在背景時由 iOS 決定檢查時間，不保證準時。")
                }
                Section("版本") {
                    Button("檢查更新（目前 v\(Updater.version)）") {
                        Task {
                            guard let r = await Updater.latest() else { message = "無法連到 GitHub，或還沒有 iPhone 版的發布"; return }
                            if Updater.compare(r.tag, Updater.version) > 0 { release = r }
                            else { message = "已是最新版（GitHub 最新 \(r.tag)）" }
                        }
                    }
                }
                Section("關於") {
                    Text("功能參考三竹股市（以 REA 分析其功能架構），程式全部重新撰寫，不使用三竹的伺服器、程式碼或素材。\n\n資料來源：\n• 即時報價：臺灣證券交易所 MIS\n• 全市場日資料：證交所、櫃買中心 OpenAPI\n• 走勢、K 線、國際行情：Yahoo Finance\n• 新聞：Yahoo 股市 RSS\n\n所有資訊僅供參考，不構成投資建議。")
                        .font(.footnote).foregroundColor(Theme.sub)
                }
            }
            .navigationTitle("設定").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
            .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) { Button("好") {} }
            .alert("有新版 \(release?.tag ?? "")", isPresented: Binding(get: { release != nil }, set: { if !$0 { release = nil } })) {
                Button("前往下載頁") { if let u = URL(string: release?.url ?? "") { openURL(u) } }
                Button("稍後", role: .cancel) {}
            } message: {
                Text("目前版本 v\(Updater.version)\n\n\((release?.body ?? "").replacingOccurrences(of: "**", with: ""))")
            }
        }
        .onChange(of: store.current) { _, _ in WidgetCenter.shared.reloadAllTimelines() }
    }
}
