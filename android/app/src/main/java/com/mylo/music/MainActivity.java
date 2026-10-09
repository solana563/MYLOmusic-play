package com.mylo.music;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.util.Base64;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

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
    private String authEmail = "";
    private String authAccessToken = "";
    private AlertDialog authDialog;
    private static final String AUTH_KEY_ALIAS = "mylo.auth";

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
        restoreAuthSession();
        render();
        handleAuthCallback(getIntent() == null ? null : getIntent().getData());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAuthCallback(intent == null ? null : intent.getData());
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
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(dp(20), dp(12) + bars.top, dp(20), dp(8) + bars.bottom);
            return windowInsets;
        });
        setContentView(root);
        ViewCompat.requestApplyInsets(root);

        ImageView brand = new ImageView(this);
        brand.setImageResource(R.drawable.mylo_wordmark);
        brand.setAdjustViewBounds(true);
        brand.setContentDescription("MYLO");
        root.addView(brand, new LinearLayout.LayoutParams(dp(200), dp(72)));
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
        if (authEmail.isEmpty()) {
            card("Sign in to MYLO", "Use your MYLO account to sign in with email, a magic link, or Google.");
            action("Sign in or create an account", this::showAuthDialog);
        } else {
            card("Signed in", authEmail);
            action("Sign out", this::signOut);
        }
        action("Browse radio", () -> { activeTab = "Radio"; render(); });
        action("Open your library", () -> { activeTab = "Library"; render(); });
        action("Create a playlist", this::createPlaylist);
        if (!tracks.isEmpty()) {
            heading("Recently added");
            for (Track track : tracks.subList(0, Math.min(4, tracks.size()))) trackRow(track);
        }
    }

    private interface AuthCallback {
        void complete(JSONObject response, String error);
    }

    private boolean authConfigured() {
        Uri projectUrl = Uri.parse(BuildConfig.SUPABASE_URL);
        return "https".equals(projectUrl.getScheme()) && projectUrl.getHost() != null
            && !BuildConfig.SUPABASE_ANON_KEY.isEmpty();
    }

    private void showAuthDialog() {
        if (!authConfigured()) {
            new AlertDialog.Builder(this).setTitle("Sign-in unavailable")
                .setMessage("This build needs MYLO_SUPABASE_URL and MYLO_SUPABASE_ANON_KEY configured.")
                .setPositiveButton("OK", null).show();
            return;
        }
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), dp(8));
        EditText email = new EditText(this);
        email.setSingleLine(true);
        email.setHint("Email address");
        email.setInputType(33);
        form.addView(email, params(-1, dp(52)));
        EditText password = new EditText(this);
        password.setSingleLine(true);
        password.setHint("Password");
        password.setInputType(129);
        form.addView(password, params(-1, dp(52)));
        Button magic = button("Send a magic link", Color.WHITE, false);
        Button google = button("Continue with Google", Color.WHITE, false);
        form.addView(magic, params(-1, dp(48)));
        form.addView(google, params(-1, dp(48)));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("MYLO account")
            .setView(form).setNegativeButton("Cancel", null)
            .setPositiveButton("Sign in", null).setNeutralButton("Create account", null).create();
        authDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (authDialog == dialog) authDialog = null;
        });
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view ->
                authenticate(email.getText().toString(), password.getText().toString(), false));
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view ->
                authenticate(email.getText().toString(), password.getText().toString(), true));
        });
        magic.setOnClickListener(view -> sendMagicLink(email.getText().toString()));
        google.setOnClickListener(view -> startGoogleSignIn());
        dialog.show();
    }

    private void authenticate(String emailValue, String password, boolean createAccount) {
        String email = emailValue.trim();
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            Toast.makeText(this, "Enter a valid email address.", Toast.LENGTH_LONG).show();
            return;
        }
        if (password.length() < (createAccount ? 6 : 1)) {
            Toast.makeText(this, createAccount ? "Use at least 6 characters." : "Enter your password.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            JSONObject body = new JSONObject().put("email", email).put("password", password);
            if (createAccount) {
                String verifier = createVerifier();
                saveEncrypted("auth_verifier", verifier);
                body.put("data", new JSONObject().put("username", email.substring(0, email.indexOf('@'))))
                    .put("code_challenge", codeChallenge(verifier)).put("code_challenge_method", "s256");
            }
            String endpoint = createAccount
                ? "/signup?redirect_to=" + Uri.encode("mylo://auth-callback")
                : "/token?grant_type=password";
            authRequest(endpoint, body, null, (response, error) -> {
                if (error != null) {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                } else if (!applyAuthSession(response)) {
                    Toast.makeText(this, "Account created. Check your email to confirm, then sign in.", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Signed in as " + authEmail, Toast.LENGTH_LONG).show();
                }
            });
        } catch (Exception error) {
            Toast.makeText(this, "Could not start sign-in.", Toast.LENGTH_LONG).show();
        }
    }

    private void sendMagicLink(String emailValue) {
        String email = emailValue.trim();
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            Toast.makeText(this, "Enter a valid email address.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            String verifier = createVerifier();
            saveEncrypted("auth_verifier", verifier);
            JSONObject body = new JSONObject().put("email", email).put("create_user", false)
                .put("code_challenge", codeChallenge(verifier)).put("code_challenge_method", "s256");
            authRequest("/otp?redirect_to=" + Uri.encode("mylo://auth-callback"), body, null,
                (response, error) -> Toast.makeText(this,
                    error == null ? "Check your inbox for the sign-in link." : error, Toast.LENGTH_LONG).show());
        } catch (Exception error) {
            Toast.makeText(this, "Could not prepare the magic link. Try again.", Toast.LENGTH_LONG).show();
        }
    }

    private void startGoogleSignIn() {
        try {
            String verifier = createVerifier();
            saveEncrypted("auth_verifier", verifier);
            Uri url = Uri.parse(BuildConfig.SUPABASE_URL.replaceAll("/+$", "") + "/auth/v1/authorize")
                .buildUpon().appendQueryParameter("provider", "google")
                .appendQueryParameter("redirect_to", "mylo://auth-callback")
                .appendQueryParameter("code_challenge", codeChallenge(verifier))
                .appendQueryParameter("code_challenge_method", "s256").build();
            startActivity(new Intent(Intent.ACTION_VIEW, url));
        } catch (Exception error) {
            Toast.makeText(this, "Could not open Google sign-in.", Toast.LENGTH_LONG).show();
        }
    }

    private String createVerifier() {
        byte[] value = new byte[32];
        new SecureRandom().nextBytes(value);
        return Base64.encodeToString(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private String codeChallenge(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes("UTF-8"));
        return Base64.encodeToString(digest, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private void handleAuthCallback(Uri callback) {
        if (callback == null || !"mylo".equals(callback.getScheme()) || !"auth-callback".equals(callback.getHost())) return;
        String error = callback.getQueryParameter("error_description");
        if (error != null) {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show();
            return;
        }
        String callbackCode = callback.getQueryParameter("code");
        if (callbackCode == null && callback.getFragment() != null) {
            Uri fragment = Uri.parse("mylo://auth-callback?" + callback.getFragment());
            callbackCode = fragment.getQueryParameter("code");
        }
        String verifier = readEncrypted("auth_verifier");
        if (callbackCode == null || verifier == null) {
            Toast.makeText(this, "Sign-in callback was incomplete. Request a new link.", Toast.LENGTH_LONG).show();
            return;
        }
        preferences.edit().remove("auth_verifier").apply();
        try {
            JSONObject body = new JSONObject().put("auth_code", callbackCode).put("code_verifier", verifier);
            authRequest("/token?grant_type=pkce", body, null, (response, requestError) -> {
                if (requestError != null) Toast.makeText(this, requestError, Toast.LENGTH_LONG).show();
                else if (applyAuthSession(response)) Toast.makeText(this, "Signed in as " + authEmail, Toast.LENGTH_LONG).show();
                else Toast.makeText(this, "Sign-in returned no session.", Toast.LENGTH_LONG).show();
            });
        } catch (Exception requestError) {
            Toast.makeText(this, "Could not complete sign-in.", Toast.LENGTH_LONG).show();
        }
    }

    private void authRequest(String endpoint, JSONObject body, String bearer, AuthCallback callback) {
        network.execute(() -> {
            HttpURLConnection connection = null;
            JSONObject response = null;
            String errorMessage = null;
            try {
                URL url = new URL(BuildConfig.SUPABASE_URL.replaceAll("/+$", "") + "/auth/v1" + endpoint);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY);
                if (bearer != null) connection.setRequestProperty("Authorization", "Bearer " + bearer);
                try (java.io.OutputStream output = connection.getOutputStream()) {
                    output.write(body.toString().getBytes("UTF-8"));
                }
                int status = connection.getResponseCode();
                java.io.InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                StringBuilder text = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) text.append(line);
                }
                response = text.length() == 0 ? new JSONObject() : new JSONObject(text.toString());
                if (status < 200 || status >= 300) {
                    errorMessage = response.optString("msg", response.optString("message",
                        response.optString("error_description", "Sign-in failed. Please try again.")));
                }
            } catch (Exception error) {
                errorMessage = "Could not connect to sign-in. Check your connection and try again.";
            } finally {
                if (connection != null) connection.disconnect();
            }
            JSONObject result = response;
            String failure = errorMessage;
            runOnUiThread(() -> callback.complete(result, failure));
        });
    }

    private boolean applyAuthSession(JSONObject response) {
        if (response == null) return false;
        String accessToken = response.optString("access_token", "");
        String refreshToken = response.optString("refresh_token", "");
        JSONObject account = response.optJSONObject("user");
        String email = account == null ? "" : account.optString("email", "");
        if (accessToken.isEmpty() || email.isEmpty()) return false;
        if (!refreshToken.isEmpty() && Build.VERSION.SDK_INT >= 23) {
            try {
                saveEncrypted("auth_refresh", refreshToken);
            } catch (Exception error) {
                Toast.makeText(this, "Secure sign-in storage is unavailable on this device.", Toast.LENGTH_LONG).show();
                return false;
            }
        }
        authAccessToken = accessToken;
        authEmail = email;
        preferences.edit().remove("auth_verifier").apply();
        if (authDialog != null) {
            authDialog.dismiss();
            authDialog = null;
        }
        render();
        return true;
    }

    private void restoreAuthSession() {
        String refreshToken = readEncrypted("auth_refresh");
        if (refreshToken == null || !authConfigured()) return;
        try {
            authRequest("/token?grant_type=refresh_token", new JSONObject().put("refresh_token", refreshToken),
                null, (response, error) -> {
                    if (error != null) {
                        if (error.toLowerCase().contains("refresh token")) {
                            preferences.edit().remove("auth_refresh").apply();
                            Toast.makeText(this, "Your saved sign-in expired. Please sign in again.", Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(this, "Could not restore sign-in. Check your connection.", Toast.LENGTH_LONG).show();
                        }
                    } else applyAuthSession(response);
                });
        } catch (Exception error) {
            Toast.makeText(this, "Could not restore sign-in. Check your connection.", Toast.LENGTH_LONG).show();
        }
    }

    private void signOut() {
        String token = authAccessToken;
        authAccessToken = "";
        authEmail = "";
        preferences.edit().remove("auth_refresh").remove("auth_verifier").apply();
        render();
        if (token.isEmpty()) return;
        try {
            authRequest("/logout", new JSONObject(), token, (response, error) -> {
                if (error != null) Toast.makeText(this, "Signed out on this device; remote sign-out failed.", Toast.LENGTH_LONG).show();
            });
        } catch (Exception error) {
            Toast.makeText(this, "Signed out on this device; remote sign-out failed.", Toast.LENGTH_LONG).show();
        }
    }

    private void saveEncrypted(String preferenceKey, String value) throws Exception {
        if (Build.VERSION.SDK_INT < 23) {
            if ("auth_verifier".equals(preferenceKey)) {
                preferences.edit().putString(preferenceKey, "plain:" + value).apply();
                return;
            }
            throw new IllegalStateException("Secure storage requires Android 6 or later.");
        }
        SecretKey key = getAuthKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        String encoded = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
            + Base64.encodeToString(cipher.doFinal(value.getBytes("UTF-8")), Base64.NO_WRAP);
        preferences.edit().putString(preferenceKey, encoded).apply();
    }

    private String readEncrypted(String preferenceKey) {
        String encoded = preferences.getString(preferenceKey, null);
        if (encoded == null) return null;
        if (encoded.startsWith("plain:") && "auth_verifier".equals(preferenceKey)) return encoded.substring(6);
        if (Build.VERSION.SDK_INT < 23) return null;
        try {
            String[] parts = encoded.split(":", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getAuthKey(),
                new GCMParameterSpec(128, Base64.decode(parts[0], Base64.DEFAULT)));
            return new String(cipher.doFinal(Base64.decode(parts[1], Base64.DEFAULT)), "UTF-8");
        } catch (Exception error) {
            preferences.edit().remove(preferenceKey).apply();
            Toast.makeText(this, "Saved sign-in could not be restored.", Toast.LENGTH_LONG).show();
            return null;
        }
    }

    private SecretKey getAuthKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(AUTH_KEY_ALIAS)) return (SecretKey) keyStore.getKey(AUTH_KEY_ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(AUTH_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
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
