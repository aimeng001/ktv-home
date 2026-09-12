package com.homektv.tv.ui

import android.animation.ObjectAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.children
import androidx.core.view.descendants
import androidx.lifecycle.lifecycleScope
import com.homektv.tv.R
import com.homektv.tv.databinding.ActivitySetupBinding
import com.homektv.tv.net.AppConfig
import com.homektv.tv.net.DiscoveredServer
import com.homektv.tv.net.LanDiscovery
import com.homektv.tv.net.LanScanner
import com.homektv.tv.net.SavedServer
import com.homektv.tv.session.DeviceMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * TV 服务选择页：历史设备、按需扫描的局域网设备，以及手动输入。
 *
 * TV server selection screen for remembered devices, on-demand LAN discovery,
 * and manual server entry.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var config: AppConfig
    private lateinit var discovery: LanDiscovery
    private val scanner = LanScanner()
    private val discovered = linkedMapOf<String, DiscoveredServer>()
    private var scanJob: Job? = null
    private var credentialHost: String? = null
    private var initialCredential = ""
    private val rhythmAnimators = mutableListOf<ObjectAnimator>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = AppConfig(this)
        if (config.isConfigured && !intent.getBooleanExtra(EXTRA_FORCE_SETUP, false)) {
            val target = if (config.effectiveMode() == DeviceMode.CONTROLLER) {
                ControllerActivity::class.java
            } else {
                MainActivity::class.java
            }
            startActivity(Intent(this, target))
            finish()
            return
        }
        discovery = LanDiscovery(this)
        applyRecommendedOrientation()
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        credentialHost = config.serverHost
        initialCredential = config.playerCredential
        binding.inputCredential.setText(initialCredential)
        binding.inputNickname.setText(config.nicknameFor())
        renderMode(config.modeFor() ?: config.recommendedMode)
        binding.modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                binding.modePlayer.id -> DeviceMode.PLAYER
                binding.modeController.id -> DeviceMode.CONTROLLER
                binding.modeCombined.id -> DeviceMode.COMBINED
                else -> return@setOnCheckedChangeListener
            }
            applyOrientationForMode(mode)
        }

        binding.btnRefresh.setOnClickListener { startScan() }
        binding.btnConnect.setOnClickListener { submitManual() }
        binding.inputHost.setOnEditorActionListener { _, _, _ ->
            submitManual()
            true
        }

        renderHistory()
        if (SetupAutoScanPolicy.shouldAutoScan(config.savedServers.isEmpty())) {
            startScan()
        }
        startRhythm()
        rebuildFocusChain()
        binding.root.post { firstFocusableView()?.requestFocus() }
    }

    private fun startRhythm() {
        val bars = listOf(binding.rhythmBar1, binding.rhythmBar2, binding.rhythmBar3, binding.rhythmBar4)
        bars.forEachIndexed { index, bar ->
            bar.post {
                bar.pivotY = bar.height.toFloat()
                ObjectAnimator.ofFloat(bar, View.SCALE_Y, 0.45f, 1f).apply {
                    duration = 460L + index * 90L
                    startDelay = index * 70L
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                    interpolator = AccelerateDecelerateInterpolator()
                    rhythmAnimators += this
                    start()
                }
            }
        }
    }

    override fun onDestroy() {
        scanJob?.cancel()
        if (::discovery.isInitialized) discovery.close()
        scanner.close()
        rhythmAnimators.forEach(ObjectAnimator::cancel)
        rhythmAnimators.clear()
        super.onDestroy()
    }

    private fun startScan() {
        scanJob?.cancel()
        discovered.clear()
        binding.lanContainer.removeAllViews()
        binding.txtLanEmpty.setText(R.string.setup_scanning)
        binding.progressScan.apply {
            isIndeterminate = true
            visibility = View.VISIBLE
        }
        binding.txtScanStatus.setText(R.string.setup_scanning)
        // Keep refresh enabled and focused while scanning so the DPAD focus never disappears.
        binding.btnRefresh.requestFocus()

        scanJob = lifecycleScope.launch {
            val servers = discovery.discoverAll(
                onStage = { stage -> runOnUiThread { showStage(stage) } },
                onProgress = { scanned, total -> runOnUiThread {
                    binding.progressScan.isIndeterminate = false
                    binding.progressScan.max = total
                    binding.progressScan.progress = scanned
                    binding.txtScanStatus.text = getString(R.string.setup_scan_progress, scanned, total)
                } },
                onDiscovered = { server -> runOnUiThread { addDiscoveredServer(server) } },
            )
            binding.progressScan.visibility = View.GONE
            binding.txtScanStatus.text = getString(R.string.setup_scan_done, servers.size)
            binding.txtLanEmpty.visibility = if (servers.isEmpty()) View.VISIBLE else View.GONE
            if (servers.isEmpty()) binding.txtLanEmpty.setText(R.string.setup_lan_empty)
            rebuildFocusChain()
        }
    }

    private fun showStage(stage: LanDiscovery.Stage) {
        binding.progressScan.isIndeterminate = stage != LanDiscovery.Stage.SUBNET
        binding.txtScanStatus.setText(when (stage) {
            LanDiscovery.Stage.MDNS -> R.string.setup_discovering_mdns
            LanDiscovery.Stage.UDP -> R.string.setup_discovering_udp
            LanDiscovery.Stage.SUBNET -> R.string.setup_scanning
        })
    }

    private fun renderHistory() {
        binding.historyContainer.removeAllViews()
        config.savedServers.forEach(::addHistoryServer)
        binding.txtHistoryEmpty.visibility =
            if (config.savedServers.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun addHistoryServer(server: SavedServer) {
        val row = LayoutInflater.from(this).inflate(
            R.layout.item_history_server, binding.historyContainer, false,
        )
        row.findViewById<TextView>(R.id.txtHistoryName).text = server.name
        row.findViewById<TextView>(R.id.txtHistoryAddress).text = server.hostPort
        row.findViewById<Button>(R.id.btnHistoryConnect).apply {
            id = View.generateViewId()
            setOnClickListener { connect(server) }
        }
        row.findViewById<Button>(R.id.btnHistoryDelete).apply {
            id = View.generateViewId()
            setOnClickListener {
                config.removeSavedServer(server.hostPort)
                renderHistory()
                rebuildFocusChain()
                binding.btnRefresh.requestFocus()
            }
        }
        binding.historyContainer.addView(row)
    }

    private fun addDiscoveredServer(server: DiscoveredServer) {
        if (discovered.putIfAbsent(server.hostPort, server) != null) return
        binding.txtLanEmpty.visibility = View.GONE
        val row = LayoutInflater.from(this).inflate(
            R.layout.item_lan_server, binding.lanContainer, false,
        )
        row.findViewById<TextView>(R.id.txtLanName).text = server.name
        row.findViewById<TextView>(R.id.txtLanAddress).text = server.hostPort
        row.findViewById<Button>(R.id.btnLanConnect).apply {
            id = View.generateViewId()
            setOnClickListener { connect(SavedServer(server.hostPort, server.name)) }
        }
        binding.lanContainer.addView(row)
        rebuildFocusChain()
    }

    private fun submitManual() {
        val host = AppConfig.normalizeHost(binding.inputHost.text.toString())
        if (host == null) {
            Toast.makeText(this, R.string.setup_empty, Toast.LENGTH_SHORT).show()
            return
        }
        verifyAndConnect(SavedServer(host, host), binding.btnConnect)
    }

    private fun connect(server: SavedServer) {
        val focusedButton = currentFocus as? Button ?: binding.btnRefresh
        verifyAndConnect(server, focusedButton)
    }

    private fun verifyAndConnect(server: SavedServer, button: Button) {
        button.isEnabled = false
        binding.txtScanStatus.text = getString(R.string.setup_verifying, server.hostPort)
        lifecycleScope.launch {
            if (scanner.validate(server.hostPort)) {
                config.rememberServer(server)
                val enteredCredential = binding.inputCredential.text.toString()
                config.playerCredential = if (server.hostPort == credentialHost ||
                    enteredCredential != initialCredential
                ) enteredCredential else ""
                config.saveNickname(binding.inputNickname.text.toString(), server.hostPort)
                val mode = selectedMode()
                config.saveMode(mode, server.hostPort)
                if (intent.getBooleanExtra(EXTRA_RETURN_TO_CALLER, false)) {
                    setResult(RESULT_OK)
                } else {
                    val target = if (mode == DeviceMode.CONTROLLER) ControllerActivity::class.java
                    else MainActivity::class.java
                    startActivity(Intent(this@SetupActivity, target))
                }
                finish()
            } else {
                button.isEnabled = true
                Toast.makeText(this@SetupActivity, R.string.setup_invalid, Toast.LENGTH_LONG).show()
                binding.txtScanStatus.setText(R.string.setup_scan_idle)
                button.requestFocus()
            }
        }
    }

    private fun rebuildFocusChain() {
        val focusables = mutableListOf<View>()
        binding.historyContainer.children.forEach { row ->
            focusables += (row as ViewGroup).descendants.filterIsInstance<Button>().toList()
        }
        focusables += binding.btnRefresh
        binding.lanContainer.children.forEach { row ->
            focusables += (row as ViewGroup).descendants.filterIsInstance<Button>().toList()
        }
        focusables += binding.inputHost
        focusables += binding.inputNickname
        focusables += binding.modeGroup.children.toList()
        focusables += binding.btnConnect

        focusables.forEachIndexed { index, view ->
            view.nextFocusUpId = focusables.getOrNull(index - 1)?.id ?: view.id
            view.nextFocusDownId = focusables.getOrNull(index + 1)?.id ?: view.id
            view.setOnFocusChangeListener { focused, hasFocus ->
                if (hasFocus) scrollIntoView(focused)
            }
        }
    }

    private fun firstFocusableView(): View? =
        binding.historyContainer.descendants.filterIsInstance<Button>().firstOrNull()
            ?: binding.btnRefresh

    private fun scrollIntoView(view: View) {
        binding.setupScroll.post {
            val rect = android.graphics.Rect()
            view.getDrawingRect(rect)
            view.requestRectangleOnScreen(rect, true)
        }
    }

    private fun selectedMode(): DeviceMode = when {
        binding.modePlayer.isChecked -> DeviceMode.PLAYER
        binding.modeController.isChecked -> DeviceMode.CONTROLLER
        else -> DeviceMode.COMBINED
    }

    private fun renderMode(mode: DeviceMode) {
        when (mode) {
            DeviceMode.PLAYER -> binding.modePlayer.isChecked = true
            DeviceMode.CONTROLLER -> binding.modeController.isChecked = true
            DeviceMode.COMBINED -> binding.modeCombined.isChecked = true
        }
        applyOrientationForMode(mode)
    }

    private fun applyRecommendedOrientation() {
        applyOrientationForMode(config.modeFor() ?: config.recommendedMode)
    }

    private fun applyOrientationForMode(mode: DeviceMode) {
        val isTelevision = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
        val hasTouchscreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        requestedOrientation = when {
            isTelevision || !hasTouchscreen -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            mode == DeviceMode.CONTROLLER && resources.configuration.smallestScreenWidthDp < 600 ->
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    companion object {
        const val EXTRA_FORCE_SETUP = "force_setup"
        const val EXTRA_RETURN_TO_CALLER = "return_to_caller"
    }
}

object SetupAutoScanPolicy {
    fun shouldAutoScan(isFreshInstall: Boolean): Boolean = isFreshInstall
}
