import Foundation
import AVFoundation
import UserNotifications
import BackgroundTasks
import UIKit

/// 小柯唸報價：AVSpeechSynthesizer，中文（台灣）。
enum Speaker {
    private static let synth = AVSpeechSynthesizer()

    static func say(_ text: String) {
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio, options: .duckOthers)
        try? AVAudioSession.sharedInstance().setActive(true)
        synth.stopSpeaking(at: .immediate)
        let u = AVSpeechUtterance(string: text)
        u.voice = AVSpeechSynthesisVoice(language: "zh-TW")
        synth.speak(u)
    }

    static func stop() { synth.stopSpeaking(at: .immediate) }

    /// 例：「台積電，2440元，下跌50元，跌幅2.01%。」
    static func describe(_ q: Quote) -> String {
        let ch = q.change
        var s = (q.name.isEmpty ? q.code : q.name) + "，" + say(q.price)
        if q.isTw { s += "元" }
        if ch.isNaN || ch == 0 { s += "，平盤" }
        else {
            s += (ch > 0 ? "，上漲" : "，下跌") + say(abs(ch)) + (q.isTw ? "元" : "")
            s += (ch > 0 ? "，漲幅" : "，跌幅") + String(format: "%.2f", abs(q.pct)) + "%"
        }
        return s + "。"
    }

    private static func say(_ v: Double) -> String {
        if v.isNaN { return "無資料" }
        if v == v.rounded() { return String(Int64(v)) }
        var s = String(format: "%.2f", v)
        while s.hasSuffix("0") { s.removeLast() }
        return s
    }
}

/// 到價提醒：App 在前景時每分鐘檢查；背景由 BGAppRefreshTask 檢查（時間由 iOS 決定，不保證準時）。
enum AlertChecker {
    static let taskID = "com.achir.xiaokestock.alerts"

    /// 台股：平日 09:00–13:35（台北時間）才檢查；海外商品不限時段
    static func twOpen() -> Bool {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Asia/Taipei")!
        let now = Date()
        let wd = cal.component(.weekday, from: now)
        if wd == 1 || wd == 7 { return false }
        let m = cal.component(.hour, from: now) * 60 + cal.component(.minute, from: now)
        return m >= 9 * 60 && m <= 13 * 60 + 35
    }

    static func today() -> String {
        let f = DateFormatter()
        f.timeZone = TimeZone(identifier: "Asia/Taipei")
        f.dateFormat = "yyyyMMdd"
        return f.string(from: Date())
    }

    static func requestPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    /// 回傳這次發出幾則通知
    @discardableResult
    static func check() async -> Int {
        let store = await MainActor.run { Store.shared }
        let alerts = await MainActor.run { store.alerts }
        let day = today(), open = twOpen()
        let codes = alerts.filter { $0.on && $0.fired != day && (!Quotes.isTw($0.code) || open) }.map(\.code)
        if codes.isEmpty { return 0 }
        // 還沒允許通知就先不檢查，免得標成「今天已提醒」卻沒有真的發出
        let auth = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
        if auth != .authorized && auth != .provisional { return 0 }
        let qs = await Quotes.fetch(codes)
        var fired: [String] = []
        for a in alerts where codes.contains(a.code) {
            guard let q = qs[a.code], !q.price.isNaN else { continue }
            var why: String?
            if let v = a.above, q.price >= v { why = "漲到 \(Fmt.price(q.price))（≥ \(Fmt.price(v))）" }
            else if let v = a.below, q.price <= v { why = "跌到 \(Fmt.price(q.price))（≤ \(Fmt.price(v))）" }
            else if let v = a.pct, abs(q.pct) >= v { why = "漲跌幅 \(Fmt.pct(q.pct))" }
            guard let why else { continue }
            let c = UNMutableNotificationContent()
            c.title = "小柯提醒：\(q.name) \(a.code)"
            c.body = "\(why)，\(Fmt.pct(q.pct))"
            c.sound = .default
            c.userInfo = ["code": a.code]
            try? await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "alert-\(a.code)", content: c, trigger: nil))
            fired.append(a.code)
        }
        if !fired.isEmpty {
            await MainActor.run {
                for i in store.alerts.indices where fired.contains(store.alerts[i].code) { store.alerts[i].fired = day }
            }
        }
        return fired.count
    }

    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskID, using: nil) { task in
            schedule()
            let job = Task {
                await check()
                task.setTaskCompleted(success: true)
            }
            task.expirationHandler = { job.cancel() }
        }
    }

    static func schedule() {
        let r = BGAppRefreshTaskRequest(identifier: taskID)
        r.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(r)
    }
}

/// 檢查更新：iOS 不能自己安裝，只比對 GitHub 上 ios-v 開頭的最新 Release，有新版就開 Release 頁面。
enum Updater {
    struct Release { var tag: String, body: String, url: String }

    static var version: String { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0" }

    static func latest() async -> Release? {
        guard let arr = try? await Net.json("https://api.github.com/repos/achir1015/xiaoke-stock/releases") as? [[String: Any]] else { return nil }
        let ios = arr.filter { ($0["tag_name"] as? String ?? "").hasPrefix("ios-v") && ($0["draft"] as? Bool) != true }
        guard let best = ios.max(by: { compare($0["tag_name"] as? String ?? "", $1["tag_name"] as? String ?? "") < 0 }) else { return nil }
        return Release(tag: best["tag_name"] as? String ?? "", body: best["body"] as? String ?? "", url: best["html_url"] as? String ?? "")
    }

    /// "ios-v1.2.0" 與 "1.10" 這類版本號逐段比數字
    static func compare(_ a: String, _ b: String) -> Int {
        func parts(_ s: String) -> [Int] { s.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) } }
        let x = parts(a), y = parts(b)
        for i in 0..<max(x.count, y.count) {
            let p = i < x.count ? x[i] : 0, q = i < y.count ? y[i] : 0
            if p != q { return p < q ? -1 : 1 }
        }
        return 0
    }
}
