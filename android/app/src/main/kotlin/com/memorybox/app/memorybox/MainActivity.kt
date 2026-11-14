package com.memorybox.app.memorybox

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MainActivity : FlutterActivity() {
    private val bridgeScope = CoroutineScope(SupervisorJob())

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            YtDlpBridge.CHANNEL,
        ).setMethodCallHandler(YtDlpBridge(applicationContext, bridgeScope))
    }

    override fun onDestroy() {
        bridgeScope.cancel()
        super.onDestroy()
    }
}
