// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.debugger.core.stepping

import com.intellij.debugger.DebuggerManagerEx
import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.StepIntoMethodBreakpoint
import com.intellij.debugger.engine.SuspendContextImpl
import com.intellij.debugger.engine.events.SuspendContextCommandImpl
import com.intellij.debugger.settings.DebuggerSettings
import com.intellij.openapi.util.registry.Registry
import com.sun.jdi.Location
import com.sun.jdi.Method
import com.sun.jdi.event.LocatableEvent
import com.sun.jdi.request.EventRequest
import java.util.function.Function

object CoroutineBreakpointFacility {
    fun installCoroutineResumedBreakpoint(context: SuspendContextImpl, method: Method): Boolean {
        val debugProcess = context.debugProcess
        val project = debugProcess.project
        val suspendAll = context.suspendPolicy == EventRequest.SUSPEND_ALL

        val useCoroutineIdFiltering = Registry.`is`("debugger.filter.breakpoints.by.coroutine.id")

        val breakpoint = object : StepIntoMethodBreakpoint(method.declaringType().name(), method.name(), method.signature(), project) {
            override fun processLocatableEvent(action: SuspendContextCommandImpl, event: LocatableEvent): Boolean {
                val result = super.processLocatableEvent(action, event)
                if (result) {
                    debugProcess.requestsManager.deleteRequest(this) // breakpoint is hit - disable the request already
                }

                if (useCoroutineIdFiltering && suspendAll) {
                    // schedule stepping over switcher after suspend-all replacement happened
                    return result
                }

                // support same thread old-way stepping
                if (!result) return false

                val suspendContextImpl = action.suspendContext ?: return true
                return scheduleStepOverCommandForSuspendSwitch(suspendContextImpl)
            }

            override fun callbackAfterReplacementForAllThreadSuspendContext(): Function<SuspendContextImpl, Boolean>? {
                if (suspendAll) {
                    return Function<SuspendContextImpl, Boolean> { scheduleStepOverCommandForSuspendSwitch(it) }
                }
                return null
            }

            private fun scheduleStepOverCommandForSuspendSwitch(it: SuspendContextImpl): Boolean {
                DebuggerSteppingHelper.createStepOverCommandForSuspendSwitch(it).prepareSteppingRequestsAndHints(it)
                // false return value will resume the execution in the `DebugProcessEvents` and
                // the scheduled above steps will perform stepping through the coroutine switch until line location.
                return false
            }
        }

        breakpoint.suspendPolicy = when (context.suspendPolicy) {
            EventRequest.SUSPEND_ALL -> if (useCoroutineIdFiltering) DebuggerSettings.SUSPEND_THREAD else DebuggerSettings.SUSPEND_ALL
            EventRequest.SUSPEND_EVENT_THREAD -> DebuggerSettings.SUSPEND_THREAD
            EventRequest.SUSPEND_NONE -> DebuggerSettings.SUSPEND_NONE
            else -> DebuggerSettings.SUSPEND_ALL
        }
        if (!useCoroutineIdFiltering) {
            applyEmptyThreadFilter(debugProcess)
        }
        breakpoint.createRequest(debugProcess)
        debugProcess.setSteppingBreakpoint(breakpoint)

        return true
    }
}

fun SuspendContextImpl.getLocationCompat(): Location? {
    return this.location
}

private fun applyEmptyThreadFilter(debugProcess: DebugProcessImpl) {
    // TODO this is nasty. Find a way to apply an empty thread filter only to the newly created breakpoint
    // TODO consider moving this filtering to event loop?
    val breakpointManager = DebuggerManagerEx.getInstanceEx(debugProcess.project).breakpointManager
    breakpointManager.removeThreadFilter(debugProcess)
}
