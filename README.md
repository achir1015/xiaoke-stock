# 小柯股市助手（XiaoKeStock）
<img width="562" height="945" alt="image" src="https://github.com/user-attachments/assets/d0ac378b-7545-497f-810f-48142c354447" />

## 📲 下載安裝（Android 7.0 以上）

**[⬇️ 下載 XiaoKeStock.apk（v1.1.0）](https://github.com/achir1015/xiaoke-stock/raw/main/release/XiaoKeStock.apk)**

也可以到 [Releases 頁面](https://github.com/achir1015/xiaoke-stock/releases/latest) 下載。

<img src="release/qr.png" width="180" alt="手機掃描下載 APK">

1. 用手機開上面的連結，或掃 QR Code 下載 APK。
2. 點開下載好的檔案。如果出現「為了安全起見，你的手機目前無法安裝來自這個來源的應用程式」，按「設定」→ 開啟「允許來自這個來源的應用程式」→ 返回再按「安裝」。
3. 三星手機如果被 Play Protect 擋下，選「仍要安裝」。
4. v1.1.0 起可以在 App 裡按「⚙ → 檢查更新」：有新版會自動下載並開啟安裝畫面。直接安裝覆蓋即可，自選股和到價提醒都會保留。

Android 看盤 App，功能參考「三竹股市」。先用 REA 分析三竹 APK 的功能架構（見 [docs/REA_ANALYSIS.md](docs/REA_ANALYSIS.md)），程式再全部重新寫過；只用公開資料，不連三竹的伺服器，也沒有用到三竹的程式碼或素材。

## 📱 iPhone 版（ios/）

用 SwiftUI 寫的 iPhone 版，功能和 Android 版相同：自選、大盤、個股走勢與 K 線（KD、MACD）、排行、選股、新聞、到價提醒、桌面小工具、小柯唸報價。

目前沒有上架 App Store，要在 Mac 上用 Xcode 編譯後裝到手機（需要 Xcode 15 以上、iOS 17 以上）。

**需要準備**
- Mac 一台，裝好 Xcode（App Store 下載），並用 Apple ID 登入：Xcode → Settings → Accounts → ＋。免費帳號就可以。
- 安裝 xcodegen：`brew install xcodegen`
- iPhone 用傳輸線接上 Mac，手機上按「信任這部電腦」。
- 開啟 iPhone 的開發者模式：設定 → 隱私權與安全性 → 開發者模式 → 開啟（手機會重開機）。

**步驟**
1. 下載程式並產生 Xcode 專案：
   ```bash
   git clone https://github.com/achir1015/xiaoke-stock.git
   cd xiaoke-stock/ios
   xcodegen generate
   ```
2. 不是用 achir1015 的帳號的話，要改成自己的帳號。打開 `ios/project.yml`：
   - 把兩個 `DEVELOPMENT_TEAM` 換成自己的 Team ID（Xcode → Settings → Accounts 可以看到）。
   - 把 `com.achir.xiaokestock` 換成自己的名字，例如 `com.yourname.xiaokestock`。
   - App Group 也要一起改：`XiaoKeStock/XiaoKeStock.entitlements`、`Widget/Widget.entitlements`、`XiaoKeStock/Store.swift` 裡的 `group.com.achir.xiaokestock`。
   - 改完再執行一次 `xcodegen generate`。
3. 安裝。任選一種：
   - **用 Xcode**：打開 `ios/XiaoKeStock.xcodeproj`，上方選你的 iPhone，按 ▶ 執行。
   - **用指令**：
     ```bash
     xcodebuild -project XiaoKeStock.xcodeproj -scheme XiaoKeStock -destination 'generic/platform=iOS' -derivedDataPath /tmp/xks -allowProvisioningUpdates build
     xcrun devicectl device install app --device <iPhone 的 UDID> /tmp/xks/Build/Products/Debug-iphoneos/XiaoKeStock.app
     ```
     iPhone 的 UDID 可以用 `xcrun devicectl list devices` 查。如果專案放在有 iCloud 同步的「文件」資料夾裡，`-derivedDataPath` 一定要指到 iCloud 以外的地方，不然簽名會失敗，錯誤是 `resource fork, Finder information, or similar detritus not allowed`。
4. 第一次打開時如果出現「未受信任的開發者」：到 iPhone 的設定 → 一般 → VPN 與裝置管理 → 點你的 Apple ID → 信任。
5. 第一次設到價提醒時，系統會問要不要讓 App 發通知，請按「允許」。
6. 加桌面小工具：長按桌面空白處 → 左上角「＋」→ 搜尋「小柯股市」→ 選大小 → 加入。按 ⟳ 更新報價、按 ▶ 換群組、點個股會開啟個股頁。

**注意**
- 用免費 Apple ID 簽名的 App **7 天後會無法開啟**，重新執行一次步驟 3 就好。自選股和到價提醒都會保留。付費開發者帳號可以用一年。

iPhone 版和 Android 版的差異：
- 到價提醒：App 開著時每分鐘檢查；在背景時由 iOS 決定什麼時候檢查，不保證準時。
- 檢查更新：iOS 不能自己安裝新版，會比對 GitHub 上 `ios-v` 開頭的 Release，有新版時開啟 Release 頁面，再照上面步驟 3 重新安裝。
- 桌面小工具：iOS 的自動更新時間由系統決定；按 ⟳ 可以立即更新。


## 功能
- **自選**：最多 10 個群組、每組 60 檔，可以放台股上市、上櫃、ETF，也可以放美股（例如 NVDA、AAPL）；自動更新，長按可以排序或刪除
- **🤖 小柯唸自選**：用系統 TTS 念出自選股的報價
- **大盤**：加權指數當日走勢，加權、櫃買、道瓊、那斯達克、S&P 500、費半、日經、恆生、韓股、上證、匯率、黃金、原油、比特幣
- **個股頁**：即時報價、最佳五檔、漲停跌停價、本益比、殖利率、淨值比、分時走勢（含均價線）、日／週／月 K 線（MA5/20/60、成交量、KD、MACD）；K 線可以拖曳、雙指縮放，長按會出現十字線
- **排行**：漲幅、跌幅、成交量、成交值、殖利率，可以只看全部、上市、上櫃或 ETF
- **選股**：可設本益比、殖利率、淨值比、股價區間、成交量、漲跌幅等條件；另有快速條件：高殖利率、低本益比、價值股、強勢股、銅板股
- **新聞**：台股新聞、國際財經（Yahoo 股市 RSS）
- **到價提醒**：可設漲到、跌到、漲跌幅超過某個百分比；台股在平日盤中約每 15 分鐘檢查一次，觸發後會推播通知
- **桌面小工具**：顯示自選群組的報價，按 ⟳ 更新、按 ▶ 換群組

## 資料來源
| 用途 | 來源 |
|---|---|
| 台股即時報價、五檔 | 臺灣證券交易所 MIS |
| 全市場日資料、本益比、殖利率 | 證交所 OpenAPI、櫃買中心 OpenAPI |
| 走勢、K 線、美股、國際指數 | Yahoo Finance |
| 新聞 | Yahoo 股市 RSS |

所有資訊僅供參考，不構成投資建議。

## 編譯（不用 Gradle）
```bash
python build.py
```
產生 `build/XiaoKeStock.apk`。`local.properties` 設定 `sdk.dir` 與 `jdk.dir`；加 `install` 參數會用 adb 安裝並啟動。

