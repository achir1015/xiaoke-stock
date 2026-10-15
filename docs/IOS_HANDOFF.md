# 小柯股市助手 iPhone 版：交接文件

這份文件是寫給 **MacBook Pro 上的 Claude Code** 看的：請依照這份文件，在本 repo 的 `ios/` 資料夾做出 iPhone 版。
Android 版的原始碼在 `src/com/achir/xiaokestock/`，是行為的標準答案；這份文件沒寫清楚的地方，請直接看對應的 Java 檔。

## 0. 開發條件與決定

- 用 **SwiftUI**，最低支援 **iOS 16**，Bundle ID 用 `com.achir.xiaokestock`（如果跟免費帳號衝突，可以加後綴，例如 `.ios`）。
- App 名稱是「小柯股市助手」，介面全部用繁體中文；色系、漲紅跌綠的規則和 Android 版一樣（見第 6 節）。
- 只用公開資料（第 3 節），**不要**連三竹的伺服器，也**不要**用三竹的任何素材。
- 使用者**沒有**加入 Apple Developer Program（年費 US$99），可用的方式：
  - **方案 A（建議先用這個）**：Xcode ＋ 免費 Apple ID 直接裝到 iPhone。7 天後簽章會過期，要在 Mac 上重新 Run 一次。
  - **方案 B**：GitHub Actions 用 macOS runner 編出未簽章的 IPA，再用 SideStore 或 AltStore 搭配免費 Apple ID 簽章；SideStore 可以直接在手機上續簽。
- 免費帳號有些功能限制（例如 App Groups）。如果桌面小工具因為簽章問題做不出來，**先把小工具拿掉**，主 App 優先。
- 專案檔建議用 **XcodeGen**（`ios/project.yml`），不要把 `.xcodeproj` 提交進 repo；如果 Mac 上沒有 XcodeGen，就先執行 `brew install xcodegen`。
- 不需要任何第三方套件：網路用 `URLSession`，JSON 用 `JSONSerialization` 或 `Codable`，圖表用 SwiftUI `Canvas` 自己畫，語音用 `AVSpeechSynthesizer`。

## 1. 功能清單（全部都要做，對應 Android 版）

| 分頁 / 頁面 | 內容 | Android 對應檔 |
|---|---|---|
| 自選 | 最多 10 個群組、每組 60 檔；上方是群組的橫向按鈕列，最後一個是「＋」新增群組；長按群組可以改名或刪除（至少要留一組）；每一列顯示名稱、代號、成交量、價格、漲跌幅色塊；長按股票可以置頂、上移、下移、刪除；依設定秒數自動更新；「🤖 小柯唸自選」最多念 10 檔 | MainActivity.buildWatch |
| 大盤 | 上方是加權指數當日走勢圖，下方是兩欄卡片：加權、櫃買、道瓊、那斯達克、S&P 500、費半、日經、恆生、韓國綜合、上證、美元/台幣、日圓/台幣、黃金、原油、比特幣；點卡片進個股頁 | buildMarket、Data.INDEXES |
| 排行 | 種類有漲幅、跌幅、成交量、成交值、殖利率，範圍有全部、上市、上櫃、ETF；顯示前 100 名；漲幅、跌幅、殖利率排行要排除成交量少於 100 張的股票；上方顯示資料日期 | buildRank |
| 選股 | 條件：本益比 ≤、殖利率 ≥、淨值比 ≤、股價 ≥、股價 ≤、成交量 ≥（張）、漲跌幅 ≥；可勾選「排除 ETF」（預設勾選）；快速條件見下表；結果依成交量排序，最多 200 檔 | buildScreener |
| 新聞 | 台股新聞（`tw-market`）、國際財經（`intl-markets`）；點標題用 Safari 開啟 | buildNews |
| 搜尋 | 用代號或名稱即時搜尋全市場清單；每一列都有「＋自選」；輸入英數字（例如 AAPL）時，另外多一列「查詢 XXX（海外 / Yahoo 代號）」 | showSearch |
| 個股頁 | 標題列顯示名稱、代號、上市/上櫃/指數/海外；有價格、漲跌、更新時間；按鈕有加入自選（可以多選群組）、到價提醒、小柯唸；圖表可切換走勢、日K、週K、月K，K 線可再切 KD 或 MACD；行情資訊有開盤、昨收、最高、最低、總量（張）、振幅、漲停、跌停、本益比、殖利率、淨值比、資料日；最佳五檔是四欄：委買量、買價、賣價、委賣量 | StockActivity |
| 到價提醒 | 條件有漲到 ≥、跌到 ≤、漲跌幅絕對值 ≥ %；同一檔同一天只提醒一次；台股只在平日 09:00–13:35（台北時間）檢查 | AlertJob |
| 設定 | 自動更新秒數（5/10/30/60 循環切換）、漲跌顯示（% 或點數）、到價提醒清單（可以啟用/停用、立即檢查）、立即更新全市場資料、檢查更新、關於 | showSettings |
| 桌面小工具 | 顯示目前群組前 8 檔報價（WidgetKit），點一下開啟 App | QuoteWidget |

選股的快速條件（欄位依序是：本益比≤、殖利率≥、淨值比≤、股價≥、股價≤、量≥、漲跌幅≥；空白代表不限）：

| 名稱 | PE≤ | 殖≥ | PB≤ | 價≥ | 價≤ | 量≥ | 漲≥ |
|---|---|---|---|---|---|---|---|
| 高殖利率 | 15 | 5 | | | | 500 | |
| 低本益比 | 10 | | 1.5 | | | 300 | |
| 價值股 | | 4 | 1 | | | 200 | |
| 強勢股 | | | | | | 1000 | 5 |
| 銅板股 | | | | | 10 | 500 | |

預設自選：「自選1」放 2330、2317、2454、2308、0050、0056、00878、NVDA；另外建一個空的「自選2」。

## 2. iOS 跟 Android 的差異

| Android 做法 | iOS 做法 |
|---|---|
| JobScheduler 每 15 分鐘檢查到價 | `BGAppRefreshTask`（系統決定什麼時候執行，**不保證準時**），加上 App 在前景時每次更新報價都順便檢查；用 `UNUserNotificationCenter` 發本機通知。設定畫面要寫明「iOS 背景檢查時間由系統決定」 |
| 檢查更新：下載 APK 後安裝 | iOS 沒辦法自己安裝。改成比對 GitHub 最新 Release 的 `tag_name`（iOS 版用 `ios-v1.0.0` 這種 tag，見第 7 節），有新版就提示並用 Safari 開 Release 頁面 |
| TextToSpeech | `AVSpeechSynthesizer`，語音用 `AVSpeechSynthesisVoice(language: "zh-TW")` |
| SharedPreferences | `UserDefaults`，key 和 JSON 格式照第 5 節 |
| files/market_v2.json | 存在 Application Support 底下的 `market_v2.json`，格式照第 4 節 |
| 桌面小工具 RemoteViews | WidgetKit；如果要跟 App 共用資料就需要 App Group，免費帳號做不到的話就先不做 |

## 3. 資料來源（都已經用 Android 版實測過）

所有請求都要帶 `User-Agent: Mozilla/5.0 (iPhone) XiaoKeStock/1.0`（Yahoo 沒有 UA 可能會擋），逾時設 10–20 秒。

### 3.1 證交所 MIS 即時報價
```
GET https://mis.twse.com.tw/stock/api/getStockInfo.jsp?json=1&delay=0&_=<毫秒時間戳>&ex_ch=<key1>|<key2>|...
```
- key：上市用 `tse_2330.tw`，上櫃用 `otc_6488.tw`，加權指數 `tse_t00.tw`，櫃買指數 `otc_o00.tw`。`|` 要 URL 編碼。**一次最多 50 個 key**，超過就分批。
- **不知道是上市還是上櫃的代號**（全市場清單還沒載入時），就把 `tse_` 和 `otc_` 兩個 key 都送出去。無效的 key 會回傳 `c` 是空字串的項目，直接忽略。
- 回傳 `msgArray[]` 的欄位：

| 欄位 | 意義 |
|---|---|
| `c` | 代號 |
| `n` | 名稱 |
| `ex` | `tse` 或 `otc` |
| `z` | 成交價，**可能是 `"-"`** |
| `pz` | 上一筆成交價 |
| `y` | 昨收 |
| `o` `h` `l` | 開、高、低 |
| `v` | 累計成交量（張） |
| `u` `w` | 漲停、跌停 |
| `t` | 時間 `13:30:00` |
| `b` `g` | 五檔買價、買量，用 `_` 分隔，結尾也有 `_` |
| `a` `f` | 五檔賣價、賣量 |

- 現價的取法（依序往下）：`z` → `pz` → 第一檔買價 → `y`（開盤前）。
- t00、o00 的名稱要自己設成「加權指數」「櫃買指數」，market 標成 `idx`。
- 數字字串可能是 `"-"`、空字串或帶逗號，一律轉成 NaN 或 nil。

### 3.2 Yahoo Finance 圖表（K 線、分時、海外報價）
```
GET https://query1.finance.yahoo.com/v8/finance/chart/<symbol>?range=<r>&interval=<i>&includePrePost=false
```
- symbol 對照：上市 `2330.TW`、上櫃 `6488.TWO`、加權 `^TWII`、櫃買 `^TWOII`；海外直接用原代號，例如 `NVDA`、`^DJI`、`TWD=X`、`GC=F`、`BTC-USD`。
- **個股頁要先用 MIS 的 `ex` 判斷 .TW 還是 .TWO**；如果 MIS 沒有回傳，就看全市場清單的 market。
- 各圖表的 range/interval：走勢 `1d/1m`（如果完全沒有資料，例如休市日，就改用 `5d/5m`）、日K `2y/1d`、週K `10y/1wk`、月K `max/1mo`。
- 解析：`chart.result[0]` 底下有 `timestamp[]`、`indicators.quote[0].{open,high,low,close,volume}[]`。**陣列裡可能有 null**：close 是 null 的那一筆整筆跳過；open/high/low 是 null 就用 close 代替；volume 是 null 就當 0。
- `meta` 欄位：`regularMarketPrice`、`previousClose`（沒有就用 `chartPreviousClose`）、`regularMarketDayHigh/Low`、`regularMarketVolume`、`regularMarketTime`（秒）、`shortName`/`longName`。
- **台股（.TW/.TWO）的 volume 單位是股，要除以 1000 換成張。**

### 3.3 全市場日資料（排行、選股、搜尋、判斷上市/上櫃）
- 上市：`https://openapi.twse.com.tw/v1/exchangeReport/STOCK_DAY_ALL`，欄位有 `Code` `Name` `Date` `ClosingPrice` `Change` `OpeningPrice` `HighestPrice` `LowestPrice` `TradeVolume`（股，要除以 1000） `TradeValue`。
- 上櫃：`https://www.tpex.org.tw/openapi/v1/tpex_mainboard_daily_close_quotes`，欄位有 `SecuritiesCompanyCode` `CompanyName` `Date` `Close` `Change`（**可能有空白，要 trim**） `Open` `High` `Low` `TradingShares`（股，要除以 1000） `TransactionAmount`。
- 本益比、殖利率、淨值比：上市 `https://openapi.twse.com.tw/v1/exchangeReport/BWIBBU_ALL`（`Code` `PEratio` `DividendYield` `PBratio`）；上櫃 `https://www.tpex.org.tw/openapi/v1/tpex_mainboard_peratio_analysis`（`SecuritiesCompanyCode` `PriceEarningRatio` `YieldRatio` `PriceBookRatio`）。這兩個抓失敗不影響其他功能。
- **只收股票和 ETF**，代號要符合正規式 `^(\d{4}[A-Z]?|00\d{2,4}[A-Z]?)$`；權證、牛熊證都不要收，不然排行會被權證洗版。
- 漲跌幅 = Change / (Close − Change) × 100。
- 日期是民國年，例如 `1151014` 要顯示成 `115/10/14`。上市和上櫃的資料日可能差一天，這時兩個都顯示：「上市 115/10/14、上櫃 115/10/15」。
- 快取 6 小時；設定裡的「立即更新全市場資料」可以強制重抓。

### 3.4 新聞
`https://tw.stock.yahoo.com/rss?category=tw-market`（國際財經是 `intl-markets`）。標準 RSS：`<item><title><![CDATA[..]]></title><link>..</link><pubDate>RFC822</pubDate>`，日期顯示成 `MM/dd HH:mm`。

### 3.5 檢查更新
`https://api.github.com/repos/achir1015/xiaoke-stock/releases` → 找出 `tag_name` 以 `ios-v` 開頭的最新一筆，比對 App 的 `CFBundleShortVersionString`（逐段比數字）。

## 4. 指標算法（照 ChartView.java，結果要跟 Android 版一致）
- MA(n)：簡單移動平均，前 n−1 根是 NaN。畫 MA5（白）、MA20（#FFB020）、MA60（#5EA8FF）。
- KD(9,3,3)：RSV = (C − 9日最低) / (9日最高 − 9日最低) × 100（如果最高等於最低就當 50）；K = K前 × 2/3 + RSV/3，D = D前 × 2/3 + K/3，K 和 D 的初始值都是 50。
- MACD(12,26,9)：EMA12、EMA26 的初始值都用第一根收盤價，α = 2/(n+1)；DIF = EMA12 − EMA26；DEA 是 DIF 的 9 日 EMA（初始值用第一個 DIF）；OSC = DIF − DEA，紅柱代表 ≥ 0。
- 分時走勢：**X 軸依時間**（從第一根的時間算起，一分鐘一格，總共 max(270, 實際分鐘數) 格），沒有成交的分鐘要留空，不要把線壓縮在一起；Y 軸以昨收為中心上下對稱；均價線 = 累計(價×量)/累計量（指數沒有成交量就不畫均價線，量的區塊顯示「指數無成交量資料」）。
- K 線互動：左右拖曳可以捲動、雙指縮放（同時顯示 20–300 根）、長按後滑動顯示十字線，上方圖例跟著顯示當根的開、高、低、收。

## 5. 儲存格式（UserDefaults）
| key | 格式 |
|---|---|
| `groups` | `[{"name":"自選1","codes":["2330","NVDA"]}, ...]` |
| `group` | 目前群組的 index（Int） |
| `alerts` | `[{"code":"2330","name":"台積電","above":2500,"below":2300,"pct":5,"on":true,"fired":"20261016"}]`（above、below、pct 可以省略） |
| `refresh` | 自動更新秒數，預設 10 |
| `showPct` | Bool，預設 true |
| `tab` | 最後停留的分頁 |

## 6. 外觀
- 色碼：背景 `#101522`、卡片 `#1A2133`、分隔線 `#2A3247`、文字 `#E8ECF4`、次要文字 `#8A93A8`、上漲 `#FF4D4F`、下跌 `#22C55E`、強調色 `#FFB020`、粉紅 `#FF6F91`。
- 一律深色模式；底部 TabView 有 5 個分頁：★自選、📈大盤、🏆排行、🔎選股、📰新聞；右上角是 🔍搜尋和 ⚙設定。
- 價格格式：絕對值 ≥ 10000 顯示千分位和兩位小數，≥ 1000 顯示一位小數，其他顯示兩位小數。成交量 ≥ 1 億顯示「x.xx億」，≥ 1 萬顯示「x.x萬」。
- App 圖示：小柯機器人的臉，右上角是 K 線和上升走勢線，向量路徑在 `res/mipmap/ic_launcher.xml`（512×512 畫布，背景 `#101522` 圓角方形）。請轉成 1024×1024 的 PNG 放進 AppIcon。
- 小柯念報價的句子格式照 `Speaker.describe`，例如「台積電，2440元，下跌50元，跌幅2.01%。」（指數和海外商品不加「元」）。

## 7. 版本與發布
- iOS 版從 `1.0.0` 開始，Release 的 tag 用 `ios-v1.0.0`，這樣不會跟 Android 版的 `v1.x.x` 混在一起；Android 版的「檢查更新」只看 `releases/latest`，所以發布 iOS Release 時要**取消勾選**「Set as the latest release」，不然 Android 版會抓到 iOS 版的 tag。
- 提交 commit 時用：`git -c user.name=achir1015 -c user.email=achir1015@gmail.com commit ...`；`.gitignore` 要加上 `ios/build/`、`*.xcodeproj/xcuserdata`、`DerivedData`。
- README 加一節「iPhone 版」，寫安裝方式。

## 8. 驗收清單
1. 自選頁可以看到 2330 的即時價；開盤前會顯示昨收。
2. 個股頁的走勢、日K、週K、月K 都畫得出來，KD 和 MACD 可以切換，長按會出現十字線。
3. 加入一檔美股（NVDA）後，能看到報價和 K 線。
4. 排行頁沒有權證（例如 6 碼、結尾是 U 的代號）。
5. 選股頁按「高殖利率」再按開始，會列出結果。
6. 設定到價提醒後，按「立即檢查」能收到本機通知（開盤時間測試）。
7. 「🤖 小柯唸自選」用中文念報價。
