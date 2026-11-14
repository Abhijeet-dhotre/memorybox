package com.memorybox.app.memorybox

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Android side of `YtDlpRunner` (lib/ingest/ytdlp.dart), channel
 * `memorybox/ytdlp`.
 *
 * Uses the VibeVault-proven `youtubedl-android` 0.18.1 library
 * (io.github.junkfood02.youtubedl-android), which bundles CPython +
 * yt-dlp + QuickJS in the APK — there is no yt-dlp executable on Android,
 * so the Dart `Process.run` path cannot work here.
 *
 * Methods:
 * - `run` {args, cwd}: runs yt-dlp with the given CLI-style args and
 *   returns {exitCode, stdout, stderr}. `--dump-json` is served from
 *   `YoutubeDL.getInfo()` and re-serialised to the JSON the Dart parser
 *   reads. Caption/audio outputs are forced into `cwd` by rewriting `-o`.
 * - `ffmpeg` {args}: runs the bundled mobile-ffmpeg with the given args
 *   (used for Path B audio compression/splitting), returns {exitCode,
 *   stdout, stderr}.
 *
 * Every call runs on Dispatchers.IO; yt-dlp blocking calls must never run
 * on the UI thread.
 */
class YtDlpBridge(
    private val context: Context,
    private val scope: CoroutineScope,
) : MethodChannel.MethodCallHandler {

    companion object {
        const val CHANNEL = "memorybox/ytdlp"

        /** Options that take a following value (everything else is a flag). */
        private val VALUE_OPTIONS = setOf(
            "-f", "--format",
            "-o", "--output",
            "--sub-langs", "--sub-format",
            "--audio-format", "--audio-quality",
            "--cookies", "--proxy",
            "--exec", "--remux-video", "--recode-video",
            "--paths", "--output-na-placeholder",
        )
    }

    private var initialized = false

    @Synchronized
    private fun ensureInit() {
        if (initialized) return
        YoutubeDL.getInstance().init(context.applicationContext)
        FFmpeg.getInstance().init(context.applicationContext)
        initialized = true
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "run" -> {
                val args = call.argument<List<String>>("args") ?: emptyList()
                val cwd = call.argument<String>("cwd")
                scope.launch(Dispatchers.IO) {
                    try {
                        ensureInit()
                        result.success(runYtDlp(args, cwd))
                    } catch (e: Exception) {
                        result.success(
                            mapOf(
                                "exitCode" to 1,
                                "stdout" to "",
                                "stderr" to (e.message ?: e.toString()),
                            ),
                        )
                    }
                }
            }
            "ffmpeg" -> {
                val args = call.argument<List<String>>("args") ?: emptyList()
                scope.launch(Dispatchers.IO) {
                    try {
                        ensureInit()
                        val rc = FFmpeg.getInstance().execute(args.toTypedArray())
                        result.success(
                            mapOf(
                                "exitCode" to rc,
                                "stdout" to "",
                                "stderr" to if (rc == 0) "" else "ffmpeg exited with code $rc",
                            ),
                        )
                    } catch (e: Exception) {
                        result.success(
                            mapOf(
                                "exitCode" to 1,
                                "stdout" to "",
                                "stderr" to (e.message ?: e.toString()),
                            ),
                        )
                    }
                }
            }
            else -> result.notImplemented()
        }
    }

    private fun runYtDlp(args: List<String>, cwd: String?): Map<String, Any> {
        val url = args.lastOrNull { !it.startsWith("-") }
            ?: throw IllegalArgumentException("No URL in yt-dlp args: $args")
        val workDir = cwd?.let { File(it) } ?: context.cacheDir
        if (!workDir.exists()) workDir.mkdirs()

        if (args.contains("--dump-json")) {
            val info = YoutubeDL.getInstance().getInfo(url)
            val json = JSONObject().apply {
                put("extractor", info.extractor ?: "")
                put("id", info.id ?: "")
                put("title", info.title ?: "")
                put("uploader", info.uploader ?: "")
                put("duration", info.duration ?: 0)
                put("webpage_url", info.webpageUrl ?: url)
                // Caption availability: rebuild the two sections the Dart
                // parser scans for language codes.
                val subs = JSONObject()
                val auto = JSONObject()
                for (lang in info.subtitles.keys) subs.put(lang, JSONObject())
                for (lang in info.automaticCaptions.keys) auto.put(lang, JSONObject())
                put("subtitles", subs)
                put("automatic_captions", auto)
            }
            return mapOf("exitCode" to 0, "stdout" to json.toString(), "stderr" to "")
        }

        val request = YoutubeDLRequest(url)
        var i = 0
        var hasOutput = false
        while (i < args.size) {
            val a = args[i]
            if (a == url) {
                i++
                continue
            }
            if (a.startsWith("-")) {
                val name = a.substringBefore("=")
                val inlineValue = a.substringAfter("=", "")
                when {
                    inlineValue.isNotEmpty() -> request.addOption(name, inlineValue)
                    name in VALUE_OPTIONS && i + 1 < args.size -> {
                        var value = args[i + 1]
                        if (name == "-o") {
                            // Force outputs into the caller's work dir.
                            value = File(workDir, value).absolutePath
                            hasOutput = true
                        }
                        request.addOption(name, value)
                        i++
                    }
                    else -> request.addOption(name)
                }
            }
            i++
        }
        if (!hasOutput && (args.contains("--write-subs") || args.contains("--write-auto-subs"))) {
            // Captions follow the -o template; without one they land in the
            // library's working dir, not in the caller's work dir.
            request.addOption("-o", File(workDir, "%(id)s.%(ext)s").absolutePath)
        }
        val response = YoutubeDL.getInstance().execute(request)
        return mapOf(
            "exitCode" to 0,
            "stdout" to (response.out ?: ""),
            "stderr" to (response.err ?: ""),
        )
    }
}
