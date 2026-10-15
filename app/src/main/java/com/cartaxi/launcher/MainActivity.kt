package com.cartaxi.launcher

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val pad = (24 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        val title = TextView(this).apply {
            text = "CarTaxiLauncher"
            textSize = 22f
        }
        val qr = EditText(this).apply {
            hint = "Nội dung mã QR (link, số tài khoản...)"
            setText(prefs.getString("qr", ""))
        }
        val save = Button(this).apply {
            text = "Lưu mã QR"
            setOnClickListener {
                prefs.edit().putString("qr", qr.text.toString()).apply()
                Toast.makeText(this@MainActivity, "Đã lưu", Toast.LENGTH_SHORT).show()
            }
        }
        val perm = Button(this).apply {
            text = "Cấp quyền vị trí (đo tốc độ)"
            setOnClickListener {
                this@MainActivity.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            }
        }
        root.addView(title)
        root.addView(qr)
        root.addView(save)
        root.addView(perm)
        setContentView(root)
    }
}
