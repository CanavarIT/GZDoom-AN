package com.az.doom;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Start screen. Three tabs (GAMES / MODS / SETTINGS), a row of cards with the cover of every
 * game file, an "Args" line and two big buttons at the bottom. Deliberately plain.
 */
public class LauncherActivity extends Activity {

    private static final int REQ_PICK = 1;
    private static final String[] GAME_EXT = { ".wad", ".pk3", ".pk7", ".pkz", ".ipk3", ".ipk7", ".deh", ".bex" };

    private static final int C_BAR = 0xFF1B1E24;
    private static final int C_BG = 0xFFEFF0F4;
    private static final int C_TEXT = 0xFF202124;
    private static final int C_DIM = 0xFF666A73;
    private static final int C_BLUE = 0xFF3F5F9A;
    private static final int C_BTN = 0xFFD8D9DB;

    private SharedPreferences prefs;
    private File home;
    private File wadsDir;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private TextView status;
    private final TextView[] tabs = new TextView[3];
    private final View[] pages = new View[3];

    private LinearLayout gamesRow, modsRow;
    private TextView gamesEmpty, modsEmpty;
    private CheckBox cbControls, cbHaptics;
    private SeekBar sensBar;
    private TextView sensLabel;
    private EditText extraArgs;
    private Button playButton;

    private final List<File> iwads = new ArrayList<>();
    private final List<File> mods = new ArrayList<>();
    private final List<View> iwadCards = new ArrayList<>();
    private final Set<String> enabledMods = new HashSet<>();
    private int selectedIwad = -1;

    private volatile boolean busy;
    private volatile List<File> iwadSnapshot = Collections.emptyList();
    private volatile byte[] palette;
    private volatile boolean paletteReady;

    // ------------------------------------------------------------------ life cycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.setStatusBarColor(C_BAR);
        w.setNavigationBarColor(0xFF000000);

        prefs = getSharedPreferences("gzdoom", MODE_PRIVATE);
        home = AssetInstaller.homeDir(this);
        wadsDir = AssetInstaller.wadsDir(home);
        //noinspection ResultOfMethodCallIgnored
        wadsDir.mkdirs();
        enabledMods.addAll(prefs.getStringSet("mods", Collections.<String>emptySet()));

        buildUi();
        selectTab(0);
        refreshFiles();
        installAssetsIfNeeded();
    }

    @Override
    protected void onPause() {
        savePrefs();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ UI

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button flatButton(String s, int bg, int fg) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(18);
        b.setTextColor(fg);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        b.setBackground(box(bg, 0, 0));
        return b;
    }

    private GradientDrawable box(int fill, int stroke, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        if (stroke != 0) g.setStroke(dp(strokeDp), stroke);
        return g;
    }

    private void tint(CheckBox c) {
        c.setTextColor(C_TEXT);
        c.setButtonTintList(ColorStateList.valueOf(C_BLUE));
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        // ---- top bar
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(C_BAR);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), 0, dp(14), 0);

        TextView title = text("GZDoom", 18, 0xFFFFFFFF, true);
        title.setPadding(0, 0, dp(22), 0);
        bar.addView(title);

        String[] names = { "GAMES", "MODS", "SETTINGS" };
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView tv = text(names[i], 15, 0xFF9AA0AA, true);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(dp(14), 0, dp(14), 0);
            tv.setOnClickListener(v -> selectTab(idx));
            tabs[i] = tv;
            bar.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }

        status = text("", 12, 0xFF9AA0AA, false);
        status.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        bar.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));

        // ---- pages
        FrameLayout content = new FrameLayout(this);
        pages[0] = cardsPage(false);
        pages[1] = cardsPage(true);
        pages[2] = settingsPage();
        for (View p : pages) {
            content.addView(p, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- Args line
        LinearLayout argsRow = new LinearLayout(this);
        argsRow.setOrientation(LinearLayout.HORIZONTAL);
        argsRow.setGravity(Gravity.CENTER_VERTICAL);
        argsRow.setPadding(dp(14), 0, dp(14), 0);
        argsRow.addView(text("Args:", 16, C_TEXT, false));

        extraArgs = new EditText(this);
        extraArgs.setHint("+map map05 -skill 3");
        extraArgs.setTextColor(C_TEXT);
        extraArgs.setHintTextColor(0xFF9AA0AA);
        extraArgs.setTextSize(15);
        extraArgs.setSingleLine(true);
        extraArgs.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        extraArgs.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        extraArgs.setBackgroundTintList(ColorStateList.valueOf(C_BLUE));
        extraArgs.setText(prefs.getString("extra", ""));
        LinearLayout.LayoutParams lpArgs = new LinearLayout.LayoutParams(0, dp(44), 1f);
        lpArgs.leftMargin = dp(8);
        argsRow.addView(extraArgs, lpArgs);
        root.addView(argsRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- two big buttons
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(dp(10), dp(2), dp(10), dp(8));

        Button add = flatButton("Add WADs / mods", C_BTN, C_TEXT);
        add.setOnClickListener(v -> pickFiles());
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, dp(48), 1f);
        l1.rightMargin = dp(5);
        btnRow.addView(add, l1);

        playButton = flatButton("Play", C_BLUE, 0xFFFFFFFF);
        playButton.setOnClickListener(v -> play());
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, dp(48), 1f);
        l2.leftMargin = dp(5);
        btnRow.addView(playButton, l2);
        root.addView(btnRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    private View cardsPage(boolean mods) {
        FrameLayout page = new FrameLayout(this);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(6));
        hs.addView(row, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        page.addView(hs, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView empty = text(mods
                ? "No mods yet.\nUse Add WADs / mods and pick a .wad or .pk3 add-on."
                : "No games.\nUse Add WADs and pick doom2.wad, doom.wad, freedoom2.wad ...",
                15, C_DIM, false);
        empty.setGravity(Gravity.CENTER);
        page.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        if (mods) { modsRow = row; modsEmpty = empty; } else { gamesRow = row; gamesEmpty = empty; }
        return page;
    }

    private View settingsPage() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(10), dp(20), dp(10));
        sv.addView(col);

        cbControls = new CheckBox(this);
        cbControls.setText("Show on-screen buttons");
        cbControls.setChecked(prefs.getBoolean("controls", true));
        tint(cbControls);
        col.addView(cbControls);

        cbHaptics = new CheckBox(this);
        cbHaptics.setText("Vibrate when a button is pressed");
        cbHaptics.setChecked(prefs.getBoolean("haptics", true));
        tint(cbHaptics);
        col.addView(cbHaptics);

        sensLabel = text("", 14, C_TEXT, false);
        sensLabel.setPadding(dp(4), dp(10), 0, 0);
        col.addView(sensLabel);
        sensBar = new SeekBar(this);
        sensBar.setMax(190);                         // 0.10 .. 2.00
        sensBar.setProgress(Math.round(prefs.getFloat("sens", 1.0f) * 100f) - 10);
        sensBar.setProgressTintList(ColorStateList.valueOf(C_BLUE));
        sensBar.setThumbTintList(ColorStateList.valueOf(C_BLUE));
        sensBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { updateSensLabel(); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        col.addView(sensBar);
        updateSensLabel();

        TextView info = text("In the game press the gear button to move, resize or hide the buttons.\n\n"
                + "Game files: " + wadsDir.getAbsolutePath() + "\n"
                + "Long press a card to delete the file.", 13, C_DIM, false);
        info.setPadding(dp(4), dp(14), 0, 0);
        info.setTextIsSelectable(true);
        col.addView(info);
        return sv;
    }

    private android.graphics.drawable.Drawable underline() {
        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[] { box(0x00000000, 0, 0), box(C_BLUE, 0, 0) });
        ld.setLayerHeight(1, dp(3));
        ld.setLayerGravity(1, Gravity.BOTTOM);
        return ld;
    }

    private void selectTab(int idx) {
        for (int i = 0; i < pages.length; i++) {
            pages[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            tabs[i].setTextColor(i == idx ? 0xFFFFFFFF : 0xFF9AA0AA);
            tabs[i].setBackground(i == idx ? underline() : null);
        }
    }

    private float sensValue() {
        return (sensBar.getProgress() + 10) / 100f;
    }

    private void updateSensLabel() {
        sensLabel.setText(String.format(Locale.US, "Look sensitivity: %.2f", sensValue()));
    }

    private void setStatus(final String s) {
        runOnUiThread(() -> status.setText(s));
    }

    // ------------------------------------------------------------------ cards

    private View buildCard(final File f, final boolean isMod, final int index) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(3), dp(3), dp(3), dp(3));
        card.setBackground(box(0xFFFFFFFF, 0xFFC9CCD3, 1));

        final ImageView cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.FIT_XY);
        cover.setImageBitmap(CoverLoader.placeholder(f.getName()));
        card.addView(cover, new LinearLayout.LayoutParams(dp(168), dp(126)));

        TextView name = text(prettyName(f.getName()), 13, C_TEXT, true);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(dp(4), dp(5), dp(4), 0);
        card.addView(name);

        TextView sub = text(sizeText(f.length()), 11, C_DIM, false);
        sub.setPadding(dp(4), 0, dp(4), 0);
        card.addView(sub);

        if (isMod) {
            CheckBox cb = new CheckBox(this);
            cb.setText("use");
            cb.setTextSize(13);
            cb.setTag("check");
            cb.setChecked(enabledMods.contains(f.getName()));
            tint(cb);
            cb.setOnClickListener(v -> {
                String n = f.getName();
                if (((CheckBox) v).isChecked()) enabledMods.add(n); else enabledMods.remove(n);
                styleCard((View) v.getParent(), enabledMods.contains(n));
            });
            card.addView(cb);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(10);
        card.setLayoutParams(lp);

        card.setOnClickListener(v -> {
            if (isMod) {
                CheckBox cb = (CheckBox) v.findViewWithTag("check");
                cb.setChecked(!cb.isChecked());
                String n = f.getName();
                if (cb.isChecked()) enabledMods.add(n); else enabledMods.remove(n);
                styleCard(v, cb.isChecked());
            } else {
                selectedIwad = index;
                for (int i = 0; i < iwadCards.size(); i++) styleCard(iwadCards.get(i), i == selectedIwad);
            }
        });
        card.setOnLongClickListener(v -> { confirmDelete(f); return true; });

        // the cover is read from the WAD in the background (and cached)
        worker.execute(() -> {
            final Bitmap bmp = CoverLoader.load(LauncherActivity.this, f, palette());
            runOnUiThread(() -> {
                if (!isDestroyed() && bmp != null) cover.setImageBitmap(bmp);
            });
        });
        return card;
    }

    private void styleCard(View card, boolean selected) {
        card.setBackground(selected ? box(0xFFFFFFFF, C_BLUE, 3) : box(0xFFFFFFFF, 0xFFC9CCD3, 1));
    }

    private byte[] palette() {
        if (!paletteReady) {
            synchronized (this) {
                if (!paletteReady) {
                    palette = CoverLoader.findPalette(iwadSnapshot);
                    paletteReady = true;
                }
            }
        }
        return palette;
    }

    private static String sizeText(long bytes) {
        if (bytes >= 1024L * 1024L) return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        return Math.max(1, bytes / 1024) + " KB";
    }

    private static String prettyName(String file) {
        String n = file.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        String key = dot > 0 ? n.substring(0, dot) : n;
        switch (key) {
            case "doom":       return "DOOM";
            case "doom1":      return "DOOM (Shareware)";
            case "doomu":      return "The Ultimate DOOM";
            case "doom2":      return "DOOM II: Hell on Earth";
            case "plutonia":   return "Final DOOM: The Plutonia Experiment";
            case "tnt":        return "Final DOOM: TNT Evilution";
            case "freedoom1":  return "Freedoom: Phase 1";
            case "freedoom2":  return "Freedoom: Phase 2";
            case "freedm":     return "FreeDM";
            case "heretic":    return "Heretic";
            case "hexen":      return "Hexen";
            case "strife1":    return "Strife";
            case "chex":       return "Chex Quest";
            case "chex3":      return "Chex Quest 3";
            case "hacx":       return "HacX";
            case "rotwb":      return "Rise of the Wool Ball";
            case "blasphemer": return "Blasphemer";
            default:
                String s = (dot > 0 ? file.substring(0, dot) : file).replace('_', ' ').replace('-', ' ');
                return s.isEmpty() ? file : s;
        }
    }

    // ------------------------------------------------------------------ assets

    private void installAssetsIfNeeded() {
        if (AssetInstaller.isInstalled(this, home)) {
            updateStatus();
            return;
        }
        busy = true;
        playButton.setEnabled(false);
        playButton.setAlpha(0.5f);
        setStatus("Preparing game files (first start)…");
        new Thread(() -> {
            String err = null;
            try {
                AssetInstaller.install(LauncherActivity.this, home);
            } catch (IOException e) {
                err = e.toString();
            }
            final String fe = err;
            runOnUiThread(() -> {
                busy = false;
                playButton.setEnabled(true);
                playButton.setAlpha(1f);
                if (fe != null) status.setText("Could not install game files: " + fe);
                else updateStatus();
            });
        }, "asset-install").start();
    }

    private void updateStatus() {
        status.setText(iwads.size() + " game(s)  ·  " + mods.size() + " add-on(s)");
    }

    // ------------------------------------------------------------------ file list

    private static boolean hasGameExt(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String e : GAME_EXT) if (n.endsWith(e)) return true;
        return false;
    }

    private static boolean isIwad(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        if (n.endsWith(".ipk3") || n.endsWith(".ipk7")) return true;
        if (!n.endsWith(".wad")) return false;
        try (InputStream in = new FileInputStream(f)) {
            byte[] h = new byte[4];
            return in.read(h) == 4 && h[0] == 'I' && h[1] == 'W' && h[2] == 'A' && h[3] == 'D';
        } catch (IOException e) {
            return false;
        }
    }

    private void refreshFiles() {
        iwads.clear();
        mods.clear();
        File[] all = wadsDir.listFiles();
        if (all != null) {
            Arrays.sort(all, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File f : all) {
                if (!f.isFile() || !hasGameExt(f.getName())) continue;
                if (isIwad(f)) iwads.add(f); else mods.add(f);
            }
        }
        iwadSnapshot = new ArrayList<>(iwads);
        paletteReady = false;

        String last = prefs.getString("iwad", "");
        selectedIwad = -1;
        for (int i = 0; i < iwads.size(); i++) if (iwads.get(i).getName().equals(last)) selectedIwad = i;
        if (selectedIwad < 0 && !iwads.isEmpty()) selectedIwad = 0;

        gamesRow.removeAllViews();
        iwadCards.clear();
        for (int i = 0; i < iwads.size(); i++) {
            View c = buildCard(iwads.get(i), false, i);
            styleCard(c, i == selectedIwad);
            iwadCards.add(c);
            gamesRow.addView(c);
        }
        gamesEmpty.setVisibility(iwads.isEmpty() ? View.VISIBLE : View.GONE);

        modsRow.removeAllViews();
        Set<String> existing = new HashSet<>();
        for (File f : mods) existing.add(f.getName());
        enabledMods.retainAll(existing);
        for (int i = 0; i < mods.size(); i++) {
            View c = buildCard(mods.get(i), true, i);
            styleCard(c, enabledMods.contains(mods.get(i).getName()));
            modsRow.addView(c);
        }
        modsEmpty.setVisibility(mods.isEmpty() ? View.VISIBLE : View.GONE);

        if (!busy) updateStatus();
    }

    private void confirmDelete(final File f) {
        new AlertDialog.Builder(this)
                .setTitle("Delete " + f.getName() + "?")
                .setPositiveButton("Delete", (d, w) -> {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                    refreshFiles();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------ importing files

    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null) return;
        final List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;

        busy = true;
        playButton.setEnabled(false);
        playButton.setAlpha(0.5f);
        new Thread(() -> {
            int ok = 0;
            String lastError = null;
            for (int i = 0; i < uris.size(); i++) {
                Uri u = uris.get(i);
                String name = displayName(u);
                setStatus("Copying " + name + " (" + (i + 1) + "/" + uris.size() + ")…");
                try {
                    copyUri(u, new File(wadsDir, name));
                    ok++;
                } catch (IOException e) {
                    lastError = name + ": " + e.getMessage();
                }
            }
            final int fok = ok;
            final String ferr = lastError;
            runOnUiThread(() -> {
                busy = false;
                playButton.setEnabled(true);
                playButton.setAlpha(1f);
                refreshFiles();
                if (ferr != null) status.setText("Copy failed - " + ferr);
                else status.setText("Added " + fok + " file(s)");
            });
        }, "import").start();
    }

    private String displayName(Uri u) {
        String name = null;
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        if (name == null || name.isEmpty()) {
            String p = u.getLastPathSegment();
            name = p != null ? p.substring(p.lastIndexOf('/') + 1) : "file.wad";
        }
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private void copyUri(Uri u, File dst) throws IOException {
        File tmp = new File(dst.getParentFile(), dst.getName() + ".part");
        try (InputStream in = getContentResolver().openInputStream(u);
             OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new IOException("cannot open");
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (dst.exists() && !dst.delete()) throw new IOException("cannot replace existing file");
        if (!tmp.renameTo(dst)) throw new IOException("rename failed");
    }

    // ------------------------------------------------------------------ launching

    private void savePrefs() {
        if (extraArgs == null) return;
        SharedPreferences.Editor e = prefs.edit();
        if (selectedIwad >= 0 && selectedIwad < iwads.size()) e.putString("iwad", iwads.get(selectedIwad).getName());
        e.putStringSet("mods", new HashSet<>(enabledMods));
        e.putBoolean("controls", cbControls.isChecked());
        e.putBoolean("haptics", cbHaptics.isChecked());
        e.putFloat("sens", sensValue());
        e.putString("extra", extraArgs.getText().toString());
        e.apply();
    }

    private static List<String> splitArgs(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quote = false, any = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"') { quote = !quote; any = true; }
            else if (Character.isWhitespace(ch) && !quote) {
                if (any || cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); any = false; }
            } else cur.append(ch);
        }
        if (any || cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** autoexec.cfg is run by the engine at start-up; it makes the touch buttons work with any config. */
    private void writeAutoexec() {
        File dir = new File(home, ".config/gzdoom");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String cfg =
                "// Generated by the Android launcher - edits are overwritten on every start\n"
              + "bind ctrl +attack\n"
              + "bind shift +speed\n"
              + "bind q weapprev\n"
              + "bind r weapnext\n"
              + "set freelook true\n"
              + "set use_mouse true\n";
        try (OutputStream out = new FileOutputStream(new File(dir, "autoexec.cfg"))) {
            out.write(cfg.getBytes("UTF-8"));
        } catch (IOException ignored) {
        }
    }

    private void play() {
        if (busy) return;
        if (iwads.isEmpty() || selectedIwad < 0) {
            selectTab(0);
            Toast.makeText(this, "Add an IWAD first (doom2.wad, freedoom2.wad, …)", Toast.LENGTH_LONG).show();
            return;
        }
        if (!AssetInstaller.isInstalled(this, home)) {
            Toast.makeText(this, "Game files are not ready yet", Toast.LENGTH_SHORT).show();
            installAssetsIfNeeded();
            return;
        }
        savePrefs();
        File iwad = iwads.get(Math.min(selectedIwad, iwads.size() - 1));

        List<String> args = new ArrayList<>();
        args.add("-androidhome");
        args.add(home.getAbsolutePath());
        args.add("-iwad");
        args.add(iwad.getAbsolutePath());
        List<String> files = new ArrayList<>();
        for (File m : mods) {
            if (enabledMods.contains(m.getName())) files.add(m.getAbsolutePath());
        }
        if (!files.isEmpty()) {
            args.add("-file");
            args.addAll(files);
        }
        args.addAll(splitArgs(extraArgs.getText().toString()));

        writeAutoexec();

        Intent i = new Intent(this, GZDoomActivity.class);
        i.putExtra(GZDoomActivity.EXTRA_ARGS, args.toArray(new String[0]));
        i.putExtra(GZDoomActivity.EXTRA_LOOK_SENS, sensValue());
        i.putExtra(GZDoomActivity.EXTRA_HAPTICS, cbHaptics.isChecked());
        i.putExtra(GZDoomActivity.EXTRA_CONTROLS, cbControls.isChecked());
        startActivity(i);
    }
}
