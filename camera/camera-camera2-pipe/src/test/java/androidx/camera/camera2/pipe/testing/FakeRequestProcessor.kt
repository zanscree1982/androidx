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

package androidx.camera.camera2.pipe.testing

import android.view.Surface
import androidx.camera.camera2.pipe.Request
import androidx.camera.camera2.pipe.StreamId
import androidx.camera.camera2.pipe.impl.GraphState3A
import androidx.camera.camera2.pipe.impl.RequestProcessorFactory
import androidx.camera.camera2.pipe.impl.RequestProcessor
import androidx.camera.camera2.pipe.impl.TokenLock
import androidx.camera.camera2.pipe.impl.TokenLockImpl
import androidx.camera.camera2.pipe.wrapper.CameraCaptureSessionWrapper
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout

/**
 * Fake implementation of a [RequestProcessor] for tests.
 */
internal class FakeRequestProcessor(private val graphState3A: GraphState3A) :
    RequestProcessor, RequestProcessorFactory {
    private val eventChannel = Channel<Event>(Channel.UNLIMITED)

    val requestQueue: MutableList<FakeRequest> = mutableListOf()
    var repeatingRequest: FakeRequest? = null

    var rejectRequests = false
    var abortInvoked = false
        private set
    var closeInvoked = false
        private set
    var stopInvoked = false
        private set

    var captureSession: CameraCaptureSessionWrapper? = null
    var surfaceMap: Map<StreamId, Surface>? = null

    private val tokenLock = TokenLockImpl(1)
    private var token: TokenLock.Token? = null

    data class FakeRequest(
        val burst: List<Request>,
        val defaultParameters: Map<*, Any>,
        val internalParameters: Map<*, Any>,
        val requiredParameters: Map<*, Any>,
        val parameters: Map<*, Any>,
        val requireStreams: Boolean = false
    )

    override fun create(
        session: CameraCaptureSessionWrapper,
        surfaceMap: Map<StreamId, Surface>
    ): RequestProcessor {
        captureSession = session
        this.surfaceMap = surfaceMap
        return this
    }

    override fun submit(
        request: Request,
        defaultParameters: Map<*, Any>,
        requiredParameters: Map<*, Any>
    ): Boolean {
        val fakeRequest =
            createFakeRequest(listOf(request), defaultParameters, requiredParameters)

        if (rejectRequests || closeInvoked) {
            check(eventChannel.offer(Event(request = fakeRequest, rejected = true)))
            return false
        }

        requestQueue.add(fakeRequest)
        check(eventChannel.offer(Event(request = fakeRequest, submit = true)))

        return true
    }

    override fun submit(
        requests: List<Request>,
        defaultParameters: Map<*, Any>,
        requiredParameters: Map<*, Any>
    ): Boolean {
        val fakeRequest =
            createFakeRequest(requests, defaultParameters, requiredParameters)
        if (rejectRequests || closeInvoked) {
            check(eventChannel.offer(Event(request = fakeRequest, rejected = true)))
            return false
        }

        requestQueue.add(fakeRequest)
        check(eventChannel.offer(Event(request = fakeRequest, submit = true)))

        return true
    }

    override fun setRepeating(
        request: Request,
        defaultParameters: Map<*, Any>,
        requiredParameters: Map<*, Any>
    ): Boolean {
        val fakeRequest =
            createFakeRequest(listOf(request), defaultParameters, requiredParameters)
        if (rejectRequests || closeInvoked) {
            check(eventChannel.offer(Event(request = fakeRequest, rejected = true)))
            return false
        }

        repeatingRequest = fakeRequest
        check(eventChannel.offer(Event(request = fakeRequest, setRepeating = true)))
        return true
    }

    override fun abortCaptures() {
        abortInvoked = true
        check(eventChannel.offer(Event(abort = true)))
    }

    override fun stopRepeating() {
        stopInvoked = true
        check(eventChannel.offer(Event(stop = true)))
    }

    override fun close() {
        closeInvoked = true
        check(eventChannel.offer(Event(close = true)))
    }

    /**
     * Get the next event from queue with an option to specify a timeout for tests.
     */
    suspend fun nextEvent(timeMillis: Long = 500): Event = withTimeout(timeMillis) {
        eventChannel.receive()
    }

    private fun createFakeRequest(
        burst: List<Request>,
        defaultParameters: Map<*, Any>,
        requiredParameters: Map<*, Any>
    ): FakeRequest {
        val internalParameters = graphState3A.readState()
        val parameterMap = mutableMapOf<Any, Any>()
        for ((k, v) in defaultParameters) {
            if (k != null) {
                parameterMap[k] = v
            }
        }
        parameterMap.putAll(internalParameters)
        for ((k, v) in requiredParameters) {
            if (k != null) {
                parameterMap[k] = v
            }
        }
        return FakeRequest(
            burst,
            defaultParameters,
            internalParameters,
            requiredParameters,
            parameterMap
        )
    }
}

internal data class Event(
    val request: FakeRequestProcessor.FakeRequest? = null,
    val rejected: Boolean = false,
    val abort: Boolean = false,
    val close: Boolean = false,
    val stop: Boolean = false,
    val submit: Boolean = false,
    val setRepeating: Boolean = false
)