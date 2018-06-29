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
package com.foxsports.worldcup.streamtest;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.content.ContextCompat;
import android.util.JsonReader;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseExpandableListAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ExpandableListView;
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
import java.util.HashMap;
import java.util.Map;
import java.io.UnsupportedEncodingException;

import android.view.View.OnKeyListener;
import android.view.KeyEvent;
import android.view.inputmethod.InputMethodManager;
import java.time.ZonedDateTime;
import java.util.TimeZone;
import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.AuthFailureError;
import com.android.volley.toolbox.JsonObjectRequest;
import org.json.JSONException;
import org.json.JSONObject;


/** An activity for selecting from a list of media samples. */
public class SampleChooserActivity extends Activity
    implements DownloadTracker.Listener {

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
    sampleListView.setItemsCanFocus(true);

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
            uriList.add("http://hisense-fox.azurewebsites.net/streamtest/media.exolist.json");
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
      boolean concluded = false;
      ArrayList<Sample> samples = new ArrayList<>();

      reader.beginObject();
      while (reader.hasNext()) {
        String name = reader.nextName();
        switch (name) {
          case "name":
            groupName = reader.nextString();
            break;

            case "concluded":
                concluded = reader.nextBoolean();
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

      SampleGroup group = getGroup(groupName, concluded, groups);
      group.samples.addAll(samples);
    }

    private Sample readEntry(JsonReader reader, boolean insidePlaylist) throws IOException {
      String sampleName = null;
      Uri uri = null;
      boolean editable = false;
      //DateTimeFormatter dtFormatter = DateTimeFormatter.ISO_ZONED_DATE_TIME;

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
            case "editable":
                editable = reader.nextBoolean();
                break;
          case "startDateTime":
              reader.nextString();
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
            sampleName, preferExtensionDecoders, abrAlgorithm, drmInfo, uri, editable, extension, startDateTime, adTagUri);
      }
    }

    private SampleGroup getGroup(String groupName, boolean concluded, List<SampleGroup> groups) {
      for (int i = 0; i < groups.size(); i++) {
        if (Util.areEqual(groupName, groups.get(i).title)) {
          return groups.get(i);
        }
      }
      SampleGroup group = new SampleGroup(groupName, concluded);
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
    public View getChildView(int groupPosition, int childPosition, boolean isLastChild, View convertView, ViewGroup parent) {
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

         }

        View editTextView = view.findViewById(R.id.sample_uri_edit);
        editTextView.setOnKeyListener(new OnKeyListener() {

            public boolean onKey(View view, int keyCode, KeyEvent keyevent) {
                //If the keyevent is a key-down event on the "enter" button
                if ((keyevent.getAction() == KeyEvent.ACTION_DOWN) && (keyCode == KeyEvent.KEYCODE_ENTER)) {
                    View tokenizeButton = view.getRootView().findViewById(R.id.tokenize_button);
                    tokenizeButton.requestFocus();
                    return true;
                }
                return false;
            }
        });

        View tokenizeButton = view.findViewById(R.id.tokenize_button);

      // Reference the URI TextView View
      final TextView sampleUri = view.findViewById(R.id.sample_uri);
      final EditText sampleUriText = view.findViewById(R.id.sample_uri_edit);
      final Button samplePlayButton = view.findViewById(R.id.play_button);

      final View x = view;

      tokenizeButton.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
          final Sample sample = (Sample) x.getTag();

            // Tokenization URL
          String url = "https://hbswcfox.deltatre.net/api/api-akamai/tokenize";

          try
          {

            // Format Request Body
            JSONObject jsonBody = new JSONObject();
            jsonBody.put("Type", 1);
            jsonBody.put("User", "cookieUser");
            jsonBody.put("VideoId", "0");
            jsonBody.put("VideoSource", ((UriSample) sample).uri.toString());
            jsonBody.put("VideoKind", "Replays");
            jsonBody.put("AssetState", "3");
            jsonBody.put("PlayerType", "TAL");

            final String mRequestBody = jsonBody.toString();

            JsonObjectRequest jsonObjectRequest = new JsonObjectRequest(Request.Method.POST,
                    url, null,
                    new Response.Listener<JSONObject>() {
                      @Override
                      public void onResponse(JSONObject response) {
                        //Success Callback

                        try
                        {
                          String tokenizedUrl = response.get("ContentUrl").toString();

                          // Set the text value on the object and text view
                            sampleUri.setText(tokenizedUrl);
                            sampleUriText.setText(tokenizedUrl);
                            sampleUri.setTextColor( ContextCompat.getColor(getApplicationContext(), R.color.holo_green_dark));

                            sampleUriText.setTextColor( ContextCompat.getColor(getApplicationContext(), R.color.holo_green_dark));
                          ((UriSample) sample).uri = Uri.parse(tokenizedUrl);

                            samplePlayButton.setTextColor(ContextCompat.getColor(getApplicationContext(), R.color.holo_green_dark));
                            samplePlayButton.requestFocus();

                        }
                        catch (JSONException exc) {
                          Toast.makeText(getApplicationContext(), exc.getMessage(), Toast.LENGTH_LONG)
                                  .show();
                        }

                      }
                    },
                    new Response.ErrorListener() {
                      @Override
                      public void onErrorResponse(VolleyError error) {
                        //Failure Callback

                        Toast.makeText(getApplicationContext(), error.getMessage(), Toast.LENGTH_LONG)
                                .show();

                      }

                    })


              {
              @Override
                public String getBodyContentType() {
                  return "application/json";
              }

                /** Passing some request headers* */
                @Override
                public Map<String,String> getHeaders() throws AuthFailureError {
                  HashMap<String,String> headers = new HashMap<String,String>();
                  headers.put("Content-Type", "application/json");
                  headers.put("Referer", "https://api.foxsports.com/dev-hbs-wcfox-tal/api.foxsports.com");
                  return headers;
                }

                @Override
              public byte[] getBody() {
                try {
                  return mRequestBody == null ? null : mRequestBody.getBytes("utf-8");
                } catch (UnsupportedEncodingException uee) {
                  Toast.makeText(getApplicationContext(), uee.getMessage(), Toast.LENGTH_LONG)
                          .show();
                  return null;
                }
              }
            };

            // Access the RequestQueue through your singleton class.
            MySingleton.getInstance(getApplicationContext()).addToRequestQueue(jsonObjectRequest);


          }
          catch (JSONException exc) {
            Toast.makeText(getApplicationContext(), R.string.tokenize_error, Toast.LENGTH_LONG)
                    .show();
            return;
          }

        }
      });


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

      SampleGroup group = getGroup(groupPosition);
      ((TextView) view).setText(group.title);


      if (group.concluded) {
          ((TextView) view).setTextColor(Color.parseColor("darkGrey"));
      }
      else {
          ((TextView) view).setTextColor(Color.parseColor("white"));

      }

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

    private void initializeChildView(View view, final Sample sample) {

        // Set Tag to Item
        view.setTag(sample);

        // Set the Title
        TextView sampleTitle = view.findViewById(R.id.sample_title);
        sampleTitle.setText(sample.name);

        // Reference the Views
        final TextView sampleUri = view.findViewById(R.id.sample_uri);
        final EditText sampleUriEdit = view.findViewById(R.id.sample_uri_edit);
        final Button tokenizeButton = view.findViewById(R.id.tokenize_button);
        final Button playButton = view.findViewById(R.id.play_button);

        // Cast to UriSample
        UriSample uriSample = ((UriSample) sample);

        if (uriSample != null) {
          // Set the text values
          sampleUri.setText(uriSample.uri.toString());
          sampleUriEdit.setText(uriSample.uri.toString());

          if (uriSample.editable) {
            sampleUri.setVisibility(View.GONE);
            sampleUriEdit.setVisibility(View.VISIBLE);
            playButton.setTextColor(ContextCompat.getColor(getApplicationContext(), R.color.holo_green_dark));
          }
          else {
            sampleUri.setVisibility(View.VISIBLE);
            sampleUriEdit.setVisibility(View.GONE);
          }
        }

    }
  }

  private static final class SampleGroup {

    public final String title;
    public final boolean concluded;
    public final List<Sample> samples;

    public SampleGroup(String title, boolean concluded) {
      this.title = title;
      this.concluded = concluded;
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

    public Uri uri;
    public boolean editable;
    public final String extension;
    public final ZonedDateTime startDateTime;
    public final String adTagUri;

    public UriSample(
        String name,
        boolean preferExtensionDecoders,
        String abrAlgorithm,
        DrmInfo drmInfo,
        Uri uri,
        boolean editable,
        String extension,
        ZonedDateTime startDateTime,
        String adTagUri) {
      super(name, preferExtensionDecoders, abrAlgorithm, drmInfo);
      this.uri = uri;
      this.editable = editable;
      this.extension = extension;
      this.startDateTime = startDateTime;
      this.adTagUri = adTagUri;
    }

    @Override
    public Intent buildIntent(Context context) {
      return super.buildIntent(context)
          .setData(uri)
          .putExtra("editable", editable)
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
