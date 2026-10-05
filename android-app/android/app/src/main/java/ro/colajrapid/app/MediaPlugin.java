package ro.colajrapid.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;

import androidx.core.content.FileProvider;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Saves generated pictures/videos into the phone's gallery (album "Colaj Rapid")
 * and opens the Android share sheet (Instagram, WhatsApp, TikTok...).
 * Files arrive from the web page as base64 strings.
 */
@CapacitorPlugin(name = "Media")
public class MediaPlugin extends Plugin {

    private static final String ALBUM = "Colaj Rapid";

    @PluginMethod
    public void saveToGallery(PluginCall call) {
        String name = call.getString("name", "colaj.jpg");
        String mime = call.getString("mime", "image/jpeg");
        String data = call.getString("data");
        if (data == null) {
            call.reject("Lipsește fișierul");
            return;
        }
        boolean video = mime.startsWith("video");
        try {
            byte[] bytes = Base64.decode(data, Base64.DEFAULT);
            Uri saved;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver cr = getContext().getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/" + ALBUM);
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri collection = video
                        ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                saved = cr.insert(collection, v);
                if (saved == null) throw new IllegalStateException("Galeria nu a acceptat fișierul");
                try (OutputStream os = cr.openOutputStream(saved)) {
                    os.write(bytes);
                }
                v.clear();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(saved, v, null, null);
            } else {
                // Android 9 and older: write into the public folder and let the media scanner index it
                File dir = new File(Environment.getExternalStoragePublicDirectory(video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES), ALBUM);
                dir.mkdirs();
                File f = new File(dir, name);
                try (FileOutputStream os = new FileOutputStream(f)) {
                    os.write(bytes);
                }
                saved = Uri.fromFile(f);
                getContext().sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, saved));
            }
            JSObject result = new JSObject();
            result.put("uri", saved.toString());
            call.resolve(result);
        } catch (Exception e) {
            call.reject(e.getMessage() != null ? e.getMessage() : "Salvarea a eșuat", e);
        }
    }

    @PluginMethod
    public void share(PluginCall call) {
        JSArray files = call.getArray("files");
        if (files == null || files.length() == 0) {
            call.reject("Nimic de trimis");
            return;
        }
        try {
            File dir = new File(getContext().getCacheDir(), "share");
            dir.mkdirs();
            ArrayList<Uri> uris = new ArrayList<>();
            String type = null;
            List<JSONObject> list = files.toList();
            for (JSONObject o : list) {
                String name = o.optString("name", "colaj.jpg");
                String mime = o.optString("mime", "image/jpeg");
                File f = new File(dir, name);
                try (FileOutputStream os = new FileOutputStream(f)) {
                    os.write(Base64.decode(o.getString("data"), Base64.DEFAULT));
                }
                uris.add(FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", f));
                type = type == null || type.equals(mime) ? mime : "*/*";
            }
            Intent send = new Intent(uris.size() > 1 ? Intent.ACTION_SEND_MULTIPLE : Intent.ACTION_SEND);
            send.setType(type);
            if (uris.size() > 1) send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            else send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getActivity().startActivity(Intent.createChooser(send, "Trimite"));
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage() != null ? e.getMessage() : "Trimiterea a eșuat", e);
        }
    }
}
