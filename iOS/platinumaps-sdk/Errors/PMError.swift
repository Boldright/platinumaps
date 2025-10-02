import Foundation

enum PMError: Error {
    /// 定形外
    case dynamic(reason: String)
    
    /// インスタンス破棄済み（非同期・弱参照）
    case gone
}
