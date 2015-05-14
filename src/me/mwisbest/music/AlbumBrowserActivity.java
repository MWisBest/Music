/*
 * Copyright (C) 2007 The Android Open Source Project
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

package me.mwisbest.music;

import android.app.AlertDialog;
import android.app.ListActivity;
import android.app.SearchManager;
import android.content.AsyncQueryHandler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.AlphabetIndexer;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SectionIndexer;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;

public class AlbumBrowserActivity extends ListActivity
		implements View.OnCreateContextMenuListener, MusicUtils.Defs, ServiceConnection {
	private String mCurrentAlbumId;
	private String mCurrentAlbumName;
	private String mCurrentArtistNameForAlbum;
	boolean mIsUnknownArtist;
	boolean mIsUnknownAlbum;
	private AlbumListAdapter mAdapter;
	private boolean mAdapterSent;
	private final static int SEARCH = CHILD_MENU_BASE;
	private static int mLastListPosCourse = -1;
	private static int mLastListPosFine = -1;
	private MusicUtils.ServiceToken mToken;

	public AlbumBrowserActivity() {
	}

	/**
	 * Called when the activity is first created.
	 */
	@Override
	public void onCreate( Bundle icicle ) {
		if( icicle != null ) {
			mCurrentAlbumId = icicle.getString( "selectedalbum" );
			mArtistId = icicle.getString( "artist" );
		} else {
			mArtistId = getIntent().getStringExtra( "artist" );
		}
		super.onCreate( icicle );
		requestWindowFeature( Window.FEATURE_NO_TITLE );
		setVolumeControlStream( AudioManager.STREAM_MUSIC );
		mToken = MusicUtils.bindToService( this, this );

		IntentFilter f = new IntentFilter();
		f.addAction( Intent.ACTION_MEDIA_SCANNER_STARTED );
		f.addAction( Intent.ACTION_MEDIA_SCANNER_FINISHED );
		f.addAction( Intent.ACTION_MEDIA_UNMOUNTED );
		f.addDataScheme( "file" );
		registerReceiver( mScanListener, f );

		setContentView( R.layout.media_picker_activity );
		MusicUtils.updateButtonBar( this, R.id.albumtab );
		ListView lv = getListView();
		lv.setOnCreateContextMenuListener( this );
		lv.setTextFilterEnabled( true );

		mAdapter = (AlbumListAdapter)getLastNonConfigurationInstance();
		if( mAdapter == null ) {
			//Log.i("@@@", "starting query");
			mAdapter = new AlbumListAdapter(
					getApplication(),
					this,
					R.layout.track_list_item,
					mAlbumCursor,
					new String[] {},
					new int[] {} );
			setListAdapter( mAdapter );
			setTitle( R.string.working_albums );
			getAlbumCursor( mAdapter.getQueryHandler(), null );
		} else {
			mAdapter.setActivity( this );
			setListAdapter( mAdapter );
			mAlbumCursor = mAdapter.getCursor();
			if( mAlbumCursor != null ) {
				init( mAlbumCursor );
			} else {
				getAlbumCursor( mAdapter.getQueryHandler(), null );
			}
		}
	}

	@Override
	public Object onRetainNonConfigurationInstance() {
		mAdapterSent = true;
		return mAdapter;
	}

	@Override
	public void onSaveInstanceState( @NonNull Bundle outcicle ) {
		// need to store the selected item so we don't lose it in case
		// of an orientation switch. Otherwise we could lose it while
		// in the middle of specifying a playlist to add the item to.
		outcicle.putString( "selectedalbum", mCurrentAlbumId );
		outcicle.putString( "artist", mArtistId );
		super.onSaveInstanceState( outcicle );
	}

	@Override
	public void onDestroy() {
		ListView lv = getListView();
		if( lv != null ) {
			mLastListPosCourse = lv.getFirstVisiblePosition();
			View cv = lv.getChildAt( 0 );
			if( cv != null ) {
				mLastListPosFine = cv.getTop();
			}
		}
		MusicUtils.unbindFromService( mToken );
		// If we have an adapter and didn't send it off to another activity yet, we should
		// close its cursor, which we do by assigning a null cursor to it. Doing this
		// instead of closing the cursor directly keeps the framework from accessing
		// the closed cursor later.
		if( !mAdapterSent && mAdapter != null ) {
			mAdapter.changeCursor( null );
		}
		// Because we pass the adapter to the next activity, we need to make
		// sure it doesn't keep a reference to this activity. We can do this
		// by clearing its DatasetObservers, which setListAdapter(null) does.
		setListAdapter( null );
		mAdapter = null;
		unregisterReceiver( mScanListener );
		super.onDestroy();
	}

	@Override
	public void onResume() {
		super.onResume();
		IntentFilter f = new IntentFilter();
		f.addAction( MediaPlaybackService.META_CHANGED );
		f.addAction( MediaPlaybackService.QUEUE_CHANGED );
		registerReceiver( mTrackListListener, f );
		mTrackListListener.onReceive( null, null );

		// TODO: Deprecated...kinda...
		//MusicUtils.setSpinnerState( this );
	}

	private BroadcastReceiver mTrackListListener = new BroadcastReceiver() {
		@Override
		public void onReceive( Context context, Intent intent ) {
			getListView().invalidateViews();
			MusicUtils.updateNowPlaying( AlbumBrowserActivity.this );
		}
	};
	private BroadcastReceiver mScanListener = new BroadcastReceiver() {
		@Override
		public void onReceive( Context context, Intent intent ) {
			// TODO: Deprecated...kinda...
			//MusicUtils.setSpinnerState( AlbumBrowserActivity.this );
			mReScanHandler.sendEmptyMessage( 0 );
			if( intent.getAction().equals( Intent.ACTION_MEDIA_UNMOUNTED ) ) {
				MusicUtils.clearAlbumArtCache();
			}
		}
	};

	private Handler mReScanHandler = new Handler() {
		@Override
		public void handleMessage( Message msg ) {
			if( mAdapter != null ) {
				getAlbumCursor( mAdapter.getQueryHandler(), null );
			}
		}
	};

	@Override
	public void onPause() {
		unregisterReceiver( mTrackListListener );
		mReScanHandler.removeCallbacksAndMessages( null );
		super.onPause();
	}

	public void init( Cursor c ) {

		if( mAdapter == null ) {
			return;
		}
		mAdapter.changeCursor( c ); // also sets mAlbumCursor

		if( mAlbumCursor == null ) {
			MusicUtils.displayDatabaseError( this );
			closeContextMenu();
			mReScanHandler.sendEmptyMessageDelayed( 0, 1000 );
			return;
		}

		// restore previous position
		if( mLastListPosCourse >= 0 ) {
			getListView().setSelectionFromTop( mLastListPosCourse, mLastListPosFine );
			mLastListPosCourse = -1;
		}

		MusicUtils.hideDatabaseError( this );
		MusicUtils.updateButtonBar( this, R.id.albumtab );
		setTitle();
	}

	private void setTitle() {
		CharSequence fancyName = "";
		if( mAlbumCursor != null && mAlbumCursor.getCount() > 0 ) {
			mAlbumCursor.moveToFirst();
			fancyName = mAlbumCursor.getString(
					mAlbumCursor.getColumnIndex( MediaStore.Audio.Albums.ARTIST ) );
			if( fancyName == null || fancyName.equals( MediaStore.UNKNOWN_STRING ) )
				fancyName = getText( R.string.unknown_artist_name );
		}

		if( mArtistId != null && fancyName != null )
			setTitle( fancyName );
		else
			setTitle( R.string.albums_title );
	}

	@Override
	public void onCreateContextMenu( ContextMenu menu, View view, ContextMenuInfo menuInfoIn ) {
		menu.add( 0, PLAY_SELECTION, 0, R.string.play_selection );
		SubMenu sub = menu.addSubMenu( 0, ADD_TO_PLAYLIST, 0, R.string.add_to_playlist );
		MusicUtils.makePlaylistMenu( this, sub );
		menu.add( 0, DELETE_ITEM, 0, R.string.delete_item );

		AdapterContextMenuInfo mi = (AdapterContextMenuInfo)menuInfoIn;
		mAlbumCursor.moveToPosition( mi.position );
		mCurrentAlbumId = mAlbumCursor.getString( mAlbumCursor.getColumnIndexOrThrow( MediaStore.Audio.Albums._ID ) );
		mCurrentAlbumName = mAlbumCursor.getString( mAlbumCursor.getColumnIndexOrThrow( MediaStore.Audio.Albums.ALBUM ) );
		mCurrentArtistNameForAlbum = mAlbumCursor.getString(
				mAlbumCursor.getColumnIndexOrThrow( MediaStore.Audio.Albums.ARTIST ) );
		mIsUnknownArtist = mCurrentArtistNameForAlbum == null ||
				mCurrentArtistNameForAlbum.equals( MediaStore.UNKNOWN_STRING );
		mIsUnknownAlbum = mCurrentAlbumName == null ||
				mCurrentAlbumName.equals( MediaStore.UNKNOWN_STRING );
		if( mIsUnknownAlbum ) {
			menu.setHeaderTitle( getString( R.string.unknown_album_name ) );
		} else {
			menu.setHeaderTitle( mCurrentAlbumName );
		}
		if( !mIsUnknownAlbum || !mIsUnknownArtist ) {
			menu.add( 0, SEARCH, 0, R.string.search_title );
		}
	}

	@Override
	public boolean onContextItemSelected( MenuItem item ) {
		switch( item.getItemId() ) {
			case PLAY_SELECTION: {
				// play the selected album
				long[] list = MusicUtils.getSongListForAlbum( this, Long.parseLong( mCurrentAlbumId ) );
				MusicUtils.playAll( this, list, 0 );
				return true;
			}

			case QUEUE: {
				long[] list = MusicUtils.getSongListForAlbum( this, Long.parseLong( mCurrentAlbumId ) );
				MusicUtils.addToCurrentPlaylist( this, list );
				return true;
			}

			case NEW_PLAYLIST: {
				final EditText input = new EditText( this );
				String defaultname = MusicUtils.makePlaylistName( this );
				input.setText( defaultname );
				input.setSelection( defaultname.length() );

				AlertDialog.Builder dialogBuilder = new AlertDialog.Builder( AlbumBrowserActivity.this )
						.setTitle( R.string.create_playlist_create_text_prompt )
						.setNegativeButton( android.R.string.cancel, null )
						.setPositiveButton( R.string.create_playlist_create_text, new DialogInterface.OnClickListener() {
							@Override
							public void onClick( DialogInterface dialog, int which ) {
								String name = input.getText().toString();
								// Shouldn't be able to click us if it's not > 0, but better safe than sorry.
								if( name.length() > 0 ) {
									ContentResolver resolver = getContentResolver();
									int id = MusicUtils.idForPlaylist( AlbumBrowserActivity.this, name );
									Uri uri;
									if( id >= 0 ) {
										// overwrite existing playlist
										uri = ContentUris.withAppendedId( MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, id );
										MusicUtils.clearPlaylist( AlbumBrowserActivity.this, id );
									} else {
										ContentValues values = new ContentValues( 1 );
										values.put( MediaStore.Audio.Playlists.NAME, name );
										uri = resolver.insert( MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, values );
									}
									if( uri != null ) {
										long[] list = MusicUtils.getSongListForAlbum(
												AlbumBrowserActivity.this, Long.parseLong( mCurrentAlbumId ) );
										MusicUtils.addToPlaylist( AlbumBrowserActivity.this,
												list, Long.parseLong( uri.getLastPathSegment() ) );
									}
								}
							}
						} );
				final AlertDialog dialog = dialogBuilder.create();
				int horizontalPadding = MusicUtils.getPixelsForDP( 14 );
				dialog.setView( input, horizontalPadding, 0, horizontalPadding, 0 );

				input.addTextChangedListener( new TextWatcher() {
					@Override
					public void beforeTextChanged( CharSequence s, int start, int count, int after ) {
						// don't care about this one
					}

					@Override
					public void onTextChanged( CharSequence s, int start, int before, int count ) {
						String newText = input.getText().toString();
						if( newText.trim().length() == 0 ) {
							dialog.getButton( DialogInterface.BUTTON_POSITIVE ).setEnabled( false );
						} else {
							dialog.getButton( DialogInterface.BUTTON_POSITIVE ).setEnabled( true );
							// check if playlist with current name exists already, and warn the user if so.
							if( MusicUtils.idForPlaylist( AlbumBrowserActivity.this, newText ) >= 0 ) {
								dialog.getButton( DialogInterface.BUTTON_POSITIVE )
										.setText( R.string.create_playlist_overwrite_text );
							} else {
								dialog.getButton( DialogInterface.BUTTON_POSITIVE )
										.setText( R.string.create_playlist_create_text );
							}
						}
					}

					@Override
					public void afterTextChanged( Editable s ) {
						// don't care about this one
					}
				} );

				dialog.show();
				return true;
			}

			case PLAYLIST_SELECTED: {
				long[] list = MusicUtils.getSongListForAlbum( this, Long.parseLong( mCurrentAlbumId ) );
				long playlist = item.getIntent().getLongExtra( "playlist", 0 );
				MusicUtils.addToPlaylist( this, list, playlist );
				return true;
			}
			case DELETE_ITEM: {
				long[] list = MusicUtils.getSongListForAlbum( this, Long.parseLong( mCurrentAlbumId ) );
				String desc = getString( R.string.delete_album_desc, mCurrentAlbumName );

				final long[] finallist = list;

				new AlertDialog.Builder( this )
						.setMessage( desc )
						.setNegativeButton( android.R.string.cancel, null )
						.setPositiveButton( android.R.string.ok, new DialogInterface.OnClickListener() {
							@Override
							public void onClick( DialogInterface dialog, int which ) {
								MusicUtils.deleteTracks( AlbumBrowserActivity.this, finallist );
							}
						} )
						.show();

				return true;
			}
			case SEARCH:
				doSearch();
				return true;

		}
		return super.onContextItemSelected( item );
	}

	void doSearch() {
		CharSequence title = "";
		String query = "";

		Intent i = new Intent();
		i.setAction( MediaStore.INTENT_ACTION_MEDIA_SEARCH );
		i.setFlags( Intent.FLAG_ACTIVITY_NEW_TASK );

		if( !mIsUnknownAlbum ) {
			query = mCurrentAlbumName;
			i.putExtra( MediaStore.EXTRA_MEDIA_ALBUM, mCurrentAlbumName );
			title = mCurrentAlbumName;
		}
		if( !mIsUnknownArtist ) {
			query = query + " " + mCurrentArtistNameForAlbum;
			i.putExtra( MediaStore.EXTRA_MEDIA_ARTIST, mCurrentArtistNameForAlbum );
			title = title + " " + mCurrentArtistNameForAlbum;
		}
		// Since we hide the 'search' menu item when both album and artist are
		// unknown, the query and title strings will have at least one of those.
		i.putExtra( MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE );
		title = getString( R.string.mediasearch, title );
		i.putExtra( SearchManager.QUERY, query );

		startActivity( Intent.createChooser( i, title ) );
	}

	@Override
	protected void onActivityResult( int requestCode, int resultCode, Intent intent ) {
		switch( requestCode ) {
			case SCAN_DONE:
				if( resultCode == RESULT_CANCELED ) {
					finish();
				} else {
					getAlbumCursor( mAdapter.getQueryHandler(), null );
				}
				break;
		}
	}

	@Override
	protected void onListItemClick( ListView l, View v, int position, long id ) {
		Intent intent = new Intent( Intent.ACTION_PICK );
		intent.setDataAndType( Uri.EMPTY, "vnd.android.cursor.dir/track" );
		intent.putExtra( "album", Long.valueOf( id ).toString() );
		intent.putExtra( "artist", mArtistId );
		startActivity( intent );
	}

	@Override
	public boolean onCreateOptionsMenu( Menu menu ) {
		super.onCreateOptionsMenu( menu );
		menu.add( 0, PARTY_SHUFFLE, 0, R.string.party_shuffle ); // icon will be set in onPrepareOptionsMenu()
		menu.add( 0, SHUFFLE_ALL, 0, R.string.shuffle_all ).setIcon( R.drawable.ic_menu_shuffle );
		return true;
	}

	@Override
	public boolean onPrepareOptionsMenu( Menu menu ) {
		MusicUtils.setPartyShuffleMenuIcon( menu );
		return super.onPrepareOptionsMenu( menu );
	}

	@Override
	public boolean onOptionsItemSelected( MenuItem item ) {
		Intent intent;
		Cursor cursor;
		switch( item.getItemId() ) {
			case PARTY_SHUFFLE:
				MusicUtils.togglePartyShuffle();
				break;

			case SHUFFLE_ALL:
				cursor = MusicUtils.query( this, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
						new String[] { MediaStore.Audio.Media._ID },
						MediaStore.Audio.Media.IS_MUSIC + "=1", null,
						MediaStore.Audio.Media.DEFAULT_SORT_ORDER );
				if( cursor != null ) {
					MusicUtils.shuffleAll( this, cursor );
					cursor.close();
				}
				return true;
		}
		return super.onOptionsItemSelected( item );
	}

	private Cursor getAlbumCursor( AsyncQueryHandler async, String filter ) {
		String[] cols = new String[] {
				MediaStore.Audio.Albums._ID,
				MediaStore.Audio.Albums.ARTIST,
				MediaStore.Audio.Albums.ALBUM,
				MediaStore.Audio.Albums.ALBUM_ART
		};


		Cursor ret = null;
		if( mArtistId != null ) {
			Uri uri = MediaStore.Audio.Artists.Albums.getContentUri( "external",
					Long.valueOf( mArtistId ) );
			if( !TextUtils.isEmpty( filter ) ) {
				uri = uri.buildUpon().appendQueryParameter( "filter", Uri.encode( filter ) ).build();
			}
			if( async != null ) {
				async.startQuery( 0, null, uri,
						cols, null, null, MediaStore.Audio.Albums.DEFAULT_SORT_ORDER );
			} else {
				ret = MusicUtils.query( this, uri,
						cols, null, null, MediaStore.Audio.Albums.DEFAULT_SORT_ORDER );
			}
		} else {
			Uri uri = MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI;
			if( !TextUtils.isEmpty( filter ) ) {
				uri = uri.buildUpon().appendQueryParameter( "filter", Uri.encode( filter ) ).build();
			}
			if( async != null ) {
				async.startQuery( 0, null,
						uri,
						cols, null, null, MediaStore.Audio.Albums.DEFAULT_SORT_ORDER );
			} else {
				ret = MusicUtils.query( this, uri,
						cols, null, null, MediaStore.Audio.Albums.DEFAULT_SORT_ORDER );
			}
		}
		return ret;
	}

	static class AlbumListAdapter extends SimpleCursorAdapter implements SectionIndexer {

		private final Drawable mNowPlayingOverlay;
		private final BitmapDrawable mDefaultAlbumIcon;
		private int mAlbumIdx;
		private int mArtistIdx;
		private int mAlbumArtIndex;
		private final Resources mResources;
		private final StringBuilder mStringBuilder = new StringBuilder();
		private final String mUnknownAlbum;
		private final String mUnknownArtist;
		private final String mAlbumSongSeparator;
		private final Object[] mFormatArgs = new Object[1];
		private AlphabetIndexer mIndexer;
		private AlbumBrowserActivity mActivity;
		private AsyncQueryHandler mQueryHandler;
		private String mConstraint = null;
		private boolean mConstraintIsValid = false;

		static class ViewHolder {
			TextView line1;
			TextView line2;
			ImageView play_indicator;
			ImageView icon;
		}

		class QueryHandler extends AsyncQueryHandler {
			QueryHandler( ContentResolver res ) {
				super( res );
			}

			@Override
			protected void onQueryComplete( int token, Object cookie, Cursor cursor ) {
				//Log.i("@@@", "query complete");
				mActivity.init( cursor );
			}
		}

		AlbumListAdapter( Context context, AlbumBrowserActivity currentactivity,
						  int layout, Cursor cursor, String[] from, int[] to ) {
			super( context, layout, cursor, from, to, MusicUtils.DEP_FLAG_AUTO_REQUERY );

			mActivity = currentactivity;
			mQueryHandler = new QueryHandler( context.getContentResolver() );

			mUnknownAlbum = context.getString( R.string.unknown_album_name );
			mUnknownArtist = context.getString( R.string.unknown_artist_name );
			mAlbumSongSeparator = context.getString( R.string.albumsongseparator );

			Resources r = context.getResources();
			mNowPlayingOverlay = r.getDrawable( R.drawable.indicator_ic_mp_playing_list, null );

			Bitmap b = BitmapFactory.decodeResource( r, R.drawable.albumart_mp_unknown_list );
			mDefaultAlbumIcon = new BitmapDrawable( context.getResources(), b );
			// no filter or dither, it's a lot faster and we can't tell the difference
			mDefaultAlbumIcon.setFilterBitmap( false );
			mDefaultAlbumIcon.setDither( false );
			getColumnIndices( cursor );
			mResources = context.getResources();
		}

		private void getColumnIndices( Cursor cursor ) {
			if( cursor != null ) {
				mAlbumIdx = cursor.getColumnIndexOrThrow( MediaStore.Audio.Albums.ALBUM );
				mArtistIdx = cursor.getColumnIndexOrThrow( MediaStore.Audio.Albums.ARTIST );
				mAlbumArtIndex = cursor.getColumnIndexOrThrow( MediaStore.Audio.Albums.ALBUM_ART );

				if( mIndexer != null ) {
					mIndexer.setCursor( cursor );
				} else {
					mIndexer = new MusicAlphabetIndexer( cursor, mAlbumIdx, mResources.getString(
							R.string.fast_scroll_alphabet ) );
				}
			}
		}

		public void setActivity( AlbumBrowserActivity newactivity ) {
			mActivity = newactivity;
		}

		public AsyncQueryHandler getQueryHandler() {
			return mQueryHandler;
		}

		@Override
		public View newView( Context context, Cursor cursor, ViewGroup parent ) {
			View v = super.newView( context, cursor, parent );
			ViewHolder vh = new ViewHolder();
			vh.line1 = (TextView)v.findViewById( R.id.line1 );
			vh.line2 = (TextView)v.findViewById( R.id.line2 );
			vh.play_indicator = (ImageView)v.findViewById( R.id.play_indicator );
			vh.icon = (ImageView)v.findViewById( R.id.icon );
			vh.icon.setBackground( mDefaultAlbumIcon );
			vh.icon.setPadding( 0, 0, 1, 0 );
			v.setTag( vh );
			return v;
		}

		@Override
		public void bindView( @NonNull View view, Context context, @NonNull Cursor cursor ) {

			ViewHolder vh = (ViewHolder)view.getTag();

			String name = cursor.getString( mAlbumIdx );
			String displayname = name;
			boolean unknown = name == null || name.equals( MediaStore.UNKNOWN_STRING );
			if( unknown ) {
				displayname = mUnknownAlbum;
			}
			vh.line1.setText( displayname );

			name = cursor.getString( mArtistIdx );
			displayname = name;
			if( name == null || name.equals( MediaStore.UNKNOWN_STRING ) ) {
				displayname = mUnknownArtist;
			}
			vh.line2.setText( displayname );

			ImageView iv = vh.icon;
			// We don't actually need the path to the thumbnail file,
			// we just use it to see if there is album art or not
			String art = cursor.getString( mAlbumArtIndex );
			long aid = cursor.getLong( 0 );
			if( unknown || art == null || art.length() == 0 ) {
				iv.setImageDrawable( null );
			} else {
				Drawable d = MusicUtils.getCachedArtwork( context, aid, mDefaultAlbumIcon );
				iv.setImageDrawable( d );
			}

			long currentalbumid = MusicUtils.getCurrentAlbumId();
			iv = vh.play_indicator;
			if( currentalbumid == aid ) {
				iv.setImageDrawable( mNowPlayingOverlay );
			} else {
				iv.setImageDrawable( null );
			}
		}

		@Override
		public void changeCursor( Cursor cursor ) {
			if( mActivity.isFinishing() && cursor != null ) {
				cursor.close();
				cursor = null;
			}
			if( cursor != mActivity.mAlbumCursor ) {
				mActivity.mAlbumCursor = cursor;
				getColumnIndices( cursor );
				super.changeCursor( cursor );
			}
		}

		@Override
		public Cursor runQueryOnBackgroundThread( CharSequence constraint ) {
			String s = constraint.toString();
			if( mConstraintIsValid && (
					(s == null && mConstraint == null) ||
							(s != null && s.equals( mConstraint ))) ) {
				return getCursor();
			}
			Cursor c = mActivity.getAlbumCursor( null, s );
			mConstraint = s;
			mConstraintIsValid = true;
			return c;
		}

		public Object[] getSections() {
			return mIndexer.getSections();
		}

		public int getPositionForSection( int section ) {
			return mIndexer.getPositionForSection( section );
		}

		public int getSectionForPosition( int position ) {
			return 0;
		}
	}

	private Cursor mAlbumCursor;
	private String mArtistId;

	public void onServiceConnected( ComponentName name, IBinder service ) {
		MusicUtils.updateNowPlaying( this );
	}

	public void onServiceDisconnected( ComponentName name ) {
		finish();
	}
}
