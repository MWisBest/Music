package me.mwisbest.music;

import android.media.AudioManager;
import android.media.MediaPlayer;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.io.IOException;

public class MusicPlayer extends MediaPlayer {
	private boolean prepared = false;

	public MusicPlayer() {
		super();
		this.setOnPreparedListener( null );
	}

	@Override
	public void prepare() throws IOException, IllegalStateException {
		// We only do music!
		this.setAudioStreamType( AudioManager.STREAM_MUSIC );
		super.prepare();
	}

	/**
	 * Sorry, but we're the only onPrepared listener...
	 * ...or are we? :D
	 * @param listener 'post-listener'
	 */
	@Override
	public void setOnPreparedListener( OnPreparedListener listener ) {
		OnPreparedListenerOverride overrideListener = new OnPreparedListenerOverride();
		overrideListener.setPostListener( listener );
		super.setOnPreparedListener( overrideListener );
	}

	private class OnPreparedListenerOverride implements MediaPlayer.OnPreparedListener {
		private MediaPlayer.OnPreparedListener postListener;

		public void setPostListener( MediaPlayer.OnPreparedListener postListener ) {
			this.postListener = postListener;
		}

		@Override
		public void onPrepared( MediaPlayer mp ) {
			prepared = true;
			if( this.postListener != null ) {
				this.postListener.onPrepared( mp );
			}
		}
	}


	/* Remove video-related junk. */

	@Override
	public int getVideoWidth() {
		return 0;
	}

	@Override
	public int getVideoHeight() {
		return 0;
	}

	@Override
	public void setOnVideoSizeChangedListener( OnVideoSizeChangedListener listener ) {}

	@Override
	public void setDisplay( SurfaceHolder sh ) {}

	@Override
	public void setSurface( Surface surface ) {}

	@Override
	public void setScreenOnWhilePlaying( boolean screenOn ) {}


	/* Remove other stuff we don't use, etc */

	@Override
	public void setAudioStreamType( int streamtype ) {
		super.setAudioStreamType( AudioManager.STREAM_MUSIC );
	}
}
