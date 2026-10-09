package com.mylo.music;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQUEST_AUDIO = 41;
    private static final int BG = Color.rgb(12, 10, 18);
    private static final int SURFACE = Color.rgb(28, 24, 37);
    private static final int ACCENT = Color.rgb(230, 0, 35);
    private static final String PREFS = "mylo_native";
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final List<Track> tracks = new ArrayList<>();
    private final List<Playlist> playlists = new ArrayList<>();
    private SharedPreferences preferences;
    private LinearLayout root;
    private LinearLayout body;
    private TextView nowPlaying;
    private MediaPlayer player;
    private String activeTab = "Listen";
    private String country = "US";
    private String playingTitle = "";
    private boolean isPlaying;

    private static final class Track {
        String id;
        String title;
        String artist;
        String uri;
        Track(String id, String title, String artist, String uri) {
            this.id = id; this.title = title; this.artist = artist; this.uri = uri;
        }
    }

    private static final class Playlist {
        String name;
        final List<String> ids = new ArrayList<>();
        Playlist(String name) { this.name = name; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadSavedState();
        render();
    }

    private void loadSavedState() {
        try {
            JSONArray savedTracks = new JSONArray(preferences.getString("tracks", "[]"));
            for (int i = 0; i < savedTracks.length(); i++) {
                JSONObject item = savedTracks.getJSONObject(i);
                tracks.add(new Track(item.getString("id"), item.getString("title"),
                    item.optString("artist", "Unknown artist"), item.getString("uri")));
            }
            JSONArray savedPlaylists = new JSONArray(preferences.getString("playlists", "[]"));
            for (int i = 0; i < savedPlaylists.length(); i++) {
                JSONObject item = savedPlaylists.getJSONObject(i);
                Playlist playlist = new Playlist(item.getString("name"));
                JSONArray ids = item.getJSONArray("ids");
                for (int j = 0; j < ids.length(); j++) playlist.ids.add(ids.getString(j));
                playlists.add(playlist);
            }
            country = preferences.getString("country", "US");
        } catch (Exception error) {
            Toast.makeText(this, "Saved music data could not be read.", Toast.LENGTH_LONG).show();
        }
    }

    private void persist() {
        try {
            JSONArray savedTracks = new JSONArray();
            for (Track track : tracks) {
                JSONObject item = new JSONObject();
                item.put("id", track.id); item.put("title", track.title);
                item.put("artist", track.artist); item.put("uri", track.uri);
                savedTracks.put(item);
            }
            JSONArray savedPlaylists = new JSONArray();
            for (Playlist playlist : playlists) {
                JSONObject item = new JSONObject();
                item.put("name", playlist.name);
                JSONArray ids = new JSONArray();
                for (String id : playlist.ids) ids.put(id);
                item.put("ids", ids);
                savedPlaylists.put(item);
            }
            preferences.edit().putString("tracks", savedTracks.toString())
                .putString("playlists", savedPlaylists.toString())
                .putString("country", country).apply();
        } catch (Exception error) {
            Toast.makeText(this, "Could not save your library.", Toast.LENGTH_LONG).show();
        }
    }

    private void render() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(20), dp(12), dp(20), dp(8));
        setContentView(root);

        ImageView brand = new ImageView(this);
        brand.setImageResource(R.drawable.mylo_wordmark);
        brand.setAdjustViewBounds(true);
        brand.setContentDescription("MYLO");
        root.addView(brand, new LinearLayout.LayoutParams(dp(160), dp(60)));
        TextView subtitle = text("RADIO  ·  YOUR MUSIC", 11, 0xffaaa3b5, true);
        root.addView(subtitle);

        ScrollView scroll = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(20), 0, dp(16));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        nowPlaying = text(playingTitle.isEmpty() ? "Nothing playing" : playingTitle, 14, Color.WHITE, true);
        nowPlaying.setPadding(dp(12), dp(12), dp(12), dp(12));
        nowPlaying.setBackgroundColor(SURFACE);
        nowPlaying.setOnClickListener(view -> togglePlayback());
        root.addView(nowPlaying, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        for (String tab : new String[]{"Listen", "Radio", "Library", "Playlists"}) {
            Button button = button(tab, tab.equals(activeTab) ? Color.WHITE : 0xffaaa3b5, false);
            button.setOnClickListener(view -> { activeTab = tab; render(); });
            nav.addView(button, new LinearLayout.LayoutParams(0, dp(52), 1));
        }
        root.addView(nav);

        switch (activeTab) {
            case "Radio": showRadio(); break;
            case "Library": showLibrary(); break;
            case "Playlists": showPlaylists(); break;
            default: showListen(); break;
        }
    }

    private void showListen() {
        heading("Listen now");
        card("A player for your music and the radio.", "Import audio files, browse live stations, and build playlists that stay on this device.");
        action("Browse radio", () -> { activeTab = "Radio"; render(); });
        action("Open your library", () -> { activeTab = "Library"; render(); });
        action("Create a playlist", this::createPlaylist);
        if (!tracks.isEmpty()) {
            heading("Recently added");
            for (Track track : tracks.subList(0, Math.min(4, tracks.size()))) trackRow(track);
        }
    }

    private void showRadio() {
        heading("Radio");
        card("Live radio around the world", "Choose a country code to find popular stations.");
        EditText countryInput = new EditText(this);
        countryInput.setSingleLine(true);
        countryInput.setText(country);
        countryInput.setHint("Country code, e.g. US");
        countryInput.setTextColor(Color.WHITE);
        countryInput.setHintTextColor(0xffaaa3b5);
        countryInput.setBackgroundColor(SURFACE);
        body.addView(countryInput, params(-1, dp(48)));
        action("Find stations", () -> {
            String entered = countryInput.getText().toString().trim().toUpperCase();
            if (!entered.matches("[A-Z]{2}")) {
                Toast.makeText(this, "Enter a two-letter country code.", Toast.LENGTH_SHORT).show();
                return;
            }
            country = entered;
            persist();
            fetchStations(entered);
        });
        TextView loading = text("Popular stations in " + country, 14, 0xffaaa3b5, false);
        loading.setPadding(0, dp(16), 0, dp(8));
        body.addView(loading);
        fetchStations(country);
    }

    private void fetchStations(String code) {
        final String requestedTab = activeTab;
        network.execute(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL("https://de1.api.radio-browser.info/json/stations/bycountrycodeexact/"
                    + code + "?limit=40&order=clickcount&reverse=true");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestProperty("User-Agent", "MYLO/1.0");
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(12000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) response.append(line);
                JSONArray stations = new JSONArray(response.toString());
                runOnUiThread(() -> {
                    if (!requestedTab.equals(activeTab)) return;
                    body.removeAllViews();
                    heading("Popular stations · " + code);
                    if (stations.length() == 0) { card("No stations found", "Try another country code."); return; }
                    for (int i = 0; i < stations.length(); i++) {
                        JSONObject station = stations.optJSONObject(i);
                        if (station == null) continue;
                        String name = station.optString("name", "Radio station").trim();
                        if (name.isEmpty()) name = "Radio station";
                        String stream = station.optString("url_resolved", "").trim();
                        if (stream.isEmpty()) stream = station.optString("url", "").trim();
                        String detail = station.optString("tags", "");
                        stationRow(name, detail.isEmpty() ? code : detail, stream);
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (requestedTab.equals(activeTab)) card("Radio unavailable", "Check your connection and try again.");
                    Toast.makeText(this, "Could not load radio stations.", Toast.LENGTH_LONG).show();
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void showLibrary() {
        heading("Your library");
        action("＋  Import audio files", this::pickAudio);
        if (tracks.isEmpty()) card("No music yet", "Import audio files from your device to start your library.");
        else {
            for (Track track : tracks) {
                trackRow(track);
                action("Add to playlist · " + track.title, () -> choosePlaylist(track));
            }
        }
    }

    private void showPlaylists() {
        heading("Playlists");
        action("＋  New playlist", this::createPlaylist);
        if (playlists.isEmpty()) card("No playlists yet", "Create a playlist, then add songs from your library.");
        for (Playlist playlist : playlists) {
            heading(playlist.name + " · " + playlist.ids.size() + " songs");
            action("Delete playlist · " + playlist.name, () -> deletePlaylist(playlist));
            for (String id : playlist.ids) {
                Track track = trackById(id);
                if (track != null) trackRow(track);
            }
            action("Add songs to " + playlist.name, () -> chooseTrackFor(playlist));
        }
    }

    private void pickAudio() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, REQUEST_AUDIO);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_AUDIO || resultCode != RESULT_OK || data == null) return;
        ArrayList<Uri> selected = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++)
                selected.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) selected.add(data.getData());
        for (Uri uri : selected) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                String title = queryName(uri);
                String id = uri.toString();
                if (trackById(id) == null) tracks.add(new Track(id, title, "Local audio", id));
            } catch (SecurityException error) {
                Toast.makeText(this, "Could not keep access to the selected audio file.", Toast.LENGTH_LONG).show();
            }
        }
        persist();
        activeTab = "Library";
        render();
    }

    private String queryName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null) return name.replaceFirst("\\.[^.]+$", "");
            }
        }
        return "Imported audio";
    }

    private void createPlaylist() {
        EditText input = new EditText(this);
        input.setHint("Playlist name");
        new AlertDialog.Builder(this).setTitle("New playlist").setView(input)
            .setNegativeButton("Cancel", null).setPositiveButton("Create", (dialog, which) -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty()) { Toast.makeText(this, "Enter a playlist name.", Toast.LENGTH_SHORT).show(); return; }
                playlists.add(new Playlist(name));
                persist(); activeTab = "Playlists"; render();
            }).show();
    }

    private void choosePlaylist(Track track) {
        if (playlists.isEmpty()) { createPlaylist(); return; }
        String[] names = new String[playlists.size()];
        for (int i = 0; i < names.length; i++) names[i] = playlists.get(i).name;
        new AlertDialog.Builder(this).setTitle("Add to playlist").setItems(names, (dialog, which) -> {
            Playlist playlist = playlists.get(which);
            if (!playlist.ids.contains(track.id)) playlist.ids.add(track.id);
            persist(); Toast.makeText(this, "Added to " + playlist.name, Toast.LENGTH_SHORT).show();
        }).show();
    }

    private void chooseTrackFor(Playlist playlist) {
        if (tracks.isEmpty()) { Toast.makeText(this, "Import music first.", Toast.LENGTH_SHORT).show(); return; }
        String[] names = new String[tracks.size()];
        for (int i = 0; i < tracks.size(); i++) names[i] = tracks.get(i).title;
        new AlertDialog.Builder(this).setTitle("Add a song").setItems(names, (dialog, which) -> {
            Track track = tracks.get(which);
            if (!playlist.ids.contains(track.id)) playlist.ids.add(track.id);
            persist(); render();
        }).show();
    }

    private void deletePlaylist(Playlist playlist) {
        new AlertDialog.Builder(this)
            .setTitle("Delete playlist?")
            .setMessage("Your songs will stay in the library.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete", (dialog, which) -> {
                playlists.remove(playlist);
                persist();
                render();
            }).show();
    }

    private void removeTrack(Track track) {
        new AlertDialog.Builder(this)
            .setTitle("Remove from library?")
            .setMessage(track.title + " will be removed from your library and playlists.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove", (dialog, which) -> {
                tracks.remove(track);
                for (Playlist playlist : playlists) playlist.ids.remove(track.id);
                persist();
                render();
            }).show();
    }

    private void trackRow(Track track) {
        LinearLayout row = row();
        TextView title = text(track.title, 15, Color.WHITE, true);
        TextView artist = text(track.artist, 12, 0xffaaa3b5, false);
        LinearLayout labels = new LinearLayout(this); labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(title); labels.addView(artist);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        Button play = button("Play", Color.WHITE, true);
        play.setOnClickListener(view -> play(track.uri, track.title));
        row.addView(play);
        Button remove = button("Remove", 0xffaaa3b5, false);
        remove.setOnClickListener(view -> removeTrack(track));
        row.addView(remove);
        body.addView(row);
    }

    private void stationRow(String name, String detail, String stream) {
        LinearLayout row = row();
        LinearLayout labels = new LinearLayout(this); labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(name, 15, Color.WHITE, true));
        labels.addView(text(detail, 12, 0xffaaa3b5, false));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        Button play = button("Play", Color.WHITE, true);
        play.setOnClickListener(view -> {
            if (!stream.startsWith("https://")) {
                Toast.makeText(this, "This station has no secure, playable stream.", Toast.LENGTH_SHORT).show();
                return;
            }
            play(stream, name);
        });
        row.addView(play);
        body.addView(row);
    }

    private void play(String source, String title) {
        releasePlayer();
        playingTitle = title;
        isPlaying = false;
        if (nowPlaying != null) nowPlaying.setText("Loading · " + title);
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            player.setDataSource(this, Uri.parse(source));
            player.setOnPreparedListener(media -> { media.start(); isPlaying = true; updatePlayerLabel(); });
            player.setOnErrorListener((media, what, extra) -> {
                Toast.makeText(this, "Could not play this audio source.", Toast.LENGTH_LONG).show();
                isPlaying = false; updatePlayerLabel(); return true;
            });
            player.prepareAsync();
        } catch (Exception error) {
            Toast.makeText(this, "Could not open this audio source.", Toast.LENGTH_LONG).show();
            updatePlayerLabel();
        }
    }

    private void togglePlayback() {
        if (player == null) return;
        if (isPlaying) { player.pause(); isPlaying = false; }
        else { player.start(); isPlaying = true; }
        updatePlayerLabel();
    }

    private void updatePlayerLabel() {
        if (nowPlaying != null) nowPlaying.setText((isPlaying ? "Ⅱ  " : "▶  ") + playingTitle);
    }

    private void releasePlayer() {
        if (player != null) { player.release(); player = null; }
    }

    private Track trackById(String id) {
        for (Track track : tracks) if (track.id.equals(id)) return track;
        return null;
    }

    private void heading(String value) {
        TextView view = text(value, 20, Color.WHITE, true);
        view.setPadding(0, dp(18), 0, dp(8));
        body.addView(view);
    }

    private void card(String title, String description) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(16));
        box.setBackgroundColor(SURFACE);
        box.addView(text(title, 16, Color.WHITE, true));
        TextView detail = text(description, 13, 0xffaaa3b5, false);
        detail.setPadding(0, dp(7), 0, 0);
        box.addView(detail);
        body.addView(box, params(-1, -2));
    }

    private void action(String label, Runnable callback) {
        Button button = button(label, Color.WHITE, true);
        button.setOnClickListener(view -> callback.run());
        body.addView(button, params(-1, dp(48)));
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setBackgroundColor(SURFACE);
        return row;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private Button button(String value, int color, boolean filled) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(color);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(filled ? ACCENT : BG));
        return button;
    }

    private LinearLayout.LayoutParams params(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override protected void onDestroy() {
        network.shutdownNow();
        releasePlayer();
        super.onDestroy();
    }
}
