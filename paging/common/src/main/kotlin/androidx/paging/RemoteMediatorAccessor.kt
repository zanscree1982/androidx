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

package androidx.paging

import androidx.paging.AccessorState.BlockState.COMPLETED
import androidx.paging.AccessorState.BlockState.REQUIRES_REFRESH
import androidx.paging.AccessorState.BlockState.UNBLOCKED
import androidx.paging.RemoteMediator.MediatorResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Interface provided to the snapshot to trigger load events.
 */
internal interface RemoteMediatorConnection<Key : Any, Value : Any> {
    fun requestLoad(
        loadType: LoadType,
        pagingState: PagingState<Key, Value>
    )

    fun retryFailed(pagingState: PagingState<Key, Value>)
}

@OptIn(ExperimentalPagingApi::class, ExperimentalCoroutinesApi::class)
internal interface RemoteMediatorAccessor<Key : Any, Value : Any> :
    RemoteMediatorConnection<Key, Value> {
    val state: StateFlow<LoadStates>

    suspend fun initialize(): RemoteMediator.InitializeAction
}

@Suppress("FunctionName")
@OptIn(ExperimentalPagingApi::class)
internal fun <Key : Any, Value : Any> RemoteMediatorAccessor(
    scope: CoroutineScope,
    delegate: RemoteMediator<Key, Value>
): RemoteMediatorAccessor<Key, Value> = RemoteMediatorAccessImpl(scope, delegate)

/**
 * Simple wrapper around the local state of accessor to ensure we don't concurrently change it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private class AccessorStateHolder<Key : Any, Value : Any> {
    private val lock = ReentrantLock()
    private val _loadStates = MutableStateFlow(LoadStates.IDLE)
    val loadStates
        get(): StateFlow<LoadStates> = _loadStates
    private val internalState = AccessorState<Key, Value>()
    fun <R> use(block: (AccessorState<Key, Value>) -> R): R {
        return lock.withLock {
            block(internalState).also {
                _loadStates.value = internalState.computeLoadStates()
            }
        }
    }
}

/**
 * The internal state of the accessor.
 *
 * It holds all pending requests, errors and whether certain types should be blocked (e.g. when
 * append prepend needs refresh).
 *
 * It does not directly hold the LoadStates. Instead, LoadStates is computed from the previous
 * information after each edit to keep them consistent.
 */
private class AccessorState<Key : Any, Value : Any>() {
    // TODO this can be a bit flag instead
    private val blockStates = Array<BlockState>(LoadType.values().size) {
        UNBLOCKED
    }

    // keep these as error states to avoid recreating them all the time
    private val errors = Array<LoadState.Error?>(LoadType.values().size) {
        null
    }
    private val pendingRequests = ArrayDeque<PendingRequest<Key, Value>>()

    fun computeLoadStates(): LoadStates {
        return LoadStates(
            refresh = computeLoadTypeState(LoadType.REFRESH),
            append = computeLoadTypeState(LoadType.APPEND),
            prepend = computeLoadTypeState(LoadType.PREPEND)
        )
    }

    private fun computeLoadTypeState(loadType: LoadType): LoadState {
        val hasPending = pendingRequests.any {
            it.loadType == loadType
        }
        if (hasPending) {
            return LoadState.Loading
        }
        errors[loadType.ordinal]?.let {
            return it
        }
        // now there are 3 cases here:
        // a) it might be completed & blocked -> Blocked
        // b) it might be blocked due to refresh being required first -> Incomplete
        // c) it might have never run -> Incomplete
        return when (blockStates[loadType.ordinal]) {
            COMPLETED -> LoadState.NotLoading.Complete
            REQUIRES_REFRESH -> LoadState.NotLoading.Incomplete
            UNBLOCKED -> LoadState.NotLoading.Incomplete
        }
    }

    /**
     * return true IF a new item is added and fetchers should be launched.
     */
    fun add(
        loadType: LoadType,
        pagingState: PagingState<Key, Value>
    ): Boolean {
        val existing = pendingRequests.firstOrNull {
            it.loadType == loadType
        }
        if (existing != null) {
            existing.pagingState = pagingState
            return false
        }
        val blockState = blockStates[loadType.ordinal]
        // Ignore block state for REFRESH as it is only sent in cases where we want to clear all
        // AccessorState, but we cannot simply generate a new one for an existing PageFetcher as
        // we need to cancel in-flight requests and prevent races between clearing state and
        // triggering remote REFRESH by clearing state as part of handling the load request.
        if (blockState != UNBLOCKED && loadType != LoadType.REFRESH) {
            return false
        }
        if (loadType == LoadType.REFRESH) {
            // for refresh, we ignore error states. see: b/173438474
            setError(LoadType.REFRESH, null)
        }
        return if (errors[loadType.ordinal] == null) {
            pendingRequests.add(PendingRequest(loadType, pagingState))
        } else {
            false
        }
    }

    /**
     * Can be used to block - unblock certain request types based on the mediator state.
     *
     * Note that a load type can still be blocked if it last returned an error.
     */
    fun setBlockState(loadType: LoadType, state: BlockState) {
        blockStates[loadType.ordinal] = state
    }

    fun getPendingRefresh() = pendingRequests.firstOrNull {
        it.loadType == LoadType.REFRESH
    }?.pagingState

    fun getPendingBoundary() = pendingRequests.firstOrNull {
        it.loadType != LoadType.REFRESH
    }?.let {
        // make a copy
        it.loadType to it.pagingState
    }

    fun clearPendingRequests() {
        pendingRequests.clear()
    }

    fun clearPendingRequest(loadType: LoadType) {
        pendingRequests.removeAll {
            it.loadType == loadType
        }
    }

    fun clearErrors() {
        for (i in errors.indices) {
            errors[i] = null
        }
    }

    fun setError(loadType: LoadType, errorState: LoadState.Error?) {
        errors[loadType.ordinal] = errorState
    }

    class PendingRequest<Key : Any, Value : Any>(
        val loadType: LoadType,
        var pagingState: PagingState<Key, Value>
    )

    enum class BlockState {
        UNBLOCKED,
        COMPLETED,
        REQUIRES_REFRESH
    }
}

@OptIn(ExperimentalPagingApi::class, ExperimentalCoroutinesApi::class)
private class RemoteMediatorAccessImpl<Key : Any, Value : Any>(
    private val scope: CoroutineScope,
    private val remoteMediator: RemoteMediator<Key, Value>
) : RemoteMediatorAccessor<Key, Value> {
    override val state: StateFlow<LoadStates>
        get() = accessorState.loadStates

    // all internal state is kept in accessorState to avoid concurrent access
    private val accessorState = AccessorStateHolder<Key, Value>()

    // an isolation runner is used to ensure no concurrent requests are made to the remote mediator.
    // it also handles cancelling lower priority calls with higher priority calls.
    private val isolationRunner = SingleRunner(cancelPreviousInEqualPriority = false)

    override fun requestLoad(loadType: LoadType, pagingState: PagingState<Key, Value>) {
        val newRequest = accessorState.use {
            it.add(loadType, pagingState)
        }
        if (newRequest) {
            when (loadType) {
                LoadType.REFRESH -> launchRefresh()
                else -> launchBoundary()
            }
        }
    }

    private fun launchRefresh() {
        scope.launch {
            var launchAppendPrepend = false
            isolationRunner.runInIsolation(
                priority = PRIORITY_REFRESH
            ) {
                val pendingPagingState = accessorState.use {
                    it.getPendingRefresh()
                }
                pendingPagingState?.let {
                    val loadResult = remoteMediator.load(LoadType.REFRESH, pendingPagingState)
                    launchAppendPrepend = when (loadResult) {
                        is MediatorResult.Success -> {
                            // clean append prepend as they are not valid anymore
                            accessorState.use {
                                it.clearPendingRequests()

                                // we can accept new append prepend requests
                                val blockState = when {
                                    loadResult.endOfPaginationReached -> COMPLETED
                                    else -> UNBLOCKED
                                }
                                if (loadResult.endOfPaginationReached) {
                                    it.setBlockState(LoadType.REFRESH, COMPLETED)
                                }
                                it.setBlockState(LoadType.APPEND, blockState)
                                it.setBlockState(LoadType.PREPEND, blockState)

                                // clean their errors
                                it.setError(LoadType.APPEND, null)
                                it.setError(LoadType.PREPEND, null)
                            }
                            // do not launch append prepend
                            false
                        }
                        is MediatorResult.Error -> {
                            // if refresh failed, don't change append/prepend states so that if
                            // refresh is not required, they can run.
                            accessorState.use {
                                // only clear refresh. we can use append prepend
                                it.clearPendingRequest(LoadType.REFRESH)
                                it.setError(LoadType.REFRESH, LoadState.Error(loadResult.throwable))
                                // if there is a pending boundary, trigger its launch
                                // if they were blocked, there won't be any requests
                                it.getPendingBoundary() != null
                            }
                        }
                    }
                }
            }
            // launch this after we leave the restricted scope otherwise append / prepend won't
            // make it since they have a lower priority
            if (launchAppendPrepend) {
                launchBoundary()
            }
        }
    }

    private fun launchBoundary() {
        scope.launch {
            isolationRunner.runInIsolation(
                priority = PRIORITY_APPEND_PREPEND
            ) {
                while (true) {
                    val (loadType, pendingPagingState) = accessorState.use {
                        it.getPendingBoundary()
                    } ?: break
                    when (val loadResult = remoteMediator.load(loadType, pendingPagingState)) {
                        is MediatorResult.Success -> {
                            accessorState.use {
                                it.clearPendingRequest(loadType)
                                if (loadResult.endOfPaginationReached) {
                                    it.setBlockState(loadType, COMPLETED)
                                }
                            }
                        }
                        is MediatorResult.Error -> {
                            accessorState.use {
                                it.clearPendingRequest(loadType)
                                it.setError(loadType, LoadState.Error(loadResult.throwable))
                            }
                        }
                    }
                }
            }
        }
    }

    override fun retryFailed(pagingState: PagingState<Key, Value>) {
        val toBeStarted = mutableListOf<LoadType>()
        accessorState.use { accessorState ->
            val loadStates = accessorState.computeLoadStates()
            val willTriggerRefresh = loadStates.refresh is LoadState.Error

            accessorState.clearErrors()
            if (willTriggerRefresh) {
                toBeStarted.add(LoadType.REFRESH)
                accessorState.setBlockState(LoadType.REFRESH, UNBLOCKED)
            }
            if (loadStates.append is LoadState.Error) {
                if (!willTriggerRefresh) {
                    toBeStarted.add(LoadType.APPEND)
                }
                accessorState.clearPendingRequest(LoadType.APPEND)
            }
            if (loadStates.prepend is LoadState.Error) {
                if (!willTriggerRefresh) {
                    toBeStarted.add(LoadType.PREPEND)
                }
                accessorState.clearPendingRequest(LoadType.PREPEND)
            }
        }

        toBeStarted.forEach {
            requestLoad(it, pagingState)
        }
    }

    override suspend fun initialize(): RemoteMediator.InitializeAction {
        return remoteMediator.initialize().also { action ->
            if (action == RemoteMediator.InitializeAction.LAUNCH_INITIAL_REFRESH) {
                accessorState.use {
                    it.setBlockState(LoadType.APPEND, REQUIRES_REFRESH)
                    it.setBlockState(LoadType.PREPEND, REQUIRES_REFRESH)
                }
            }
        }
    }

    companion object {
        private const val PRIORITY_REFRESH = 2
        private const val PRIORITY_APPEND_PREPEND = 1
    }
}
