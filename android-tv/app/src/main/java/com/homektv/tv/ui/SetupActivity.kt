package com.homektv.tv.ui

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
import com.homektv.tv.ui.kiosk.KtvDashboardBackground
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
    private var verifyJob: Job? = null
    private var initialCredential = ""
    private var initialServer: SavedServer? = null
    private var recoveryCandidate: DiscoveredServer? = null
    private var setupPage = SetupPage.DISCOVERY
    private val rhythmAnimators = mutableListOf<ObjectAnimator>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = AppConfig(this)
        if (config.isConfigured && !intent.getBooleanExtra(EXTRA_FORCE_SETUP, false)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        discovery = LanDiscovery(this)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        KtvDashboardBackground.applyTo(binding.root)
        recoveryCandidate = intent.getStringExtra(EXTRA_CANDIDATE_HOST)?.let { host ->
            DiscoveredServer(
                hostPort = host,
                name = intent.getStringExtra(EXTRA_CANDIDATE_NAME).orEmpty().ifBlank { host },
                instanceId = intent.getStringExtra(EXTRA_CANDIDATE_INSTANCE_ID),
            )
        }
        val prefillHost = recoveryCandidate?.hostPort ?: config.serverHost.orEmpty()
        if (savedInstanceState == null && binding.inputHost.text.isNullOrBlank() && prefillHost.isNotBlank()) {
            binding.inputHost.setText(prefillHost)
        }
        recoveryCandidate?.let { binding.inputHost.setText(it.hostPort) }
        initialServer = config.serverForHost(config.serverHost)
        initialCredential = initialServer?.let(config::playerCredentialFor).orEmpty()
        binding.inputCredential.setText(initialCredential)

        binding.btnRefresh.setOnClickListener { startScan() }
        binding.btnConnect.setOnClickListener { submitManual() }
        binding.btnOpenManualSetup.setOnClickListener { showSetupPage(SetupPage.MANUAL) }
        binding.btnSetupBackToDiscovery.setOnClickListener { showSetupPage(SetupPage.DISCOVERY) }
        binding.inputHost.setOnEditorActionListener { _, _, _ ->
            submitManual()
            true
        }

        setupPage = savedInstanceState?.getString(KEY_SETUP_PAGE)
            ?.let { runCatching { SetupPage.valueOf(it) }.getOrNull() }
            ?: SetupPage.DISCOVERY
        showSetupPage(setupPage, requestFocus = false)
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (setupPage == SetupPage.MANUAL) {
                        showSetupPage(SetupPage.DISCOVERY)
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
        renderHistory()
        if (SetupAutoScanPolicy.shouldAutoScan(config.savedServers.isEmpty())) {
            startScan()
        }
        startRhythm()
        rebuildFocusChain()
        binding.root.post { firstFocusableView()?.requestFocus() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_SETUP_PAGE, setupPage.name)
        super.onSaveInstanceState(outState)
    }

    private fun showSetupPage(page: SetupPage, requestFocus: Boolean = true) {
        setupPage = page
        val showingDiscovery = page == SetupPage.DISCOVERY
        binding.setupDiscoveryPage.visibility = if (showingDiscovery) View.VISIBLE else View.GONE
        binding.setupManualPage.visibility = if (showingDiscovery) View.GONE else View.VISIBLE
        rebuildFocusChain()
        if (requestFocus) {
            binding.root.post { firstFocusableView()?.requestFocus() }
        }
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
        verifyJob?.cancel()
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
        if (setupPage == SetupPage.DISCOVERY) binding.btnRefresh.requestFocus()

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
            setOnLongClickListener {
                AlertDialog.Builder(this@SetupActivity, R.style.Theme_HomeKtvTv_UpdateDialog)
                    .setTitle(R.string.setup_forget_server_title)
                    .setMessage(getString(R.string.setup_forget_server_msg, server.name))
                    .setPositiveButton(R.string.setup_forget_confirm) { _, _ ->
                        config.forgetServer(server)
                        renderHistory()
                        rebuildFocusChain()
                        binding.btnRefresh.requestFocus()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
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
            setOnClickListener { connect(SavedServer(server.hostPort, server.name, server.instanceId)) }
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
        val knownServer = config.savedServers.firstOrNull { it.hostPort == host }
        verifyAndConnect(
            SetupCandidatePolicy.resolve(host, recoveryCandidate, knownServer),
            binding.btnConnect,
        )
    }

    private fun connect(server: SavedServer) {
        val focusedButton = currentFocus as? Button ?: binding.btnRefresh
        verifyAndConnect(server, focusedButton)
    }

    private fun verifyAndConnect(server: SavedServer, button: Button) {
        button.isEnabled = false
        binding.txtScanStatus.text = getString(R.string.setup_verifying, server.hostPort)
        verifyJob?.cancel()
        verifyJob = lifecycleScope.launch {
            val confirmed = scanner.discover(server.hostPort)
                ?.let { discovered -> SetupVerificationPolicy.confirm(server, discovered) }
            if (confirmed != null) {
                if (LegacyIdentityMigrationPolicy.requiresConfirmation(initialServer, confirmed)) {
                    AlertDialog.Builder(this@SetupActivity, R.style.Theme_HomeKtvTv_UpdateDialog)
                        .setTitle(R.string.setup_identity_migration_title)
                        .setMessage(R.string.setup_identity_migration_message)
                        .setPositiveButton(R.string.setup_identity_migration_keep) { _, _ ->
                            commitConfirmedServer(confirmed, button, legacyMigrationAccepted = true)
                        }
                        .setNegativeButton(R.string.setup_identity_migration_new) { _, _ ->
                            commitConfirmedServer(confirmed, button, legacyMigrationAccepted = false)
                        }
                        .setOnCancelListener { restoreSetupButton(button) }
                        .show()
                } else {
                    commitConfirmedServer(confirmed, button, legacyMigrationAccepted = false)
                }
            } else {
                restoreSetupButton(button)
                Toast.makeText(this@SetupActivity, R.string.setup_invalid, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun commitConfirmedServer(
        confirmed: SavedServer,
        button: Button,
        legacyMigrationAccepted: Boolean,
    ) {
        val enteredCredential = binding.inputCredential.text.toString()
        val credentialChanged = enteredCredential != initialCredential
        val willUseCredential = SetupCandidatePolicy.shouldReuseInitialCredential(
            initialServer = initialServer,
            targetServer = confirmed,
            credentialChanged = credentialChanged,
            legacyMigrationAccepted = legacyMigrationAccepted,
        )
        if (SetupCandidatePolicy.requiresHttpsForPlayerCredential(
                serverHost = confirmed.hostPort,
                credential = enteredCredential,
                credentialWillBeUsed = willUseCredential,
            )
        ) {
            restoreSetupButton(button)
            Toast.makeText(this, R.string.setup_credential_requires_https, Toast.LENGTH_LONG).show()
            return
        }
        if (legacyMigrationAccepted && initialServer != null &&
            !runCatching { config.migrateLegacyScope(initialServer!!, confirmed) }.getOrDefault(false)
        ) {
            restoreSetupButton(button)
            Toast.makeText(this, R.string.setup_identity_migration_failed, Toast.LENGTH_LONG).show()
            return
        }

        config.rememberServer(confirmed)
        if (!legacyMigrationAccepted || credentialChanged) {
            config.playerCredential = if (SetupCandidatePolicy.shouldReuseInitialCredential(
                    initialServer = initialServer,
                    targetServer = confirmed,
                    credentialChanged = credentialChanged,
                    legacyMigrationAccepted = legacyMigrationAccepted,
                )
            ) enteredCredential else ""
        }
        if (intent.getBooleanExtra(EXTRA_RETURN_TO_CALLER, false)) {
            setResult(RESULT_OK)
        } else {
            startActivity(Intent(this@SetupActivity, MainActivity::class.java))
        }
        finish()
    }

    private fun restoreSetupButton(button: Button) {
        button.isEnabled = true
        binding.txtScanStatus.setText(R.string.setup_scan_idle)
        button.requestFocus()
    }

    private fun rebuildFocusChain() {
        val rows = mutableListOf<List<View>>()
        if (setupPage == SetupPage.DISCOVERY) {
            binding.historyContainer.children.forEach { row ->
                val buttons = (row as ViewGroup).descendants.filterIsInstance<Button>().toList()
                if (buttons.isNotEmpty()) rows += buttons
            }
            rows += listOf(binding.btnRefresh)
            binding.lanContainer.children.forEach { row ->
                val buttons = (row as ViewGroup).descendants.filterIsInstance<Button>().toList()
                if (buttons.isNotEmpty()) rows += buttons
            }
            rows += listOf(binding.btnOpenManualSetup)
        } else {
            val focusables = mutableListOf<View>()
            focusables += binding.btnSetupBackToDiscovery
            focusables += binding.inputHost
            focusables += binding.inputCredential
            focusables += binding.btnConnect
            focusables.forEach { rows += listOf(it) }
        }

        rows.forEachIndexed { rowIndex, rowViews ->
            val prevRow = rows.getOrNull(rowIndex - 1)
            val nextRow = rows.getOrNull(rowIndex + 1)
            rowViews.forEachIndexed { colIndex, view ->
                view.nextFocusLeftId = rowViews.getOrNull(colIndex - 1)?.id ?: view.id
                view.nextFocusRightId = rowViews.getOrNull(colIndex + 1)?.id ?: view.id
                view.nextFocusUpId = prevRow?.let { it.getOrNull(colIndex) ?: it.firstOrNull() }?.id ?: view.id
                view.nextFocusDownId = nextRow?.let { it.getOrNull(colIndex) ?: it.firstOrNull() }?.id ?: view.id
                view.setOnFocusChangeListener { focused, hasFocus ->
                    if (hasFocus) scrollIntoView(focused)
                }
            }
        }
    }

    private fun firstFocusableView(): View? = if (setupPage == SetupPage.DISCOVERY) {
        binding.historyContainer.descendants.filterIsInstance<Button>().firstOrNull()
            ?: binding.btnRefresh
    } else {
        binding.btnSetupBackToDiscovery
    }

    private fun scrollIntoView(view: View) {
        binding.setupFormScroll.post {
            val rect = android.graphics.Rect()
            view.getDrawingRect(rect)
            view.requestRectangleOnScreen(rect, true)
        }
    }

    companion object {
        private const val KEY_SETUP_PAGE = "setup_connection_page"
        const val EXTRA_FORCE_SETUP = "force_setup"
        const val EXTRA_RETURN_TO_CALLER = "return_to_caller"
        const val EXTRA_CANDIDATE_HOST = "candidate_host"
        const val EXTRA_CANDIDATE_NAME = "candidate_name"
        const val EXTRA_CANDIDATE_INSTANCE_ID = "candidate_instance_id"
    }

    private enum class SetupPage { DISCOVERY, MANUAL }
}

object SetupAutoScanPolicy {
    fun shouldAutoScan(isFreshInstall: Boolean): Boolean = isFreshInstall
}
