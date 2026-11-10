import Foundation
import AppKit
import ImageIO

func fail(_ text: String) -> Never { FileHandle.standardError.write(Data((text + "\n").utf8)); exit(1) }
let args = CommandLine.arguments
if args.count < 3 { fail("用法：images inspect PNG | icon PNG 输出资产目录") }
let url = URL(fileURLWithPath: args[2])
guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
      CGImageSourceGetType(source) as String? == "public.png",
      let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString:Any],
      let w = props[kCGImagePropertyPixelWidth] as? Int, let h = props[kCGImagePropertyPixelHeight] as? Int,
      w > 0, h > 0, w <= 10000, h <= 10000, w * h <= 60_000_000,
      let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { fail("无法解码 PNG 或图片尺寸超限") }
// 扫描实际 alpha，接受具有 alpha 通道但所有像素均不透明的 PNG。
var rgba = [UInt8](repeating: 0, count: image.width * image.height * 4)
let opaque = rgba.withUnsafeMutableBytes { bytes -> Bool in
    guard let ctx = CGContext(data: bytes.baseAddress, width: image.width, height: image.height,
        bitsPerComponent: 8, bytesPerRow: image.width * 4, space: CGColorSpaceCreateDeviceRGB(),
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
    ctx.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
    return stride(from: 3, to: bytes.count, by: 4).allSatisfy { bytes[$0] == 255 }
}
if args[1] == "inspect" {
    let data = try JSONSerialization.data(withJSONObject: ["width": image.width, "height": image.height, "opaque": opaque])
    print(String(decoding: data, as: UTF8.self)); exit(0)
}
let compatible = args[1] == "icon-compatible"
guard (args[1] == "icon" || compatible), args.count == 4, (compatible || (image.width == 1024 && image.height == 1024 && opaque)), image.width == image.height else { fail("图标需要 1024×1024 不透明 PNG") }
let folder = URL(fileURLWithPath: args[3]); try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
var entries = [[String: String]]()
for (size, scales) in [(20, [2,3]), (29,[2,3]), (40,[2,3]), (60,[2,3]), (1024,[1])] {
    for scale in scales {
        let pixels = size * scale; let name = "icon-\(size)-\(scale).png"
        guard let ctx = CGContext(data: nil, width: pixels, height: pixels, bitsPerComponent: 8, bytesPerRow: 0,
                                 space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { fail("图标生成失败") }
        ctx.setFillColor(CGColor(gray: 0, alpha: 1)); ctx.fill(CGRect(x: 0, y: 0, width: pixels, height: pixels))
        ctx.interpolationQuality = .high; ctx.draw(image, in: CGRect(x: 0, y: 0, width: pixels, height: pixels))
        guard let rendered = ctx.makeImage(), let dest = CGImageDestinationCreateWithURL(folder.appendingPathComponent(name) as CFURL, "public.png" as CFString, 1, nil) else { fail("图标写入失败") }
        CGImageDestinationAddImage(dest, rendered, nil); guard CGImageDestinationFinalize(dest) else { fail("图标编码失败") }
        entries.append(["idiom": size == 1024 ? "ios-marketing" : "iphone", "size": "\(size)x\(size)", "scale": "\(scale)x", "filename": name])
    }
}
let json = try JSONSerialization.data(withJSONObject: ["images": entries, "info": ["version":1,"author":"xcode"]], options: [.prettyPrinted,.sortedKeys])
try json.write(to: folder.appendingPathComponent("Contents.json"))
