package com.blurr.voice

import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.android.billingclient.api.*
import com.blurr.voice.assistant.AssistantRoleService
import com.blurr.voice.v2.AgentService
import com.blurr.voice.utilities.AuthGate
import com.blurr.voice.utilities.FreemiumManager
import com.blurr.voice.utilities.Logger
import com.blurr.voice.utilities.OnboardingManager
import com.blurr.voice.utilities.PermissionManager
import com.blurr.voice.utilities.UserIdManager
import com.blurr.voice.utilities.UserProfileManager
import com.blurr.voice.utilities.DeltaState
import com.blurr.voice.utilities.DeltaStateManager
import com.blurr.voice.utilities.DeltaStateColorMapper
import com.blurr.voice.views.DeltaSymbolView
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.firestore
import com.google.firebase.remoteconfig.remoteConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import java.io.File

class MainActivity : BaseNavigationActivity() {

    private lateinit var handler: Handler
    private lateinit var managePermissionsButton: TextView
    private lateinit var tvPermissionStatus: TextView
    private lateinit var userId: String
    private lateinit var permissionManager: PermissionManager
    private lateinit var auth: FirebaseAuth
    private lateinit var tasksLeftTag: View
    private lateinit var freemiumManager: FreemiumManager
    private lateinit var increaseLimitsLink: TextView
    private lateinit var onboardingManager: OnboardingManager
    private lateinit var requestRoleLauncher: ActivityResultLauncher<Intent>
    private lateinit var statusTextView: TextView
    private lateinit var loadingOverlay: View
    private lateinit var deltaStateManager: DeltaStateManager
    private lateinit var stateChangeListener: (DeltaState) -> Unit
    private lateinit var permissionsTag: View
    private lateinit var permissionsStatusTag: TextView
    private lateinit var tasksLeftText: TextView
    private lateinit var deltaSymbol: DeltaSymbolView


    private lateinit var root: View
    companion object {
        const val ACTION_PURCHASE_UPDATED = "com.blurr.voice.PURCHASE_UPDATED"
    }

    private val purchaseUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PURCHASE_UPDATED) {
                Logger.d("MainActivity", "Received purchase update broadcast.")
                // Refresh billing status
                showLoading(true)
                performBillingCheck()
            }
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Toast.makeText(this, "Permission granted!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permission denied.", Toast.LENGTH_SHORT).show()
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
            Logger.d("MainActivity", "User is logged in but onboarding not completed. Relaunching permissions stepper.")
            startActivity(Intent(this, OnboardingPermissionsActivity::class.java))
            finish()
            return
        }

        requestRoleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                Toast.makeText(this, "Set as default assistant successfully!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Couldn’t become default assistant. Opening settings…", Toast.LENGTH_SHORT).show()
                Logger.w("MainActivity", "Role request canceled or app not eligible.\n${explainAssistantEligibility()}")
                openAssistantPickerSettings()
            }
            showAssistantStatus(true)
        }


        setContentView(R.layout.activity_main_content)
        findViewById<TextView>(R.id.btn_set_default_assistant).setOnClickListener {
            startActivity(Intent(this, RoleRequestActivity::class.java))
        }
        updateDefaultAssistantButtonVisibility()

        handleIntent(intent)
        managePermissionsButton = findViewById(R.id.btn_manage_permissions) // ADDED

        val userIdManager = UserIdManager(applicationContext)
        userId = userIdManager.getOrCreateUserId()
        increaseLimitsLink = findViewById(R.id.increase_limits_link) // ADDED

        permissionManager = PermissionManager(this)
        permissionManager.initializePermissionLauncher()

        managePermissionsButton = findViewById(R.id.btn_manage_permissions)
        tasksLeftText = findViewById(R.id.tasks_left_tag_text)
        tasksLeftTag = findViewById(R.id.tasks_left_tag)
        tvPermissionStatus = findViewById(R.id.tv_permission_status)
        statusTextView = findViewById(R.id.status_text)
        loadingOverlay = findViewById(R.id.loading_overlay)
        permissionsTag = findViewById(R.id.permissions_tag)
        permissionsStatusTag = findViewById(R.id.permissions_status_tag)
        deltaSymbol = findViewById(R.id.delta_symbol)
        freemiumManager = FreemiumManager()
        updateStatusText(DeltaState.IDLE)
        initializeDeltaStateManager()
        handler = Handler(Looper.getMainLooper())
        setupClickListeners()
        showLoading(true)
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
        Logger.d("MainActivity", msg)
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
        
        showLoading(true)
        performBillingCheck()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == "com.blurr.voice.WAKE_UP_DELTA") {
            Logger.d("MainActivity", "Wake up Delta shortcut activated!")
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

    override fun getContentLayoutId(): Int = R.layout.activity_main_content
    
    override fun getCurrentNavItem(): BaseNavigationActivity.NavItem = BaseNavigationActivity.NavItem.HOME

    private fun setupClickListeners() {
        managePermissionsButton.setOnClickListener {
            startActivity(Intent(this, PermissionsActivity::class.java))
        }
        increaseLimitsLink.setOnClickListener {
            requestLimitIncrease()
        }

        findViewById<TextView>(R.id.disclaimer_link).setOnClickListener {
            showDisclaimerDialog()
        }
        findViewById<TextView>(R.id.examples_link).setOnClickListener {
            showExamplesDialog()
        }
        
        // Add click listener to delta symbol
        deltaSymbol.setOnClickListener {
            // Only start conversational agent if in ready/idle state
            if (deltaStateManager.getCurrentState() == DeltaState.IDLE || deltaStateManager.getCurrentState() == DeltaState.ERROR) {
                startConversationalAgent()
            }
        }
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


    // Pro upgrade banner: removed in this fork. There is no subscription UI, so
    // there is nothing to advertise and nothing to wire up.

    /**
     * Initialize DeltaStateManager and set up state change listeners
     */
    private fun initializeDeltaStateManager() {
        deltaStateManager = DeltaStateManager.getInstance(this)
        stateChangeListener = { newState ->
            updateStatusText(newState)

            updateDeltaVisuals(newState)
            Logger.d("MainActivity", "Delta state changed to: ${newState.name}")
        }
        deltaStateManager.addStateChangeListener(stateChangeListener)
    }
    private fun updateDeltaVisuals(state: DeltaState) {
        runOnUiThread {
            // Get the color for the current state
            val color = DeltaStateColorMapper.getColor(this, state)
            deltaSymbol.setColor(color)

            // Start or stop the glow based on whether the state is "active"
            if (DeltaStateColorMapper.isActiveState(state)) {
                deltaSymbol.startGlow()
            } else {
                deltaSymbol.stopGlow()
            }
        }
    }
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onResume() {
        super.onResume()
        showLoading(true)
        performBillingCheck()
        displayDeveloperMessage()
        updateDeltaVisuals(deltaStateManager.getCurrentState())
        updateUI()
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
            Logger.d("MainActivity", "Receivers were not registered")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::deltaStateManager.isInitialized && ::stateChangeListener.isInitialized) {
            deltaStateManager.removeStateChangeListener(stateChangeListener)
            deltaStateManager.stopMonitoring()
        }
    }

    private fun showDisclaimerDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Disclaimer")
            .setMessage("Delta is an experimental AI assistant and is still in development. It may not always be accurate or perform as expected. It does small task better. Your understanding is appreciated!")
            .setPositiveButton("Okay") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(
            ContextCompat.getColor(this, R.color.white)
        )
    }

    private fun showExamplesDialog() {
        val examples = arrayOf(
            "Open YouTube and play music",
            "Send a text message",
            "Set an alarm for 30 minutes",
            "Open camera app",
            "Check weather forecast",
            "Open calculator",
            "Surprise me"
        )
        
        val dialog = AlertDialog.Builder(this)
            .setTitle("Example Commands")
            .setItems(examples) { _, which ->
                val selectedExample = examples[which]
                if (selectedExample == "Surprise me"){
                    AgentService.start(this, "play never gonna give you up on youtube")

                }
                AgentService.start(this, selectedExample)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
        
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(
            ContextCompat.getColor(this, R.color.black)
        )
    }


    private fun updateTaskCounter() {
        lifecycleScope.launch {
            val isUserSub = freemiumManager.isUserSubscribed()
            if(isUserSub){
                tasksLeftTag.visibility = View.GONE
            }
            val tasksLeft = freemiumManager.getTasksRemaining()
            tasksLeftText.text = "$tasksLeft tasks left"
        }
    }

    // Subscription status UI removed in this fork. FreemiumManager is still
    // consulted elsewhere (task limits), but nothing is shown to the user and
    // there is no upsell, so this is a no-op.
    private fun updateBillingStatus() {
        Logger.d("MainActivity", "updateBillingStatus: no subscription UI in this build")
    }

    @SuppressLint("SetTextI18n")
    private fun updateUI() {
        val allPermissionsGranted = permissionManager.areAllPermissionsGranted()
        if (allPermissionsGranted) {
            tvPermissionStatus.text = "All required permissions are granted."
            tvPermissionStatus.visibility = View.GONE
            managePermissionsButton.visibility = View.GONE
            tvPermissionStatus.setTextColor(Color.parseColor("#4CAF50")) // Green
            permissionsTag.visibility = View.VISIBLE
        } else {
            tvPermissionStatus.text = "Some permissions are missing. Tap below to manage."
            tvPermissionStatus.setTextColor(Color.parseColor("#F44336")) // Red
            permissionsTag.visibility = View.GONE
        }
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

    private fun updateDefaultAssistantButtonVisibility() {
        val btn = findViewById<TextView>(R.id.btn_set_default_assistant)
        btn.visibility = if (isThisAppDefaultAssistant()) View.GONE else View.VISIBLE
    }

    private fun showLoading(show: Boolean) {
        loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun performBillingCheck() {
        lifecycleScope.launch {
            try {
                waitForBillingClientReady()
                queryAndHandlePurchases()
                updateTaskCounter()
                updateBillingStatus()
                
            } catch (e: Exception) {
                Logger.e("MainActivity", "Error during billing check", e)
                updateTaskCounter()
                updateBillingStatus()
            } finally {
                showLoading(false)
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
                Logger.w("MainActivity", "Billing client not ready after waiting")
            }
        }
    }

    private suspend fun queryAndHandlePurchases() {
        return withContext(Dispatchers.IO) {
            if (!MyApplication.isBillingClientReady.value) {
                Logger.e("MainActivity", "queryPurchases: BillingClient is not ready")
                return@withContext
            }

            try {
                val params = QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
                
                Logger.d("MainActivity", "queryPurchases: BillingClient is ready")

                val purchasesResult = MyApplication.billingClient.queryPurchasesAsync(params)
                val billingResult = purchasesResult.billingResult
                
                Logger.d("MainActivity", "queryPurchases: Got billing result: ${billingResult.responseCode}")

                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Logger.d("MainActivity", "queryPurchases: Found ${purchasesResult.purchasesList.size} purchases")
                    purchasesResult.purchasesList.forEach { purchase ->
                        when (purchase.purchaseState) {
                            Purchase.PurchaseState.PURCHASED -> {
                                Logger.d("MainActivity", "Found purchased item: ${purchase.products}")
                                handlePurchase(purchase)
                            }
                            Purchase.PurchaseState.PENDING -> {
                                Logger.d("MainActivity", "Purchase is pending")
                            }
                            else -> {
                                Logger.d("MainActivity", "Purchase is not in a valid state: ${purchase.purchaseState}")
                            }
                        }
                    }
                } else {
                    Logger.e("MainActivity", "Failed to query purchases: ${billingResult.debugMessage}")
                }
            } catch (e: Exception) {
                Logger.e("MainActivity", "Exception during purchase query", e)
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
                                Logger.d("MainActivity", "Purchase acknowledged: ${purchase.orderId}")
                                lifecycleScope.launch {
                                    updateUserToPro()
                                }
                            } else {
                                Logger.e("MainActivity", "Failed to acknowledge purchase: ${billingResult.debugMessage}")
                            }
                        }
                    } else {
                        updateUserToPro()
                    }
                }
            } catch (e: Exception) {
                Logger.e("MainActivity", "Error handling purchase", e)
            }
        }
    }


    private suspend fun updateUserToPro() {
        val uid = AuthGate.currentUid(this)
        if (uid == null) {
            Logger.e("MainActivity", "Cannot update user to pro: user is not authenticated.")
            withContext(Dispatchers.Main) {
            }
            return
        }

        withContext(Dispatchers.IO) {
            val db = Firebase.firestore
            try {
                val userDocRef = db.collection("users").document(uid)
                userDocRef.update("plan", "pro").await()
                Logger.d("MainActivity", "Successfully updated user $uid to 'pro' plan.")
                withContext(Dispatchers.Main) {
                }

            } catch (e: Exception) {
                Logger.e("MainActivity", "Error updating user to pro", e)
                withContext(Dispatchers.Main) {
                }
            }
        }
    }

    private fun displayDeveloperMessage() {
        //lifecycleScope.launch {
            try {
                // Check if message has been shown more than once
                val sharedPrefs = getSharedPreferences("developer_message_prefs", Context.MODE_PRIVATE)
                val displayCount = sharedPrefs.getInt("developer_message_count", 0)
                
                if (displayCount >= 1) {
                    Logger.d("MainActivity", "Developer message already shown $displayCount times, skipping display")
                    return
                }

                val remoteConfig = Firebase.remoteConfig

                // Fetch and activate the latest Remote Config values
                remoteConfig.fetchAndActivate()
                    .addOnCompleteListener(this) { task ->
                        if (task.isSuccessful) {
                            val updated = task.result
                            Log.d("MainActivity", "Remote Config params updated: $updated")

                            // Get the message from the activated config
                            val message = remoteConfig.getString("developerMessage")

                            if (message.isNotEmpty()) {
                                // Your existing dialog logic
                                val dialog = AlertDialog.Builder(this@MainActivity)
                                    .setTitle("Message from Developer")
                                    .setMessage(message)
                                    .setPositiveButton("OK") { dialogInterface, _ ->
                                        dialogInterface.dismiss()
                                        val editor = sharedPrefs.edit()
                                        editor.putInt("developer_message_count", displayCount + 1)
                                        editor.apply()
                                    }
                                    .show()
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(
                                    ContextCompat.getColor(this@MainActivity, R.color.black)
                                )
                                Log.d("MainActivity", "Developer message displayed from Remote Config.")
                            } else {
                                Log.d("MainActivity", "No developer message found in Remote Config.")
                            }
                        } else {
                            Log.e("MainActivity", "Failed to fetch Remote Config.", task.exception)
                        }
                    }
                
//                val db = Firebase.firestore
//                val docRef = db.collection("settings").document("freemium")
//
//                docRef.get().addOnSuccessListener { document ->
//                    if (document != null && document.exists()) {
//                        val message = document.getString("developerMessage")
//                        if (!message.isNullOrEmpty()) {
//                            val dialog = AlertDialog.Builder(this@MainActivity)
//                                .setTitle("Message from Developer")
//                                .setMessage(message)
//                                .setPositiveButton("OK") { dialogInterface, _ ->
//                                    dialogInterface.dismiss()
//                                    // Increment the display count after user dismisses
//                                    val editor = sharedPrefs.edit()
//                                    editor.putInt("developer_message_count", displayCount + 1)
//                                    editor.apply()
//                                    Logger.d("MainActivity", "Developer message display count updated to ${displayCount + 1}")
//                                }
//                                .show()
//                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(
//                                ContextCompat.getColor(this@MainActivity, R.color.black)
//                            )
//                            Logger.d("MainActivity", "Developer message displayed in dialog")
//                        } else {
//                            Logger.d("MainActivity", "Developer message is empty")
//                        }
//                    } else {
//                        Logger.d("MainActivity", "Developer message document does not exist")
//                    }
//                }.addOnFailureListener { exception ->
//                    Logger.e("MainActivity", "Error fetching developer message", exception)
//                }
            } catch (e: Exception) {
                Logger.e("MainActivity", "Exception in displayDeveloperMessage", e)
            }
        //}
    }

    /**
     * Update the status text based on the current DeltaState
     */
    fun updateStatusText(state: DeltaState) {
        runOnUiThread {
            try {
                val statusText = DeltaStateColorMapper.getStatusText(state)
                statusTextView.text = statusText
                Logger.d("MainActivity", "Status text updated to: $statusText for state: ${state.name}")
            } catch (e: Exception) {
                Logger.e("MainActivity", "Error updating status text", e)
                statusTextView.text = "Ready" // Fallback to default
            }
        }
    }

    /**
     * Update the status text with custom text (overrides state-based text)
     */
    fun updateStatusText(customText: String) {
        runOnUiThread {
            try {
                statusTextView.text = customText
                Logger.d("MainActivity", "Status text updated to custom text: $customText")
            } catch (e: Exception) {
                Logger.e("MainActivity", "Error updating status text with custom text", e)
                statusTextView.text = "Ready" // Fallback to default
            }
        }
    }

}