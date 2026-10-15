// 產生 iPhone App 圖示：小柯臉＋右上角 K 線與上升走勢（同 Android res/mipmap/ic_launcher.xml，512 座標）。
// 用法：swift tools/make_icon.swift XiaoKeStock/Assets.xcassets/AppIcon.appiconset/icon-1024.png
import AppKit

let out = CommandLine.arguments[1]
let size = 1024
let ctx = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0,
                    space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
ctx.translateBy(x: 0, y: CGFloat(size))
ctx.scaleBy(x: CGFloat(size) / 512, y: -CGFloat(size) / 512)   // 512 座標、y 向下

func rgb(_ v: UInt32, _ a: CGFloat = 1) -> CGColor {
    CGColor(srgbRed: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255, blue: CGFloat(v & 0xFF) / 255, alpha: a)
}

// 底色（iOS 會自己裁圓角，這裡填滿）
ctx.setFillColor(rgb(0x101522)); ctx.fill(CGRect(x: 0, y: 0, width: 512, height: 512))

// 彎月笑眼
ctx.setStrokeColor(rgb(0xFFFFFF)); ctx.setLineCap(.round); ctx.setLineWidth(28)
for cx in [180.0, 332.0] {
    ctx.addArc(center: CGPoint(x: cx, y: 250), radius: 60, startAngle: .pi, endAngle: 0, clockwise: false)
    ctx.strokePath()
}
// 腮紅
ctx.setFillColor(rgb(0xFF6F91, 0.5))
ctx.fillEllipse(in: CGRect(x: 84, y: 298, width: 88, height: 44))
ctx.fillEllipse(in: CGRect(x: 340, y: 298, width: 88, height: 44))
// 微笑
ctx.setLineWidth(20)
ctx.move(to: CGPoint(x: 206, y: 330)); ctx.addQuadCurve(to: CGPoint(x: 306, y: 330), control: CGPoint(x: 256, y: 376))
ctx.strokePath()

// 右上角 K 線（綠、紅、紅）與上升走勢
ctx.setLineCap(.butt); ctx.setLineWidth(6)
for (x, y0, y1, bx, by, bh, c) in [(372.0, 70.0, 146.0, 362.0, 92.0, 36.0, UInt32(0x22C55E)),
                                   (412, 48, 132, 402, 62, 52, 0xFF4D4F),
                                   (452, 30, 100, 442, 40, 44, 0xFF4D4F)] as [(CGFloat, CGFloat, CGFloat, CGFloat, CGFloat, CGFloat, UInt32)] {
    ctx.setStrokeColor(rgb(c)); ctx.move(to: CGPoint(x: x, y: y0)); ctx.addLine(to: CGPoint(x: x, y: y1)); ctx.strokePath()
    ctx.setFillColor(rgb(c)); ctx.fill(CGRect(x: bx, y: by, width: 20, height: bh))
}
ctx.setStrokeColor(rgb(0xFFB020)); ctx.setLineWidth(10); ctx.setLineCap(.round); ctx.setLineJoin(.round)
ctx.move(to: CGPoint(x: 352, y: 150)); ctx.addLine(to: CGPoint(x: 392, y: 118))
ctx.addLine(to: CGPoint(x: 428, y: 128)); ctx.addLine(to: CGPoint(x: 476, y: 72))
ctx.strokePath()

let rep = NSBitmapImageRep(cgImage: ctx.makeImage()!)
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: out))
print("已產生 \(out)")
