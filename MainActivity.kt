package com.example.mockloc

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var latEt: EditText
    private lateinit var lngEt: EditText
    private lateinit var statusTv: TextView

    private val presets = linkedMapOf(
        "北京" to (39.9042 to 116.4074),
        "上海" to (31.2304 to 121.4737),
        "广州" to (23.1291 to 113.2644),
        "深圳" to (22.5431 to 114.0579)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(MockService.PREFS, MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(24))
        }
        root.addView(TextView(this).apply { text = "模拟定位"; textSize = 24f })
        statusTv = TextView(this).apply { textSize = 15f; setPadding(0, dp(8), 0, dp(16)) }
        root.addView(statusTv)

        latEt = numberField("纬度 latitude", prefs.getString(MockService.KEY_LAT, "39.9042")!!)
        lngEt = numberField("经度 longitude", prefs.getString(MockService.KEY_LNG, "116.4074")!!)
        root.addView(latEt)
        root.addView(lngEt)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((name, pos) in presets) {
            row.addView(Button(this).apply {
                text = name
                setOnClickListener {
                    latEt.setText(pos.first.toString())
                    lngEt.setText(pos.second.toString())
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(row)

        root.addView(button("开始模拟") { start() })
        root.addView(button("停止模拟") {
            stopService(Intent(this, MockService::class.java))
            statusTv.postDelayed({ updateStatus() }, 300)
        })
        root.addView(button("打开开发者选项") { openDevSettings() })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun start() {
        val lat = latEt.text.toString().toDoubleOrNull()
        val lng = lngEt.text.toString().toDoubleOrNull()
        if (lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
            toast("坐标格式不对")
            return
        }
        getSharedPreferences(MockService.PREFS, MODE_PRIVATE).edit()
            .putString(MockService.KEY_LAT, lat.toString())
            .putString(MockService.KEY_LNG, lng.toString())
            .apply()

        if (!isMockApp()) {
            AlertDialog.Builder(this)
                .setTitle("还差一步")
                .setMessage("请在 开发者选项 → 选择模拟位置信息应用 中选择“模拟定位”，然后回来再点开始。")
                .setPositiveButton("去设置") { _, _ -> openDevSettings() }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 1)
        } else {
            launchService()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            launchService()
        } else {
            toast("需要定位权限才能运行前台定位服务")
        }
    }

    private fun launchService() {
        // 已在运行时先停掉，以便应用新坐标
        stopService(Intent(this, MockService::class.java))
        startForegroundService(Intent(this, MockService::class.java))
        statusTv.postDelayed({ updateStatus() }, 800)
    }

    @Suppress("DEPRECATION")
    private fun isMockApp(): Boolean {
        val ops = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), packageName)
        else
            ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun openDevSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (_: Exception) {
            toast("请先开启开发者模式：设置 → 我的设备 → 全部参数 → 连点 OS 版本")
        }
    }

    private fun updateStatus() {
        statusTv.text = if (MockService.running) "状态：运行中 ✅" else "状态：未运行"
    }

    private fun numberField(hint: String, value: String) = EditText(this).apply {
        this.hint = hint
        setText(value)
        inputType = InputType.TYPE_CLASS_NUMBER or
            InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
