/*
 * Copyright 2025 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.ui.activity

import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ActivityTailscaleSettingsBinding
import com.celzero.bravedns.service.InboundTcpPorts
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.TailscaleManager
import com.celzero.bravedns.service.TailscaleManager.TsState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.util.UIUtils.openUrl
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import com.celzero.bravedns.ui.activity.WgIncludeAppsActivity

/**
 * Settings screen for the embedded Tailscale engine: enable/disable, login
 * (interactive or auth-key), Headscale control-server URL, exit node.
 */
class TailscaleSettingsActivity : BaseActivity(R.layout.activity_tailscale_settings) {

    private val b by viewBinding(ActivityTailscaleSettingsBinding::bind)
    private val persistentState by inject<PersistentState>()
    private val tsManager by inject<TailscaleManager>()

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        handleFrostEffectIfNeeded(persistentState.theme)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars =
                Themes.isActivityLightTheme(isDarkThemeOn(), persistentState.theme)
            window.isNavigationBarContrastEnforced = false
        }

        initView()
        initClickListeners()
        observe()
    }

    private fun initView() {
        b.tsEnableSwitch.isChecked = persistentState.tailscaleEnabled
        b.tsControlUrl.setText(persistentState.tailscaleControlUrl)
        b.tsExitNode.setText(persistentState.tailscaleExitNode)
        b.tsShieldsUpSwitch.isChecked = persistentState.tailscaleShieldsUp
        b.tsAcceptRoutesSwitch.isChecked = persistentState.tailscaleAcceptRoutes
        b.tsMagicDnsSwitch.isChecked = persistentState.tailscaleMagicDns
        b.tsAdvertiseRoutes.setText(persistentState.tailscaleAdvertiseRoutes)
        updateInboundTcpPortsSubtitle()
        updateStatusUi(tsManager.currentState())
        updateMagicDnsSuffix(persistentState.tailscaleMagicDns)
    }

    /** Currently selected presets (canonical order), read from prefs. */
    private fun currentPresetIds(): List<String> =
        InboundTcpPorts.parsePresetIds(persistentState.tailscaleInboundTcpPresets)

    /** Current Custom ports spec, read from prefs. */
    private fun currentCustomSpec(): String = persistentState.tailscaleInboundTcpPorts

    /** Shows what the inbound TCP allowlist currently does (see InboundTcpPorts). */
    private fun updateInboundTcpPortsSubtitle() {
        val presets = currentPresetIds()
        val custom = currentCustomSpec()
        if (InboundTcpPorts.activeGroups(presets, custom).isEmpty()) {
            b.tsInboundTcpPortsSubtitle.text = getString(R.string.ts_inbound_tcp_ports_none)
            return
        }
        val summary = InboundTcpPorts.formatCardSubtitle(presets, custom, false)
        b.tsInboundTcpPortsSubtitle.text =
            // shields-up drops inbound before netstack, so the allowlist
            // has no effect until it is off
            if (persistentState.tailscaleShieldsUp)
                getString(R.string.ts_inbound_tcp_ports_shields_up, summary)
            else summary
    }

    private fun updateMagicDnsSuffix(enabled: Boolean) {
        if (!enabled) {
            b.tsMagicDnsSuffix.visibility = View.GONE
            return
        }
        val suffix = tsManager.magicDnsSuffix()
        if (suffix.isNotEmpty()) {
            b.tsMagicDnsSuffix.text = getString(R.string.ts_magic_dns_suffix, suffix)
            b.tsMagicDnsSuffix.visibility = View.VISIBLE
        } else {
            b.tsMagicDnsSuffix.visibility = View.GONE
        }
    }

    private fun observe() {
        tsManager.stateObservable.observe(this) { state ->
            updateStatusUi(state)
            updateMagicDnsSuffix(persistentState.tailscaleMagicDns)
        }
        tsManager.loginUrlObservable.observe(this) { url ->
            if (url.isNullOrEmpty()) return@observe
            // consume the url immediately; a fresh one is issued per login
            tsManager.loginUrlObservable.value = null
            openUrl(this, url)
        }
        tsManager.errorObservable.observe(this) { err ->
            if (err.isNullOrEmpty()) {
                b.tsError.visibility = View.GONE
            } else {
                b.tsError.visibility = View.VISIBLE
                b.tsError.text = err
            }
        }
    }

    private fun updateStatusUi(state: TsState) {
        b.tsStatus.setText(stateLabelRes(state))
        val loggedIn = !state.needsLogin && state != TsState.NO_STATE && state != TsState.STOPPED
        b.tsLogoutBtn.isEnabled = loggedIn
        updateNodeInfo(loggedIn)
    }

    private fun updateNodeInfo(connected: Boolean) {
        if (!connected) {
            b.tsNodeInfo.visibility = View.GONE
            return
        }
        val info = tsManager.nodeInfo()
        if (info.ipv4.isNotEmpty()) {
            val name = info.name.ifEmpty { "unknown" }
            b.tsNodeInfo.text = getString(R.string.ts_node_info, name, info.ipv4)
            b.tsNodeInfo.visibility = View.VISIBLE
        } else {
            b.tsNodeInfo.visibility = View.GONE
        }
    }

    private fun stateLabelRes(state: TsState): Int {
        return when (state) {
            TsState.RUNNING -> R.string.ts_state_running
            TsState.STARTING -> R.string.ts_state_starting
            TsState.NEEDS_LOGIN -> R.string.ts_state_needs_login
            TsState.NEEDS_MACHINE_AUTH -> R.string.ts_state_needs_machine_auth
            TsState.STOPPED -> R.string.ts_state_stopped
            TsState.IN_USE_OTHER_USER -> R.string.ts_state_other_user
            TsState.NO_STATE -> R.string.ts_state_no_state
        }
    }

    /** Saves a changed coordination URL (and restarts the engine) before login. */
    private fun maybeApplyControlUrl() {
        val url = b.tsControlUrl.text?.toString()?.trim().orEmpty()
        val normalized = tsManager.normalizeControlUrl(url)
        if (normalized != persistentState.tailscaleControlUrl) {
            lifecycleScope.launch { tsManager.setControlUrl(url) }
        }
    }

    private fun initClickListeners() {
        b.tsEnableSwitch.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            lifecycleScope.launch {
                if (checked && persistentState.routeRethinkInRethink) {
                    showToastUiCentered(
                        applicationContext,
                        getString(R.string.ts_incompatible_with_rinr),
                        Toast.LENGTH_LONG
                    )
                    b.tsEnableSwitch.isChecked = false
                    return@launch
                }
                val hasTunnel = VpnController.hasTunnel()
                if (checked && !hasTunnel) {
                    showToastUiCentered(
                        applicationContext,
                        getString(R.string.ts_requires_vpn_toast),
                        Toast.LENGTH_LONG
                    )
                    // VPN start will boot the engine; persist the intent now
                }
                tsManager.setEnabled(checked)
                if (!checked) {
                    // remove the proxy from the tunnel, if any
                    showToastUiCentered(
                        applicationContext,
                        getString(R.string.ts_disabled_toast),
                        Toast.LENGTH_SHORT
                    )
                }
                updateStatusUi(tsManager.currentState())
            }
        }

        b.tsLoginBtn.setOnClickListener {
            maybeApplyControlUrl()
            lifecycleScope.launch {
                val (ok, err) = tsManager.loginInteractive()
                if (!ok) {
                    showToastUiCentered(
                        applicationContext,
                        getString(R.string.two_argument_colon, getString(R.string.ts_login_failed), err),
                        Toast.LENGTH_LONG
                    )
                }
            }
        }

        b.tsAuthkeyLoginBtn.setOnClickListener {
            val key = b.tsAuthKey.text?.toString()?.trim().orEmpty()
            if (key.isEmpty()) {
                b.tsAuthkeyLayout.error = getString(R.string.ts_auth_key_required)
                return@setOnClickListener
            }
            b.tsAuthkeyLayout.error = null
            maybeApplyControlUrl()
            // auth-key login needs an engine restart with the key handed to
            // Start(); persist it and bounce the engine
            persistentState.tailscaleAuthKey = key
            lifecycleScope.launch {
                val (ok, err) = tsManager.restartEngineWithAuthKey(key)
                b.tsAuthKey.setText("")
                val msg = if (ok) getString(R.string.ts_login_started)
                          else getString(R.string.two_argument_colon, getString(R.string.ts_login_failed), err)
                showToastUiCentered(applicationContext, msg, Toast.LENGTH_SHORT)
            }
        }

        b.tsLogoutBtn.setOnClickListener {
            lifecycleScope.launch {
                tsManager.logout()
                showToastUiCentered(applicationContext, getString(R.string.ts_logged_out), Toast.LENGTH_SHORT)
            }
        }

        b.tsApplyExitNodeBtn.setOnClickListener {
            val node = b.tsExitNode.text?.toString()?.trim().orEmpty()
            lifecycleScope.launch {
                val (ok, err) = tsManager.applyExitNode(node)
                val msg =
                    if (ok) getString(R.string.ts_exit_node_applied)
                    else getString(R.string.two_argument_colon, getString(R.string.ts_exit_node_failed), err)
                showToastUiCentered(applicationContext, msg, Toast.LENGTH_SHORT)
            }
        }

        b.tsShieldsUpSwitch.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            persistentState.tailscaleShieldsUp = checked
            updateInboundTcpPortsSubtitle()
            lifecycleScope.launch { tsManager.applyPendingPrefs() }
        }

        b.tsAcceptRoutesSwitch.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            persistentState.tailscaleAcceptRoutes = checked
            lifecycleScope.launch { tsManager.applyPendingPrefs() }
        }

        b.tsMagicDnsSwitch.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            lifecycleScope.launch {
                val (ok, err) = tsManager.setMagicDns(checked)
                if (!ok) {
                    showToastUiCentered(
                        applicationContext,
                        getString(R.string.two_argument_colon, getString(R.string.ts_magic_dns), err),
                        Toast.LENGTH_LONG
                    )
                    b.tsMagicDnsSwitch.isChecked = !checked
                    return@launch
                }
                updateMagicDnsSuffix(checked)
            }
        }

        b.tsApplyAdvertiseRoutesBtn.setOnClickListener {
            val routes = b.tsAdvertiseRoutes.text?.toString()?.trim().orEmpty()
            persistentState.tailscaleAdvertiseRoutes = routes
            lifecycleScope.launch {
                tsManager.applyPendingPrefs()
                val msg = if (routes.isEmpty()) getString(R.string.ts_advertise_routes_cleared)
                          else getString(R.string.ts_advertise_routes_applied)
                showToastUiCentered(applicationContext, msg, Toast.LENGTH_SHORT)
            }
        }

        b.tsAppsBtn.setOnClickListener { openAppsDialog() }

        b.tsInboundTcpPortsRl.setOnClickListener { openInboundTcpPortsDialog() }

        b.tsControlUrl.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@setOnFocusChangeListener
            val url = b.tsControlUrl.text?.toString()?.trim().orEmpty()
            if (url != persistentState.tailscaleControlUrl) {
                lifecycleScope.launch {
                    tsManager.setControlUrl(url)
                    showToastUiCentered(
                        applicationContext,
                        getString(R.string.ts_control_url_saved),
                        Toast.LENGTH_SHORT
                    )
                    updateStatusUi(tsManager.currentState())
                }
            }
        }
    }

    /**
     * Editor for the inbound TCP allowlist: starter preset groups plus Custom
     * ports/ranges on this node's Tailscale IP that netstack forwards to
     * 127.0.0.1. Nothing selected and an empty Custom = block all inbound TCP
     * (the default).
     */
    private fun openInboundTcpPortsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_inbound_tcp_ports, null)
        val inputLayout =
            dialogView.findViewById<TextInputLayout>(R.id.dib_inbound_tcp_ports_layout)
        val input = dialogView.findViewById<TextInputEditText>(R.id.dib_inbound_tcp_ports)
        val groupsView =
            dialogView.findViewById<AppCompatTextView>(R.id.dib_inbound_tcp_ports_groups)
        val presetsContainer =
            dialogView.findViewById<LinearLayout>(R.id.dib_inbound_tcp_presets_container)

        val selected = currentPresetIds().toMutableSet()
        input.setText(currentCustomSpec())

        val refreshGroups = {
            groupsView.text =
                InboundTcpPorts.formatDialogGroups(selected, input.text?.toString().orEmpty())
        }

        InboundTcpPorts.PRESETS.forEach { preset ->
            val row =
                LayoutInflater.from(this)
                    .inflate(R.layout.item_inbound_tcp_preset, presetsContainer, false)
            val check = row.findViewById<AppCompatCheckBox>(R.id.item_inbound_tcp_preset_checkbox)
            row.findViewById<AppCompatTextView>(R.id.item_inbound_tcp_preset_label).text =
                preset.label
            val spec =
                if (preset.note.isEmpty()) preset.spec
                else getString(R.string.two_argument_dot, preset.spec, preset.note)
            row.findViewById<AppCompatTextView>(R.id.item_inbound_tcp_preset_spec).text = spec
            check.isChecked = selected.contains(preset.id)
            // the whole row toggles; the checkbox itself only reflects state
            row.setOnClickListener { check.isChecked = !check.isChecked }
            check.setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(preset.id) else selected.remove(preset.id)
                refreshGroups()
            }
            presetsContainer.addView(row)
        }

        input.addTextChangedListener { text ->
            val result = InboundTcpPorts.validate(text?.toString().orEmpty())
            inputLayout.error = if (result.valid) null else result.error
            refreshGroups()
        }
        refreshGroups()

        val dialog =
            MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
                .setTitle(getString(R.string.ts_inbound_tcp_ports))
                .setView(dialogView)
                .setPositiveButton(getString(R.string.lbl_save), null)
                .setNeutralButton(getString(R.string.ts_clear)) { d, _ ->
                    lifecycleScope.launch {
                        tsManager.clearInboundTcpConfig()
                        updateInboundTcpPortsSubtitle()
                    }
                    d.dismiss()
                }
                .setNegativeButton(getString(R.string.lbl_cancel)) { d, _ -> d.dismiss() }
                .create()
        // override Save so invalid input keeps the dialog open and surfaces an
        // inline error instead of dismissing
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val result = InboundTcpPorts.validate(input.text?.toString().orEmpty())
                if (!result.valid) {
                    inputLayout.error = result.error
                    return@setOnClickListener
                }
                inputLayout.error = null
                lifecycleScope.launch {
                    val (ok, err) = tsManager.setInboundTcpConfig(selected, result.normalized)
                    if (!ok) {
                        showToastUiCentered(
                            applicationContext,
                            getString(
                                R.string.two_argument_colon,
                                getString(R.string.ts_inbound_tcp_ports_failed),
                                err
                            ),
                            Toast.LENGTH_LONG
                        )
                        return@launch
                    }
                    updateInboundTcpPortsSubtitle()
                    val msg =
                        if (selected.isEmpty() && result.normalized.isEmpty())
                            R.string.ts_inbound_tcp_ports_cleared
                        else R.string.ts_inbound_tcp_ports_saved
                    showToastUiCentered(applicationContext, getString(msg), Toast.LENGTH_SHORT)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    /** Per-app assignment of the tailnet proxy, mirroring WireGuard / Orbot. */
    private fun openAppsDialog() {
        if (!VpnController.hasTunnel()) {
            showToastUiCentered(
                applicationContext,
                getString(R.string.ts_requires_vpn_toast),
                Toast.LENGTH_LONG
            )
            return
        }
        // upstream replaced the per-app bottom dialog with a full-screen
        // activity (app/src/main/.../ui/activity/WgIncludeAppsActivity.kt)
        startActivity(
            WgIncludeAppsActivity.newIntent(
                this,
                TailscaleManager.ID_TS_BASE,
                TailscaleManager.TS_PROXY_NAME
            )
        )
    }
}
