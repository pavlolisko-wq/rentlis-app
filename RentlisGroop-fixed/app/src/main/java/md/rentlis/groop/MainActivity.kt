package md.rentlis.groop

import android.Manifest
import android.graphics.Color
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooser: ActivityResultLauncher<Intent>
    private lateinit var root: FrameLayout
    private var insetsJs: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge: the app draws behind the status bar / cutout / nav bar,
        // and the page receives the real insets (works with or without a notch).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }

        // Receives the picked image and hands it back to the WebView <input type="file">
        fileChooser = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val cb = filePathCallback
            filePathCallback = null
            if (cb != null) {
                cb.onReceiveValue(
                    WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                )
            }
        }

        // Android 13+: ask for notification permission (payment day reminders)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        web = WebView(this)
        root = FrameLayout(this)
        root.setBackgroundColor(0xFF082B23.toInt())
        root.addView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val d = resources.displayMetrics.density
            // keyboard open: shrink the WebView, page needs no extra bottom inset
            v.setPadding(0, 0, 0, ime.bottom)
            val bottom = if (ime.bottom > 0) 0 else Math.round(bars.bottom / d)
            insetsJs = "window.setInsets&&window.setInsets(" + Math.round(bars.top / d) + "," +
                Math.round(bars.right / d) + "," + bottom + "," + Math.round(bars.left / d) + ")"
            web.evaluateJavascript(insetsJs, null)
            WindowInsetsCompat.CONSUMED
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (insetsJs.isNotEmpty()) view?.evaluateJavascript(insetsJs, null)
            }
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = false
            allowContentAccess = false
        }
        web.setBackgroundColor(0xFF0C0D10.toInt())

        // Enable file/photo picker inside the WebView
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val intent = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                return try {
                    fileChooser.launch(intent)
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    false
                }
            }
        }

        web.addJavascriptInterface(Bridge(), "RentlisNative")
        Reminders.schedule(this)
        web.loadUrl("file:///android_asset/rentmaster.html")

        // System back: let the page close sheet / detail / go to the previous page first
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.appBack&&window.appBack())?'1':'0'") { r ->
                    if (r != null && r.contains("1")) return@evaluateJavascript
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Opened from a payment notification: let the page show the confirmation sheet
        web.evaluateJavascript("window.checkDue && window.checkDue()", null)
    }

    /** Called from rentmaster.html to hand over reminder data. */
    inner class Bridge {
        /** Full app-data backup, in case WebView storage is ever cleared. */
        @JavascriptInterface
        fun backup(json: String) {
            applicationContext.getSharedPreferences("backup", MODE_PRIVATE).edit().putString("data", json).apply()
        }

        @JavascriptInterface
        fun restore(): String {
            return applicationContext.getSharedPreferences("backup", MODE_PRIVATE).getString("data", "") ?: ""
        }

        /** Dark theme -> light status/navigation icons, light theme -> dark icons. */
        @JavascriptInterface
        fun bars(dark: Boolean) {
            runOnUiThread {
                val c = WindowCompat.getInsetsController(window, web)
                c.isAppearanceLightStatusBars = !dark
                c.isAppearanceLightNavigationBars = !dark
                root.setBackgroundColor(if (dark) 0xFF082B23.toInt() else 0xFFF6F1E7.toInt())
            }
        }

        @JavascriptInterface
        fun sync(json: String) {
            Reminders.save(applicationContext, json)
            Reminders.schedule(applicationContext)
        }
    }
}
