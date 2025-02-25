/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.ui.test.junit4

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ExperimentalComposeApi
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.node.Owner
import androidx.compose.ui.platform.DesktopOwner
import androidx.compose.ui.platform.DesktopOwners
import androidx.compose.ui.platform.setContent
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ExperimentalTesting
import androidx.compose.ui.test.IdlingResource
import androidx.compose.ui.test.InternalTestingApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.TestOwner
import androidx.compose.ui.test.createTestContext
import androidx.compose.ui.text.input.EditOperation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import org.jetbrains.skija.Surface
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.LinkedList
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import javax.swing.SwingUtilities.invokeAndWait
import javax.swing.SwingUtilities.isEventDispatchThread

actual fun createComposeRule(): ComposeTestRule = DesktopComposeTestRule()

@OptIn(InternalTestingApi::class)
class DesktopComposeTestRule : ComposeTestRule {

    companion object {
        var current: DesktopComposeTestRule? = null
    }

    var owners: DesktopOwners? = null
    private var owner: DesktopOwner? = null

    @ExperimentalTesting
    override val clockTestRule: AnimationClockTestRule = DesktopAnimationClockTestRule()

    override val density: Density
        get() = TODO()

    override val displaySize: IntSize get() = IntSize(1024, 768)

    val executionQueue = LinkedList<() -> Unit>()

    private val testOwner = DesktopTestOwner(this)
    private val testContext = createTestContext(testOwner)

    override fun apply(base: Statement, description: Description?): Statement {
        current = this
        return object : Statement() {
            override fun evaluate() {
                base.evaluate()
                runExecutionQueue()
                runOnUiThread {
                    owner?.dispose()
                    owner = null
                }
            }
        }
    }

    private fun runExecutionQueue() {
        while (executionQueue.isNotEmpty()) {
            executionQueue.removeFirst()()
        }
    }

    @OptIn(ExperimentalComposeApi::class)
    private fun isIdle() =
        !Snapshot.current.hasPendingChanges() &&
            !Recomposer.current().hasInvalidations()

    override fun waitForIdle() {
        while (!isIdle()) {
            runExecutionQueue()
            Thread.sleep(10)
        }
    }

    @ExperimentalTesting
    override suspend fun awaitIdle() {
        while (!isIdle()) {
            runExecutionQueue()
            delay(10)
        }
    }

    override fun <T> runOnUiThread(action: () -> T): T {
        val task: FutureTask<T> = FutureTask(action)
        invokeAndWait(task)
        try {
            return task.get()
        } catch (e: ExecutionException) { // Expose the original exception
            throw e.cause!!
        }
    }

    override fun <T> runOnIdle(action: () -> T): T {
        // We are waiting for idle before and AFTER `action` to guarantee that changes introduced
        // in `action` are propagated to components. In Android's version, it's executed in the
        // Main thread which has similar effects. This code could be reconsidered after
        // stabilization of the new rendering/dispatching model
        waitForIdle()
        return action().also { waitForIdle() }
    }

    override fun registerIdlingResource(idlingResource: IdlingResource) {
        // TODO: implement
    }

    override fun unregisterIdlingResource(idlingResource: IdlingResource) {
        // TODO: implement
    }

    override fun setContent(composable: @Composable () -> Unit) {
        check(owner == null) {
            "Cannot call setContent twice per test!"
        }

        if (isEventDispatchThread()) {
            performSetContent(composable)
        } else {
            runOnUiThread {
                performSetContent(composable)
            }

            // Only wait for idleness if not on the UI thread. If we are on the UI thread, the
            // caller clearly wants to keep tight control over execution order, so don't go
            // executing future tasks on the main thread.
            waitForIdle()
        }
    }

    private fun performSetContent(composable: @Composable() () -> Unit) {
        val surface = Surface.makeRasterN32Premul(displaySize.width, displaySize.height)!!
        val canvas = surface.canvas
        val owners = DesktopOwners(invalidate = {}).also {
            owners = it
        }
        val owner = DesktopOwner(owners)
        owner.setContent(content = composable)
        owner.setSize(displaySize.width, displaySize.height)
        owner.measureAndLayout()
        owner.draw(canvas)
        this.owner = owner
    }

    override fun onNode(
        matcher: SemanticsMatcher,
        useUnmergedTree: Boolean
    ): SemanticsNodeInteraction {
        return SemanticsNodeInteraction(testContext, useUnmergedTree, matcher)
    }

    override fun onAllNodes(
        matcher: SemanticsMatcher,
        useUnmergedTree: Boolean
    ): SemanticsNodeInteractionCollection {
        return SemanticsNodeInteractionCollection(testContext, useUnmergedTree, matcher)
    }

    private class DesktopTestOwner(val rule: DesktopComposeTestRule) : TestOwner {
        override fun sendTextInputCommand(node: SemanticsNode, command: List<EditOperation>) {
            TODO()
        }

        override fun sendImeAction(node: SemanticsNode, actionSpecified: ImeAction) {
            TODO()
        }

        override fun <T> runOnUiThread(action: () -> T): T {
            return rule.runOnUiThread(action)
        }

        override fun getOwners(): Set<Owner> {
            return rule.owners!!.list
        }
    }
}