import UIKit
import WebKit

class PMWebViewController: UIViewController {
    
    public var pageUrl: URL? = nil
    
    override func viewDidLoad() {
        super.viewDidLoad()
        
        view.backgroundColor = UIColor.white

        let webView = WKWebView(frame: CGRect.zero)
        webView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(webView)
        NSLayoutConstraint.activate([
            webView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            webView.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            webView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        ])
        
        if let nb = navigationController {
            let doneItem = UIBarButtonItem(barButtonSystemItem: .close, target: self, action: #selector(onTapDone))
            nb.navigationBar.topItem?.leftBarButtonItem = doneItem
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
