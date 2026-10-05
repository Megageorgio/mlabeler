import SwiftUI
import MLabeler

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

@main
struct MLabelerApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView().ignoresSafeArea()
        }
    }
}
