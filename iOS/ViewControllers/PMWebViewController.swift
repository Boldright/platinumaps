import UIKit
import WebKit

class PMWebViewController: UIViewController {

    @IBOutlet weak var webView: WKWebView!
    
    @IBOutlet weak var navbarView: UIView!
    @IBOutlet weak var navbarViewHeight: NSLayoutConstraint!
    @IBOutlet weak var doneButton: UIButton!
    
    public var pageUrl: URL? = nil
    
    override func viewDidLoad() {
        super.viewDidLoad()
        
        if #available(iOS 13.0, *) {
            // Navbar不要
            navbarView.isHidden = true
            navbarViewHeight.constant = 0
        } else {
            // 完了ボタン
            doneButton.addTarget(self, action: #selector(onTapDone), for: .touchUpInside)
        }

        // Do any additional setup after loading the view.
        guard let url = pageUrl else {
            return
        }
        webView.load(URLRequest(url: url))
    }
    
    @objc func onTapDone() {
        self.dismiss(animated: true, completion: nil)
    }
}
