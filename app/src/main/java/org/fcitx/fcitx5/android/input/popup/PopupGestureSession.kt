/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.KeyView

/**
 * Routes popup slide gestures after long-press: sliding within the popup changes
 * focus; sliding onto another key dismisses the current popup and starts a fresh
 * press/long-press on that key.
 */
class PopupGestureSession(
    private val originKey: KeyView,
    private val host: Host
) {
    interface Host {
        val lifecycleScope: CoroutineScope
        fun popupOnKeyPress(): Boolean
        fun isInsideVisiblePopup(viewId: Int, x: Float, y: Float): Boolean
        fun isFocusOutOfRange(viewId: Int, x: Float, y: Float): Boolean
        fun findKeyAtGestureCoords(originKey: KeyView, x: Float, y: Float): KeyView?
        fun toKeyLocalCoords(key: KeyView, originKey: KeyView, x: Float, y: Float): Pair<Float, Float>
        fun keyDef(key: KeyView): KeyDef?
        fun setKeyPressed(key: KeyView, pressed: Boolean)
        fun onPopupAction(action: PopupAction)
        fun onKeyAction(action: KeyAction)
        fun onPopupChangeFocus(viewId: Int, x: Float, y: Float): Boolean
        fun onPopupTrigger(viewId: Int): Boolean
        fun hasPopupContainer(viewId: Int): Boolean
        fun dismissPopup(viewId: Int)
        fun dismissPopupContainerOnly(viewId: Int)
    }

    var activeKey: KeyView = originKey
        private set

    private var longPressJob: Job? = null
    private var repeatJob: Job? = null
    private var repeatStarted = false
    private var behaviorLongPressTriggered = false
    private var fingerOverKey = true

    /**
     * Whether the finger has entered the visible popup grid since the current popup
     * was shown. Distinguishes sliding up into the grid from leaving after entry.
     */
    private var enteredVisiblePopup = false

    fun handleMove(originKey: KeyView, x: Float, y: Float): Boolean {
        val active = activeKey
        val (lx, ly) = host.toKeyLocalCoords(active, originKey, x, y)

        if (host.hasPopupContainer(active.id)) {
            if (host.isInsideVisiblePopup(active.id, lx, ly)) {
                enteredVisiblePopup = true
                fingerOverKey = true
                return host.onPopupChangeFocus(active.id, lx, ly)
            }
            if (!enteredVisiblePopup) {
                val target = host.findKeyAtGestureCoords(originKey, x, y)
                if (target != null && target !== active) {
                    handoff(target)
                    return true
                }
                // Popup is above the trigger key; tolerate moves before entering the grid.
                if (host.isFocusOutOfRange(active.id, lx, ly)) {
                    host.onPopupChangeFocus(active.id, lx, ly)
                }
                return true
            }
            val target = host.findKeyAtGestureCoords(originKey, x, y)
            if (target !== active) {
                if (target != null) {
                    handoff(target)
                } else {
                    if (fingerOverKey) {
                        fingerOverKey = false
                        pauseOutsideKeys()
                    }
                }
            } else {
                host.dismissPopupContainerOnly(active.id)
                restartKeyPhase(active)
            }
            return true
        }

        val target = host.findKeyAtGestureCoords(originKey, x, y)

        if (target == null) {
            if (fingerOverKey) {
                fingerOverKey = false
                pauseOutsideKeys()
            }
            return true
        }

        if (!fingerOverKey) {
            fingerOverKey = true
            if (target !== active) {
                handoff(target)
            } else {
                restartKeyPhase(active)
            }
            return true
        }

        if (target !== active) {
            handoff(target)
            return true
        }
        return true
    }

    fun handleUp(originKey: KeyView, x: Float, y: Float): Boolean {
        val active = activeKey
        val (lx, ly) = host.toKeyLocalCoords(active, originKey, x, y)

        if (host.hasPopupContainer(active.id) &&
            host.isInsideVisiblePopup(active.id, lx, ly)
        ) {
            enteredVisiblePopup = true
        }

        if (host.hasPopupContainer(active.id) && !enteredVisiblePopup) {
            val target = host.findKeyAtGestureCoords(originKey, x, y)
            if (target != null && target !== active) {
                handoff(target)
            } else if (!host.isFocusOutOfRange(active.id, lx, ly)) {
                // In-place long press or slide still approaching the grid: do not run leave logic.
                fingerOverKey = true
                host.onPopupChangeFocus(active.id, lx, ly)
            } else {
                fingerOverKey = false
            }
        } else {
            handleMove(originKey, x, y)
        }

        val shouldCommit = fingerOverKey
        val hadRepeat = repeatStarted
        val hadBehaviorLongPress = behaviorLongPressTriggered
        cancelTimers()

        if (!shouldCommit) {
            host.dismissPopup(activeKey.id)
            releaseAllPressed()
            return true
        }

        val commitKey = activeKey
        val (commitLx, commitLy) = host.toKeyLocalCoords(commitKey, originKey, x, y)

        if (host.hasPopupContainer(commitKey.id)) {
            host.onPopupChangeFocus(commitKey.id, commitLx, commitLy)
            host.onPopupTrigger(commitKey.id)
        } else if (!hadRepeat && !hadBehaviorLongPress) {
            pressAction(commitKey)?.let(host::onKeyAction)
        }
        host.dismissPopup(commitKey.id)

        releaseAllPressed()
        return true
    }

    fun handleCancel() {
        abort()
    }

    fun abort() {
        cancelTimers()
        host.dismissPopup(activeKey.id)
        releaseAllPressed()
    }

    fun clear() {
        cancelTimers()
    }

    private fun pauseOutsideKeys() {
        cancelTimers()
        if (host.hasPopupContainer(activeKey.id)) {
            host.dismissPopupContainerOnly(activeKey.id)
        }
        host.dismissPopup(activeKey.id)
        host.setKeyPressed(activeKey, false)
    }

    private fun handoff(key: KeyView) {
        enteredVisiblePopup = false
        cancelTimers()
        if (activeKey !== key) {
            host.dismissPopup(activeKey.id)
            host.setKeyPressed(activeKey, false)
        } else {
            host.dismissPopupContainerOnly(activeKey.id)
        }
        activeKey = key
        fingerOverKey = true
        restartKeyPhase(key)
    }

    private fun restartKeyPhase(key: KeyView) {
        enteredVisiblePopup = false
        key.updateBounds()
        host.setKeyPressed(key, true)
        val def = host.keyDef(key) ?: return
        var scheduledPopupLongPress = false
        def.popup?.forEach { popup ->
            when (popup) {
                is KeyDef.Popup.Preview -> {
                    if (host.popupOnKeyPress()) {
                        host.onPopupAction(
                            PopupAction.PreviewAction(key.id, popup.content, key.bounds)
                        )
                    }
                }
                is KeyDef.Popup.AltPreview -> {
                    if (host.popupOnKeyPress()) {
                        host.onPopupAction(
                            PopupAction.PreviewAction(key.id, popup.content, key.bounds)
                        )
                    }
                }
                is KeyDef.Popup.Keyboard -> {
                    if (host.popupOnKeyPress()) {
                        val label = previewLabel(def)
                        if (label.isNotEmpty()) {
                            host.onPopupAction(
                                PopupAction.PreviewAction(key.id, label, key.bounds)
                            )
                        }
                    }
                    scheduleShowKeyboard(key, popup)
                    scheduledPopupLongPress = true
                }
                is KeyDef.Popup.Menu -> {
                    scheduleShowMenu(key, popup)
                    scheduledPopupLongPress = true
                }
            }
        }
        if (!scheduledPopupLongPress) {
            def.behaviors.forEach { behavior ->
                when (behavior) {
                    is KeyDef.Behavior.Repeat -> scheduleRepeat(behavior.action)
                    is KeyDef.Behavior.LongPress -> scheduleBehaviorLongPress(behavior.action)
                    else -> {}
                }
            }
        }
    }

    private fun scheduleShowKeyboard(key: KeyView, keyboard: KeyDef.Popup.Keyboard) {
        longPressJob = host.lifecycleScope.launch {
            delay(CustomGestureView.longPressDelay.toLong())
            key.updateBounds()
            host.onPopupAction(PopupAction.ShowKeyboardAction(key.id, keyboard, key.bounds))
        }
    }

    private fun scheduleShowMenu(key: KeyView, menu: KeyDef.Popup.Menu) {
        longPressJob = host.lifecycleScope.launch {
            delay(CustomGestureView.longPressDelay.toLong())
            key.updateBounds()
            host.onPopupAction(PopupAction.ShowMenuAction(key.id, menu, key.bounds))
        }
    }

    private fun scheduleRepeat(action: KeyAction) {
        repeatJob = host.lifecycleScope.launch {
            delay(CustomGestureView.longPressDelay.toLong())
            repeatStarted = true
            var lastTriggerTime: Long
            while (isActive) {
                lastTriggerTime = SystemClock.uptimeMillis()
                host.onKeyAction(action)
                val t = lastTriggerTime + CustomGestureView.RepeatInterval -
                    SystemClock.uptimeMillis()
                if (t > 0) delay(t)
            }
        }
    }

    private fun scheduleBehaviorLongPress(action: KeyAction) {
        longPressJob = host.lifecycleScope.launch {
            delay(CustomGestureView.longPressDelay.toLong())
            behaviorLongPressTriggered = true
            host.onKeyAction(action)
        }
    }

    private fun cancelTimers() {
        longPressJob?.cancel()
        longPressJob = null
        repeatJob?.cancel()
        repeatJob = null
        repeatStarted = false
        behaviorLongPressTriggered = false
    }

    private fun releaseAllPressed() {
        host.setKeyPressed(activeKey, false)
        if (activeKey !== originKey) {
            host.setKeyPressed(originKey, false)
        }
    }

    private fun pressAction(key: KeyView): KeyAction? {
        val def = host.keyDef(key) ?: return null
        return def.behaviors.filterIsInstance<KeyDef.Behavior.Press>().firstOrNull()?.action
    }

    private fun previewLabel(def: KeyDef): String {
        return when (val appearance = def.appearance) {
            is KeyDef.Appearance.Text -> appearance.displayText
            is KeyDef.Appearance.AltText -> appearance.displayText
            is KeyDef.Appearance.ImageText -> appearance.displayText
            else -> ""
        }
    }
}
