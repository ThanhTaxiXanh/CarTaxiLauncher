package com.cartaxi.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.io.File

class MainActivity : Activity() {
    private val reqPick = 2
    private var pad = 0
    private lateinit var preview: ImageView
    private lateinit var status: TextView
    private lateinit var eventBox: LinearLayout

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        runOnUiThread { updateStatus() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        pad = (20 * density).toInt()

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
            maxHeight = (240 * density).toInt()
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
        val evTitle = TextView(this).apply {
            text = "Sự kiện hiển thị trên widget Lịch"
            textSize = 18f
            setPadding(0, pad, 0, 0)
        }
        eventBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val addEv = Button(this).apply {
            text = "Thêm sự kiện"
            setOnClickListener { this@MainActivity.showAddDialog() }
        }

        root.addView(title)
        root.addView(status)
        root.addView(shz)
        root.addView(preview)
        root.addView(pick)
        root.addView(perm)
        root.addView(evTitle)
        root.addView(eventBox)
        root.addView(addEv)
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        try {
            Shizuku.addRequestPermissionResultListener(permListener)
        } catch (t: Throwable) {
        }
        refreshPreview()
        refreshEvents()
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

    // ---------- sự kiện ----------

    private fun refreshEvents() {
        eventBox.removeAllViews()
        val list = Events.load(this)
        if (list.isEmpty()) {
            eventBox.addView(TextView(this).apply { text = "(chưa có sự kiện nào)" })
            return
        }
        for (e in list) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val tv = TextView(this).apply {
                text = "• ${e.title} - ${Events.whenText(e)}"
                textSize = 15f
            }
            row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val del = Button(this).apply {
                text = "Xóa"
                setOnClickListener {
                    Events.save(this@MainActivity, Events.load(this@MainActivity).filter { it.id != e.id })
                    this@MainActivity.refreshEvents()
                }
            }
            row.addView(del)
            eventBox.addView(row)
        }
    }

    private fun showAddDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val title = EditText(this).apply { hint = "Tên sự kiện (ví dụ: Giỗ Mẹ)" }
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbSolar = RadioButton(this).apply {
            text = "Dương lịch"
            id = View.generateViewId()
        }
        val rbLunar = RadioButton(this).apply {
            text = "Âm lịch"
            id = View.generateViewId()
        }
        group.addView(rbSolar)
        group.addView(rbLunar)
        group.check(rbSolar.id)
        val day = EditText(this).apply {
            hint = "Ngày"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val month = EditText(this).apply {
            hint = "Tháng (bỏ qua nếu lặp hằng tháng)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val year = EditText(this).apply {
            hint = "Năm (chỉ cần khi chọn Một lần)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val repeat = Spinner(this)
        repeat.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("Lặp hằng năm", "Lặp hằng tháng", "Một lần")
        )
        box.addView(title)
        box.addView(group)
        box.addView(day)
        box.addView(month)
        box.addView(year)
        box.addView(repeat)

        AlertDialog.Builder(this)
            .setTitle("Thêm sự kiện")
            .setView(box)
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Lưu") { _, _ ->
                val name = title.text.toString().trim()
                val lunar = group.checkedRadioButtonId == rbLunar.id
                val rep = repeat.selectedItemPosition
                val d = day.text.toString().toIntOrNull() ?: 0
                val m = if (rep == 1) 1 else (month.text.toString().toIntOrNull() ?: 0)
                val y = if (rep == 2) (year.text.toString().toIntOrNull() ?: 0) else 0
                val maxDay = if (lunar) 30 else 31
                val ok = name.isNotEmpty() && d in 1..maxDay && m in 1..12 && (rep != 2 || y >= 2000)
                if (!ok) {
                    Toast.makeText(this, "Thông tin chưa hợp lệ, hãy kiểm tra lại", Toast.LENGTH_LONG).show()
                } else {
                    val list = Events.load(this).toMutableList()
                    list.add(CalEvent(System.currentTimeMillis(), name, lunar, d, m, y, rep))
                    Events.save(this, list)
                    refreshEvents()
                }
            }
            .show()
    }

    // ---------- Shizuku ----------

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

    // ---------- ảnh QR ----------

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
