package io.github.ciurlaro.codexmobile.providers.telegram

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.ciurlaro.codexmobile.agent.codex.ProviderSecretStore
import io.github.ciurlaro.codexmobile.platform.android.TelegramAuthEvent
import io.github.ciurlaro.codexmobile.platform.android.AndroidProviderSecretStore
import io.github.ciurlaro.codexmobile.platform.android.TelegramAuthPrompt
import io.github.ciurlaro.codexmobile.platform.android.TelegramAuthSession
import io.github.ciurlaro.codexmobile.platform.android.TelegramCredentials
import io.github.ciurlaro.codexmobile.platform.android.TelegramDisconnectResult
import io.github.ciurlaro.codexmobile.platform.android.TelegramIntegration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TelegramSettingsActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var secrets: ProviderSecretStore
    private lateinit var telegram: TelegramIntegration
    private lateinit var status: TextView
    private lateinit var apiId: EditText
    private lateinit var apiHash: EditText
    private lateinit var saveCredentials: Button
    private lateinit var changeCredentials: Button
    private lateinit var value: EditText
    private lateinit var action: Button
    private lateinit var disconnect: Button
    private var authentication: TelegramAuthSession? = null
    private var prompt: TelegramAuthPrompt? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secrets = AndroidProviderSecretStore(applicationContext, TELEGRAM_PLUGIN_ID)
        telegram = TelegramIntegration(applicationContext, TelegramCredentials.from(secrets.snapshot()))
        status = TextView(this).apply { textSize = 18f }
        apiId = EditText(this).apply { hint = "Telegram API ID"; inputType = InputType.TYPE_CLASS_NUMBER }
        apiHash = EditText(this).apply {
            hint = "Telegram API hash"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        saveCredentials = Button(this).apply { text = "Save credentials"; setOnClickListener { saveCredentials() } }
        changeCredentials = Button(this).apply {
            text = "Change API credentials"
            setOnClickListener { changeCredentials() }
        }
        value = EditText(this).apply { hint = "Phone number (+…)"; inputType = InputType.TYPE_CLASS_PHONE }
        action = Button(this).apply { text = "Connect"; setOnClickListener { submit() } }
        disconnect = Button(this).apply { text = "Disconnect"; setOnClickListener { disconnect() } }
        setContentView(ScrollView(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val padding = (24 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
                addView(TextView(context).apply { text = "Telegram"; textSize = 26f })
                addView(status)
                addView(apiId); addView(apiHash); addView(saveCredentials); addView(changeCredentials)
                addView(value); addView(action); addView(disconnect)
            })
        })
        refresh()
    }

    override fun onDestroy() {
        authentication?.close()
        telegram.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun refresh(message: String? = null) = scope.launch {
        val current = withContext(Dispatchers.IO) { telegram.status() }
        status.text = message ?: when {
            !current.available -> "Enter Telegram application credentials from my.telegram.org."
            current.connected -> "Connected${current.username?.let { " as @$it" }.orEmpty()}"
            else -> "Not connected"
        }
        apiId.visibility = if (current.available) View.GONE else View.VISIBLE
        apiHash.visibility = apiId.visibility
        saveCredentials.visibility = apiId.visibility
        changeCredentials.visibility = if (current.available && !current.connected) View.VISIBLE else View.GONE
        value.visibility = if (current.connected || !current.available) View.GONE else View.VISIBLE
        action.visibility = value.visibility
        disconnect.visibility = if (current.connected) View.VISIBLE else View.GONE
    }

    private fun saveCredentials() {
        val credentials = TelegramCredentials(apiId.text.toString().toIntOrNull(), apiHash.text.toString().trim())
        if (!credentials.valid) {
            status.text = "Enter a positive API ID and its 32-character API hash."
            return
        }
        runCatching {
            secrets.replace(
                mapOf(
                    TELEGRAM_API_ID_SECRET to checkNotNull(credentials.apiId).toString(),
                    TELEGRAM_API_HASH_SECRET to credentials.apiHash,
                ),
            )
            telegram.close()
            telegram = TelegramIntegration(applicationContext, credentials)
            apiId.setText(""); apiHash.setText("")
        }.onSuccess {
            refresh("Telegram application credentials saved")
        }.onFailure {
            status.text = it.message ?: "Telegram application credentials could not be saved"
        }
    }

    private fun changeCredentials() {
        changeCredentials.isEnabled = false
        scope.launch {
            val result = withContext(Dispatchers.IO) { telegram.disconnect() }
            if (result == TelegramDisconnectResult.INDETERMINATE) {
                status.text = "Remote logout could not be confirmed; API credentials were not changed."
                changeCredentials.isEnabled = true
                return@launch
            }
            secrets.clear()
            telegram.close()
            telegram = TelegramIntegration(applicationContext, TelegramCredentials(null, ""))
            refresh("Disconnected. Enter replacement Telegram application credentials.")
            changeCredentials.isEnabled = true
        }
    }

    private fun submit() {
        val answer = value.text.toString().trim()
        if (answer.isEmpty()) return
        action.isEnabled = false
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val session = authentication ?: telegram.startAuthentication(answer).also { authentication = it }
                    if (prompt != null) session.submitAnswer(answer)
                    session.awaitEvent()
                }
            }.onSuccess(::showAuthenticationEvent).onFailure {
                status.text = it.message ?: "Telegram authentication failed"
                action.isEnabled = true
            }
        }
    }

    private fun showAuthenticationEvent(event: TelegramAuthEvent) {
        when (event) {
            is TelegramAuthEvent.Prompt -> {
                prompt = event.prompt
                value.setText("")
                value.hint = if (prompt == TelegramAuthPrompt.CODE) "Telegram code" else "2FA password"
                value.inputType = if (prompt == TelegramAuthPrompt.CODE) InputType.TYPE_CLASS_NUMBER
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                action.text = "Continue"
                action.isEnabled = true
                status.text = "Authentication required"
            }
            is TelegramAuthEvent.Connected -> {
                authentication?.close(); authentication = null; prompt = null
                value.hint = "Phone number (+…)"; action.text = "Connect"
                refresh("Connected${event.username?.let { " as @$it" }.orEmpty()}")
            }
            is TelegramAuthEvent.Failed -> {
                authentication?.close(); authentication = null; prompt = null
                status.text = event.message; action.isEnabled = true
            }
        }
    }

    private fun disconnect() {
        disconnect.isEnabled = false
        scope.launch {
            val result = withContext(Dispatchers.IO) { telegram.disconnect() }
            refresh(if (result == TelegramDisconnectResult.CONFIRMED) "Disconnected"
            else "Local session removed; remote logout could not be confirmed")
            disconnect.isEnabled = true
        }
    }
}
