package com.blurr.voice

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.blurr.voice.api.LlmConnectionTester
import com.blurr.voice.api.LlmProvider
import com.blurr.voice.api.LlmProviderConfig
import com.blurr.voice.api.LlmProviderStore
import com.blurr.voice.utilities.Logger
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

/**
 * Lets the user add, edit, test and choose the LLM providers the agent uses.
 * Extends BaseNavigationActivity so it keeps the bottom nav, highlighting
 * Settings since that's where it's reached from.
 */
class AiProvidersActivity : BaseNavigationActivity() {

    private lateinit var store: LlmProviderStore
    private lateinit var container: LinearLayout
    private lateinit var emptyState: TextView

    override fun getContentLayoutId(): Int = R.layout.activity_ai_providers
    override fun getCurrentNavItem(): NavItem = NavItem.SETTINGS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Required: this override is what makes BaseNavigationActivity build the
        // bottom-nav layout and inflate activity_ai_providers into it. Without
        // the call, findViewById below resolves against an empty hierarchy.
        setContentView(R.layout.activity_ai_providers)

        store = LlmProviderStore.getInstance(this)
        container = findViewById(R.id.containerProviders)
        emptyState = findViewById(R.id.textNoProviders)
        findViewById<MaterialButton>(R.id.buttonAddProvider).setOnClickListener {
            showConfigDialog(null)
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        container.removeAllViews()
        val configs = store.getConfigs()
        emptyState.visibility = if (configs.isEmpty()) View.VISIBLE else View.GONE
        val activeId = store.getActiveProviderId()
        val inflater = LayoutInflater.from(this)

        configs.forEach { config ->
            val row = inflater.inflate(R.layout.item_provider, container, false)
            val name = row.findViewById<TextView>(R.id.textProviderName)
            val model = row.findViewById<TextView>(R.id.textProviderModel)
            val badge = row.findViewById<TextView>(R.id.textActiveBadge)
            val useBtn = row.findViewById<MaterialButton>(R.id.buttonSetActive)
            val delBtn = row.findViewById<ImageButton>(R.id.buttonDeleteProvider)

            name.text = config.provider?.displayName ?: config.providerId
            model.text = config.effectiveModel.ifBlank { "No model set" }

            val isActive = activeId == config.providerId
            badge.visibility = if (isActive) View.VISIBLE else View.GONE
            useBtn.visibility = if (isActive) View.GONE else View.VISIBLE

            useBtn.setOnClickListener {
                store.setActiveProviderId(config.providerId)
                Logger.d("AiProviders", "Active provider -> ${config.providerId}")
                Toast.makeText(this, "Now using ${name.text}", Toast.LENGTH_SHORT).show()
                render()
            }

            delBtn.setOnClickListener { confirmDelete(config) }
            row.setOnClickListener { showConfigDialog(config) }

            container.addView(row)
        }
    }

    private fun confirmDelete(config: LlmProviderConfig) {
        val label = config.provider?.displayName ?: config.providerId
        AlertDialog.Builder(this)
            .setTitle("Remove $label?")
            .setMessage("The stored API key will be deleted from this device.")
            .setPositiveButton("Remove") { _, _ ->
                store.deleteConfig(config.providerId)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showConfigDialog(existing: LlmProviderConfig?) {
        val view = layoutInflater.inflate(R.layout.dialog_provider_config, null)
        val title = view.findViewById<TextView>(R.id.dialogTitle)
        val providerInput = view.findViewById<MaterialAutoCompleteTextView>(R.id.inputProvider)
        val baseUrlInput = view.findViewById<TextInputEditText>(R.id.inputBaseUrl)
        val apiKeyInput = view.findViewById<TextInputEditText>(R.id.inputApiKey)
        val modelInput = view.findViewById<TextInputEditText>(R.id.inputModel)
        val testResult = view.findViewById<TextView>(R.id.textTestResult)
        val testBtn = view.findViewById<MaterialButton>(R.id.buttonTest)
        val saveBtn = view.findViewById<MaterialButton>(R.id.buttonSave)
        val keyLayout = view.findViewById<TextInputLayout>(R.id.layoutApiKey)

        title.text = if (existing == null) "Add provider" else "Edit provider"

        val names = LlmProvider.ALL.map { it.displayName }
        providerInput.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        )

        // Selection drives the defaults, but every field stays editable.
        var selected: LlmProvider? = existing?.provider
        providerInput.setOnItemClickListener { _, _, position, _ ->
            selected = LlmProvider.ALL.getOrNull(position)
            selected?.let { p ->
                baseUrlInput.setText(p.defaultBaseUrl)
                modelInput.setText(p.defaultModel)
                keyLayout.hint = if (p.keyRequired) "API key" else "API key (optional)"
                testResult.visibility = View.GONE
            }
        }

        existing?.let { cfg ->
            providerInput.setText(cfg.provider?.displayName ?: cfg.providerId, false)
            baseUrlInput.setText(cfg.baseUrl)
            apiKeyInput.setText(cfg.apiKey)
            modelInput.setText(cfg.model)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        fun collect(): LlmProviderConfig? {
            val provider = selected ?: run {
                Toast.makeText(this, "Pick a provider first.", Toast.LENGTH_SHORT).show()
                return null
            }
            return LlmProviderConfig(
                providerId = provider.id,
                baseUrl = baseUrlInput.text?.toString()?.trim().orEmpty(),
                apiKey = apiKeyInput.text?.toString()?.trim().orEmpty(),
                model = modelInput.text?.toString()?.trim().orEmpty()
            )
        }

        testBtn.setOnClickListener {
            val cfg = collect() ?: return@setOnClickListener
            testResult.visibility = View.GONE
            testBtn.isEnabled = false
            testResult.text = "Testing…"
            testResult.setTextColor(getColor(R.color.text_gray))
            testResult.visibility = View.VISIBLE

            lifecycleScope.launch {
                val result = LlmConnectionTester.test(cfg)
                testBtn.isEnabled = true
                when (result) {
                    is LlmConnectionTester.Result.Success -> {
                        testResult.text = "OK — replied: ${result.detail}"
                        testResult.setTextColor(getColor(R.color.delta_speaking))
                    }
                    is LlmConnectionTester.Result.Failure -> {
                        testResult.text = "Failed: ${result.detail}"
                        testResult.setTextColor(getColor(R.color.delta_error))
                    }
                }
            }
        }

        saveBtn.setOnClickListener {
            val cfg = collect() ?: return@setOnClickListener
            store.saveConfig(cfg)
            Logger.d("AiProviders", "Saved provider ${cfg.providerId}")
            Toast.makeText(this, "Saved ${cfg.provider?.displayName}", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            render()
        }

        providerInput.setOnClickListener {
            LlmProvider.byId(existing?.providerId ?: "")?.docsUrl?.let { openUrl(it) }
        }

        dialog.show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Logger.e("AiProviders", "No browser to open $url")
        }
    }
}
