package com.blurr.voice.assistant

import android.content.Context
import android.os.Bundle
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * The three "owners" that Compose requires from whatever it is hosted in.
 *
 * Normally a ComponentActivity provides all of these. A VoiceInteractionSession is
 * a plain object with a Dialog window, so nothing provides them, and the failure
 * mode is not graceful degradation -- Compose throws while creating the
 * composition:
 *
 * - LifecycleOwner           -> the window recomposer and the ComposeView
 * - ViewModelStoreOwner      -> the recomposer scopes its frame clock to a
 *                               ViewModel, and `viewModel()` needs one to resolve
 * - SavedStateRegistryOwner  -> `rememberSaveable` / SaveableStateRegistry
 *
 * Attaching them to the ComposeView with the ViewTree setters is what
 * [AssistantSession] does; this class is the thing being attached.
 *
 * Verbatim from the MBTG assistant, which needed exactly this for exactly the same
 * reason: a Compose popup hosted in a voice interaction session.
 */
class SessionOwners(context: Context) :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner,
    HasDefaultViewModelProviderFactory {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()

    // create() takes the owner itself, so this initialiser has to hand over `this`.
    // That is safe: the controller only stores the reference and does not touch it
    // until performAttach()/performRestore(), which happen later in onCreate().
    private val savedStateController: SavedStateRegistryController =
        SavedStateRegistryController.create(this)

    private var created = false
    private var destroyed = false

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore get() = store

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    /**
     * Without this, `viewModel()` cannot build a factory and fails on first use. Our
     * view models are plain [androidx.lifecycle.ViewModel]s, so the stock factory is
     * exactly right; `defaultViewModelCreationExtras` keeps its interface default.
     */
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory =
        ViewModelProvider.NewInstanceFactory()

    /**
     * The lifecycle is still INITIALIZED here, which is what SavedStateRegistry
     * requires for performAttach(). performRestore(null) is what makes
     * `rememberSaveable` legal at all; a null bundle is passed deliberately because
     * there is nothing to restore across process death -- the conversation lives in
     * [AssistantSessionState] and starts empty.
     */
    fun onCreate() {
        if (created) return
        created = true
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    /** The window is up; let the recomposer start producing frames. */
    fun onStart() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    fun onStop() {
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }
    }

    /**
     * Order matters: save the registry while the lifecycle is still above
     * DESTROYED, then clear the ViewModelStore so session-scoped view models are
     * released rather than leaked for the lifetime of this long-lived process.
     */
    fun onDestroy() {
        if (destroyed) return
        destroyed = true
        if (created) {
            savedStateController.performSave(Bundle())
        }
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
