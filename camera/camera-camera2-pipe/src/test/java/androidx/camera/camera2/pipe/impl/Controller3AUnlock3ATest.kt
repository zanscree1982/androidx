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

package androidx.camera.camera2.pipe.impl

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.os.Build
import androidx.camera.camera2.pipe.FrameNumber
import androidx.camera.camera2.pipe.Request
import androidx.camera.camera2.pipe.RequestNumber
import androidx.camera.camera2.pipe.Status3A
import androidx.camera.camera2.pipe.StreamId
import androidx.camera.camera2.pipe.testing.CameraPipeRobolectricTestRunner
import androidx.camera.camera2.pipe.testing.FakeFrameMetadata
import androidx.camera.camera2.pipe.testing.FakeGraphProcessor
import androidx.camera.camera2.pipe.testing.FakeRequestMetadata
import androidx.camera.camera2.pipe.testing.FakeRequestProcessor
import com.google.common.truth.Truth
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(CameraPipeRobolectricTestRunner::class)
@Config(minSdk = Build.VERSION_CODES.LOLLIPOP)
internal class Controller3AUnlock3ATest {
    private val graphProcessor = FakeGraphProcessor()
    private val graphState3A = GraphState3A()
    private val requestProcessor = FakeRequestProcessor(graphState3A)
    private val listener3A = Listener3A()
    private val controller3A = Controller3A(graphProcessor, graphState3A, listener3A)

    @Test
    fun testUnlockAe(): Unit = runBlocking {
        initGraphProcessor()

        val unLock3AAsyncTask = GlobalScope.async {
            controller3A.unlock3A(ae = true)
        }

        // Launch a task to repeatedly invoke a given capture result.
        GlobalScope.launch {
            while (true) {
                listener3A.onRequestSequenceCreated(
                    FakeRequestMetadata(
                        requestNumber = RequestNumber(1)
                    )
                )
                listener3A.onPartialCaptureResult(
                    FakeRequestMetadata(requestNumber = RequestNumber(1)),
                    FrameNumber(101L),
                    FakeFrameMetadata(
                        frameNumber = FrameNumber(101L),
                        resultMetadata = mapOf(
                            CaptureResult.CONTROL_AE_STATE to
                                CaptureResult.CONTROL_AE_STATE_LOCKED
                        )
                    )
                )
                delay(FRAME_RATE_MS)
            }
        }

        val result = unLock3AAsyncTask.await()
        // Result of unlock3A call shouldn't be complete yet since the AE is locked.
        Truth.assertThat(result.isCompleted).isFalse()

        // There should be one request to lock AE.
        val request1 = requestProcessor.nextEvent().request
        Truth.assertThat(request1!!.parameters[CaptureRequest.CONTROL_AE_LOCK])
            .isEqualTo(false)

        GlobalScope.launch {
            listener3A.onRequestSequenceCreated(
                FakeRequestMetadata(
                    requestNumber = RequestNumber(1)
                )
            )
            listener3A.onPartialCaptureResult(
                FakeRequestMetadata(requestNumber = RequestNumber(1)),
                FrameNumber(101L),
                FakeFrameMetadata(
                    frameNumber = FrameNumber(101L),
                    resultMetadata = mapOf(
                        CaptureResult.CONTROL_AE_STATE to CaptureResult.CONTROL_AE_STATE_SEARCHING
                    )
                )
            )
        }

        val result3A = result.await()
        Truth.assertThat(result3A.frameNumber.value).isEqualTo(101L)
        Truth.assertThat(result3A.status).isEqualTo(Status3A.OK)
    }

    @Test
    fun testUnlockAf(): Unit = runBlocking {
        initGraphProcessor()

        val unLock3AAsyncTask = GlobalScope.async { controller3A.unlock3A(af = true) }

        // Launch a task to repeatedly invoke a given capture result.
        GlobalScope.launch {
            while (true) {
                listener3A.onRequestSequenceCreated(
                    FakeRequestMetadata(
                        requestNumber = RequestNumber(1)
                    )
                )
                listener3A.onPartialCaptureResult(
                    FakeRequestMetadata(requestNumber = RequestNumber(1)),
                    FrameNumber(101L),
                    FakeFrameMetadata(
                        frameNumber = FrameNumber(101L),
                        resultMetadata = mapOf(
                            CaptureResult.CONTROL_AF_STATE to
                                CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                        )
                    )
                )
                delay(FRAME_RATE_MS)
            }
        }

        val result = unLock3AAsyncTask.await()
        // Result of unlock3A call shouldn't be complete yet since the AF is locked.
        Truth.assertThat(result.isCompleted).isFalse()

        // There should be one request to unlock AF.
        val request1 = requestProcessor.nextEvent().request
        Truth.assertThat(request1!!.parameters[CaptureRequest.CONTROL_AF_TRIGGER])
            .isEqualTo(CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)

        GlobalScope.launch {
            listener3A.onRequestSequenceCreated(
                FakeRequestMetadata(
                    requestNumber = RequestNumber(1)
                )
            )
            listener3A.onPartialCaptureResult(
                FakeRequestMetadata(requestNumber = RequestNumber(1)),
                FrameNumber(101L),
                FakeFrameMetadata(
                    frameNumber = FrameNumber(101L),
                    resultMetadata = mapOf(
                        CaptureResult.CONTROL_AF_STATE to CaptureResult.CONTROL_AF_STATE_INACTIVE
                    )
                )
            )
        }

        val result3A = result.await()
        Truth.assertThat(result3A.frameNumber.value).isEqualTo(101L)
        Truth.assertThat(result3A.status).isEqualTo(Status3A.OK)
    }

    @Test
    fun testUnlockAwb(): Unit = runBlocking {
        initGraphProcessor()

        val unLock3AAsyncTask = GlobalScope.async {
            controller3A.unlock3A(awb = true)
        }

        // Launch a task to repeatedly invoke a given capture result.
        GlobalScope.launch {
            while (true) {
                listener3A.onRequestSequenceCreated(
                    FakeRequestMetadata(
                        requestNumber = RequestNumber(1)
                    )
                )
                listener3A.onPartialCaptureResult(
                    FakeRequestMetadata(requestNumber = RequestNumber(1)),
                    FrameNumber(101L),
                    FakeFrameMetadata(
                        frameNumber = FrameNumber(101L),
                        resultMetadata = mapOf(
                            CaptureResult.CONTROL_AWB_STATE to
                                CaptureResult.CONTROL_AWB_STATE_LOCKED
                        )
                    )
                )
                delay(FRAME_RATE_MS)
            }
        }

        val result = unLock3AAsyncTask.await()
        // Result of unlock3A call shouldn't be complete yet since the AWB is locked.
        Truth.assertThat(result.isCompleted).isFalse()

        // There should be one request to lock AWB.
        val request1 = requestProcessor.nextEvent().request
        Truth.assertThat(request1!!.parameters[CaptureRequest.CONTROL_AWB_LOCK])
            .isEqualTo(false)

        GlobalScope.launch {
            listener3A.onRequestSequenceCreated(
                FakeRequestMetadata(
                    requestNumber = RequestNumber(1)
                )
            )
            listener3A.onPartialCaptureResult(
                FakeRequestMetadata(requestNumber = RequestNumber(1)),
                FrameNumber(101L),
                FakeFrameMetadata(
                    frameNumber = FrameNumber(101L),
                    resultMetadata = mapOf(
                        CaptureResult.CONTROL_AWB_STATE to CaptureResult.CONTROL_AWB_STATE_SEARCHING
                    )
                )
            )
        }

        val result3A = result.await()
        Truth.assertThat(result3A.frameNumber.value).isEqualTo(101L)
        Truth.assertThat(result3A.status).isEqualTo(Status3A.OK)
    }

    @Test
    fun testUnlockAeAf(): Unit = runBlocking {
        initGraphProcessor()

        val unLock3AAsyncTask = GlobalScope.async { controller3A.unlock3A(ae = true, af = true) }

        // Launch a task to repeatedly invoke a given capture result.
        GlobalScope.launch {
            while (true) {
                listener3A.onRequestSequenceCreated(
                    FakeRequestMetadata(
                        requestNumber = RequestNumber(1)
                    )
                )
                listener3A.onPartialCaptureResult(
                    FakeRequestMetadata(requestNumber = RequestNumber(1)),
                    FrameNumber(101L),
                    FakeFrameMetadata(
                        frameNumber = FrameNumber(101L),
                        resultMetadata = mapOf(
                            CaptureResult.CONTROL_AE_STATE to CaptureResult.CONTROL_AE_STATE_LOCKED,
                            CaptureResult.CONTROL_AF_STATE to
                                CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                        )
                    )
                )
                delay(FRAME_RATE_MS)
            }
        }

        val result = unLock3AAsyncTask.await()
        // Result of unlock3A call shouldn't be complete yet since the AF is locked.
        Truth.assertThat(result.isCompleted).isFalse()

        // There should be one request to unlock AF.
        val request1 = requestProcessor.nextEvent().request
        Truth.assertThat(request1!!.parameters[CaptureRequest.CONTROL_AF_TRIGGER])
            .isEqualTo(CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
        // Then request to unlock AE.
        val request2 = requestProcessor.nextEvent().request
        Truth.assertThat(request2!!.parameters[CaptureRequest.CONTROL_AE_LOCK])
            .isEqualTo(false)

        GlobalScope.launch {
            listener3A.onRequestSequenceCreated(
                FakeRequestMetadata(
                    requestNumber = RequestNumber(1)
                )
            )
            listener3A.onPartialCaptureResult(
                FakeRequestMetadata(requestNumber = RequestNumber(1)),
                FrameNumber(101L),
                FakeFrameMetadata(
                    frameNumber = FrameNumber(101L),
                    resultMetadata = mapOf(
                        CaptureResult.CONTROL_AF_STATE to CaptureResult.CONTROL_AF_STATE_INACTIVE,
                        CaptureResult.CONTROL_AE_STATE to CaptureResult.CONTROL_AE_STATE_SEARCHING
                    )
                )
            )
        }

        val result3A = result.await()
        Truth.assertThat(result3A.frameNumber.value).isEqualTo(101L)
        Truth.assertThat(result3A.status).isEqualTo(Status3A.OK)
    }

    private fun initGraphProcessor() {
        graphProcessor.attach(requestProcessor)
        graphProcessor.setRepeating(Request(streams = listOf(StreamId(1))))
    }

    companion object {
        // The time duration in milliseconds between two frame results.
        private const val FRAME_RATE_MS = 33L
    }
}