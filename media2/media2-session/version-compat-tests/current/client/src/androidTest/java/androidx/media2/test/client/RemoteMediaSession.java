/*
 * Copyright 2018 The Android Open Source Project
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

package androidx.media2.test.client;

import static androidx.media2.test.common.CommonConstants.ACTION_MEDIA2_SESSION;
import static androidx.media2.test.common.CommonConstants.KEY_AUDIO_ATTRIBUTES;
import static androidx.media2.test.common.CommonConstants.KEY_BUFFERED_POSITION;
import static androidx.media2.test.common.CommonConstants.KEY_BUFFERING_STATE;
import static androidx.media2.test.common.CommonConstants.KEY_CURRENT_POSITION;
import static androidx.media2.test.common.CommonConstants.KEY_CURRENT_VOLUME;
import static androidx.media2.test.common.CommonConstants.KEY_MAX_VOLUME;
import static androidx.media2.test.common.CommonConstants.KEY_MEDIA_ITEM;
import static androidx.media2.test.common.CommonConstants.KEY_PLAYBACK_SPEED;
import static androidx.media2.test.common.CommonConstants.KEY_PLAYER_STATE;
import static androidx.media2.test.common.CommonConstants.KEY_PLAYLIST;
import static androidx.media2.test.common.CommonConstants.KEY_PLAYLIST_METADATA;
import static androidx.media2.test.common.CommonConstants.KEY_REPEAT_MODE;
import static androidx.media2.test.common.CommonConstants.KEY_SHUFFLE_MODE;
import static androidx.media2.test.common.CommonConstants.KEY_TRACK_INFO;
import static androidx.media2.test.common.CommonConstants.KEY_VIDEO_SIZE;
import static androidx.media2.test.common.CommonConstants.KEY_VOLUME_CONTROL_TYPE;
import static androidx.media2.test.common.CommonConstants.MEDIA2_SESSION_PROVIDER_SERVICE;
import static androidx.media2.test.common.TestUtils.PROVIDER_SERVICE_CONNECTION_TIMEOUT_MS;

import static junit.framework.TestCase.fail;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.RemoteException;
import android.support.v4.media.session.MediaSessionCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media.AudioAttributesCompat;
import androidx.media2.common.MediaItem;
import androidx.media2.common.MediaMetadata;
import androidx.media2.common.MediaParcelUtils;
import androidx.media2.common.ParcelImplListSlice;
import androidx.media2.common.SessionPlayer;
import androidx.media2.common.SubtitleData;
import androidx.media2.common.VideoSize;
import androidx.media2.session.MediaSession;
import androidx.media2.session.MediaSession.CommandButton;
import androidx.media2.session.RemoteSessionPlayer;
import androidx.media2.session.SessionCommand;
import androidx.media2.session.SessionCommandGroup;
import androidx.media2.session.SessionToken;
import androidx.media2.test.common.IRemoteMediaSession;
import androidx.media2.test.common.TestUtils;
import androidx.versionedparcelable.ParcelImpl;
import androidx.versionedparcelable.ParcelUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Represents remote {@link MediaSession} in the service app's MediaSessionProviderService.
 * Users can run {@link MediaSession} methods remotely with this object.
 */
public class RemoteMediaSession {
    private static final String TAG = "RemoteMediaSession";

    private final Context mContext;
    private final String mSessionId;
    private final Bundle mTokenExtras;

    private ServiceConnection mServiceConnection;
    private IRemoteMediaSession mBinder;
    private RemoteMockPlayer mRemotePlayer;
    private CountDownLatch mCountDownLatch;

    /**
     * Create a {@link MediaSession} in the service app.
     * Should NOT be called in main thread.
     */
    public RemoteMediaSession(@NonNull String sessionId, @NonNull Context context,
            @Nullable Bundle tokenExtras) {
        mSessionId = sessionId;
        mContext = context;
        mCountDownLatch = new CountDownLatch(1);
        mServiceConnection = new MyServiceConnection();
        mTokenExtras = tokenExtras;

        if (!connect()) {
            fail("Failed to connect to the MediaSessionProviderService.");
        }
        create();
    }

    public void cleanUp() {
        close();
        disconnect();
    }

    /**
     * Gets {@link RemoteMockPlayer} for interact with the remote MockPlayer.
     * Users can run MockPlayer methods remotely with this object.
     */
    public RemoteMockPlayer getMockPlayer() {
        return mRemotePlayer;
    }

    ////////////////////////////////////////////////////////////////////////////////
    // MediaSession methods
    ////////////////////////////////////////////////////////////////////////////////

    /**
     * Gets {@link SessionToken} from the service app.
     * Should be used after the creation of the session through {@link #create()}.
     *
     * @return A {@link SessionToken} object if succeeded, {@code null} if failed.
     */
    public SessionToken getToken() {
        SessionToken token = null;
        try {
            token = MediaParcelUtils.fromParcelable(mBinder.getToken(mSessionId));
        } catch (RemoteException ex) {
            Log.e(TAG, "Failed to get session token. sessionId=" + mSessionId);
        }
        return token;
    }

    /**
     * Gets {@link MediaSessionCompat.Token} from the service app.
     * Should be used after the creation of the session through {@link #create()}.
     *
     * @return A {@link SessionToken} object if succeeded, {@code null} if failed.
     */
    public MediaSessionCompat.Token getCompatToken() {
        MediaSessionCompat.Token token = null;
        try {
            Bundle bundle = mBinder.getCompatToken(mSessionId);
            if (bundle != null) {
                bundle.setClassLoader(MediaSession.class.getClassLoader());
            }
            token = MediaSessionCompat.Token.fromBundle(bundle);
        } catch (RemoteException ex) {
            Log.e(TAG, "Failed to get session compat token. sessionId=" + mSessionId);
        }
        return token;
    }

    public void updatePlayer(@NonNull Bundle config) {
        try {
            mBinder.updatePlayer(mSessionId, config);
        } catch (RemoteException ex) {
            Log.e(TAG, "Failed to call updatePlayerConnector()");
        }
    }

    public void broadcastCustomCommand(@NonNull SessionCommand command, @Nullable Bundle args) {
        try {
            mBinder.broadcastCustomCommand(mSessionId,
                    MediaParcelUtils.toParcelable(command), args);
        } catch (RemoteException ex) {
            Log.e(TAG, "Failed to call broadcastCustomCommand()");
        }
    }

    public void sendCustomCommand(@NonNull SessionCommand command, @Nullable Bundle args) {
        try {
            mBinder.sendCustomCommand(
                    mSessionId, null, MediaParcelUtils.toParcelable(command), args);
        } catch (RemoteException ex) {
            Log.e(TAG, "Failed to call sendCustomCommand2()");
        }
    }

    public void close() {
        try {
            mBinder.close(mSessionId);
        } catch (RemoteException ex) {
            Log.e(TAG, "Failed to call close()");
        }
    }

    public void setAllowedCommands(@NonNull SessionCommandGroup commands) {
        try {
            mBinder.setAllowedCommands(mSessionId, null,
                    MediaParcelUtils.toParcelable(commands));
        } catch (RemoteException ex) {
            Log.e(TAG, "Failed to call setAllowedCommands()");
        }
    }

    public void setCustomLayout(@NonNull List<CommandButton> layout) {
        try {
            List<ParcelImpl> parcelList = new ArrayList<>();
            for (CommandButton btn : layout) {
                parcelList.add(MediaParcelUtils.toParcelable(btn));
            }
            mBinder.setCustomLayout(mSessionId, null, parcelList);
        } catch (RemoteException ex) {
            Log.e(TAG, "Failed to call setCustomLayout()");
        }
    }

    ////////////////////////////////////////////////////////////////////////////////
    // RemoteMockPlayer methods
    ////////////////////////////////////////////////////////////////////////////////

    public class RemoteMockPlayer {

        public void setPlayerState(int state) {
            try {
                mBinder.setPlayerState(mSessionId, state);
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to call setCurrentPosition()");
            }
        }

        public void setCurrentPosition(long pos) {
            try {
                mBinder.setCurrentPosition(mSessionId, pos);
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to call setCurrentPosition()");
            }
        }

        public void setBufferedPosition(long pos) {
            try {
                mBinder.setBufferedPosition(mSessionId, pos);
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to call setBufferedPosition()");
            }
        }

        public void setDuration(long duration) {
            try {
                mBinder.setDuration(mSessionId, duration);
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to call setDuration()");
            }
        }

        public void setPlaybackSpeed(float speed) {
            try {
                mBinder.setPlaybackSpeed(mSessionId, speed);
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to call setPlaybackSpeed()");
            }
        }

        public void notifySeekCompleted(long pos) {
            try {
                mBinder.notifySeekCompleted(mSessionId, pos);
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to call notifySeekCompleted()");
            }
        }

        public void notifyBufferingStateChanged(int itemIndex, int buffState) {
            try {
                mBinder.notifyBufferingStateChanged(mSessionId, itemIndex, buffState);
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to call notifyBufferingStateChanged()");
            }
        }

        public void notifyPlayerStateChanged(int state) {
            try {
                mBinder.notifyPlayerStateChanged(mSessionId, state);
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to call notifyPlayerStateChanged()");
            }
        }

        public void notifyPlaybackSpeedChanged(float speed) {
            try {
                mBinder.notifyPlaybackSpeedChanged(mSessionId, speed);
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to call notifyPlaybackSpeedChanged()");
            }
        }

        public void notifyCurrentMediaItemChanged(int index) {
            try {
                mBinder.notifyCurrentMediaItemChanged(mSessionId, index);
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to call notifyCurrentMediaItemChanged()");
            }
        }

        public void notifyAudioAttributesChanged(AudioAttributesCompat attrs) {
            try {
                mBinder.notifyAudioAttributesChanged(
                        mSessionId, MediaParcelUtils.toParcelable(attrs));
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to call notifyAudioAttributesChanged()");
            }
        }

        public void setPlaylist(List<MediaItem> playlist) {
            try {
                mBinder.setPlaylist(
                        mSessionId, MediaTestUtils.convertToParcelImplList(playlist));
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to call setPlaylist()");
            }
        }

        public void setCurrentMediaItemMetadata(MediaMetadata metadata) {
            try {
                mBinder.setCurrentMediaItemMetadata(
                        mSessionId, MediaParcelUtils.toParcelable(metadata));
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to call setCurrentMediaItemMetadata()");
            }
        }

        /**
         * Service app will automatically create a playlist of size {@param size},
         * and sets the list to the player.
         *
         * Each item's media ID will be {@link TestUtils#getMediaIdInFakeList(int)}.
         */
        public void createAndSetFakePlaylist(int size) {
            try {
                mBinder.createAndSetFakePlaylist(mSessionId, size);
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to call createAndSetFakePlaylist()");
            }
        }

        public void setPlaylistWithFakeItem(List<MediaItem> playlist) {
            try {
                mBinder.setPlaylistWithFakeItem(
                        mSessionId, MediaTestUtils.convertToParcelImplList(playlist));
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to call setPlaylistWithFakeItem()");
            }
        }

        public void setPlaylistMetadata(MediaMetadata metadata) {
            try {
                mBinder.setPlaylistMetadata(mSessionId, MediaParcelUtils.toParcelable(metadata));
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to call setPlaylistMetadata()");
            }
        }

        public void setPlaylistMetadataWithLargeBitmaps(int count, int width, int height) {
            try {
                mBinder.setPlaylistMetadataWithLargeBitmaps(mSessionId, count, width, height);
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to call setPlaylistMetadataWithLargeBitmaps()");
            }
        }

        public void setRepeatMode(int repeatMode) {
            try {
                mBinder.setRepeatMode(mSessionId, repeatMode);
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to call setRepeatMode()");
            }
        }

        public void setShuffleMode(int shuffleMode) {
            try {
                mBinder.setShuffleMode(mSessionId, shuffleMode);
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to call setShuffleMode()");
            }
        }

        public void setCurrentMediaItem(int index) {
            try {
                mBinder.setCurrentMediaItem(mSessionId, index);
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to call setCurrentMediaItem()");
            }
        }

        public void notifyPlaylistChanged() {
            try {
                mBinder.notifyPlaylistChanged(mSessionId);
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to call notifyPlaylistChanged()");
            }
        }

        public void notifyPlaylistMetadataChanged() {
            try {
                mBinder.notifyPlaylistMetadataChanged(mSessionId);
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to call notifyPlaylistMetadataChanged()");
            }
        }

        public void notifyShuffleModeChanged() {
            try {
                mBinder.notifyShuffleModeChanged(mSessionId);
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to call notifyShuffleModeChanged()");
            }
        }

        public void notifyRepeatModeChanged() {
            try {
                mBinder.notifyRepeatModeChanged(mSessionId);
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to call notifyRepeatModeChanged()");
            }
        }

        public void notifyPlaybackCompleted() {
            try {
                mBinder.notifyPlaybackCompleted(mSessionId);
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to call notifyPlaybackCompleted()");
            }
        }

        public void notifyVideoSizeChanged(@NonNull VideoSize videoSize) {
            try {
                mBinder.notifyVideoSizeChanged(mSessionId,
                        MediaParcelUtils.toParcelable(videoSize));
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to call notifyVideoSizeChanged()");
            }
        }

        public boolean surfaceExists() throws RemoteException {
            return mBinder.surfaceExists(mSessionId);
        }

        public void notifyTracksChanged(List<SessionPlayer.TrackInfo> tracks) {
            try {
                mBinder.notifyTrackInfoChanged(mSessionId,
                        MediaParcelUtils.toParcelableList(tracks));
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to call notifyTrackInfoChanged()");
            }
        }

        public void notifyTrackSelected(SessionPlayer.TrackInfo trackInfo) {
            try {
                mBinder.notifyTrackSelected(mSessionId,
                        MediaParcelUtils.toParcelable(trackInfo));
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to call notifyTrackSelected()");
            }
        }

        public void notifyTrackDeselected(SessionPlayer.TrackInfo trackInfo) {
            try {
                mBinder.notifyTrackDeselected(mSessionId,
                        MediaParcelUtils.toParcelable(trackInfo));
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to call notifyTrackDeselected()");
            }
        }

        public void notifySubtitleData(@NonNull MediaItem item,
                @NonNull SessionPlayer.TrackInfo track, @NonNull SubtitleData data) {
            try {
                mBinder.notifySubtitleData(mSessionId,
                        MediaParcelUtils.toParcelable(item),
                        MediaParcelUtils.toParcelable(track),
                        MediaParcelUtils.toParcelable(data));
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to call notifySubtitleData");
            }
        }

        public void notifyVolumeChanged(int volume) throws RemoteException {
            mBinder.notifyVolumeChanged(mSessionId, volume);
        }
    }

    ////////////////////////////////////////////////////////////////////////////////
    // Non-public methods
    ////////////////////////////////////////////////////////////////////////////////

    /**
     * Connects to service app's MediaSessionProviderService.
     * Should NOT be called in main thread.
     *
     * @return true if connected successfully, false if failed to connect.
     */
    private boolean connect() {
        final Intent intent = new Intent(ACTION_MEDIA2_SESSION);
        intent.setComponent(MEDIA2_SESSION_PROVIDER_SERVICE);

        boolean bound = false;
        try {
            bound = mContext.bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
        } catch (Exception ex) {
            Log.e(TAG, "Failed binding to the MediaSessionProviderService of the service app");
        }

        if (bound) {
            try {
                mCountDownLatch.await(PROVIDER_SERVICE_CONNECTION_TIMEOUT_MS,
                        TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Log.e(TAG, "InterruptedException while waiting for onServiceConnected.", ex);
            }
        }
        return mBinder != null;
    }

    /**
     * Disconnects from service app's MediaSessionProviderService.
     */
    private void disconnect() {
        if (mServiceConnection != null) {
            mContext.unbindService(mServiceConnection);
        }
        mServiceConnection = null;
    }

    /**
     * Create a {@link MediaSession} in the service app.
     * Should be used after successful connection through {@link #connect}.
     */
    private void create() {
        try {
            mBinder.create(mSessionId, mTokenExtras);
            mRemotePlayer = new RemoteMockPlayer();
        } catch (RemoteException ex) {
            Log.e(TAG, "Failed to get session token. sessionId=" + mSessionId);
        }
    }

    class MyServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "Connected to service app's MediaSessionProviderService.");
            mBinder = IRemoteMediaSession.Stub.asInterface(service);
            mCountDownLatch.countDown();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "Disconnected from the service.");
        }
    }

    /**
     * Builder to build a {@link Bundle} which represents a configuration of {@link SessionPlayer}
     * in order to create a new mock player in the service app. The bundle can be passed to
     * {@link #updatePlayer(Bundle)}.
     */
    public static final class MockPlayerConfigBuilder {

        private final Bundle mBundle;

        public MockPlayerConfigBuilder() {
            mBundle = new Bundle();
        }

        public MockPlayerConfigBuilder setPlayerState(@SessionPlayer.PlayerState int state) {
            mBundle.putInt(KEY_PLAYER_STATE, state);
            return this;
        }

        public MockPlayerConfigBuilder setBufferingState(@SessionPlayer.BuffState int buffState) {
            mBundle.putInt(KEY_BUFFERING_STATE, buffState);
            return this;
        }

        public MockPlayerConfigBuilder setCurrentPosition(long pos) {
            mBundle.putLong(KEY_CURRENT_POSITION, pos);
            return this;
        }

        public MockPlayerConfigBuilder setBufferedPosition(long buffPos) {
            mBundle.putLong(KEY_BUFFERED_POSITION, buffPos);
            return this;
        }

        public MockPlayerConfigBuilder setPlaybackSpeed(float speed) {
            mBundle.putFloat(KEY_PLAYBACK_SPEED, speed);
            return this;
        }

        public MockPlayerConfigBuilder setAudioAttributes(
                @Nullable AudioAttributesCompat audioAttributes) {
            Parcelable parcelable = MediaParcelUtils.toParcelable(audioAttributes);
            mBundle.putParcelable(KEY_AUDIO_ATTRIBUTES, parcelable);
            return this;
        }

        public MockPlayerConfigBuilder setPlaylist(@NonNull List<MediaItem> playlist) {
            ParcelImplListSlice listSlice = new ParcelImplListSlice(
                    MediaTestUtils.convertToParcelImplList(playlist));
            mBundle.putParcelable(KEY_PLAYLIST, listSlice);
            return this;
        }

        public MockPlayerConfigBuilder setPlaylistMetadata(@Nullable MediaMetadata metadata) {
            ParcelUtils.putVersionedParcelable(mBundle, KEY_PLAYLIST_METADATA, metadata);
            return this;
        }

        public MockPlayerConfigBuilder setCurrentMediaItem(@Nullable MediaItem item) {
            Parcelable parcelable = MediaParcelUtils.toParcelable(item);
            mBundle.putParcelable(KEY_MEDIA_ITEM, parcelable);
            return this;
        }

        public MockPlayerConfigBuilder setVideoSize(@NonNull VideoSize videoSize) {
            Parcelable parcelable = MediaParcelUtils.toParcelable(videoSize);
            mBundle.putParcelable(KEY_VIDEO_SIZE, parcelable);
            return this;
        }

        public MockPlayerConfigBuilder setTrackInfo(@NonNull List<SessionPlayer.TrackInfo> tracks) {
            ParcelUtils.putVersionedParcelableList(mBundle, KEY_TRACK_INFO, tracks);
            return this;
        }

        public MockPlayerConfigBuilder setVolumeControlType(
                @RemoteSessionPlayer.VolumeControlType int volumeControlType) {
            mBundle.putInt(KEY_VOLUME_CONTROL_TYPE, volumeControlType);
            return this;
        }

        public MockPlayerConfigBuilder setMaxVolume(int maxVolume) {
            mBundle.putInt(KEY_MAX_VOLUME, maxVolume);
            return this;
        }

        public MockPlayerConfigBuilder setCurrentVolume(int currentVolume) {
            mBundle.putInt(KEY_CURRENT_VOLUME, currentVolume);
            return this;
        }

        public MockPlayerConfigBuilder setShuffleMode(int shuffleMode) {
            mBundle.putInt(KEY_SHUFFLE_MODE, shuffleMode);
            return this;
        }

        public MockPlayerConfigBuilder setRepeatMode(int repeatMode) {
            mBundle.putInt(KEY_REPEAT_MODE, repeatMode);
            return this;
        }

        public Bundle build() {
            return mBundle;
        }
    }
}
