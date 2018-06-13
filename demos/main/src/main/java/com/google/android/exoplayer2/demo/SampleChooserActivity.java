/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.google.android.exoplayer2.demo;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.JsonReader;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseExpandableListAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ExpandableListView;
import android.widget.ExpandableListView.OnChildClickListener;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.exoplayer2.ParserException;
import com.google.android.exoplayer2.offline.DownloadService;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSourceInputStream;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.Util;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import android.view.View.OnKeyListener;
import android.view.KeyEvent;
import android.view.inputmethod.InputMethodManager;
import java.time.format.DateTimeFormatter;
import java.time.ZonedDateTime;
import android.os.CountDownTimer;

/** An activity for selecting from a list of media samples. */
public class SampleChooserActivity extends Activity
    implements DownloadTracker.Listener, OnChildClickListener {

  private static final String TAG = "SampleChooserActivity";

  private DownloadTracker downloadTracker;
  private SampleAdapter sampleAdapter;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.sample_chooser_activity);
    sampleAdapter = new SampleAdapter();
    ExpandableListView sampleListView = findViewById(R.id.sample_list);
    sampleListView.setAdapter(sampleAdapter);

    sampleListView.setOnChildClickListener(this);

    Intent intent = getIntent();
    String dataUri = intent.getDataString();
    String[] uris;
    if (dataUri != null) {
      uris = new String[] {dataUri};
    } else {
      ArrayList<String> uriList = new ArrayList<>();
      AssetManager assetManager = getAssets();
      try {
        for (String asset : assetManager.list("")) {
          if (asset.endsWith(".exolist.json")) {
            uriList.add("asset:///" + asset);
          }
        }
      } catch (IOException e) {
        Toast.makeText(getApplicationContext(), R.string.sample_list_load_error, Toast.LENGTH_LONG)
            .show();
      }
      uris = new String[uriList.size()];
      uriList.toArray(uris);
      Arrays.sort(uris);
    }

    downloadTracker = ((DemoApplication) getApplication()).getDownloadTracker();
    SampleListLoader loaderTask = new SampleListLoader();
    loaderTask.execute(uris);

    // Ping the download service in case it's not running (but should be).
    startService(
        new Intent(this, DemoDownloadService.class).setAction(DownloadService.ACTION_INIT));
  }

  @Override
  public void onStart() {
    super.onStart();
    downloadTracker.addListener(this);
    sampleAdapter.notifyDataSetChanged();
  }

  @Override
  public void onStop() {
    downloadTracker.removeListener(this);
    super.onStop();
  }

  @Override
  public void onDownloadsChanged() {
    sampleAdapter.notifyDataSetChanged();
  }

  private void onSampleGroups(final List<SampleGroup> groups, boolean sawError) {
    if (sawError) {
      Toast.makeText(getApplicationContext(), R.string.sample_list_load_error, Toast.LENGTH_LONG)
          .show();
    }
    sampleAdapter.setSampleGroups(groups);
  }

  @Override
  public boolean onChildClick(
      ExpandableListView parent, View view, int groupPosition, int childPosition, long id) {

    EditText sampleUri = view.findViewById(R.id.sample_uri);
    sampleUri.setFocusable(true);
      sampleUri.requestFocus();

      return true;
  }

  private void onSampleDownloadButtonClicked(Sample sample) {
    int downloadUnsupportedStringId = getDownloadUnsupportedStringId(sample);
    if (downloadUnsupportedStringId != 0) {
      Toast.makeText(getApplicationContext(), downloadUnsupportedStringId, Toast.LENGTH_LONG)
          .show();
    } else {
      UriSample uriSample = (UriSample) sample;
      downloadTracker.toggleDownload(this, sample.name, uriSample.uri, uriSample.extension);
    }
  }

  private int getDownloadUnsupportedStringId(Sample sample) {
    if (sample instanceof PlaylistSample) {
      return R.string.download_playlist_unsupported;
    }

    UriSample uriSample = (UriSample) sample;
    if (uriSample.drmInfo != null) {
      return R.string.download_drm_unsupported;
    }
    if (uriSample.adTagUri != null) {
      return R.string.download_ads_unsupported;
    }
    String scheme = uriSample.uri.getScheme();
    if (!("http".equals(scheme) || "https".equals(scheme))) {
      return R.string.download_scheme_unsupported;
    }
    return 0;
  }

  private final class SampleListLoader extends AsyncTask<String, Void, List<SampleGroup>> {

    private boolean sawError;

    @Override
    protected List<SampleGroup> doInBackground(String... uris) {
      List<SampleGroup> result = new ArrayList<>();
      Context context = getApplicationContext();
      String userAgent = Util.getUserAgent(context, "ExoPlayerDemo");
      DataSource dataSource = new DefaultDataSource(context, null, userAgent, false);
      for (String uri : uris) {
        DataSpec dataSpec = new DataSpec(Uri.parse(uri));
        InputStream inputStream = new DataSourceInputStream(dataSource, dataSpec);
        try {
          readSampleGroups(new JsonReader(new InputStreamReader(inputStream, "UTF-8")), result);
        } catch (Exception e) {
          Log.e(TAG, "Error loading sample list: " + uri, e);
          sawError = true;
        } finally {
          Util.closeQuietly(dataSource);
        }
      }
      return result;
    }

    @Override
    protected void onPostExecute(List<SampleGroup> result) {
      onSampleGroups(result, sawError);
    }

    private void readSampleGroups(JsonReader reader, List<SampleGroup> groups) throws IOException {
      reader.beginArray();
      while (reader.hasNext()) {
        readSampleGroup(reader, groups);
      }
      reader.endArray();
    }

    private void readSampleGroup(JsonReader reader, List<SampleGroup> groups) throws IOException {
      String groupName = "";
      ArrayList<Sample> samples = new ArrayList<>();

      reader.beginObject();
      while (reader.hasNext()) {
        String name = reader.nextName();
        switch (name) {
          case "name":
            groupName = reader.nextString();
            break;
          case "samples":
            reader.beginArray();
            while (reader.hasNext()) {
              samples.add(readEntry(reader, false));
            }
            reader.endArray();
            break;
          case "_comment":
            reader.nextString(); // Ignore.
            break;
          default:
            throw new ParserException("Unsupported name: " + name);
        }
      }
      reader.endObject();

      SampleGroup group = getGroup(groupName, groups);
      group.samples.addAll(samples);
    }

    private Sample readEntry(JsonReader reader, boolean insidePlaylist) throws IOException {
      String sampleName = null;
      Uri uri = null;
//      DateTime dateTime = new DateTime( "2011-04-15T20:08:18Z" );
      DateTimeFormatter dtFormatter = DateTimeFormatter.ISO_ZONED_DATE_TIME;
      ZonedDateTime startDateTime = null;


      String extension = null;
      String drmScheme = null;
      String drmLicenseUrl = null;
      String[] drmKeyRequestProperties = null;
      boolean drmMultiSession = false;
      boolean preferExtensionDecoders = false;
      ArrayList<UriSample> playlistSamples = null;
      String adTagUri = null;
      String abrAlgorithm = null;

      reader.beginObject();
      while (reader.hasNext()) {
        String name = reader.nextName();
        switch (name) {
          case "name":
            sampleName = reader.nextString();
            break;
          case "uri":
            uri = Uri.parse(reader.nextString());
            break;
          case "startDateTime":
              startDateTime = ZonedDateTime.parse(reader.nextString(), dtFormatter);
            break;
          case "extension":
            extension = reader.nextString();
            break;
          case "drm_scheme":
            Assertions.checkState(!insidePlaylist, "Invalid attribute on nested item: drm_scheme");
            drmScheme = reader.nextString();
            break;
          case "drm_license_url":
            Assertions.checkState(!insidePlaylist,
                "Invalid attribute on nested item: drm_license_url");
            drmLicenseUrl = reader.nextString();
            break;
          case "drm_key_request_properties":
            Assertions.checkState(!insidePlaylist,
                "Invalid attribute on nested item: drm_key_request_properties");
            ArrayList<String> drmKeyRequestPropertiesList = new ArrayList<>();
            reader.beginObject();
            while (reader.hasNext()) {
              drmKeyRequestPropertiesList.add(reader.nextName());
              drmKeyRequestPropertiesList.add(reader.nextString());
            }
            reader.endObject();
            drmKeyRequestProperties = drmKeyRequestPropertiesList.toArray(new String[0]);
            break;
          case "drm_multi_session":
            drmMultiSession = reader.nextBoolean();
            break;
          case "prefer_extension_decoders":
            Assertions.checkState(!insidePlaylist,
                "Invalid attribute on nested item: prefer_extension_decoders");
            preferExtensionDecoders = reader.nextBoolean();
            break;
          case "playlist":
            Assertions.checkState(!insidePlaylist, "Invalid nesting of playlists");
            playlistSamples = new ArrayList<>();
            reader.beginArray();
            while (reader.hasNext()) {
              playlistSamples.add((UriSample) readEntry(reader, true));
            }
            reader.endArray();
            break;
          case "ad_tag_uri":
            adTagUri = reader.nextString();
            break;
          case "abr_algorithm":
            Assertions.checkState(
                !insidePlaylist, "Invalid attribute on nested item: abr_algorithm");
            abrAlgorithm = reader.nextString();
            break;
          default:
            throw new ParserException("Unsupported attribute name: " + name);
        }
      }
      reader.endObject();
      DrmInfo drmInfo =
          drmScheme == null
              ? null
              : new DrmInfo(drmScheme, drmLicenseUrl, drmKeyRequestProperties, drmMultiSession);
      if (playlistSamples != null) {
        UriSample[] playlistSamplesArray = playlistSamples.toArray(
            new UriSample[playlistSamples.size()]);
        return new PlaylistSample(
            sampleName, preferExtensionDecoders, abrAlgorithm, drmInfo, playlistSamplesArray);
      } else {
        return new UriSample(
            sampleName, preferExtensionDecoders, abrAlgorithm, drmInfo, uri, extension, startDateTime, adTagUri);
      }
    }

    private SampleGroup getGroup(String groupName, List<SampleGroup> groups) {
      for (int i = 0; i < groups.size(); i++) {
        if (Util.areEqual(groupName, groups.get(i).title)) {
          return groups.get(i);
        }
      }
      SampleGroup group = new SampleGroup(groupName);
      groups.add(group);
      return group;
    }

  }

  private final class SampleAdapter extends BaseExpandableListAdapter  {

    private List<SampleGroup> sampleGroups;

    public SampleAdapter() {
      sampleGroups = Collections.emptyList();
    }

    public void setSampleGroups(List<SampleGroup> sampleGroups) {
      this.sampleGroups = sampleGroups;
      notifyDataSetChanged();
    }

    @Override
    public Sample getChild(int groupPosition, int childPosition) {
      return getGroup(groupPosition).samples.get(childPosition);
    }

    @Override
    public long getChildId(int groupPosition, int childPosition) {
      return childPosition;
    }

    @Override
    public View getChildView(int groupPosition, int childPosition, boolean isLastChild,
        View convertView, ViewGroup parent) {
      View view = convertView;

      if (view == null) { view = getLayoutInflater().inflate(R.layout.sample_list_item, parent, false);
        View playButton = view.findViewById(R.id.play_button);

        final View x = view;
        playButton.setOnClickListener(new View.OnClickListener() {
              public void onClick(View v) {
                  Sample sample = (Sample) x.getTag();

                  startActivity(sample.buildIntent(getApplicationContext()));
              }
          });

        /*
              onSampleDownloadButtonClicked((Sample) view.getTag());

         */

          playButton.setFocusable(false);

      }

      initializeChildView(view, getChild(groupPosition, childPosition));


      return view;
    }

    @Override
    public int getChildrenCount(int groupPosition) {
      return getGroup(groupPosition).samples.size();
    }

    @Override
    public SampleGroup getGroup(int groupPosition) {
      return sampleGroups.get(groupPosition);
    }

    @Override
    public long getGroupId(int groupPosition) {
      return groupPosition;
    }

    @Override
    public View getGroupView(int groupPosition, boolean isExpanded, View convertView,
        ViewGroup parent) {
      View view = convertView;
      if (view == null) {
        view =
            getLayoutInflater()
                .inflate(android.R.layout.simple_expandable_list_item_1, parent, false);
      }
      ((TextView) view).setText(getGroup(groupPosition).title);
      return view;
    }

    @Override
    public int getGroupCount() {
      return sampleGroups.size();
    }

    @Override
    public boolean hasStableIds() {
      return false;
    }

    @Override
    public boolean isChildSelectable(int groupPosition, int childPosition) {
      return true;
    }

      public void hideKeyboard(View view) {
          InputMethodManager inputMethodManager =(InputMethodManager)getSystemService(Activity.INPUT_METHOD_SERVICE);
          inputMethodManager.hideSoftInputFromWindow(view.getWindowToken(),InputMethodManager.HIDE_IMPLICIT_ONLY );
      }

    private void initializeChildView(View view, Sample sample) {
      view.setTag(sample);
      TextView sampleTitle = view.findViewById(R.id.sample_title);
      sampleTitle.setText(sample.name);

      TextView sampleStartDateTime = view.findViewById(R.id.sample_startDateTime);
      sampleStartDateTime.setText(((UriSample) sample).startDateTime.toString());


      final EditText sampleUri = view.findViewById(R.id.sample_uri);
      sampleUri.setText(((UriSample) sample).uri.toString());



        sampleUri.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    sampleUri.post(new Runnable() {
                        @Override
                        public void run() {
                            InputMethodManager imm = (InputMethodManager) getApplicationContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                            imm.showSoftInput(sampleUri, InputMethodManager.SHOW_IMPLICIT);
                        }
                    });
                }
                else
                {
                    hideKeyboard(v);
                }
            }
        });

      final Button playButton = view.findViewById(R.id.play_button);

      sampleUri.setOnKeyListener(new OnKeyListener() {

          public boolean onKey(View view, int keyCode, KeyEvent keyevent) {
            //If the keyevent is a key-down event on the "enter" button
            if ((keyevent.getAction() == KeyEvent.ACTION_DOWN) && (keyCode == KeyEvent.KEYCODE_ENTER)) {
                view.clearFocus();
                view.setFocusable(false);

                playButton.setFocusable(true);
                playButton.requestFocus();
                return true;
            }
            return false;
        }
    });

      playButton.setOnFocusChangeListener(new View.OnFocusChangeListener() {
          @Override
          public void onFocusChange(View v, boolean hasFocus) {
              if (hasFocus) {
                  playButton.setTextColor(0xff669900);
              }
              else
                  playButton.setTextColor(0xffcc0000);


          }
      });

        playButton.setOnKeyListener(new OnKeyListener() {

            public boolean onKey(View view, int keyCode, KeyEvent keyevent) {
                //If the keyevent is a key-down event on the "enter" button
                if ((keyevent.getAction() == KeyEvent.ACTION_DOWN) && (keyCode == KeyEvent.KEYCODE_TAB)) {
                    view.clearFocus();
                    view.setFocusable(false);
                    return true;
                }
                return false;
            }
        });


/*
    boolean canDownload = getDownloadUnsupportedStringId(sample) == 0;
    boolean isDownloaded = canDownload && downloadTracker.isDownloaded(((UriSample) sample).uri);
      ImageButton downloadButton = view.findViewById(R.id.download_button);
      downloadButton.setTag(sample);
      downloadButton.setColorFilter(
          canDownload ? (isDownloaded ? 0xFF42A5F5 : 0xFFBDBDBD) : 0xFFEEEEEE);
      downloadButton.setImageResource(
          isDownloaded ? R.drawable.ic_download_done : R.drawable.ic_download);
*/

    }
  }

  private static final class SampleGroup {

    public final String title;
    public final List<Sample> samples;

    public SampleGroup(String title) {
      this.title = title;
      this.samples = new ArrayList<>();
    }

  }

  private static final class DrmInfo {
    public final String drmScheme;
    public final String drmLicenseUrl;
    public final String[] drmKeyRequestProperties;
    public final boolean drmMultiSession;

    public DrmInfo(
        String drmScheme,
        String drmLicenseUrl,
        String[] drmKeyRequestProperties,
        boolean drmMultiSession) {
      this.drmScheme = drmScheme;
      this.drmLicenseUrl = drmLicenseUrl;
      this.drmKeyRequestProperties = drmKeyRequestProperties;
      this.drmMultiSession = drmMultiSession;
    }

    public void updateIntent(Intent intent) {
      Assertions.checkNotNull(intent);
      intent.putExtra(PlayerActivity.DRM_SCHEME_EXTRA, drmScheme);
      intent.putExtra(PlayerActivity.DRM_LICENSE_URL_EXTRA, drmLicenseUrl);
      intent.putExtra(PlayerActivity.DRM_KEY_REQUEST_PROPERTIES_EXTRA, drmKeyRequestProperties);
      intent.putExtra(PlayerActivity.DRM_MULTI_SESSION_EXTRA, drmMultiSession);
    }
  }

  private abstract static class Sample {
    public final String name;
    public final boolean preferExtensionDecoders;
    public final String abrAlgorithm;
    public final DrmInfo drmInfo;

    public Sample(
        String name, boolean preferExtensionDecoders, String abrAlgorithm, DrmInfo drmInfo) {
      this.name = name;
      this.preferExtensionDecoders = preferExtensionDecoders;
      this.abrAlgorithm = abrAlgorithm;
      this.drmInfo = drmInfo;
    }

    public Intent buildIntent(Context context) {
      Intent intent = new Intent(context, PlayerActivity.class);
      intent.putExtra(PlayerActivity.PREFER_EXTENSION_DECODERS_EXTRA, preferExtensionDecoders);
      intent.putExtra(PlayerActivity.ABR_ALGORITHM_EXTRA, abrAlgorithm);
      if (drmInfo != null) {
        drmInfo.updateIntent(intent);
      }
      return intent;
    }

  }

  private static final class UriSample extends Sample {

    public final Uri uri;
    public final String extension;
    public final ZonedDateTime startDateTime;
    public final String adTagUri;

    public UriSample(
        String name,
        boolean preferExtensionDecoders,
        String abrAlgorithm,
        DrmInfo drmInfo,
        Uri uri,
        String extension,
        ZonedDateTime startDateTime,
        String adTagUri) {
      super(name, preferExtensionDecoders, abrAlgorithm, drmInfo);
      this.uri = uri;
      this.extension = extension;
      this.startDateTime = startDateTime;
      this.adTagUri = adTagUri;
    }

    @Override
    public Intent buildIntent(Context context) {
      return super.buildIntent(context)
          .setData(uri)
          .putExtra(PlayerActivity.EXTENSION_EXTRA, extension)
          .putExtra(PlayerActivity.AD_TAG_URI_EXTRA, adTagUri)
          .setAction(PlayerActivity.ACTION_VIEW);
    }

  }

  private static final class PlaylistSample extends Sample {

    public final UriSample[] children;

    public PlaylistSample(
        String name,
        boolean preferExtensionDecoders,
        String abrAlgorithm,
        DrmInfo drmInfo,
        UriSample... children) {
      super(name, preferExtensionDecoders, abrAlgorithm, drmInfo);
      this.children = children;
    }

    @Override
    public Intent buildIntent(Context context) {
      String[] uris = new String[children.length];
      String[] extensions = new String[children.length];
      for (int i = 0; i < children.length; i++) {
        uris[i] = children[i].uri.toString();
        extensions[i] = children[i].extension;
      }
      return super.buildIntent(context)
          .putExtra(PlayerActivity.URI_LIST_EXTRA, uris)
          .putExtra(PlayerActivity.EXTENSION_LIST_EXTRA, extensions)
          .setAction(PlayerActivity.ACTION_VIEW_LIST);
    }

  }

}
