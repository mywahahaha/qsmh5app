import UIKit

enum WebPageLayout {
    static func frame(for page: PageSpec, bounds: CGRect, safeArea: CGRect) -> CGRect {
        // 双 100% 明确表示整个宿主窗口；H5 自行用 safe-area-inset-* 避让操作区。
        if page.width == .percent(1), page.height == .percent(1) { return bounds }
        let width = min(safeArea.width, max(1, page.width.resolve(in: safeArea.width)))
        let height = min(safeArea.height, max(1, page.height.resolve(in: safeArea.height)))
        let left = min(max(0, page.left.resolve(in: safeArea.width, length: width)), safeArea.width-width)
        let top = min(max(0, page.top.resolve(in: safeArea.height, length: height)), safeArea.height-height)
        return CGRect(x: safeArea.minX+left, y: safeArea.minY+top, width: width, height: height)
    }
}
