package com.example.lectorpdf

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var pageView: PageView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var pageLabel: TextView
    private lateinit var seek: SeekBar
    private lateinit var emptyView: TextView

    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor()
    private var token = 0
    private var page = 0
    private var docKey = ""
    private var mode = 0
    private val prefs by lazy { getSharedPreferences("lector", MODE_PRIVATE) }

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mode = prefs.getInt("mode", 0)
        buildUi()
        pageView.setMode(mode)

        val incoming = intent?.data
        if (incoming != null) {
            openUri(incoming, silent = false)
        } else {
            val last = prefs.getString("last_uri", null)
            if (last != null) openUri(Uri.parse(last), silent = true) else showUi(true)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.data?.let { openUri(it, silent = false) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        pageView.post { if (renderer != null) showPage(page, force = true) }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        synchronized(lock) { closeDoc() }
    }

    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { go(1); true }
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP -> { go(-1); true }
        else -> super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------- interfaz

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun barButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 16f
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        pageView = PageView(this)
        root.addView(pageView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        emptyView = TextView(this).apply {
            text = "Toca el centro de la pantalla y pulsa «Abrir» para elegir un PDF"
            setTextColor(Color.DKGRAY)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        root.addView(emptyView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Barra superior
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xE6000000.toInt())
            setPadding(dp(8), dp(24), dp(8), dp(4))
        }
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(8), 0)
            text = "Lector PDF"
        }
        topBar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(barButton("Abrir") { pickPdf() })
        topBar.addView(barButton("Tema") {
            mode = (mode + 1) % 3
            prefs.edit().putInt("mode", mode).apply()
            pageView.setMode(mode)
        })
        root.addView(topBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // Barra inferior
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xE6000000.toInt())
            setPadding(dp(16), dp(10), dp(16), dp(16))
        }
        seek = SeekBar(this)
        pageLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(12), 0, 0, 0)
        }
        bottomBar.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottomBar.addView(pageLabel)
        root.addView(bottomBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) pageLabel.text = "${progress + 1} / ${s.max + 1}"
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) { showPage(s.progress) }
        })

        pageView.onTapZone = { zone ->
            when (zone) {
                -1 -> go(-1)
                1 -> go(1)
                else -> showUi(topBar.visibility != View.VISIBLE)
            }
        }
        pageView.onSwipe = { dir -> go(dir) }

        setContentView(root)
    }

    private fun showUi(show: Boolean) {
        val vis = if (show) View.VISIBLE else View.GONE
        topBar.visibility = vis
        bottomBar.visibility = if (renderer == null) View.GONE else vis
    }

    // ---------------------------------------------------------------- abrir documentos

    @Suppress("DEPRECATION")
    private fun pickPdf() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(i, 1)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) data?.data?.let { openUri(it, silent = false) }
    }

    private fun displayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: uri.lastPathSegment ?: "PDF"
        } catch (e: Exception) {
            uri.lastPathSegment ?: "PDF"
        }
    }

    private fun openUri(uri: Uri, silent: Boolean) {
        try {
            val newPfd = contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("sin descriptor")
            val newRenderer = try {
                PdfRenderer(newPfd)
            } catch (e: Exception) {
                newPfd.close()
                throw e
            }
            if (newRenderer.pageCount == 0) {
                newRenderer.close(); newPfd.close()
                throw IllegalStateException("PDF vacío")
            }
            synchronized(lock) {
                closeDoc()
                pfd = newPfd
                renderer = newRenderer
            }
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            docKey = uri.toString()
            prefs.edit().putString("last_uri", docKey).apply()
            titleView.text = displayName(uri)
            seek.max = (newRenderer.pageCount - 1).coerceAtLeast(0)
            emptyView.visibility = View.GONE
            showUi(false)
            val saved = prefs.getInt("page_" + docKey.hashCode(), 0)
            pageView.post { showPage(saved, force = true) }
        } catch (e: Exception) {
            if (!silent) {
                Toast.makeText(
                    this, "No se pudo abrir el PDF (¿tiene contraseña o está dañado?)",
                    Toast.LENGTH_LONG
                ).show()
            }
            showUi(true)
        }
    }

    /** Llamar siempre dentro de synchronized(lock). */
    private fun closeDoc() {
        try { renderer?.close() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        renderer = null
        pfd = null
    }

    // ---------------------------------------------------------------- páginas

    private fun go(delta: Int) {
        if (renderer != null) showPage(page + delta)
    }

    private fun showPage(index: Int, force: Boolean = false) {
        val count = synchronized(lock) { renderer?.pageCount } ?: return
        val i = index.coerceIn(0, count - 1)
        if (i == page && !force) return
        page = i
        seek.progress = i
        pageLabel.text = "${i + 1} / $count"
        prefs.edit().putInt("page_" + docKey.hashCode(), i).apply()

        val mine = ++token
        val vw = pageView.width.coerceAtLeast(1)
        val vh = pageView.height.coerceAtLeast(1)
        executor.execute {
            val bmp = renderPage(i, vw, vh)
            runOnUiThread { if (mine == token && bmp != null) pageView.setPage(bmp) }
        }
    }

    private fun renderPage(index: Int, vw: Int, vh: Int): Bitmap? {
        synchronized(lock) {
            val r = renderer ?: return null
            return try {
                val p = r.openPage(index)
                try {
                    // Se renderiza al doble de la pantalla para que el zoom se vea nítido
                    var s = minOf(vw * 2f / p.width, vh * 2f / p.height)
                    s = minOf(s, 4096f / p.width, 4096f / p.height)
                    val bmp = Bitmap.createBitmap(
                        (p.width * s).toInt().coerceAtLeast(1),
                        (p.height * s).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888
                    )
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                } finally {
                    p.close()
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
