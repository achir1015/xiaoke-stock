# 三竹股市 APK 逆向分析（REA）與小柯股市助手對應表

## 分析對象
- 檔案：`三竹股市-…_8.36.3.228.RDX3.2.1063.RDX13_APKPure.apk`（136 MB）
- REA `inspect_android_package`（Evidence `ev_5cba78fe…05ea5`）：
  套件 `com.mtk`，版本 8.36.3（versionCode 199），minSdk 24 / targetSdk 36，
  共 29,965 個 class、9,063 個資源。
- REA `search_android_classes "com.mitake.function."`（Evidence `ev_72c775d0…3650a`）：
  1,096 個功能 class（大部分有混淆，名稱像 `a`、`b0`），依套件分組可以看出各功能模組。
- apktool 解出 assets，其中 `Preload_*.zip` 是預先載入的伺服器設定（`101-6/api.txt` 伺服器清單、
  `206-3/api.txt` 智慧選股條件、`207-2` 商品樹、`208-4` 升降單位、`210-2` 券商清單）。

## 觀察到的功能模組（Observed）
| 三竹模組 | 功能 | 小柯股市助手的做法 |
|---|---|---|
| `MTKStkGroupFullProvider` / `MTKStkQuoteProvider` | 自選股桌面小工具 | `QuoteWidget`：自選群組報價小工具 |
| `CustomList`、`editmanager` | 自選股群組（最多 60 檔、10 個群組） | `Store`：多群組自選，可新增/刪除/改名 |
| `MinutePriceV2`、`RTDiagram`、`detailQuote` | 個股走勢、五檔 | `StockActivity`：即時報價、最佳五檔、分時走勢 |
| `classical`、`TechDiagram` | 技術分析 K 線 | `ChartView`：日/週/月 K、MA5/20/60、成交量、KD、MACD |
| `index`、`InternationalFrameV2` | 大盤、國際指數 | 大盤分頁：加權、櫃買、美日港指數、匯率 |
| `mtksmart`、`206-3` 智慧選股 | 條件選股 | 選股分頁：本益比、殖利率、淨值比、股價、成交量 |
| `heat_map`、排行 | 排行榜 | 排行分頁：漲幅、跌幅、成交量、成交值 |
| `newAlert`、`Alert_Setting` | 到價提醒 | `AlertJob`：盤中定期檢查到價、推播通知 |
| `news`、`NewsChannel` | 新聞 | 新聞分頁：Yahoo 股市 RSS |
| `208-4` 升降單位、`212-2` 小數位 | 價格格式 | `Ui.fmt` 依股價決定小數位數 |

## 沒有沿用的部分
- 三竹的行情和新聞來自自家伺服器（`fget*.mitake.com.tw`、`newsapi*.mtkstock.com.tw` 等），連線要用三竹的金鑰（`221-2` 內的 RSA 公鑰）跟授權，這些都屬於三竹的商業服務，所以新 App 不呼叫這些伺服器，也不複製三竹的程式碼、圖示或皮膚素材。
- 下單（`placeorder`、各家券商模組 `com.mitake.mls/snp/…`）、廣告、論壇、聊天室、影音這幾塊都需要券商或三竹的帳號，所以沒有做。

## 資料來源（公開）
- 即時報價：證交所 MIS `mis.twse.com.tw/stock/api/getStockInfo.jsp`（上市 tse_、上櫃 otc_，指數 t00/o00）
- 走勢、K 線、美股、國際指數：Yahoo Finance chart API
- 全市場日資料：證交所 OpenAPI `STOCK_DAY_ALL`、`BWIBBU_ALL`，以及櫃買中心 OpenAPI
- 新聞：Yahoo 股市 RSS

## 尚未確認（Unknowns）
- 三竹各功能的實際網路協定只用靜態方式看過，沒有實際執行或攔截，所以上面的對應表是依名稱和設定檔推論的。
