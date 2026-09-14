package com.jambus.heji

/**
 * Coordinates the safe release and destruction of the editor WebView.
 *
 * Ensures that when an editor is being released (such as during Activity destruction or screen
 * transitions), any in-flight serialization callback is never cut off by premature destruction
 * or arbitrary timeouts. The editor is destroyed immediately if idle, or immediately upon
 * serialization completion if serialization is in-flight.
 */
class EditorReleaseCoordinator<T : Any> {
    private val serializing = mutableSetOf<T>()
    private val pendingDestroy = mutableSetOf<T>()

    val hasPendingDestructions: Boolean
        get() = pendingDestroy.isNotEmpty()

    val isSerializing: Boolean
        get() = serializing.isNotEmpty()

    fun isPendingDestroy(editor: T): Boolean = editor in pendingDestroy

    fun isSerializing(editor: T): Boolean = editor in serializing

    /**
     * Marks that an editor has started serialization via evaluateJavascript.
     */
    fun onSerializationStarted(editor: T) {
        serializing.add(editor)
    }

    /**
     * Called when the editor is requested to be released (e.g. Activity onDestroy or screen switch).
     *
     * @param editor The editor being released.
     * @param destroyImmediate Callback invoked if the editor is safe to destroy immediately.
     */
    fun release(editor: T, destroyImmediate: (T) -> Unit) {
        if (editor in serializing) {
            // Serialization is in-flight: do NOT destroy now, defer until serialization completes.
            pendingDestroy.add(editor)
        } else {
            destroyImmediate(editor)
        }
    }

    /**
     * Called when serialization completes (delivered payload or failed).
     *
     * @param editor The editor that finished serialization.
     * @param destroyDeferred Callback invoked if this editor was pending destruction.
     */
    fun onSerializationCompleted(editor: T, destroyDeferred: (T) -> Unit) {
        serializing.remove(editor)
        if (pendingDestroy.remove(editor)) {
            destroyDeferred(editor)
        }
    }
}
