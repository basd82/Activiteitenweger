// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Bas van den Dikkenberg

import SwiftUI
import Shared

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ComposeView()
            .ignoresSafeArea(.keyboard)
            .onAppear {
                MainViewControllerKt.setAppActive(active: true)
            }
            .onChange(of: scenePhase) { newPhase in
                MainViewControllerKt.setAppActive(active: newPhase == .active)
            }
    }
}
