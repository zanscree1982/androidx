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

import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CaptureRequest
import android.util.ArrayMap
import android.view.Surface
import androidx.annotation.GuardedBy
import androidx.camera.camera2.pipe.CameraGraph
import androidx.camera.camera2.pipe.Metadata
import androidx.camera.camera2.pipe.Request
import androidx.camera.camera2.pipe.RequestMetadata
import androidx.camera.camera2.pipe.RequestNumber
import androidx.camera.camera2.pipe.RequestTemplate
import androidx.camera.camera2.pipe.StreamId
import androidx.camera.camera2.pipe.core.Log
import androidx.camera.camera2.pipe.wrapper.CameraCaptureSessionWrapper
import androidx.camera.camera2.pipe.wrapper.ObjectUnavailableException
import androidx.camera.camera2.pipe.writeParameters
import kotlinx.atomicfu.atomic
import java.util.Collections.singletonList
import java.util.Collections.singletonMap
import javax.inject.Inject

internal interface RequestProcessorFactory {
    fun create(
        session: CameraCaptureSessionWrapper,
        surfaceMap: Map<StreamId, Surface>
    ): RequestProcessor
}

internal class StandardRequestProcessorFactory @Inject constructor(
    private val threads: Threads,
    private val graphConfig: CameraGraph.Config,
    @ForCameraGraph private val graphListeners: ArrayList<Request.Listener>,
    private val graphState3A: GraphState3A
) : RequestProcessorFactory {
    override fun create(
        session: CameraCaptureSessionWrapper,
        surfaceMap: Map<StreamId, Surface>
    ): RequestProcessor =
        StandardRequestProcessor(
            session,
            threads,
            graphConfig,
            surfaceMap,
            graphListeners,
            graphState3A
        )
}

internal val requestProcessorDebugIds = atomic(0)
internal val requestSequenceDebugIds = atomic(0L)
internal val requestTags = atomic(0L)
internal fun nextRequestTag(): RequestNumber = RequestNumber(requestTags.incrementAndGet())

/**
 * This class is designed to synchronously handle interactions with the Camera CaptureSession.
 */
internal class StandardRequestProcessor(
    private val session: CameraCaptureSessionWrapper,
    private val threads: Threads,
    private val graphConfig: CameraGraph.Config,
    private val surfaceMap: Map<StreamId, Surface>,
    private val graphListeners: List<Request.Listener>,
    private val graphState3A: GraphState3A
) : RequestProcessor {

    @GuardedBy("inFlightRequests")
    private val inFlightRequests = mutableListOf<CaptureSequence>()
    private val debugId = requestProcessorDebugIds.incrementAndGet()
    private val closed = atomic(false)

    override fun submit(
        request: Request,
        defaultParameters: Map<*, Any>,
        requiredParameters: Map<*, Any>
    ): Boolean {
        return configureAndCapture(
            singletonList(request),
            defaultParameters,
            requiredParameters,
            requireStreams = false,
            isRepeating = false
        )
    }

    override fun submit(
        requests: List<Request>,
        defaultParameters: Map<*, Any>,
        requiredParameters: Map<*, Any>
    ): Boolean {
        return configureAndCapture(
            requests,
            defaultParameters,
            requiredParameters,
            requireStreams = false,
            isRepeating = false
        )
    }

    override fun setRepeating(
        request: Request,
        defaultParameters: Map<*, Any>,
        requiredParameters: Map<*, Any>
    ): Boolean {
        return configureAndCapture(
            singletonList(request),
            defaultParameters,
            requiredParameters,
            requireStreams = false,
            isRepeating = true
        )
    }

    override fun abortCaptures() {
        val requestsToAbort = synchronized(inFlightRequests) {
            val copy = inFlightRequests.toList()
            inFlightRequests.clear()
            copy
        }
        for (sequence in requestsToAbort) {
            sequence.invokeOnAborted()
        }
        session.abortCaptures()
    }

    override fun stopRepeating() {
        session.stopRepeating()
    }

    override fun close() {
        closed.compareAndSet(expect = false, update = true)
    }

    private fun configureAndCapture(
        requests: List<Request>,
        defaultParameters: Map<*, Any>,
        requiredParameters: Map<*, Any>,
        requireStreams: Boolean,
        isRepeating: Boolean
    ): Boolean {
        // Reject incoming requests if this instance has been stopped or closed.
        if (closed.value) {
            return false
        }

        val requestMap = ArrayMap<RequestNumber, RequestInfo>(requests.size)
        val captureRequests = ArrayList<CaptureRequest>(requests.size)

        val surfaceToStreamMap = ArrayMap<Surface, StreamId>()
        val streamToSurfaceMap = ArrayMap<StreamId, Surface>()

        for (request in requests) {
            val requestTemplate = request.template ?: graphConfig.defaultTemplate

            Log.debug { "Building CaptureRequest for $request" }

            // Check to see if there is at least one valid surface for each stream.
            var hasSurface = false
            for (stream in request.streams) {
                if (streamToSurfaceMap.contains(stream)) {
                    hasSurface = true
                    continue
                }

                val surface = surfaceMap[stream]
                if (surface != null) {
                    Log.debug { "  Binding $stream to $surface" }

                    // TODO(codelogic) There should be a more efficient way to do these lookups than
                    // having two maps.
                    surfaceToStreamMap[surface] = stream
                    streamToSurfaceMap[stream] = surface
                    hasSurface = true
                } else if (requireStreams) {
                    Log.info { "  Failed to bind surface to $stream" }
                    // If requireStreams is set we are required to map every stream to a valid
                    // Surface object for this request. If this condition is violated, then we
                    // return false because we cannot submit these request(s) until there is a valid
                    // StreamId -> Surface mapping for all streams.
                    return false
                }
            }

            // If there are no surfaces on a particular request, camera2 will now allow us to
            // submit it.
            if (!hasSurface) {
                return false
            }

            // Create the request builder. There is a risk this will throw an exception or return null
            // if the CameraDevice has been closed or disconnected. If this fails, indicate that the
            // request was not submitted.
            val requestBuilder: CaptureRequest.Builder
            try {
                requestBuilder = session.device.createCaptureRequest(requestTemplate)
            } catch (exception: ObjectUnavailableException) {
                return false
            }

            // Apply the output surfaces to the requestBuilder
            hasSurface = false
            for (stream in request.streams) {
                val surface = streamToSurfaceMap[stream]
                if (surface != null) {
                    requestBuilder.addTarget(surface)
                    hasSurface = true
                }
            }

            // Soundness check to make sure we add at least one surface. This should be guaranteed
            // because we are supposed to exit early and return false if we cannot map at least one
            // surface per request.
            check(hasSurface)

            // Apply default parameters to the request builder first.
            requestBuilder.writeParameters(defaultParameters)

            // Apply request parameters to the request builder.
            requestBuilder.writeParameters(request.parameters)

            // Apply the 3A parameters. This gives the users of camerapipe the ability to
            // still override the 3A parameters for complicated use cases.
            //
            // TODO(sushilnath@): Implement one of the two options. (1) Apply the 3A parameters
            // from internal 3A state machine at last and provide a flag in the Request object to
            // specify when the clients want to explicitly override some of the 3A parameters
            // directly. Add code to handle the flag. (2) Let clients override the 3A parameters
            // freely and when that happens intercept those parameters from the request and keep the
            // internal 3A state machine in sync.
            graphState3A.writeTo(requestBuilder)

            // Finally, write required parameters to the request builder. This will override any
            // value that has ben previously set.
            requestBuilder.writeParameters(requiredParameters)

            // The tag must be set for every request. We use it to lookup listeners for the
            // individual requests so that each request can specify individual listeners.
            val requestTag = nextRequestTag()
            requestBuilder.setTag(requestTag)

            // Create the camera2 captureRequest and add it to our list of requests.
            val captureRequest = requestBuilder.build()
            captureRequests.add(captureRequest)

            @Suppress("SyntheticAccessor")
            requestMap[requestTag] = RequestInfo(
                captureRequest,
                defaultParameters,
                requiredParameters,
                streamToSurfaceMap,
                requestTemplate,
                isRepeating,
                request,
                requestTag
            )
        }

        // Create the captureSequence listener
        @Suppress("SyntheticAccessor")
        val captureSequence = CaptureSequence(
            graphListeners,
            if (requests.size == 1) {
                singletonMap(requestMap.keyAt(0), requestMap.valueAt(0))
            } else {
                requestMap
            },
            captureRequests,
            surfaceToStreamMap,
            inFlightRequests,
            session.device.cameraId
        )

        // Non-repeating requests must always be aware of abort calls.
        if (!isRepeating) {
            synchronized(inFlightRequests) {
                inFlightRequests.add(captureSequence)
            }
        }

        var captured = false
        return try {
            Log.debug { "Submitting $captureSequence" }
            capture(captureRequests, captureSequence, isRepeating)
            captured = true
            Log.debug { "Submitted $captureSequence" }
            true
        } catch (closedException: ObjectUnavailableException) {
            false
        } catch (accessException: CameraAccessException) {
            false
        } finally {
            // If ANY unhandled exception occurs, don't throw, but make sure we remove it from the
            // list of in-flight requests.
            if (!captured && !isRepeating) {
                captureSequence.invokeOnAborted()
            }
        }
    }

    private fun capture(
        captureRequests: List<CaptureRequest>,
        captureSequence: CaptureSequence,
        isRepeating: Boolean
    ) {
        captureSequence.invokeOnRequestSequenceCreated()

        // NOTE: This is a funny synchronization call. The purpose is to avoid a rare but possible
        // situation where calling capture causes one of the callback methods to be invoked before
        // sequenceNumber has been set on the callback. Both this call and the synchronized
        // behavior on the CaptureSequence listener have been designed to minimize the number of
        // synchronized calls.
        synchronized(lock = captureSequence) {
            // TODO: Update these calls to use executors on newer versions of the OS
            val sequenceNumber: Int = if (captureRequests.size == 1) {
                if (isRepeating) {
                    session.setRepeatingRequest(
                        captureRequests[0],
                        captureSequence,
                        threads.camera2Handler
                    )
                } else {
                    session.capture(captureRequests[0], captureSequence, threads.camera2Handler)
                }
            } else {
                if (isRepeating) {
                    session.setRepeatingBurst(
                        captureRequests,
                        captureSequence,
                        threads.camera2Handler
                    )
                } else {
                    session.captureBurst(captureRequests, captureSequence, threads.camera2Handler)
                }
            }
            captureSequence.sequenceNumber = sequenceNumber
        }

        // Invoke callbacks without holding a lock.
        captureSequence.invokeOnRequestSequenceSubmitted()
    }

    override fun toString(): String {
        return "RequestProcessor-$debugId"
    }
}

/**
 * This class packages together information about a request that was submitted to the camera.
 */
@Suppress("SyntheticAccessor") // Using an inline class generates a synthetic constructor
internal class RequestInfo(
    private val captureRequest: CaptureRequest,
    private val defaultParameters: Map<*, Any>,
    private val requiredParameters: Map<*, Any>,
    override val streams: Map<StreamId, Surface>,
    override val template: RequestTemplate,
    override val repeating: Boolean,
    override val request: Request,
    override val requestNumber: RequestNumber
) : RequestMetadata {
    override fun <T> get(key: CaptureRequest.Key<T>): T? = captureRequest[key]
    override fun <T> getOrDefault(key: CaptureRequest.Key<T>, default: T): T =
        get(key) ?: default

    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: Metadata.Key<T>): T? =
        (requiredParameters[key] ?: request.extras[key] ?: defaultParameters[key]) as T?

    override fun <T> getOrDefault(key: Metadata.Key<T>, default: T): T = get(key) ?: default

    override fun unwrap(): CaptureRequest = captureRequest
}
