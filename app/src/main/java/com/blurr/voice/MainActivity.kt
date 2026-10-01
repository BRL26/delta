package com.blurr.voice

import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.android.billingclient.api.*
import com.blurr.voice.assistant.AssistantRoleService
import com.blurr.voice.ui.app.DeltaDestination
import com.blurr.voice.ui.app.DeltaNav
import com.blurr.voice.ui.home.EXAMPLES
import com.blurr.voice.ui.home.HomeScreen
import com.blurr.voice.ui.home.HomeUiState
import com.blurr.voice.ui.theme.BlurrTheme
import com.blurr.voice.utilities.AuthGate
import com.blurr.voice.utilities.DeltaState
import com.blurr.voice.utilities.DeltaStateColorMapper
import com.blurr.voice.utilities.DeltaStateManager
import com.blurr.voice.utilities.FreemiumManager
import com.blurr.voice.utilities.Logger
import com.blurr.voice.utilities.OnboardingManager
import com.blurr.voice.utilities.PermissionManager
import com.blurr.voice.utilities.UserIdManager
import com.blurr.voice.utilities.UserProfileManager
import com.blurr.voice.v2.AgentService
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.firestore
import com.google.firebase.remoteconfig.remoteConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * The launcher, and the app's home screen.
 *
 * The screen itself is [HomeScreen]; this class is the auth gate, the subscription
 * check, the assistant-state listener and the owner of the [HomeUiState] the screen
 * draws. Nothing here touches a `View`.
 *
 * That split is the point of the rewrite. The old version found eleven different
 * widgets by id, toggled their `visibility` from six places and set two of them to
 * `View.GONE` at once, and had its own `Color.parseColor("#4CAF50")` for the
 * permission line -- which is why the home screen was the only orange page in the
 * app. Here every one of those is a field on the state.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var userId: String
    private lateinit var permissionManager: PermissionManager
    private lateinit var auth: FirebaseAuth
    private lateinit var freemiumManager: FreemiumManager
    private lateinit var onboardingManager: OnboardingManager
    private lateinit var requestRoleLauncher: ActivityResultLauncher<Intent>
    private lateinit var deltaStateManager: DeltaStateManager
    private lateinit var stateChangeListener: (DeltaState) -> Unit

    /** Everything [HomeScreen] draws. Written only through the helpers below. */
    private var ui by mutableStateOf(
        HomeUiState(
            deltaState = DeltaState.IDLE,
            statusText = "Ready",
            loading = true,
            permissionsGranted = false,
            isDefaultAssistant = false,
            tasksLeft = null,
            developerMessage = null,
        ),
    )

    private val purchaseUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PURCHASE_UPDATED) {
                Logger.d(TAG, "Received purchase update broadcast.")
                ui = ui.copy(loading = true)
                performBillingCheck()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        auth = Firebase.auth
        val profileManager = UserProfileManager(this)

        // AuthGate decides. In local mode (the default for this fork) it
        // provisions a placeholder profile and returns true, so there is no
        // login wall. It falls back to the original Firebase check only if the
        // mode is explicitly set back to "firebase".
        val signedIn = AuthGate.isSignedIn(this)

        if (!signedIn || !profileManager.isProfileComplete()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        onboardingManager = OnboardingManager(this)
        if (!onboardingManager.isOnboardingCompleted()) {
            Logger.d(TAG, "User is logged in but onboarding not completed. Relaunching permissions stepper.")
            startActivity(Intent(this, OnboardingPermissionsActivity::class.java))
            finish()
            return
        }

        requestRoleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                Toast.makeText(this, "Set as default assistant successfully!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Couldn’t become default assistant. Opening settings…", Toast.LENGTH_SHORT).show()
                Logger.w(TAG, "Role request canceled or app not eligible.\n${explainAssistantEligibility()}")
                openAssistantPickerSettings()
            }
            showAssistantStatus(toast = false)
        }

        enableEdgeToEdge()
        setContent {
            BlurrTheme {
                HomeScreen(
                    state = ui,
                    onNavigate = { DeltaNav.navigate(this, it) },
                    onDeltaTap = { startConversationalAgent() },
                    onSetAssistantClick = {
                        startActivity(Intent(this, RoleRequestActivity::class.java))
                    },
                    onManagePermissionsClick = {
                        startActivity(Intent(this, PermissionsActivity::class.java))
                    },
                    onEmailDeveloperClick = { requestLimitIncrease() },
                    onDisclaimerClick = { ui = ui.copy(disclaimerOpen = true) },
                    onExamplesClick = { ui = ui.copy(examplesOpen = true) },
                    onDeveloperMessageDismiss = { dismissDeveloperMessage() },
                    onDisclaimerDismiss = { ui = ui.copy(disclaimerOpen = false) },
                    onExamplesDismiss = { ui = ui.copy(examplesOpen = false) },
                    onExampleChosen = { example -> runExample(example) },
                )
            }
        }

        handleIntent(intent)

        userId = UserIdManager(applicationContext).getOrCreateUserId()

        permissionManager = PermissionManager(this)
        permissionManager.initializePermissionLauncher()

        freemiumManager = FreemiumManager()
        initializeDeltaStateManager()
        ui = ui.copy(isDefaultAssistant = isThisAppDefaultAssistant())
        performBillingCheck()
    }

    private fun openAssistantPickerSettings() {
        val specifics = listOf(
            Intent("android.settings.VOICE_INPUT_SETTINGS"),
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        )
        for (i in specifics) {
            if (i.resolveActivity(packageManager) != null) {
                startActivity(i); return
            }
        }
        Toast.makeText(this, "Assistant settings not available on this device.", Toast.LENGTH_SHORT).show()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun showAssistantStatus(toast: Boolean = false) {
        val rm = getSystemService(RoleManager::class.java)
        val held = rm?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        val msg = if (held) "This app is the default assistant." else "This app is NOT the default assistant."
        if (toast) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        Logger.d(TAG, msg)
    }

    private fun explainAssistantEligibility(): String {
        val pm = packageManager
        val pkg = packageName

        val assistIntent = Intent(Intent.ACTION_ASSIST).setPackage(pkg)
        val assistActivities = pm.queryIntentActivities(assistIntent, 0)

        val visIntent = Intent("android.service.voice.VoiceInteractionService").setPackage(pkg)
        val visServices = pm.queryIntentServices(visIntent, 0)

        return buildString {
            append("Assistant eligibility:\n")
            append("• ACTION_ASSIST activity: ${if (assistActivities.isNotEmpty()) "FOUND" else "NOT FOUND"}\n")
            append("• VoiceInteractionService: ${if (visServices.isNotEmpty()) "FOUND" else "NOT FOUND"}\n")
            append("Note: Many OEMs only list apps with a VoiceInteractionService as selectable assistants.\n")
        }
    }

    override fun onStart() {
        super.onStart()
        if (!AuthGate.isSignedIn(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        ui = ui.copy(loading = true)
        performBillingCheck()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == "com.blurr.voice.WAKE_UP_DELTA") {
            Logger.d(TAG, "Wake up Delta shortcut activated!")
            startConversationalAgent()
        }
    }

    private fun startConversationalAgent() {
        // The popup, through the same code path the power-button gesture and the key
        // use. There is deliberately no second front end any more: the service's own
        // overlays -- the "Listening..." bubble, the input box, the thinking indicator,
        // the clarification cards -- are all gone, so starting the service without a
        // session window would leave the user with a running microphone and nothing on
        // screen to talk to.
        if (AssistantRoleService.showAssistantPopup()) return

        // Not the assistant role holder, so there is no session window to show. Asking
        // for the role is the only honest answer: without it the platform will not give
        // this app a popup, and the request has nowhere to land.
        startActivity(Intent(this, RoleRequestActivity::class.java))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun requestLimitIncrease() {
        val userEmail = AuthGate.currentEmail(this)
        if (userEmail.isNullOrEmpty()) {
            Toast.makeText(this, "Could not get your email. Please try again.", Toast.LENGTH_SHORT).show()
            return
        }

        val recipient = "ayush0000ayush@gmail.com"
        val subject = "I am facing issue in"
        val body = "Hello,\n\nI am facing issue for my account: $userEmail\n <issue-content>.... \n\nThank you."

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:") // Only email apps should handle this
            putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }

        // Verify that the intent will resolve to an activity
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, "No email application found.", Toast.LENGTH_SHORT).show()
        }
    }

    /** Starts the agent on [example], with the old fork's easter egg for the last row. */
    private fun runExample(example: String) {
        ui = ui.copy(examplesOpen = false)
        val instruction =
            if (example == EXAMPLES.last()) "play never gonna give you up on youtube" else example
        AgentService.start(this, instruction)
    }

    /**
     * Initialize DeltaStateManager and set up state change listeners
     */
    private fun initializeDeltaStateManager() {
        deltaStateManager = DeltaStateManager.getInstance(this)
        stateChangeListener = { newState ->
            updateStatusText(newState)
            ui = ui.copy(deltaState = newState)
            Logger.d(TAG, "Delta state changed to: ${newState.name}")
        }
        deltaStateManager.addStateChangeListener(stateChangeListener)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onResume() {
        super.onResume()
        ui = ui.copy(
            loading = true,
            isDefaultAssistant = isThisAppDefaultAssistant(),
        )
        performBillingCheck()
        displayDeveloperMessage()
        ui = ui.copy(deltaState = deltaStateManager.getCurrentState())
        updatePermissionState()
        deltaStateManager.startMonitoring()
        val purchaseFilter = IntentFilter(ACTION_PURCHASE_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(purchaseUpdateReceiver, purchaseFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(purchaseUpdateReceiver, purchaseFilter)
        }
    }

    override fun onPause() {
        super.onPause()
        deltaStateManager.stopMonitoring()
        try {
            unregisterReceiver(purchaseUpdateReceiver)
        } catch (e: IllegalArgumentException) {
            Logger.d(TAG, "Receivers were not registered")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::deltaStateManager.isInitialized && ::stateChangeListener.isInitialized) {
            deltaStateManager.removeStateChangeListener(stateChangeListener)
            deltaStateManager.stopMonitoring()
        }
    }

    private fun updateTaskCounter() {
        lifecycleScope.launch {
            val isUserSub = freemiumManager.isUserSubscribed()
            // A subscriber has no counter to show, and "0 tasks left" would be a
            // lie about a limit they are not on.
            ui = ui.copy(tasksLeft = if (isUserSub) null else freemiumManager.getTasksRemaining())
        }
    }

    /** Refreshes whether Delta has everything it needs, and says so on the screen. */
    private fun updatePermissionState() {
        ui = ui.copy(permissionsGranted = permissionManager.areAllPermissionsGranted())
    }

    private fun isThisAppDefaultAssistant(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            rm?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        } else {
            val flat = Settings.Secure.getString(contentResolver, "voice_interaction_service")
            val currentPkg = flat?.substringBefore('/')
            currentPkg == packageName
        }
    }

    private fun performBillingCheck() {
        lifecycleScope.launch {
            try {
                waitForBillingClientReady()
                queryAndHandlePurchases()
            } catch (e: Exception) {
                Logger.e(TAG, "Error during billing check", e)
            } finally {
                updateTaskCounter()
                ui = ui.copy(loading = false)
            }
        }
    }

    private suspend fun waitForBillingClientReady() {
        return withContext(Dispatchers.IO) {
            var attempts = 0
            val maxAttempts = 10

            while (!MyApplication.isBillingClientReady.value && attempts < maxAttempts) {
                kotlinx.coroutines.delay(500)
                attempts++
            }

            if (!MyApplication.isBillingClientReady.value) {
                Logger.w(TAG, "Billing client not ready after waiting")
            }
        }
    }

    private suspend fun queryAndHandlePurchases() {
        return withContext(Dispatchers.IO) {
            if (!MyApplication.isBillingClientReady.value) {
                Logger.e(TAG, "queryPurchases: BillingClient is not ready")
                return@withContext
            }

            try {
                val params = QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()

                Logger.d(TAG, "queryPurchases: BillingClient is ready")

                val purchasesResult = MyApplication.billingClient.queryPurchasesAsync(params)
                val billingResult = purchasesResult.billingResult

                Logger.d(TAG, "queryPurchases: Got billing result: ${billingResult.responseCode}")

                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Logger.d(TAG, "queryPurchases: Found ${purchasesResult.purchasesList.size} purchases")
                    purchasesResult.purchasesList.forEach { purchase ->
                        when (purchase.purchaseState) {
                            Purchase.PurchaseState.PURCHASED -> {
                                Logger.d(TAG, "Found purchased item: ${purchase.products}")
                                handlePurchase(purchase)
                            }
                            Purchase.PurchaseState.PENDING -> {
                                Logger.d(TAG, "Purchase is pending")
                            }
                            else -> {
                                Logger.d(TAG, "Purchase is not in a valid state: ${purchase.purchaseState}")
                            }
                        }
                    }
                } else {
                    Logger.e(TAG, "Failed to query purchases: ${billingResult.debugMessage}")
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Exception during purchase query", e)
            }
        }
    }

    private suspend fun handlePurchase(purchase: Purchase) {
        return withContext(Dispatchers.IO) {
            try {
                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    if (!purchase.isAcknowledged) {
                        val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                            .setPurchaseToken(purchase.purchaseToken)
                            .build()

                        MyApplication.billingClient.acknowledgePurchase(acknowledgePurchaseParams) { billingResult ->
                            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                                Logger.d(TAG, "Purchase acknowledged: ${purchase.orderId}")
                                lifecycleScope.launch {
                                    updateUserToPro()
                                }
                            } else {
                                Logger.e(TAG, "Failed to acknowledge purchase: ${billingResult.debugMessage}")
                            }
                        }
                    } else {
                        updateUserToPro()
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Error handling purchase", e)
            }
        }
    }


    private suspend fun updateUserToPro() {
        val uid = AuthGate.currentUid(this)
        if (uid == null) {
            Logger.e(TAG, "Cannot update user to pro: user is not authenticated.")
            return
        }

        withContext(Dispatchers.IO) {
            val db = Firebase.firestore
            try {
                val userDocRef = db.collection("users").document(uid)
                userDocRef.update("plan", "pro").await()
                Logger.d(TAG, "Successfully updated user $uid to 'pro' plan.")
            } catch (e: Exception) {
                Logger.e(TAG, "Error updating user to pro", e)
            }
        }
    }

    /**
     * Fetches a one-off message from Remote Config and shows it, at most once.
     */
    private fun displayDeveloperMessage() {
        try {
            val sharedPrefs = getSharedPreferences("developer_message_prefs", Context.MODE_PRIVATE)
            val displayCount = sharedPrefs.getInt("developer_message_count", 0)

            if (displayCount >= 1) {
                Logger.d(TAG, "Developer message already shown $displayCount times, skipping display")
                return
            }

            val remoteConfig = Firebase.remoteConfig

            remoteConfig.fetchAndActivate()
                .addOnCompleteListener(this) { task ->
                    if (task.isSuccessful) {
                        val message = remoteConfig.getString("developerMessage")
                        if (message.isNotEmpty()) {
                            ui = ui.copy(developerMessage = message)
                            Log.d(TAG, "Developer message displayed from Remote Config.")
                        } else {
                            Log.d(TAG, "No developer message found in Remote Config.")
                        }
                    } else {
                        Log.e(TAG, "Failed to fetch Remote Config.", task.exception)
                    }
                }
        } catch (e: Exception) {
            Logger.e(TAG, "Exception in displayDeveloperMessage", e)
        }
    }

    /** Counts the message as shown, so it is not shown again. */
    private fun dismissDeveloperMessage() {
        ui = ui.copy(developerMessage = null)
        getSharedPreferences("developer_message_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("developer_message_count", 1)
            .apply()
    }

    /**
     * Update the status text based on the current DeltaState
     */
    fun updateStatusText(state: DeltaState) {
        try {
            val status = DeltaStateColorMapper.getStatusText(state)
            ui = ui.copy(statusText = status)
            Logger.d(TAG, "Status text updated to: $status for state: ${state.name}")
        } catch (e: Exception) {
            Logger.e(TAG, "Error updating status text", e)
            ui = ui.copy(statusText = "Ready")
        }
    }

    /**
     * Update the status text with custom text (overrides state-based text)
     */
    fun updateStatusText(customText: String) {
        try {
            ui = ui.copy(statusText = customText)
            Logger.d(TAG, "Status text updated to custom text: $customText")
        } catch (e: Exception) {
            Logger.e(TAG, "Error updating status text with custom text", e)
            ui = ui.copy(statusText = "Ready")
        }
    }

    companion object {
        const val ACTION_PURCHASE_UPDATED = "com.blurr.voice.PURCHASE_UPDATED"
        private const val TAG = "MainActivity"
    }
}
