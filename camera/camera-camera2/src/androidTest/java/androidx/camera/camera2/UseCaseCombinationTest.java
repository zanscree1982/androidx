/*
 * Copyright (C) 2019 The Android Open Source Project
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

package androidx.camera.camera2;

import static androidx.camera.testing.SurfaceTextureProvider.createSurfaceTextureProvider;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.CameraX;
import androidx.camera.core.CameraXConfig;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.Preview;
import androidx.camera.core.impl.utils.executor.CameraXExecutors;
import androidx.camera.testing.CameraUtil;
import androidx.camera.testing.GLUtil;
import androidx.camera.testing.SurfaceTextureProvider;
import androidx.camera.testing.fakes.FakeLifecycleOwner;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;

/**
 * Contains tests for {@link androidx.camera.core.CameraX} which varies use case combinations to
 * run.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public final class UseCaseCombinationTest {
    private static final CameraSelector DEFAULT_SELECTOR =
            new CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build();
    private final MutableLiveData<Long> mAnalysisResult = new MutableLiveData<>();
    @Rule
    public GrantPermissionRule mRuntimePermissionRule = GrantPermissionRule.grant(
            Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);
    private final Instrumentation mInstrumentation = InstrumentationRegistry.getInstrumentation();
    private Semaphore mSemaphore;
    private FakeLifecycleOwner mLifecycle;
    private ImageCapture mImageCapture;
    private ImageAnalysis mImageAnalysis;
    private Preview mPreview;
    private ImageAnalysis.Analyzer mImageAnalyzer;

    private Observer<Long> createCountIncrementingObserver() {
        return new Observer<Long>() {
            @Override
            public void onChanged(Long value) {
                mSemaphore.release();
            }
        };
    }

    @Before
    public void setUp() {
        assumeTrue(CameraUtil.deviceHasCamera());

        Context context = ApplicationProvider.getApplicationContext();
        CameraXConfig config = Camera2Config.defaultConfig();

        CameraX.initialize(context, config);

        mLifecycle = new FakeLifecycleOwner();

        mSemaphore = new Semaphore(0);
    }

    @After
    public void tearDown() throws InterruptedException, ExecutionException {
        if (CameraX.isInitialized()) {
            mInstrumentation.runOnMainSync(CameraX::unbindAll);
        }
        CameraX.shutdown().get();
    }

    /**
     * Test Combination: Preview + ImageCapture
     */
    @Test
    public void previewCombinesImageCapture() throws InterruptedException {
        initPreview();
        initImageCapture();
        mInstrumentation.runOnMainSync(() -> {
            mPreview.setPreviewSurfaceProvider(createSurfaceTextureProvider(
                    new SurfaceTextureProvider.SurfaceTextureCallback() {
                        boolean mIsSurfaceTextureReleased = false;
                        Object mIsSurfaceTextureReleasedLock = new Object();
                        @Override
                        public void onSurfaceTextureReady(@NonNull SurfaceTexture surfaceTexture,
                                @NonNull Size resolution) {
                            surfaceTexture.attachToGLContext(GLUtil.getTexIdFromGLContext());
                            surfaceTexture.setOnFrameAvailableListener(
                                    surfaceTexture1 -> {
                                        synchronized (mIsSurfaceTextureReleasedLock) {
                                            if (!mIsSurfaceTextureReleased) {
                                                surfaceTexture.updateTexImage();
                                            }
                                        }
                                        mSemaphore.release();
                                    });
                        }

                        @Override
                        public void onSafeToRelease(@NonNull SurfaceTexture surfaceTexture) {
                            synchronized (mIsSurfaceTextureReleasedLock) {
                                mIsSurfaceTextureReleased = true;
                                surfaceTexture.release();
                            }
                        }
                    }));
            CameraX.bindToLifecycle(mLifecycle, DEFAULT_SELECTOR, mPreview, mImageCapture);
            mLifecycle.startAndResume();
        });

        // Wait for the frame available update.
        mSemaphore.acquire(10);

        assertThat(mLifecycle.getObserverCount()).isEqualTo(2);
        assertThat(CameraX.isBound(mPreview)).isTrue();
        assertThat(CameraX.isBound(mImageCapture)).isTrue();
    }

    /**
     * Test Combination: Preview + ImageAnalysis
     */
    @Test
    public void previewCombinesImageAnalysis() throws InterruptedException {
        initImageAnalysis();
        initPreview();

        mInstrumentation.runOnMainSync(new Runnable() {
            @Override
            public void run() {
                CameraX.bindToLifecycle(mLifecycle, DEFAULT_SELECTOR, mPreview, mImageAnalysis);
                mImageAnalysis.setAnalyzer(CameraXExecutors.mainThreadExecutor(), mImageAnalyzer);
                mAnalysisResult.observe(mLifecycle,
                        createCountIncrementingObserver());
                mLifecycle.startAndResume();
            }
        });

        // Wait for 10 frames to be analyzed.
        mSemaphore.acquire(10);

        assertThat(CameraX.isBound(mPreview)).isTrue();
        assertThat(CameraX.isBound(mImageAnalysis)).isTrue();
    }

    /**
     * Test Combination: Preview + ImageAnalysis + ImageCapture
     */
    @Test
    public void previewCombinesImageAnalysisAndImageCapture() throws InterruptedException {
        initPreview();
        initImageAnalysis();
        initImageCapture();

        mInstrumentation.runOnMainSync(new Runnable() {
            @Override
            public void run() {
                CameraX.bindToLifecycle(mLifecycle, DEFAULT_SELECTOR, mPreview, mImageAnalysis,
                        mImageCapture);
                mImageAnalysis.setAnalyzer(CameraXExecutors.mainThreadExecutor(), mImageAnalyzer);
                mAnalysisResult.observe(mLifecycle,
                        createCountIncrementingObserver());
                mLifecycle.startAndResume();
            }
        });

        // Wait for 10 frames to be analyzed.
        mSemaphore.acquire(10);

        assertThat(mLifecycle.getObserverCount()).isEqualTo(3);
        assertThat(CameraX.isBound(mPreview)).isTrue();
        assertThat(CameraX.isBound(mImageAnalysis)).isTrue();
        assertThat(CameraX.isBound(mImageCapture)).isTrue();
    }

    private void initImageAnalysis() {
        mImageAnalyzer = (image) -> {
            mAnalysisResult.postValue(image.getImageInfo().getTimestamp());
            image.close();
        };
        mImageAnalysis = new ImageAnalysis.Builder()
                .setTargetName("ImageAnalysis")
                .build();
    }

    private void initImageCapture() {
        mImageCapture = new ImageCapture.Builder().build();
    }

    private void initPreview() {
        mPreview = new Preview.Builder().setTargetName("Preview").build();
    }
}
