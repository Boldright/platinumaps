import UIKit
import WebKit

class PMWebViewController: UIViewController {

    public var pageUrl: URL? = nil
    
    override func viewDidLoad() {
        super.viewDidLoad()
        
        view.backgroundColor = UIColor.white
        
        let navBarView = UIView(frame: CGRect.zero)
        navBarView.backgroundColor = UIColor.white
        navBarView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(navBarView)
        let navBarViewHeigth = navBarView.heightAnchor.constraint(equalToConstant: 44)
        NSLayoutConstraint.activate([
            navBarView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            navBarViewHeigth,
            navBarView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            navBarView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        ])
        
        let doneButton = UIButton(type: .system)
        doneButton.setTitle("完了", for: .normal)
        doneButton.translatesAutoresizingMaskIntoConstraints = false
        navBarView.addSubview(doneButton)
        NSLayoutConstraint.activate([
            doneButton.leadingAnchor.constraint(equalTo: navBarView.leadingAnchor, constant: 16),
            doneButton.centerYAnchor.constraint(equalTo: navBarView.centerYAnchor)
        ])

        let webView = WKWebView(frame: CGRect.zero)
        webView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(webView)
        NSLayoutConstraint.activate([
            webView.topAnchor.constraint(equalTo: navBarView.bottomAnchor),
            webView.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            webView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        ])

        if #available(iOS 13.0, *) {
            // Navbar不要
            navBarView.isHidden = true
            navBarViewHeigth.constant = 0
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
