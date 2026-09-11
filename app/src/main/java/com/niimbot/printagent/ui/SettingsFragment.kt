package com.niimbot.printagent.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.niimbot.printagent.R
import com.niimbot.printagent.pos.IntegrationConfigStore
import com.niimbot.printagent.pos.PosApiClient
import com.niimbot.printagent.pos.PosApiResult
import com.niimbot.printagent.pos.PosIdentity
import com.niimbot.printagent.pos.PosProductRules
import com.niimbot.printagent.service.PrintForegroundService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    @javax.inject.Inject
    lateinit var integrationConfigStore: IntegrationConfigStore

    @javax.inject.Inject
    lateinit var posApiClient: PosApiClient

    private var etPosBaseUrl: EditText? = null
    private var etPosUsername: EditText? = null
    private var etPosPassword: EditText? = null
    private var tvPosIdentity: TextView? = null
    private var btnPosLogin: Button? = null
    private var btnTestPosConnection: Button? = null
    private var btnPosLogout: Button? = null

    // Server config
    private var etServerPort: EditText? = null
    private var btnSavePort: Button? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindViews(view)
        setupClickListeners()
        loadSettings()
    }

    private fun bindViews(view: View) {
        etPosBaseUrl = view.findViewById(R.id.et_pos_base_url)
        etPosUsername = view.findViewById(R.id.et_pos_username)
        etPosPassword = view.findViewById(R.id.et_pos_password)
        tvPosIdentity = view.findViewById(R.id.tv_pos_identity)
        btnPosLogin = view.findViewById(R.id.btn_pos_login)
        btnTestPosConnection = view.findViewById(R.id.btn_test_pos_connection)
        btnPosLogout = view.findViewById(R.id.btn_pos_logout)

        // Server
        etServerPort = view.findViewById(R.id.et_server_port)
        btnSavePort = view.findViewById(R.id.btn_save_port)
    }

    private fun loadSettings() {
        val prefs = requireContext().getSharedPreferences("niimbot_prefs", android.content.Context.MODE_PRIVATE)

        etServerPort?.setText(prefs.getInt("server_port", 8080).toString())
        etPosBaseUrl?.setText(integrationConfigStore.getBaseUrl())
        showIdentity(integrationConfigStore.getIdentity())
    }

    private fun setupClickListeners() {
        btnPosLogin?.setOnClickListener { loginPos() }
        btnTestPosConnection?.setOnClickListener { testPosConnection() }
        btnPosLogout?.setOnClickListener { logoutPos() }

        // Server port
        btnSavePort?.setOnClickListener {
            val port = etServerPort?.text?.toString()?.toIntOrNull()
            if (port == null || port !in 1..65535) {
                etServerPort?.error = getString(R.string.port_invalid)
                return@setOnClickListener
            }
            val prefs = requireContext().getSharedPreferences("niimbot_prefs", android.content.Context.MODE_PRIVATE)
            prefs.edit().putInt("server_port", port).apply()

            // Restart print server with new port
            restartPrintServer(port)

            Toast.makeText(requireContext(), getString(R.string.port_saved, port), Toast.LENGTH_SHORT).show()
        }
    }

    private fun validatedBaseUrl(): String? {
        val baseUrl = PosProductRules.normalizeBaseUrl(etPosBaseUrl?.text.toString())
        if (baseUrl.toHttpUrlOrNull() == null) {
            etPosBaseUrl?.error = getString(R.string.pos_url_invalid)
            return null
        }
        etPosBaseUrl?.error = null
        return baseUrl
    }

    private fun loginPos() {
        val baseUrl = validatedBaseUrl() ?: return
        val username = etPosUsername?.text?.toString()?.trim().orEmpty()
        val password = etPosPassword?.text?.toString().orEmpty()
        if (username.isBlank()) {
            etPosUsername?.error = getString(R.string.pos_username_required)
            return
        }
        if (password.isBlank()) {
            etPosPassword?.error = getString(R.string.pos_password_required)
            return
        }
        setPosBusy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val login = try {
                posApiClient.login(baseUrl, username, password)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                PosApiResult.Failure(error.message ?: getString(R.string.pos_request_failed))
            }
            etPosPassword?.text?.clear()
            val message = when (login) {
                is PosApiResult.Success -> finishLogin(baseUrl, login.value.accessToken)
                is PosApiResult.Failure -> login.message
                PosApiResult.SessionExpired -> getString(R.string.pos_session_expired)
                PosApiResult.NotFound -> getString(R.string.pos_request_failed)
            }
            if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
            setPosBusy(false)
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun finishLogin(baseUrl: String, accessToken: String): String {
        return when (val me = posApiClient.me(baseUrl, accessToken)) {
            is PosApiResult.Success -> {
                integrationConfigStore.setBaseUrl(baseUrl)
                integrationConfigStore.setAuthenticatedSession(accessToken, me.value)
                showIdentity(me.value)
                getString(R.string.pos_login_success)
            }
            is PosApiResult.Failure -> me.message
            PosApiResult.SessionExpired -> {
                expireSession()
                getString(R.string.pos_session_expired)
            }
            PosApiResult.NotFound -> getString(R.string.pos_request_failed)
        }
    }

    private fun testPosConnection() {
        val baseUrl = validatedBaseUrl() ?: return
        val accessToken = integrationConfigStore.getAccessToken()
        if (accessToken.isNullOrBlank()) {
            Toast.makeText(requireContext(), R.string.pos_login_required, Toast.LENGTH_LONG).show()
            return
        }
        setPosBusy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = try {
                posApiClient.testConnection(baseUrl, accessToken)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                PosApiResult.Failure(error.message ?: getString(R.string.pos_request_failed))
            }
            val message = when (result) {
                is PosApiResult.Success -> {
                    integrationConfigStore.setBaseUrl(baseUrl)
                    integrationConfigStore.setAuthenticatedSession(accessToken, result.value)
                    showIdentity(result.value)
                    getString(R.string.pos_test_success)
                }
                PosApiResult.SessionExpired -> {
                    expireSession()
                    getString(R.string.pos_session_expired)
                }
                is PosApiResult.Failure -> result.message
                PosApiResult.NotFound -> getString(R.string.pos_request_failed)
            }
            if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
            setPosBusy(false)
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
    }

    private fun logoutPos() {
        integrationConfigStore.clearSession()
        etPosPassword?.text?.clear()
        showIdentity(null)
        Toast.makeText(requireContext(), R.string.pos_logout_success, Toast.LENGTH_SHORT).show()
    }

    private fun expireSession() {
        integrationConfigStore.clearSession()
        showIdentity(null)
    }

    private fun showIdentity(identity: PosIdentity?) {
        tvPosIdentity?.text = identity?.let {
            getString(R.string.pos_authenticated_identity, it.username, it.role)
        } ?: getString(R.string.pos_not_authenticated)
        btnPosLogout?.isEnabled = identity != null
        btnTestPosConnection?.isEnabled = identity != null
    }

    private fun setPosBusy(busy: Boolean) {
        btnPosLogin?.isEnabled = !busy
        btnPosLogout?.isEnabled = !busy && integrationConfigStore.hasAccessToken()
        btnTestPosConnection?.isEnabled = !busy && integrationConfigStore.hasAccessToken()
    }

    override fun onDestroyView() {
        etPosPassword?.text?.clear()
        etPosBaseUrl = null
        etPosUsername = null
        etPosPassword = null
        tvPosIdentity = null
        btnPosLogin = null
        btnTestPosConnection = null
        btnPosLogout = null
        etServerPort = null
        btnSavePort = null
        super.onDestroyView()
    }

    private fun restartPrintServer(port: Int) {
        val context = requireContext()
        val intent = android.content.Intent(context, PrintForegroundService::class.java).apply {
            action = PrintForegroundService.ACTION_RESTART_SERVER
            putExtra(PrintForegroundService.EXTRA_SERVER_PORT, port)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
