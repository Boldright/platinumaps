import UIKit
import AVFoundation

protocol PMQRCodeReaderViewControllerDelegate: NSObjectProtocol {
    func qrCodeReaderViewController(_ vc: PMQRCodeReaderViewController?, readString value: String?)
    func qrCodeReaderViewControllerClose(_ vc: PMQRCodeReaderViewController)
}

class PMQRCodeReaderViewController: UIViewController {

    @IBOutlet weak var baseView: UIView!
    
    @IBOutlet weak var previewView: PMCameraPreviewView!
    
    @IBOutlet weak var messageLabel: UILabel!

    @IBOutlet weak var cancelButton: UIButton!
    @IBOutlet weak var cancelButtonHeightConstraint: NSLayoutConstraint!
    
    var command: PMMainViewController.PMCommand? = nil
    var requestId: String? = nil

    private lazy var captureSession = AVCaptureSession()
    private lazy var captureOutput = AVCaptureMetadataOutput()
    
    private var isProsessing = false
    
    private var readValue: String? = nil

    public var eventDelegate: PMQRCodeReaderViewControllerDelegate? = nil
    
    public var safeArea = UIEdgeInsets.zero
    
    private var isFirstLayoutSubviews = false

    class func requestVideoAccess(_ completion: @escaping (Bool) -> Void) -> Bool {
        let status = AVCaptureDevice.authorizationStatus(for: .video)
        switch status {
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { (granted) in
                completion(granted)
            }
            return true
        case .authorized:
            completion(true)
            return true
        default:
            completion(false)
            return false
        }
    }
    
    deinit {
        NotificationCenter.default.removeObserver(self)
    }
    
    override func viewDidLoad() {
        super.viewDidLoad()
        
        // Do any additional setup after loading the view.
        
        // 背景はWebのものをそのまま見せたいのでこいつ自身は透明にする
        view.backgroundColor = UIColor(white: 0.0, alpha: 0.0)
        // そのままだと部品の背景色はOSの設定によるので明示的に指定する
        setupBackgoundColor(baseView)
        
        baseView.layer.cornerRadius = 20
        baseView.clipsToBounds = true
        
        let messageText = "カメラを二次元コードに\n向けてください。"
        var messageAttributes = [NSAttributedString.Key: Any]()
        let messageParagraphStyle = NSMutableParagraphStyle()
        messageParagraphStyle.lineSpacing = 10.0
        messageParagraphStyle.alignment = .center
        messageAttributes.updateValue(messageParagraphStyle, forKey: .paragraphStyle)
        messageLabel.attributedText = NSAttributedString(string: messageText, attributes: messageAttributes)

        cancelButton.backgroundColor = UIColor(red: 0xC2/0xFF, green: 0xC2/0xFF, blue: 0xC2/0xFF, alpha: 1.0)
        cancelButton.layer.cornerRadius = 22
        cancelButton.clipsToBounds = true

        NotificationCenter.default.addObserver(self,
                                               selector: #selector(willEnterForegroundNotification(_:)),
                                               name: UIApplication.willEnterForegroundNotification,
                                               object: nil)
        NotificationCenter.default.addObserver(self,
                                               selector: #selector(didEnterBackgroundNotification(_:)),
                                               name: UIApplication.didEnterBackgroundNotification,
                                               object: nil)

        isFirstLayoutSubviews = true
    }
    
    private func setupBackgoundColor(_ targetView: UIView) {
        targetView.subviews.forEach { (subview) in
            setupBackgoundColor(subview)
        }
        if targetView == previewView {
            targetView.backgroundColor = UIColor(white: 0.0, alpha: 1.0)
        } else if targetView == cancelButton {
            // --
        } else {
            targetView.backgroundColor = UIColor(white: 1.0, alpha: 1.0)
        }
    }

    @objc private func willEnterForegroundNotification(_ notification: Notification) {
        startCapture()
    }

    @objc private func didEnterBackgroundNotification(_ notification: Notification) {
        stopCapture()
    }

    /*
     // MARK: - Navigation
     
     // In a storyboard-based application, you will often want to do a little preparation before navigation
     override func prepare(for segue: UIStoryboardSegue, sender: Any?) {
     // Get the new view controller using segue.destination.
     // Pass the selected object to the new view controller.
     }
     */
    
    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        
        _ = PMQRCodeReaderViewController.requestVideoAccess { [weak self] (granted) in
            if granted {
                self?.setupCameraCapture()
            } else {
                self?.willCloseVC()
            }
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        stopCapture()
    }
    
    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
    }
    
    @IBAction func onCancelButtonTapped(_ sender: Any) {
        stopCapture()
        willCloseVC()
    }
    
    private func willCloseVC() {
        eventDelegate?.qrCodeReaderViewController(self, readString: readValue)
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(0)) { [weak self] in
            self?.closeVC()
        }
    }
    private func closeVC() {
        guard let delegete = eventDelegate else {
            dismiss(animated: true, completion: nil)
            return
        }
        delegete.qrCodeReaderViewControllerClose(self)
    }
}

extension PMQRCodeReaderViewController {
    private func setupCameraCapture() {
        if captureSession.isRunning {
            return
        }
        guard captureSession.inputs.isEmpty else {
            return
        }
        
        guard let captureDevice = AVCaptureDevice.default(for: .video) else {
            return
        }
        
        do {
            let input = try AVCaptureDeviceInput(device: captureDevice)
            captureSession.addInput(input)
            captureOutput.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
            captureSession.addOutput(captureOutput)
            captureOutput.metadataObjectTypes = [AVMetadataObject.ObjectType.qr]
            previewView.session = captureSession
            captureSession.startRunning()
        } catch {
            print(error)
        }
    }
    
    private func stopCapture() {
        if captureSession.inputs.isEmpty {
            return
        } else if !captureSession.isRunning {
            return
        }
        captureSession.stopRunning()
    }
    
    private func startCapture() {
        if captureSession.inputs.isEmpty {
            return
        } else if captureSession.isRunning {
            return
        }
        captureSession.startRunning()
    }
}

extension PMQRCodeReaderViewController: AVCaptureMetadataOutputObjectsDelegate {
    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard !isProsessing else {
            return
        }
        
        guard let objects = metadataObjects as? [AVMetadataMachineReadableCodeObject] else {
            return
        }
        
        
        guard let target = objects.first(where: {$0.stringValue?.isEmpty == false}) else {
            return
        }
        
        isProsessing = true
        
        readValue = target.stringValue
        willCloseVC()
    }
}
