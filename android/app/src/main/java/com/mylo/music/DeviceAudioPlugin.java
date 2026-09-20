package com.mylo.music;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.Settings;
import android.webkit.MimeTypeMap;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

@CapacitorPlugin(
    name = "DeviceAudio",
    permissions = {
        @Permission(
            strings = { Manifest.permission.READ_MEDIA_AUDIO },
            alias = "readMediaAudio"
        ),
        @Permission(
            strings = { Manifest.permission.READ_EXTERNAL_STORAGE },
            alias = "readStorage"
        )
    }
)
public class DeviceAudioPlugin extends Plugin {

    private boolean hasAudioPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    @PluginMethod
    public void checkPermissions(PluginCall call) {
        JSObject ret = new JSObject();
        boolean granted = hasAudioPermission();
        ret.put("granted", granted);
        ret.put("status", granted ? "granted" : "prompt");
        call.resolve(ret);
    }

    @PluginMethod
    public void requestPermissions(PluginCall call) {
        if (hasAudioPermission()) {
            JSObject ret = new JSObject();
            ret.put("granted", true);
            ret.put("status", "granted");
            call.resolve(ret);
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionForAlias("readMediaAudio", call, "permissionCallback");
        } else {
            requestPermissionForAlias("readStorage", call, "permissionCallback");
        }
    }

    @PermissionCallback
    private void permissionCallback(PluginCall call) {
        boolean granted = hasAudioPermission();
        JSObject ret = new JSObject();
        ret.put("granted", granted);
        ret.put("status", granted ? "granted" : "denied");
        call.resolve(ret);
    }

    @PluginMethod
    public void scanDeviceAudio(PluginCall call) {
        if (!hasAudioPermission()) {
            call.reject("Permission not granted to access device audio files");
            return;
        }

        new Thread(() -> {
            try {
                ContentResolver resolver = getContext().getContentResolver();
                Uri musicUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
                String[] projection = new String[] {
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DATE_MODIFIED
                };

                Cursor cursor = resolver.query(musicUri, projection, selection, null, MediaStore.Audio.Media.TITLE + " ASC");
                JSArray tracks = new JSArray();

                if (cursor != null) {
                    int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                    int titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                    int artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                    int albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                    int durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                    int dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
                    int albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
                    int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);
                    int dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED);

                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idCol);
                        String title = cursor.getString(titleCol);
                        String artist = cursor.getString(artistCol);
                        String album = cursor.getString(albumCol);
                        long durationMs = cursor.getLong(durCol);
                        String path = cursor.getString(dataCol);
                        long albumId = cursor.getLong(albumIdCol);
                        long size = cursor.getLong(sizeCol);
                        long dateModified = cursor.getLong(dateCol);

                        // Skip audio shorter than 4 seconds (notifications, ringtones)
                        if (durationMs > 0 && durationMs < 4000) continue;

                        JSObject track = new JSObject();
                        track.put("id", "device_" + id);
                        track.put("title", (title != null && !title.trim().isEmpty()) ? title.trim() : "Unknown Title");
                        track.put("artist", (artist != null && !artist.trim().isEmpty() && !artist.equals("<unknown>")) ? artist.trim() : "Unknown Artist");
                        track.put("album", (album != null && !album.trim().isEmpty() && !album.equals("<unknown>")) ? album.trim() : "Device Audio");
                        track.put("duration", durationMs / 1000.0);
                        track.put("path", path);
                        track.put("size", size);
                        track.put("dateModified", dateModified);

                        Uri contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                        track.put("contentUri", contentUri.toString());

                        Uri albumArtUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId);
                        track.put("albumArtUri", albumArtUri.toString());

                        tracks.put(track);
                    }
                    cursor.close();
                }

                JSObject res = new JSObject();
                res.put("tracks", tracks);
                res.put("count", tracks.length());
                call.resolve(res);
            } catch (Exception e) {
                call.reject("Error scanning audio files: " + e.getMessage(), e);
            }
        }).start();
    }

    @PluginMethod
    public void materializeAudio(PluginCall call) {
        String uriStr = call.getString("uri");
        String path = call.getString("path");
        if (uriStr == null && path == null) {
            call.reject("URI or path required");
            return;
        }

        // MediaStore's DATA path is available after READ_MEDIA_AUDIO has been
        // granted. Capacitor.convertFileSrc() can serve it directly to the
        // WebView, so there is no need to duplicate the file.
        if (path != null) {
            File original = new File(path);
            if (original.exists() && original.canRead()) {
                JSObject ret = new JSObject();
                ret.put("path", Uri.fromFile(original).toString());
                call.resolve(ret);
                return;
            }
        }

        new Thread(() -> {
            try {
                Uri contentUri = uriStr != null ? Uri.parse(uriStr) : Uri.fromFile(new File(path));
                ContentResolver resolver = getContext().getContentResolver();
                InputStream inputStream = resolver.openInputStream(contentUri);
                if (inputStream == null) {
                    call.reject("Cannot open input stream for audio");
                    return;
                }

                // Some OEMs hide DATA even with permission. Copy only the
                // selected track into app cache as a fallback. Never base64 the
                // whole music library across the Capacitor bridge.
                String mime = resolver.getType(contentUri);
                String ext = mime != null ? MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) : null;
                if (ext == null || ext.isEmpty()) ext = "audio";
                String cacheKey = Integer.toHexString(contentUri.toString().hashCode());
                File dir = new File(getContext().getCacheDir(), "MYLODeviceAudio");
                if (!dir.exists() && !dir.mkdirs()) {
                    inputStream.close();
                    call.reject("Cannot create the audio cache");
                    return;
                }
                File output = new File(dir, cacheKey + "." + ext);
                FileOutputStream out = new FileOutputStream(output);
                byte[] buffer = new byte[65536];
                int len;
                while ((len = inputStream.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
                inputStream.close();
                out.flush();
                out.close();

                JSObject ret = new JSObject();
                ret.put("path", Uri.fromFile(output).toString());
                call.resolve(ret);
            } catch (Exception e) {
                call.reject("Error preparing audio file: " + e.getMessage(), e);
            }
        }).start();
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getContext().getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            call.resolve();
        } catch (Exception e) {
            call.reject("Could not open app settings", e);
        }
    }
}
