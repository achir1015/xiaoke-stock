import SwiftUI

/// 分時走勢與 K 線圖（同 Android ChartView.java）。
/// K 線：左右拖曳捲動、雙指縮放、長按後滑動顯示十字線、點一下關掉十字線；主圖 MA5/20/60，下方成交量與 KD 或 MACD。
struct ChartData {
    enum Mode { case intraday, kline }
    var bars = Bars()
    var mode = Mode.kline
    var baseline = Double.nan          // 分時：昨收
    var timeFmt = "yy/MM/dd"
    var ma5: [Double] = [], ma20: [Double] = [], ma60: [Double] = []
    var k: [Double] = [], d: [Double] = [], dif: [Double] = [], dea: [Double] = [], osc: [Double] = []
    var timeAxis = false               // 分時依時間排（沒成交的分鐘留空）
    var t0 = 0, span = 270

    static func intraday(_ b: Bars, prev: Double) -> ChartData {
        var c = ChartData()
        c.bars = b; c.mode = .intraday; c.baseline = prev; c.timeFmt = "HH:mm"
        let n = b.count
        c.timeAxis = n > 1 && b.t[n - 1] - b.t[0] < 86400     // 只有單日資料才依時間排
        if c.timeAxis {
            c.t0 = b.t[0]
            c.span = max(270, (b.t[n - 1] - c.t0) / 60 + 1)
        }
        return c
    }

    static func kline(_ b: Bars, fmt: String) -> ChartData {
        var c = ChartData()
        c.bars = b; c.mode = .kline; c.timeFmt = fmt
        c.ma5 = ma(b.c, 5); c.ma20 = ma(b.c, 20); c.ma60 = ma(b.c, 60)
        let n = b.count
        // KD(9,3,3)
        var pk = 50.0, pd = 50.0
        for i in 0..<n {
            let lo = b.l[max(0, i - 8)...i].min() ?? 0, hi = b.h[max(0, i - 8)...i].max() ?? 0
            let rsv = hi > lo ? (b.c[i] - lo) / (hi - lo) * 100 : 50
            pk = pk * 2 / 3 + rsv / 3
            pd = pd * 2 / 3 + pk / 3
            c.k.append(pk); c.d.append(pd)
        }
        // MACD(12,26,9)
        var e12 = n > 0 ? b.c[0] : 0, e26 = e12, s9 = 0.0
        for i in 0..<n {
            e12 += (b.c[i] - e12) * 2 / 13
            e26 += (b.c[i] - e26) * 2 / 27
            let df = e12 - e26
            s9 = i == 0 ? df : s9 + (df - s9) * 2 / 10
            c.dif.append(df); c.dea.append(s9); c.osc.append(df - s9)
        }
        return c
    }

    static func ma(_ c: [Double], _ n: Int) -> [Double] {
        var r: [Double] = [], sum = 0.0
        for i in 0..<c.count {
            sum += c[i]
            if i >= n { sum -= c[i - n] }
            r.append(i >= n - 1 ? sum / Double(n) : .nan)
        }
        return r
    }
}

struct ChartView: View {
    let data: ChartData?
    var kd = true                      // false = MACD

    @State private var visible = 70
    @State private var endOffset = 0
    @State private var cross: Int?
    @State private var dragStart: Int?
    @State private var zoomStart: Int?

    private let rightPad: CGFloat = 54

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            Canvas { ctx, size in draw(&ctx, size) }
                .contentShape(Rectangle())
                .onTapGesture { cross = nil }
                .gesture(LongPressGesture(minimumDuration: 0.3).sequenced(before: DragGesture(minimumDistance: 0))
                    .onChanged { v in
                        if case .second(true, let drag) = v {
                            cross = index(at: drag?.location.x ?? w / 2, width: w)
                        }
                    })
                .simultaneousGesture(DragGesture(minimumDistance: 12)
                    .onChanged { v in
                        guard cross == nil, let dt = data, dt.mode == .kline,
                              abs(v.translation.width) > abs(v.translation.height) else { return }
                        if dragStart == nil { dragStart = endOffset }
                        let bw = barWidth(w)
                        let n = dt.bars.count
                        endOffset = max(0, min(max(0, n - visible), (dragStart ?? 0) + Int(v.translation.width / bw)))
                    }
                    .onEnded { _ in dragStart = nil })
                .simultaneousGesture(MagnifyGesture()
                    .onChanged { v in
                        guard let dt = data, dt.mode == .kline else { return }
                        if zoomStart == nil { zoomStart = visible }
                        let n = dt.bars.count
                        visible = max(20, min(max(20, min(300, n)), Int(Double(zoomStart ?? 70) / v.magnification)))
                        endOffset = max(0, min(endOffset, max(0, n - visible)))
                    }
                    .onEnded { _ in zoomStart = nil })
        }
        .background(Theme.bg)
        .onChange(of: data?.bars.t.last) { _, _ in cross = nil; endOffset = 0 }
    }

    // ───────── 版面 ─────────
    private func left() -> CGFloat { 4 }
    private func right(_ w: CGFloat) -> CGFloat { w - rightPad }

    private func barWidth(_ w: CGFloat) -> CGFloat {
        guard let d = data else { return 1 }
        if d.mode == .intraday && d.timeAxis { return (right(w) - left()) / CGFloat(d.span) }
        let n = d.mode == .kline ? min(visible, d.bars.count) : max(d.bars.count, 270)
        return (right(w) - left()) / CGFloat(max(1, n))
    }

    private func first() -> Int {
        guard let d = data else { return 0 }
        return d.mode == .kline ? max(0, d.bars.count - endOffset - visible) : 0
    }

    private func last() -> Int {
        guard let d = data else { return 0 }
        return d.mode == .kline ? d.bars.count - endOffset - 1 : d.bars.count - 1
    }

    /// 第 i 根的格位：分時依分鐘，其他依筆數
    private func pos(_ i: Int) -> CGFloat {
        guard let d = data else { return 0 }
        if d.mode == .intraday && d.timeAxis { return CGFloat(d.bars.t[i] - d.t0) / 60 }
        return CGFloat(i - first())
    }

    private func index(at x: CGFloat, width w: CGFloat) -> Int? {
        guard let d = data, d.bars.count > 0 else { return nil }
        let slot = (x - left()) / barWidth(w)
        if d.mode == .intraday && d.timeAxis {
            var best = 0
            for i in 1..<d.bars.count where abs(pos(i) - slot) < abs(pos(best) - slot) { best = i }
            return best
        }
        return max(first(), min(last(), first() + Int(slot)))
    }

    // ───────── 繪圖 ─────────
    private func draw(_ ctx: inout GraphicsContext, _ size: CGSize) {
        guard let d = data, d.bars.count > 0 else {
            ctx.draw(Text("載入中或暫無資料…").font(.system(size: 13)).foregroundColor(Theme.sub),
                     at: CGPoint(x: 16, y: 24), anchor: .leading)
            return
        }
        let b = d.bars, w = size.width, h = size.height
        let kline = d.mode == .kline
        let top: CGFloat = 18
        let mainBottom = kline ? h * 0.58 : h * 0.74
        let volTop = mainBottom + 14, volBottom = kline ? h * 0.76 : h - 14
        let subTop = volBottom + 14, subBottom = h - 14
        let a = first(), z = last()
        guard a <= z else { return }

        var hi = -Double.infinity, lo = Double.infinity, vmax = 0.0
        for i in a...z {
            hi = max(hi, b.h[i]); lo = min(lo, b.l[i]); vmax = max(vmax, b.v[i])
            if kline { for m in [d.ma5, d.ma20, d.ma60] where !m[i].isNaN { hi = max(hi, m[i]); lo = min(lo, m[i]) } }
        }
        if !kline && !d.baseline.isNaN {     // 以昨收為中線上下對稱
            var r = max(abs(hi - d.baseline), abs(lo - d.baseline))
            if r == 0 { r = d.baseline * 0.01 }
            hi = d.baseline + r; lo = d.baseline - r
        }
        if hi <= lo { hi += 1; lo -= 1 }
        let pad = (hi - lo) * 0.05
        hi += pad; lo -= pad
        func y(_ v: Double, _ t: CGFloat, _ bt: CGFloat, _ h: Double, _ l: Double) -> CGFloat {
            t + (bt - t) * CGFloat((h - v) / (h - l))
        }
        func label(_ s: String, _ p: CGPoint, _ c: Color, _ anchor: UnitPoint = .leading) {
            ctx.draw(Text(s).font(.system(size: 10)).foregroundColor(c), at: p, anchor: anchor)
        }

        // 格線與價格座標
        for g in 0...4 {
            let yy = top + (mainBottom - top) * CGFloat(g) / 4
            ctx.stroke(Path { $0.move(to: CGPoint(x: left(), y: yy)); $0.addLine(to: CGPoint(x: right(w), y: yy)) }, with: .color(Theme.line))
            let v = hi - (hi - lo) * Double(g) / 4
            label(Fmt.price(v), CGPoint(x: right(w) + 4, y: yy), !kline && !d.baseline.isNaN ? Theme.color(v - d.baseline) : Theme.sub)
        }
        ctx.stroke(Path { $0.move(to: CGPoint(x: left(), y: volBottom)); $0.addLine(to: CGPoint(x: right(w), y: volBottom)) }, with: .color(Theme.line))

        let bw = barWidth(w)
        if kline {
            for i in a...z {
                let x = left() + pos(i) * bw, cx = x + bw / 2
                let yo = y(b.o[i], top, mainBottom, hi, lo), yc = y(b.c[i], top, mainBottom, hi, lo)
                let col = b.c[i] > b.o[i] ? Theme.up : b.c[i] < b.o[i] ? Theme.down : Theme.flat
                ctx.stroke(Path { $0.move(to: CGPoint(x: cx, y: y(b.h[i], top, mainBottom, hi, lo)))
                    $0.addLine(to: CGPoint(x: cx, y: y(b.l[i], top, mainBottom, hi, lo))) }, with: .color(col), lineWidth: 1)
                ctx.fill(Path(CGRect(x: x + bw * 0.12, y: min(yo, yc), width: bw * 0.76, height: max(1, abs(yc - yo)))), with: .color(col))
            }
            series(&ctx, d.ma5, a, z, bw, top, mainBottom, hi, lo, .white)
            series(&ctx, d.ma20, a, z, bw, top, mainBottom, hi, lo, Theme.accent)
            series(&ctx, d.ma60, a, z, bw, top, mainBottom, hi, lo, Theme.blue)
        } else {
            if !d.baseline.isNaN {
                let yb = y(d.baseline, top, mainBottom, hi, lo)
                ctx.stroke(Path { $0.move(to: CGPoint(x: left(), y: yb)); $0.addLine(to: CGPoint(x: right(w), y: yb)) },
                           with: .color(Theme.sub), style: StrokeStyle(lineWidth: 1, dash: [6, 4]))
            }
            var line = Path(), fill = Path(), avg = Path()
            var sumPV = 0.0, sumV = 0.0
            for i in a...z {
                let x = left() + (pos(i) + 0.5) * bw
                let yy = y(b.c[i], top, mainBottom, hi, lo)
                sumPV += b.c[i] * b.v[i]; sumV += b.v[i]
                let ya = y(sumV > 0 ? sumPV / sumV : b.c[i], top, mainBottom, hi, lo)
                if i == a { line.move(to: CGPoint(x: x, y: yy)); fill.move(to: CGPoint(x: x, y: mainBottom)); avg.move(to: CGPoint(x: x, y: ya)) }
                else { line.addLine(to: CGPoint(x: x, y: yy)); avg.addLine(to: CGPoint(x: x, y: ya)) }
                fill.addLine(to: CGPoint(x: x, y: yy))
                if i == z { fill.addLine(to: CGPoint(x: x, y: mainBottom)); fill.closeSubpath() }
            }
            let col = Theme.color(b.c[z] - d.baseline)
            ctx.fill(fill, with: .color(col.opacity(0.2)))
            ctx.stroke(line, with: .color(col), lineWidth: 1.6)
            if sumV > 0 { ctx.stroke(avg, with: .color(Theme.accent), lineWidth: 1.2) }   // 指數沒有成交量，不畫均價線
        }

        // 成交量
        for i in a...z {
            let x = left() + pos(i) * bw
            let yy = volBottom - (volBottom - volTop) * CGFloat(vmax > 0 ? b.v[i] / vmax : 0)
            let ref = kline ? b.o[i] : (i > 0 ? b.c[i - 1] : d.baseline)
            ctx.fill(Path(CGRect(x: x + bw * 0.15, y: yy, width: max(0.5, bw * 0.7), height: volBottom - yy)), with: .color(Theme.color(b.c[i] - ref)))
        }
        if vmax > 0 { label("量 " + Fmt.vol(vmax), CGPoint(x: right(w) + 4, y: volTop + 4), Theme.sub) }
        else { label("指數無成交量資料", CGPoint(x: left(), y: volTop + 10), Theme.sub) }

        // 副圖
        if kline {
            ctx.stroke(Path { $0.move(to: CGPoint(x: left(), y: subTop)); $0.addLine(to: CGPoint(x: right(w), y: subTop)) }, with: .color(Theme.line))
            if kd {
                for g in [20.0, 50, 80] {
                    let yy = y(g, subTop, subBottom, 100, 0)
                    ctx.stroke(Path { $0.move(to: CGPoint(x: left(), y: yy)); $0.addLine(to: CGPoint(x: right(w), y: yy)) }, with: .color(Theme.line))
                    label(String(Int(g)), CGPoint(x: right(w) + 4, y: yy), Theme.sub)
                }
                series(&ctx, d.k, a, z, bw, subTop, subBottom, 100, 0, Theme.accent)
                series(&ctx, d.d, a, z, bw, subTop, subBottom, 100, 0, Theme.blue)
            } else {
                var m = 0.0
                for i in a...z { m = max(m, abs(d.osc[i]), abs(d.dif[i]), abs(d.dea[i])) }
                if m == 0 { m = 1 }
                let zero = y(0, subTop, subBottom, m, -m)
                for i in a...z {
                    let x = left() + pos(i) * bw
                    let yy = y(d.osc[i], subTop, subBottom, m, -m)
                    ctx.fill(Path(CGRect(x: x + bw * 0.2, y: min(zero, yy), width: max(0.5, bw * 0.6), height: abs(zero - yy))),
                             with: .color(d.osc[i] >= 0 ? Theme.up : Theme.down))
                }
                series(&ctx, d.dif, a, z, bw, subTop, subBottom, m, -m, Theme.accent)
                series(&ctx, d.dea, a, z, bw, subTop, subBottom, m, -m, Theme.blue)
            }
        }

        // 日期座標
        let f = DateFormatter()
        f.dateFormat = d.timeFmt
        label(f.string(from: Date(timeIntervalSince1970: Double(b.t[a]))), CGPoint(x: left(), y: h - 6), Theme.sub)
        label(f.string(from: Date(timeIntervalSince1970: Double(b.t[z]))), CGPoint(x: right(w), y: h - 6), Theme.sub, .trailing)

        // 圖例 / 十字線
        let i = cross ?? z
        let dateS = f.string(from: Date(timeIntervalSince1970: Double(b.t[i])))
        if kline {
            label("\(dateS)  開\(Fmt.price(b.o[i])) 高\(Fmt.price(b.h[i])) 低\(Fmt.price(b.l[i])) 收\(Fmt.price(b.c[i]))",
                  CGPoint(x: left(), y: 8), Theme.text)
            legend(&ctx, CGPoint(x: left(), y: mainBottom + 7),
                   [("MA5 " + Fmt.fix(d.ma5[i]), .white), ("MA20 " + Fmt.fix(d.ma20[i]), Theme.accent), ("MA60 " + Fmt.fix(d.ma60[i]), Theme.blue)])
            if kd {
                legend(&ctx, CGPoint(x: left(), y: volBottom + 7), [("K " + Fmt.fix(d.k[i]), Theme.accent), ("D " + Fmt.fix(d.d[i]), Theme.blue)])
            } else {
                legend(&ctx, CGPoint(x: left(), y: volBottom + 7),
                       [("DIF " + Fmt.fix(d.dif[i]), Theme.accent), ("MACD " + Fmt.fix(d.dea[i]), Theme.blue), ("OSC " + Fmt.fix(d.osc[i]), Theme.color(d.osc[i]))])
            }
        } else {
            label("\(dateS)  成交 \(Fmt.price(b.c[i]))  量 \(Fmt.vol(b.v[i]))", CGPoint(x: left(), y: 8),
                  d.baseline.isNaN ? Theme.text : Theme.color(b.c[i] - d.baseline))
        }
        if let c = cross {
            let x = left() + (pos(c) + 0.5) * bw
            let yy = y(b.c[c], top, mainBottom, hi, lo)
            ctx.stroke(Path { $0.move(to: CGPoint(x: x, y: top)); $0.addLine(to: CGPoint(x: x, y: kline ? subBottom : volBottom))
                $0.move(to: CGPoint(x: left(), y: yy)); $0.addLine(to: CGPoint(x: right(w), y: yy)) }, with: .color(Theme.accent))
            ctx.fill(Path(CGRect(x: right(w), y: yy - 7, width: rightPad, height: 14)), with: .color(Theme.accent))
            label(Fmt.price(b.c[c]), CGPoint(x: right(w) + 4, y: yy), Theme.bg)
        }
    }

    private func series(_ ctx: inout GraphicsContext, _ s: [Double], _ a: Int, _ z: Int, _ bw: CGFloat,
                        _ top: CGFloat, _ bottom: CGFloat, _ hi: Double, _ lo: Double, _ color: Color) {
        var p = Path(), started = false
        for i in a...z where !s[i].isNaN {
            let pt = CGPoint(x: left() + (pos(i) + 0.5) * bw, y: top + (bottom - top) * CGFloat((hi - s[i]) / (hi - lo)))
            if started { p.addLine(to: pt) } else { p.move(to: pt); started = true }
        }
        ctx.stroke(p, with: .color(color), lineWidth: 1.2)
    }

    private func legend(_ ctx: inout GraphicsContext, _ at: CGPoint, _ items: [(String, Color)]) {
        var x = at.x
        for (s, c) in items {
            let t = ctx.resolve(Text(s).font(.system(size: 10)).foregroundColor(c))
            ctx.draw(t, at: CGPoint(x: x, y: at.y), anchor: .leading)
            x += t.measure(in: CGSize(width: 400, height: 20)).width + 10
        }
    }
}
