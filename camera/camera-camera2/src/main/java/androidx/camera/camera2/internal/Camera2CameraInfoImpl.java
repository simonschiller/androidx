/*
 * Copyright 2019 The Android Open Source Project
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

package androidx.camera.camera2.internal;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.util.Pair;
import android.view.Surface;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.camera.camera2.internal.compat.CameraCharacteristicsCompat;
import androidx.camera.camera2.internal.compat.quirk.CameraQuirks;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalExposureCompensation;
import androidx.camera.core.ExposureState;
import androidx.camera.core.Logger;
import androidx.camera.core.ZoomState;
import androidx.camera.core.impl.CamcorderProfileProvider;
import androidx.camera.core.impl.CameraCaptureCallback;
import androidx.camera.core.impl.CameraInfoInternal;
import androidx.camera.core.impl.ImageOutputConfig.RotationValue;
import androidx.camera.core.impl.Quirks;
import androidx.camera.core.impl.utils.CameraOrientationUtil;
import androidx.core.util.Preconditions;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.Observer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Implementation of the {@link CameraInfoInternal} interface that exposes parameters through
 * camera2.
 *
 * <p>Construction consists of two stages. The constructor creates a implementation without a
 * {@link Camera2CameraControlImpl} and will return default values for camera control related
 * states like zoom/exposure/torch. After {@link #linkWithCameraControl} is called,
 * zoom/exposure/torch API will reflect the states in the {@link Camera2CameraControlImpl}. Any
 * CameraCaptureCallbacks added before this link will also be added
 * to the {@link Camera2CameraControlImpl}.
 */
@OptIn(markerClass = ExperimentalCamera2Interop.class)
public final class Camera2CameraInfoImpl implements CameraInfoInternal {

    private static final String TAG = "Camera2CameraInfo";
    private final String mCameraId;
    private final CameraCharacteristicsCompat mCameraCharacteristicsCompat;
    private final Camera2CameraInfo mCamera2CameraInfo;

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    @Nullable
    private Camera2CameraControlImpl mCamera2CameraControlImpl;
    @GuardedBy("mLock")
    @Nullable
    private RedirectableLiveData<Integer> mRedirectTorchStateLiveData = null;
    @GuardedBy("mLock")
    @Nullable
    private RedirectableLiveData<ZoomState> mRedirectZoomStateLiveData = null;
    @GuardedBy("mLock")
    @Nullable
    private List<Pair<CameraCaptureCallback, Executor>> mCameraCaptureCallbacks = null;

    @NonNull
    private final Quirks mCameraQuirks;
    @NonNull
    private final CamcorderProfileProvider mCamera2CamcorderProfileProvider;

    /**
     * Constructs an instance. Before {@link #linkWithCameraControl(Camera2CameraControlImpl)} is
     * called, camera control related API (torch/exposure/zoom) will return default values.
     */
    Camera2CameraInfoImpl(@NonNull String cameraId,
            @NonNull CameraCharacteristicsCompat cameraCharacteristicsCompat) {
        mCameraId = Preconditions.checkNotNull(cameraId);
        mCameraCharacteristicsCompat = cameraCharacteristicsCompat;
        mCamera2CameraInfo = new Camera2CameraInfo(this);
        mCameraQuirks = CameraQuirks.get(cameraId, cameraCharacteristicsCompat);
        mCamera2CamcorderProfileProvider = new Camera2CamcorderProfileProvider(cameraId,
                cameraCharacteristicsCompat);
    }

    /**
     * Links with a {@link Camera2CameraControlImpl}. After the link, zoom/torch/exposure
     * operations of CameraControl will modify the states in this Camera2CameraInfoImpl.
     * Also, any CameraCaptureCallbacks added before this link will be added to the
     * {@link Camera2CameraControlImpl}.
     */
    void linkWithCameraControl(@NonNull Camera2CameraControlImpl camera2CameraControlImpl) {
        synchronized (mLock) {
            mCamera2CameraControlImpl = camera2CameraControlImpl;

            if (mRedirectZoomStateLiveData != null) {
                mRedirectZoomStateLiveData.redirectTo(
                        mCamera2CameraControlImpl.getZoomControl().getZoomState());
            }

            if (mRedirectTorchStateLiveData != null) {
                mRedirectTorchStateLiveData.redirectTo(
                        mCamera2CameraControlImpl.getTorchControl().getTorchState());
            }

            if (mCameraCaptureCallbacks != null) {
                for (Pair<CameraCaptureCallback, Executor> pair :
                        mCameraCaptureCallbacks) {
                    mCamera2CameraControlImpl.addSessionCameraCaptureCallback(pair.second,
                            pair.first);
                }
                mCameraCaptureCallbacks = null;
            }
        }
        logDeviceInfo();
    }

    @NonNull
    @Override
    public String getCameraId() {
        return mCameraId;
    }

    @NonNull
    public CameraCharacteristicsCompat getCameraCharacteristicsCompat() {
        return mCameraCharacteristicsCompat;
    }

    @Nullable
    @Override
    public Integer getLensFacing() {
        Integer lensFacing = mCameraCharacteristicsCompat.get(CameraCharacteristics.LENS_FACING);
        Preconditions.checkNotNull(lensFacing);
        switch (lensFacing) {
            case CameraCharacteristics.LENS_FACING_FRONT:
                return CameraSelector.LENS_FACING_FRONT;
            case CameraCharacteristics.LENS_FACING_BACK:
                return CameraSelector.LENS_FACING_BACK;
            default:
                return null;
        }
    }

    @Override
    public int getSensorRotationDegrees(@RotationValue int relativeRotation) {
        Integer sensorOrientation = getSensorOrientation();
        int relativeRotationDegrees =
                CameraOrientationUtil.surfaceRotationToDegrees(relativeRotation);
        // Currently this assumes that a back-facing camera is always opposite to the screen.
        // This may not be the case for all devices, so in the future we may need to handle that
        // scenario.
        final Integer lensFacing = getLensFacing();
        boolean isOppositeFacingScreen =
                (lensFacing != null && CameraSelector.LENS_FACING_BACK == lensFacing);
        return CameraOrientationUtil.getRelativeImageRotation(
                relativeRotationDegrees,
                sensorOrientation,
                isOppositeFacingScreen);
    }

    int getSensorOrientation() {
        Integer sensorOrientation =
                mCameraCharacteristicsCompat.get(CameraCharacteristics.SENSOR_ORIENTATION);
        Preconditions.checkNotNull(sensorOrientation);
        return sensorOrientation;
    }

    int getSupportedHardwareLevel() {
        Integer deviceLevel =
                mCameraCharacteristicsCompat.get(
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        Preconditions.checkNotNull(deviceLevel);
        return deviceLevel;
    }

    @Override
    public int getSensorRotationDegrees() {
        return getSensorRotationDegrees(Surface.ROTATION_0);
    }

    private void logDeviceInfo() {
        // Extend by adding logging here as needed.
        logDeviceLevel();
    }

    private void logDeviceLevel() {
        String levelString;

        int deviceLevel = getSupportedHardwareLevel();
        switch (deviceLevel) {
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY:
                levelString = "INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY";
                break;
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL:
                levelString = "INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL";
                break;
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED:
                levelString = "INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED";
                break;
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL:
                levelString = "INFO_SUPPORTED_HARDWARE_LEVEL_FULL";
                break;
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3:
                levelString = "INFO_SUPPORTED_HARDWARE_LEVEL_3";
                break;
            default:
                levelString = "Unknown value: " + deviceLevel;
                break;
        }
        Logger.i(TAG, "Device Level: " + levelString);
    }

    @Override
    public boolean hasFlashUnit() {
        Boolean hasFlashUnit = mCameraCharacteristicsCompat.get(
                CameraCharacteristics.FLASH_INFO_AVAILABLE);
        Preconditions.checkNotNull(hasFlashUnit);
        return hasFlashUnit;
    }

    @NonNull
    @Override
    public LiveData<Integer> getTorchState() {
        synchronized (mLock) {
            if (mCamera2CameraControlImpl == null) {
                if (mRedirectTorchStateLiveData == null) {
                    mRedirectTorchStateLiveData =
                            new RedirectableLiveData<>(TorchControl.DEFAULT_TORCH_STATE);
                }
                return mRedirectTorchStateLiveData;
            }

            // if RedirectableLiveData exists,  use it directly.
            if (mRedirectTorchStateLiveData != null) {
                return mRedirectTorchStateLiveData;
            }

            return mCamera2CameraControlImpl.getTorchControl().getTorchState();
        }
    }

    @NonNull
    @Override
    public LiveData<ZoomState> getZoomState() {
        synchronized (mLock) {
            if (mCamera2CameraControlImpl == null) {
                if (mRedirectZoomStateLiveData == null) {
                    mRedirectZoomStateLiveData = new RedirectableLiveData<>(
                            ZoomControl.getDefaultZoomState(mCameraCharacteristicsCompat));
                }
                return mRedirectZoomStateLiveData;
            }

            // if RedirectableLiveData exists,  use it directly.
            if (mRedirectZoomStateLiveData != null) {
                return mRedirectZoomStateLiveData;
            }

            return mCamera2CameraControlImpl.getZoomControl().getZoomState();
        }
    }

    @NonNull
    @Override
    @ExperimentalExposureCompensation
    public ExposureState getExposureState() {
        synchronized (mLock) {
            if (mCamera2CameraControlImpl == null) {
                return ExposureControl.getDefaultExposureState(mCameraCharacteristicsCompat);
            }
            return mCamera2CameraControlImpl.getExposureControl().getExposureState();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>When the CameraX configuration is {@link androidx.camera.camera2.Camera2Config}, the
     * return value depends on whether the device is legacy
     * ({@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL} {@code ==
     * }{@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY}).
     *
     * @return {@link #IMPLEMENTATION_TYPE_CAMERA2_LEGACY} if the device is legacy, otherwise
     * {@link #IMPLEMENTATION_TYPE_CAMERA2}.
     */
    @NonNull
    @Override
    public String getImplementationType() {
        final int hardwareLevel = getSupportedHardwareLevel();
        return hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
                ? IMPLEMENTATION_TYPE_CAMERA2_LEGACY : IMPLEMENTATION_TYPE_CAMERA2;
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    public CamcorderProfileProvider getCamcorderProfileProvider() {
        return mCamera2CamcorderProfileProvider;
    }

    @Override
    public void addSessionCaptureCallback(@NonNull Executor executor,
            @NonNull CameraCaptureCallback callback) {
        synchronized (mLock) {
            if (mCamera2CameraControlImpl == null) {
                if (mCameraCaptureCallbacks == null) {
                    mCameraCaptureCallbacks = new ArrayList<>();
                }
                mCameraCaptureCallbacks.add(new Pair<>(callback, executor));
                return;
            }

            mCamera2CameraControlImpl.addSessionCameraCaptureCallback(executor, callback);
        }
    }

    @Override
    public void removeSessionCaptureCallback(@NonNull CameraCaptureCallback callback) {
        synchronized (mLock) {
            if (mCamera2CameraControlImpl == null) {
                if (mCameraCaptureCallbacks == null) {
                    return;
                }
                Iterator<Pair<CameraCaptureCallback, Executor>> it =
                        mCameraCaptureCallbacks.iterator();
                while (it.hasNext()) {
                    Pair<CameraCaptureCallback, Executor> pair = it.next();
                    if (pair.first == callback) {
                        it.remove();
                    }
                }
                return;
            }
            mCamera2CameraControlImpl.removeSessionCameraCaptureCallback(callback);
        }
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    public Quirks getCameraQuirks() {
        return mCameraQuirks;
    }

    /**
     * Gets the implementation of {@link Camera2CameraInfo}.
     */
    @NonNull
    public Camera2CameraInfo getCamera2CameraInfo() {
        return mCamera2CameraInfo;
    }

    /**
     * A {@link LiveData} which can be redirected to another {@link LiveData}. If no redirection
     * is set, initial value will be used.
     */
    static class RedirectableLiveData<T> extends MediatorLiveData<T> {
        private LiveData<T> mLiveDataSource;
        private T mInitialValue;

        RedirectableLiveData(T initialValue) {
            mInitialValue = initialValue;
        }

        void redirectTo(@NonNull LiveData<T> liveDataSource) {
            if (mLiveDataSource != null) {
                super.removeSource(mLiveDataSource);
            }
            mLiveDataSource = liveDataSource;
            super.addSource(liveDataSource, this::setValue);
        }

        @Override
        public <S> void addSource(@NonNull LiveData<S> source,
                @NonNull Observer<? super S> onChanged) {
            throw new UnsupportedOperationException();
        }

        // Overrides getValue() to reflect the correct value from source. This is required to ensure
        // getValue() is correct when observe() or observeForever() is not called.
        @Override
        public T getValue() {
            // Returns initial value if source is not set.
            return mLiveDataSource == null ? mInitialValue : mLiveDataSource.getValue();
        }
    }

}
