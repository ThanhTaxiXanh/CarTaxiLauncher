package com.cartaxi.launcher

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.io.File

class MainActivity : Activity() {
    private val reqPick = 2
    private val names = arrayOf("Tự động", "Ngang", "Dọc")
    private lateinit var preview: ImageView
    private lateinit var status: TextView

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        runOnUiThread { updateStatus() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val density = resources.displayMetrics.density
        val pad = (24 * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        val title = TextView(this).apply {
            text = "CarTaxiLauncher"
            textSize = 22f
        }
        status = TextView(this).apply { textSize = 16f }
        preview = ImageView(this).apply {
            adjustViewBounds = true
            maxHeight = (260 * density).toInt()
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val pick = Button(this).apply {
            text = "Chọn ảnh mã QR từ điện thoại"
            setOnClickListener {
                val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                this@MainActivity.startActivityForResult(i, reqPick)
            }
        }
        val layoutBtn = Button(this).apply {
            text = "Bố cục: ${names[prefs.getInt("layout", 1)]}"
            setOnClickListener {
                val next = (prefs.getInt("layout", 1) + 1) % 3
                prefs.edit().putInt("layout", next).apply()
                text = "Bố cục: ${names[next]}"
            }
        }
        val perm = Button(this).apply {
            text = "Cấp quyền vị trí (đo tốc độ)"
            setOnClickListener {
                this@MainActivity.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            }
        }
        val shz = Button(this).apply {
            text = "Kết nối Shizuku"
            setOnClickListener { this@MainActivity.connectShizuku() }
        }
        root.addView(title)
        root.addView(status)
        root.addView(shz)
        root.addView(preview)
        root.addView(pick)
        root.addView(layoutBtn)
        root.addView(perm)
        setContentView(root)

        try {
            Shizuku.addRequestPermissionResultListener(permListener)
        } catch (t: Throwable) {
        }
        refreshPreview()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(permListener)
        } catch (t: Throwable) {
        }
        super.onDestroy()
    }

    private fun updateStatus() {
        val s = try {
            if (!Shizuku.pingBinder()) {
                "Shizuku: chưa chạy"
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                "Shizuku: đã kết nối"
            } else {
                "Shizuku: đang chạy, chưa cấp quyền"
            }
        } catch (t: Throwable) {
            "Shizuku: không khả dụng"
        }
        status.text = s
    }

    private fun connectShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, "Hãy mở app Shizuku và bấm Start trước", Toast.LENGTH_LONG).show()
            } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(100)
            }
        } catch (t: Throwable) {
            Toast.makeText(this, "Lỗi Shizuku: " + t.message, Toast.LENGTH_LONG).show()
        }
        updateStatus()
    }

    private fun refreshPreview() {
        val f = File(filesDir, "qr.png")
        if (f.exists()) preview.setImageBitmap(BitmapFactory.decodeFile(f.path))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqPick && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            saveQr(uri)
        }
    }

    private fun saveQr(uri: Uri) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: throw IllegalStateException("decode")
            File(filesDir, "qr.png").outputStream().use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            refreshPreview()
            Toast.makeText(this, "Đã lưu mã QR", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Không đọc được ảnh", Toast.LENGTH_SHORT).show()
        }
    }
}
