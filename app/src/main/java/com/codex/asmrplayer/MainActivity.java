package com.codex.asmrplayer;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int REQ_OPEN_TREE = 1001;
    private static final int MAX_SCAN_DEPTH = 8;
    private static final int PAGE_WORKS = 0;
    private static final int PAGE_TRACKS = 1;
    private static final int PAGE_PLAYER = 2;
    private static final int PAGE_LYRICS = 3;
    private static final String PREFS = "asmr_pocket_prefs";
    private static final String KEY_TREE_URI = "tree_uri";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<WorkItem> works = new ArrayList<>();
    private final List<MediaItem> playlist = new ArrayList<>();
    private final List<Cue> cues = new ArrayList<>();
    private final BrowserAdapter browserAdapter = new BrowserAdapter();
    private final LyricsAdapter lyricsAdapter = new LyricsAdapter();
    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            handler.postDelayed(this, 250);
        }
    };

    private MediaPlayer player;
    private WorkItem activeWork;
    private int currentIndex = -1;
    private int currentCueIndex = -1;
    private boolean userSeeking;
    private int pageMode = PAGE_WORKS;

    private LinearLayout playerArea;
    private ListView browserList;
    private ListView lyricsList;
    private TextView sectionTitleView;
    private TextView sectionSubtitleView;
    private ImageView coverView;
    private TextView titleView;
    private TextView folderView;
    private TextView lyricView;
    private TextView timeView;
    private TextView statusView;
    private SeekBar seekBar;
    private Button playButton;
    private Button lyricsButton;
    private Button backButton;
    private LinearLayout miniPlayer;
    private TextView miniTitleView;
    private TextView miniTimeView;
    private SeekBar miniSeekBar;
    private Button miniPlayButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(16, 18, 21));
        window.setNavigationBarColor(Color.rgb(16, 18, 21));
        buildUi();

        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TREE_URI, null);
        if (!TextUtils.isEmpty(saved)) {
            scanFolder(Uri.parse(saved));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(progressTick);
        releasePlayer();
    }

    @Override
    public void onBackPressed() {
        if (pageMode != PAGE_WORKS) {
            goBackPage();
            return;
        }
        super.onBackPressed();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(16, 18, 21));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(24), dp(24), dp(22));
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(top, new LinearLayout.LayoutParams(-1, dp(44)));

        TextView appName = new TextView(this);
        appName.setText("白沫播放器");
        appName.setTextColor(Color.WHITE);
        appName.setTextSize(20);
        appName.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(appName, new LinearLayout.LayoutParams(0, -1, 1));

        backButton = new Button(this);
        backButton.setText("返回");
        backButton.setTextColor(Color.WHITE);
        backButton.setTextSize(14);
        backButton.setAllCaps(false);
        backButton.setVisibility(View.GONE);
        backButton.setBackgroundResource(R.drawable.button_icon);
        backButton.setOnClickListener(v -> goBackPage());
        top.addView(backButton, new LinearLayout.LayoutParams(dp(72), dp(40)));

        Button openButton = new Button(this);
        openButton.setText("选择文件夹");
        openButton.setTextColor(Color.WHITE);
        openButton.setTextSize(14);
        openButton.setAllCaps(false);
        openButton.setBackgroundResource(R.drawable.button_primary);
        openButton.setOnClickListener(v -> openFolderPicker());
        LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(dp(112), dp(40));
        openParams.leftMargin = dp(8);
        top.addView(openButton, openParams);

        LinearLayout sectionHeader = new LinearLayout(this);
        sectionHeader.setOrientation(LinearLayout.VERTICAL);
        sectionHeader.setPadding(0, dp(18), 0, dp(4));
        page.addView(sectionHeader, new LinearLayout.LayoutParams(-1, dp(82)));

        sectionTitleView = label("作品列表", 22, Color.WHITE);
        sectionTitleView.setSingleLine(true);
        sectionTitleView.setEllipsize(TextUtils.TruncateAt.END);
        sectionHeader.addView(sectionTitleView, new LinearLayout.LayoutParams(-1, 0, 1));

        sectionSubtitleView = label("请选择文件夹，自动识别 ASMR 作品", 13, Color.rgb(184, 193, 202));
        sectionSubtitleView.setSingleLine(true);
        sectionSubtitleView.setEllipsize(TextUtils.TruncateAt.END);
        sectionHeader.addView(sectionSubtitleView, new LinearLayout.LayoutParams(-1, 0, 1));

        playerArea = new LinearLayout(this);
        playerArea.setOrientation(LinearLayout.VERTICAL);
        playerArea.setVisibility(View.GONE);
        page.addView(playerArea, new LinearLayout.LayoutParams(-1, 0, 1.45f));

        coverView = new ImageView(this);
        coverView.setBackgroundColor(Color.rgb(31, 35, 41));
        coverView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams coverParams = new LinearLayout.LayoutParams(-1, 0);
        coverParams.weight = 1f;
        coverParams.topMargin = dp(18);
        playerArea.addView(coverView, coverParams);

        LinearLayout infoPanel = panel();
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(-1, dp(132));
        infoParams.topMargin = dp(12);
        playerArea.addView(infoPanel, infoParams);

        titleView = label("请选择包含 RJ 作品文件夹的目录", 18, Color.WHITE);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        infoPanel.addView(titleView, new LinearLayout.LayoutParams(-1, dp(30)));

        folderView = label("会递归识别 mp3/wav、图片和 .vtt，并自动按作品分组。", 13, Color.rgb(184, 193, 202));
        folderView.setSingleLine(true);
        folderView.setEllipsize(TextUtils.TruncateAt.END);
        infoPanel.addView(folderView, new LinearLayout.LayoutParams(-1, dp(25)));

        lyricView = label("", 15, Color.rgb(234, 239, 236));
        lyricView.setGravity(Gravity.CENTER);
        lyricView.setMaxLines(3);
        infoPanel.addView(lyricView, new LinearLayout.LayoutParams(-1, 0, 1));

        seekBar = new SeekBar(this);
        seekBar.setProgressDrawable(getDrawable(R.drawable.seekbar_progress));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && player != null) {
                    timeView.setText(formatTime(progress) + " / " + formatTime(player.getDuration()));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                if (player != null) {
                    player.seekTo(bar.getProgress());
                }
                userSeeking = false;
            }
        });
        playerArea.addView(seekBar, new LinearLayout.LayoutParams(-1, dp(40)));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        playerArea.addView(controls, new LinearLayout.LayoutParams(-1, dp(62)));

        controls.addView(iconButton("⏮", v -> playRelative(-1, true)), buttonParams());
        controls.addView(iconButton("-15", v -> seekBy(-15000)), buttonParams());
        playButton = new Button(this);
        playButton.setText("播放");
        playButton.setTextColor(Color.WHITE);
        playButton.setTextSize(16);
        playButton.setAllCaps(false);
        playButton.setBackgroundResource(R.drawable.button_primary);
        playButton.setOnClickListener(v -> togglePlayback());
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(dp(68), dp(48));
        playParams.leftMargin = dp(3);
        playParams.rightMargin = dp(3);
        controls.addView(playButton, playParams);
        controls.addView(iconButton("+15", v -> seekBy(15000)), buttonParams());
        controls.addView(iconButton("⏭", v -> playRelative(1, true)), buttonParams());
        lyricsButton = new Button(this);
        lyricsButton.setText("台词");
        lyricsButton.setTextColor(Color.WHITE);
        lyricsButton.setTextSize(13);
        lyricsButton.setAllCaps(false);
        lyricsButton.setBackgroundResource(R.drawable.button_icon);
        lyricsButton.setOnClickListener(v -> showLyrics());
        LinearLayout.LayoutParams lyricsParams = new LinearLayout.LayoutParams(dp(48), dp(44));
        lyricsParams.leftMargin = dp(2);
        controls.addView(lyricsButton, lyricsParams);

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        playerArea.addView(meta, new LinearLayout.LayoutParams(-1, dp(30)));

        timeView = label("00:00 / 00:00", 12, Color.rgb(184, 193, 202));
        meta.addView(timeView, new LinearLayout.LayoutParams(0, -1, 1));
        statusView = label("未加载", 12, Color.rgb(184, 193, 202));
        statusView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        meta.addView(statusView, new LinearLayout.LayoutParams(0, -1, 1));

        browserList = new ListView(this);
        browserList.setDivider(new ColorDrawable(Color.rgb(45, 51, 59)));
        browserList.setDividerHeight(1);
        browserList.setCacheColorHint(Color.TRANSPARENT);
        browserList.setBackgroundColor(Color.TRANSPARENT);
        browserList.setClipToPadding(false);
        browserList.setPadding(0, dp(16), 0, dp(20));
        browserList.setAdapter(browserAdapter);
        browserList.setOnItemClickListener(this::onListItemClick);
        page.addView(browserList, new LinearLayout.LayoutParams(-1, 0, 1.7f));

        lyricsList = new ListView(this);
        lyricsList.setDivider(new ColorDrawable(Color.rgb(45, 51, 59)));
        lyricsList.setDividerHeight(1);
        lyricsList.setCacheColorHint(Color.TRANSPARENT);
        lyricsList.setBackgroundColor(Color.TRANSPARENT);
        lyricsList.setClipToPadding(false);
        lyricsList.setPadding(0, dp(16), 0, dp(24));
        lyricsList.setVisibility(View.GONE);
        lyricsList.setAdapter(lyricsAdapter);
        lyricsList.setOnItemClickListener((parent, view, position, id) -> seekToCue(position));
        page.addView(lyricsList, new LinearLayout.LayoutParams(-1, 0, 1.7f));

        miniPlayer = new LinearLayout(this);
        miniPlayer.setOrientation(LinearLayout.VERTICAL);
        miniPlayer.setPadding(dp(10), dp(8), dp(10), dp(8));
        miniPlayer.setBackgroundResource(R.drawable.panel);
        miniPlayer.setVisibility(View.GONE);
        miniPlayer.setOnClickListener(v -> showPlayer());
        LinearLayout.LayoutParams miniParams = new LinearLayout.LayoutParams(-1, dp(104));
        miniParams.topMargin = dp(10);
        page.addView(miniPlayer, miniParams);

        LinearLayout miniTop = new LinearLayout(this);
        miniTop.setOrientation(LinearLayout.HORIZONTAL);
        miniTop.setGravity(Gravity.CENTER_VERTICAL);
        miniPlayer.addView(miniTop, new LinearLayout.LayoutParams(-1, dp(40)));

        miniTitleView = label("未播放", 13, Color.rgb(236, 240, 243));
        miniTitleView.setSingleLine(true);
        miniTitleView.setEllipsize(TextUtils.TruncateAt.END);
        miniTop.addView(miniTitleView, new LinearLayout.LayoutParams(0, -1, 1));

        miniTop.addView(iconButton("⏮", v -> playRelative(-1, false)), new LinearLayout.LayoutParams(dp(42), dp(36)));
        miniPlayButton = new Button(this);
        miniPlayButton.setText("暂停");
        miniPlayButton.setTextColor(Color.WHITE);
        miniPlayButton.setTextSize(13);
        miniPlayButton.setAllCaps(false);
        miniPlayButton.setBackgroundResource(R.drawable.button_primary);
        miniPlayButton.setOnClickListener(v -> togglePlayback());
        LinearLayout.LayoutParams miniPlayParams = new LinearLayout.LayoutParams(dp(64), dp(36));
        miniPlayParams.leftMargin = dp(6);
        miniPlayParams.rightMargin = dp(6);
        miniTop.addView(miniPlayButton, miniPlayParams);
        miniTop.addView(iconButton("⏭", v -> playRelative(1, false)), new LinearLayout.LayoutParams(dp(42), dp(36)));

        miniSeekBar = new SeekBar(this);
        miniSeekBar.setProgressDrawable(getDrawable(R.drawable.seekbar_progress));
        miniSeekBar.setOnTouchListener((v, event) -> {
            showPlayer();
            return true;
        });
        miniPlayer.addView(miniSeekBar, new LinearLayout.LayoutParams(-1, dp(34)));

        miniTimeView = label("00:00 / 00:00", 12, Color.rgb(184, 193, 202));
        miniTimeView.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        miniPlayer.addView(miniTimeView, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(10), dp(12), dp(10));
        panel.setBackgroundResource(R.drawable.panel);
        return panel;
    }

    private TextView label(String text, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(38), dp(44));
        params.leftMargin = dp(2);
        params.rightMargin = dp(2);
        return params;
    }

    private ImageButton iconButton(String text, View.OnClickListener listener) {
        ImageButton button = new ImageButton(this);
        button.setContentDescription(text);
        button.setBackgroundResource(R.drawable.button_icon);
        button.setImageDrawable(new TextDrawable(text));
        button.setOnClickListener(listener);
        return button;
    }

    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQ_OPEN_TREE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OPEN_TREE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) {
                return;
            }
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_TREE_URI, uri.toString()).apply();
            scanFolder(uri);
        }
    }

    private void scanFolder(Uri treeUri) {
        works.clear();
        playlist.clear();
        cues.clear();
        activeWork = null;
        currentIndex = -1;
        releasePlayer();
        resetPlaybackUi();
        updateMiniPlayer();

        String treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
        String selectedName = displayNameFromTreeId(treeDocumentId);
        List<FileDoc> files = listTreeFilesRecursive(treeUri, treeDocumentId);
        List<WorkBuilder> builders = buildWorks(files, selectedName);

        final Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(builders, (left, right) -> collator.compare(left.name, right.name));
        for (WorkBuilder builder : builders) {
            WorkItem work = builder.toWork(collator);
            if (!work.tracks.isEmpty()) {
                works.add(work);
            }
        }

        pageMode = PAGE_WORKS;
        browserAdapter.notifyDataSetChanged();
        updatePageChrome(false);
        folderView.setText(treeUri.toString());
        statusView.setText(works.size() + " 个作品");
        if (works.isEmpty()) {
            sectionTitleView.setText("作品列表");
            sectionSubtitleView.setText("没有识别到可播放作品");
            titleView.setText("没有识别到 ASMR 音频作品");
            lyricView.setText("请选择包含 RJ 文件夹或音频文件的目录");
        } else {
            sectionTitleView.setText("作品列表");
            sectionSubtitleView.setText("已识别 " + works.size() + " 个作品");
            titleView.setText("已识别 " + works.size() + " 个作品");
            lyricView.setText("点击作品查看音轨并播放");
            showWorkPreview(works.get(0));
        }
    }

    private List<WorkBuilder> buildWorks(List<FileDoc> files, String selectedName) {
        Map<String, WorkBuilder> map = new LinkedHashMap<>();
        boolean selectedLooksLikeWork = looksLikeWorkName(selectedName);

        for (FileDoc file : files) {
            if (!isSupportedAsset(file.name)) {
                continue;
            }
            String key = selectedLooksLikeWork ? "" : firstPathSegment(file.relativePath);
            String name = selectedLooksLikeWork || TextUtils.isEmpty(key) ? selectedName : key;
            WorkBuilder builder = map.get(key);
            if (builder == null) {
                builder = new WorkBuilder(name);
                map.put(key, builder);
            }
            builder.add(file);
        }

        if (map.size() == 1 && !hasRootAudio(files) && !selectedLooksLikeWork) {
            WorkBuilder only = map.values().iterator().next();
            if (!looksLikeWorkName(only.name) && looksLikeWorkName(selectedName)) {
                only.name = selectedName;
            }
        }
        return new ArrayList<>(map.values());
    }

    private boolean hasRootAudio(List<FileDoc> files) {
        for (FileDoc file : files) {
            if (isAudio(file.name) && firstPathSegment(file.relativePath).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private List<FileDoc> listTreeFilesRecursive(Uri treeUri, String treeDocumentId) {
        List<FileDoc> files = new ArrayList<>();
        scanDocumentChildren(treeUri, treeDocumentId, treeDocumentId, "", 0, files);
        return files;
    }

    private void scanDocumentChildren(Uri treeUri, String rootId, String documentId, String relativeDir, int depth, List<FileDoc> files) {
        if (depth > MAX_SCAN_DEPTH) {
            return;
        }
        ContentResolver resolver = getContentResolver();
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        String[] projection = new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        try (Cursor cursor = resolver.query(childrenUri, projection, null, null, null)) {
            if (cursor == null) {
                return;
            }
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                if (TextUtils.isEmpty(name)) {
                    continue;
                }
                String relativePath = relativeDir.isEmpty() ? name : relativeDir + "/" + name;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    scanDocumentChildren(treeUri, rootId, id, relativePath, depth + 1, files);
                } else {
                    Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                    files.add(new FileDoc(name, mime, docUri, relativePath, relativeDir));
                }
            }
        } catch (Exception ex) {
            Toast.makeText(this, "读取文件夹失败: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void onListItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (pageMode == PAGE_WORKS) {
            openWork(works.get(position));
        } else if (pageMode == PAGE_TRACKS) {
            playAt(position, true);
        }
    }

    private void openWork(WorkItem work) {
        WorkItem previousWork = activeWork;
        boolean keepCurrentPlayback = player != null && currentIndex >= 0 && previousWork == work;
        if (player != null && !keepCurrentPlayback) {
            releasePlayer();
        }
        activeWork = work;
        playlist.clear();
        playlist.addAll(work.tracks);
        pageMode = PAGE_TRACKS;
        updatePageChrome(true);
        browserAdapter.notifyDataSetChanged();
        if (!keepCurrentPlayback) {
            currentIndex = -1;
            resetPlaybackUi();
        }
        sectionTitleView.setText(work.name);
        sectionSubtitleView.setText("选择音轨进入播放页");
        titleView.setText(work.name);
        folderView.setText(work.tracks.size() + " 首音轨");
        lyricView.setText("点击音轨开始播放");
        if (work.cover != null) {
            coverView.setImageURI(work.cover.uri);
        }
        statusView.setText("作品已打开");
        updateMiniPlayer();
    }

    private void showWorks() {
        pageMode = PAGE_WORKS;
        updatePageChrome(true);
        browserAdapter.notifyDataSetChanged();
        sectionTitleView.setText("作品列表");
        sectionSubtitleView.setText(works.size() + " 个作品");
        titleView.setText(works.isEmpty() ? "没有识别到 ASMR 音频作品" : "作品列表");
        folderView.setText(works.size() + " 个作品");
        lyricView.setText(works.isEmpty() ? "请选择包含 RJ 文件夹或音频文件的目录" : "点击作品查看音轨并播放");
        if (activeWork != null && activeWork.cover != null) {
            coverView.setImageURI(activeWork.cover.uri);
        }
        updateMiniPlayer();
    }

    private void showTracks() {
        if (activeWork == null) {
            showWorks();
            return;
        }
        pageMode = PAGE_TRACKS;
        updatePageChrome(true);
        browserAdapter.notifyDataSetChanged();
        sectionTitleView.setText(activeWork.name);
        sectionSubtitleView.setText("音轨列表");
        titleView.setText(activeWork.name);
        folderView.setText(activeWork.tracks.size() + " 首音轨");
        lyricView.setText(currentIndex >= 0 ? "正在播放: " + playlist.get(currentIndex).audio.name : "点击音轨进入播放页");
        statusView.setText(currentIndex >= 0 && player != null && player.isPlaying() ? "播放中" : "音轨列表");
        if (activeWork.cover != null) {
            coverView.setImageURI(activeWork.cover.uri);
        }
        updateMiniPlayer();
    }

    private void showPlayer() {
        pageMode = PAGE_PLAYER;
        updatePageChrome(true);
        browserAdapter.notifyDataSetChanged();
        sectionTitleView.setText("正在播放");
        sectionSubtitleView.setText(activeWork == null ? "" : activeWork.name);
        updateMiniPlayer();
    }

    private void showLyrics() {
        if (cues.isEmpty()) {
            Toast.makeText(this, "当前音轨没有可查看的 .vtt 台词", Toast.LENGTH_SHORT).show();
            return;
        }
        pageMode = PAGE_LYRICS;
        updatePageChrome(true);
        lyricsAdapter.notifyDataSetChanged();
        sectionTitleView.setText("台词");
        sectionSubtitleView.setText(currentIndex >= 0 ? playlist.get(currentIndex).audio.name : "点击台词跳转播放位置");
        if (currentCueIndex >= 0) {
            lyricsList.post(() -> lyricsList.smoothScrollToPosition(currentCueIndex));
        }
        updateMiniPlayer();
    }

    private void goBackPage() {
        if (pageMode == PAGE_PLAYER) {
            showTracks();
        } else if (pageMode == PAGE_LYRICS) {
            showPlayer();
        } else if (pageMode == PAGE_TRACKS) {
            showWorks();
        }
    }

    private void updatePageChrome(boolean animate) {
        boolean playerVisible = pageMode == PAGE_PLAYER;
        boolean lyricsVisible = pageMode == PAGE_LYRICS;
        boolean listVisible = pageMode == PAGE_WORKS || pageMode == PAGE_TRACKS;
        backButton.setVisibility(pageMode == PAGE_WORKS ? View.GONE : View.VISIBLE);
        playerArea.setVisibility(playerVisible ? View.VISIBLE : View.GONE);
        browserList.setVisibility(listVisible ? View.VISIBLE : View.GONE);
        lyricsList.setVisibility(lyricsVisible ? View.VISIBLE : View.GONE);
        updateMiniPlayer();
        if (animate) {
            View target = playerVisible ? playerArea : (lyricsVisible ? lyricsList : browserList);
            target.setAlpha(0f);
            target.animate().alpha(1f).setDuration(180).start();
        }
    }

    private void showWorkPreview(WorkItem work) {
        if (work.cover != null) {
            coverView.setImageURI(work.cover.uri);
        } else {
            coverView.setImageDrawable(null);
        }
    }

    private void playAt(int index) {
        playAt(index, true);
    }

    private void playAt(int index, boolean openPlayerPage) {
        if (index < 0 || index >= playlist.size()) {
            return;
        }
        currentIndex = index;
        if (openPlayerPage) {
            showPlayer();
        }
        browserAdapter.notifyDataSetChanged();
        MediaItem item = playlist.get(index);
        titleView.setText(item.audio.name);
        folderView.setText(activeWork == null ? item.audio.parentPath : activeWork.name);
        lyricView.setText(item.vtt == null ? "未找到同名 .vtt 字幕" : "字幕已载入");
        if (item.image != null) {
            coverView.setImageURI(item.image.uri);
        } else if (activeWork != null && activeWork.cover != null) {
            coverView.setImageURI(activeWork.cover.uri);
        } else {
            coverView.setImageDrawable(null);
        }
        loadCues(item.vtt);
        currentCueIndex = -1;
        lyricsAdapter.notifyDataSetChanged();
        releasePlayer();
        player = new MediaPlayer();
        try {
            player.setDataSource(this, item.audio.uri);
            player.setOnPreparedListener(mp -> {
                seekBar.setMax(mp.getDuration());
                timeView.setText("00:00 / " + formatTime(mp.getDuration()));
                mp.start();
                playButton.setText("暂停");
                miniPlayButton.setText("暂停");
                statusView.setText("播放中");
                handler.removeCallbacks(progressTick);
                handler.post(progressTick);
                updateMiniPlayer();
            });
            player.setOnCompletionListener(mp -> playRelative(1, pageMode == PAGE_PLAYER));
            player.prepareAsync();
            statusView.setText("加载中");
            updateMiniPlayer();
        } catch (Exception ex) {
            Toast.makeText(this, "无法播放: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            statusView.setText("播放失败");
            updateMiniPlayer();
        }
    }

    private void togglePlayback() {
        if (player == null) {
            if (!playlist.isEmpty()) {
                playAt(currentIndex >= 0 ? currentIndex : 0, true);
            } else if (!works.isEmpty() && pageMode == PAGE_WORKS) {
                openWork(works.get(0));
            }
            return;
        }
        if (player.isPlaying()) {
            player.pause();
            playButton.setText("播放");
            miniPlayButton.setText("播放");
            statusView.setText("已暂停");
        } else {
            player.start();
            playButton.setText("暂停");
            miniPlayButton.setText("暂停");
            statusView.setText("播放中");
            handler.post(progressTick);
        }
        updateMiniPlayer();
    }

    private void playRelative(int delta) {
        playRelative(delta, true);
    }

    private void playRelative(int delta, boolean openPlayerPage) {
        if (playlist.isEmpty()) {
            return;
        }
        int next = currentIndex + delta;
        if (next < 0) {
            next = playlist.size() - 1;
        } else if (next >= playlist.size()) {
            next = 0;
        }
        playAt(next, openPlayerPage);
    }

    private void seekBy(int millis) {
        if (player == null) {
            return;
        }
        int target = Math.max(0, Math.min(player.getDuration(), player.getCurrentPosition() + millis));
        player.seekTo(target);
        updateProgress();
    }

    private void updateProgress() {
        if (player == null) {
            return;
        }
        try {
            int position = player.getCurrentPosition();
            int duration = player.getDuration();
            if (!userSeeking) {
                seekBar.setMax(duration);
                seekBar.setProgress(position);
            }
            timeView.setText(formatTime(position) + " / " + formatTime(duration));
            miniSeekBar.setMax(duration);
            miniSeekBar.setProgress(position);
            miniTimeView.setText(formatTime(position) + " / " + formatTime(duration));
            updateLyric(position);
            if (player.isPlaying()) {
                statusView.setText("播放中");
            }
            updateMiniPlayer();
        } catch (IllegalStateException ignored) {
        }
    }

    private void updateLyric(int positionMs) {
        if (cues.isEmpty()) {
            return;
        }
        for (int i = 0; i < cues.size(); i++) {
            Cue cue = cues.get(i);
            if (positionMs >= cue.startMs && positionMs <= cue.endMs) {
                if (!cue.text.contentEquals(lyricView.getText())) {
                    lyricView.setText(cue.text);
                }
                if (currentCueIndex != i) {
                    currentCueIndex = i;
                    lyricsAdapter.notifyDataSetChanged();
                    if (pageMode == PAGE_LYRICS) {
                        lyricsList.smoothScrollToPosition(i);
                    }
                }
                return;
            }
        }
        lyricView.setText("");
        if (currentCueIndex != -1) {
            currentCueIndex = -1;
            lyricsAdapter.notifyDataSetChanged();
        }
    }

    private void loadCues(FileDoc vtt) {
        cues.clear();
        if (vtt == null) {
            return;
        }
        try (InputStream input = getContentResolver().openInputStream(vtt.uri)) {
            if (input == null) {
                return;
            }
            String text = new String(readAll(input), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .replace("\r", "\n");
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            String[] blocks = text.split("\n\n+");
            for (String block : blocks) {
                parseCueBlock(block.trim());
            }
            lyricsAdapter.notifyDataSetChanged();
        } catch (Exception ex) {
            Toast.makeText(this, "字幕读取失败: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void seekToCue(int position) {
        if (position < 0 || position >= cues.size() || player == null) {
            return;
        }
        Cue cue = cues.get(position);
        player.seekTo((int) cue.startMs);
        if (!player.isPlaying()) {
            player.start();
        }
        currentCueIndex = position;
        lyricView.setText(cue.text);
        playButton.setText("暂停");
        miniPlayButton.setText("暂停");
        statusView.setText("播放中");
        handler.post(progressTick);
        lyricsAdapter.notifyDataSetChanged();
        updateProgress();
    }

    private void parseCueBlock(String block) {
        if (block.isEmpty() || block.equals("WEBVTT") || block.startsWith("NOTE")) {
            return;
        }
        String[] lines = block.split("\n");
        int timeLine = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("-->")) {
                timeLine = i;
                break;
            }
        }
        if (timeLine < 0) {
            return;
        }
        String[] parts = lines[timeLine].split("-->");
        if (parts.length < 2) {
            return;
        }
        long start = parseTimestamp(parts[0].trim());
        long end = parseTimestamp(parts[1].trim().split("\\s+")[0]);
        if (end <= start) {
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = timeLine + 1; i < lines.length; i++) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(lines[i].replaceAll("<[^>]+>", "").trim());
        }
        String cueText = builder.toString().trim();
        if (!cueText.isEmpty()) {
            cues.add(new Cue(start, end, cueText));
        }
    }

    private long parseTimestamp(String raw) {
        String timestamp = raw.replace(',', '.');
        String[] parts = timestamp.split(":");
        try {
            double seconds;
            if (parts.length == 3) {
                seconds = Integer.parseInt(parts[0]) * 3600
                        + Integer.parseInt(parts[1]) * 60
                        + Double.parseDouble(parts[2]);
            } else if (parts.length == 2) {
                seconds = Integer.parseInt(parts[0]) * 60
                        + Double.parseDouble(parts[1]);
            } else {
                seconds = Double.parseDouble(parts[0]);
            }
            return (long) (seconds * 1000);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private void resetPlaybackUi() {
        coverView.setImageDrawable(null);
        lyricView.setText("");
        seekBar.setProgress(0);
        seekBar.setMax(0);
        miniSeekBar.setProgress(0);
        miniSeekBar.setMax(0);
        miniTimeView.setText("00:00 / 00:00");
        timeView.setText("00:00 / 00:00");
        playButton.setText("播放");
        miniPlayButton.setText("播放");
        updateMiniPlayer();
    }

    private void releasePlayer() {
        handler.removeCallbacks(progressTick);
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
        updateMiniPlayer();
    }

    private void updateMiniPlayer() {
        if (miniPlayer == null) {
            return;
        }
        boolean hasPlayback = player != null && currentIndex >= 0 && currentIndex < playlist.size();
        boolean showMini = hasPlayback && pageMode != PAGE_PLAYER;
        miniPlayer.setVisibility(showMini ? View.VISIBLE : View.GONE);
        if (!hasPlayback) {
            return;
        }
        MediaItem item = playlist.get(currentIndex);
        miniTitleView.setText(item.audio.name);
        boolean playing = false;
        try {
            playing = player.isPlaying();
        } catch (IllegalStateException ignored) {
        }
        miniPlayButton.setText(playing ? "暂停" : "播放");
        playButton.setText(playing ? "暂停" : "播放");
    }

    private boolean isSupportedAsset(String name) {
        return isAudio(name) || isImage(name.toLowerCase(Locale.ROOT)) || name.toLowerCase(Locale.ROOT).endsWith(".vtt");
    }

    private boolean isAudio(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp3") || lower.endsWith(".wav");
    }

    private boolean isImage(String lowerName) {
        return lowerName.endsWith(".jpg")
                || lowerName.endsWith(".jpeg")
                || lowerName.endsWith(".png")
                || lowerName.endsWith(".webp")
                || lowerName.endsWith(".bmp");
    }

    private boolean looksLikeWorkName(String name) {
        if (TextUtils.isEmpty(name)) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.matches(".*(RJ|VJ|BJ)\\d{5,}.*");
    }

    private String firstPathSegment(String relativePath) {
        int slash = relativePath.indexOf('/');
        return slash >= 0 ? relativePath.substring(0, slash) : "";
    }

    private String displayNameFromTreeId(String treeDocumentId) {
        int colon = treeDocumentId.indexOf(':');
        String path = colon >= 0 ? treeDocumentId.substring(colon + 1) : treeDocumentId;
        if (TextUtils.isEmpty(path)) {
            return "已选文件夹";
        }
        String[] parts = path.split("/");
        return parts.length == 0 ? path : parts[parts.length - 1];
    }

    private String stem(String name) {
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name).toLowerCase(Locale.ROOT);
    }

    private String formatTime(int millis) {
        int total = Math.max(0, millis / 1000);
        int minutes = total / 60;
        int seconds = total % 60;
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private class BrowserAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return pageMode == PAGE_WORKS ? works.size() : playlist.size();
        }

        @Override
        public Object getItem(int position) {
            return pageMode == PAGE_WORKS ? works.get(position) : playlist.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (pageMode == PAGE_WORKS) {
                return workRow(works.get(position), convertView);
            }
            return trackRow(playlist.get(position), position, convertView);
        }

        private View workRow(WorkItem work, View convertView) {
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(8), dp(8), dp(8));
            row.setMinimumHeight(dp(88));

            ImageView thumb = new ImageView(MainActivity.this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackgroundColor(Color.rgb(31, 35, 41));
            if (work.cover != null) {
                thumb.setImageURI(work.cover.uri);
            } else {
                thumb.setImageDrawable(new TextDrawable("ASMR"));
            }
            row.addView(thumb, new LinearLayout.LayoutParams(dp(72), dp(72)));

            LinearLayout texts = new LinearLayout(MainActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setGravity(Gravity.CENTER_VERTICAL);
            texts.setPadding(dp(12), 0, 0, 0);
            row.addView(texts, new LinearLayout.LayoutParams(0, -1, 1));

            TextView title = label(work.name, 16, Color.rgb(236, 240, 243));
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title, new LinearLayout.LayoutParams(-1, 0, 1));

            String coverState = work.cover == null ? "无封面" : "有封面";
            TextView sub = label(work.tracks.size() + " 首音轨 · " + coverState, 13, Color.rgb(160, 169, 178));
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(sub, new LinearLayout.LayoutParams(-1, 0, 1));

            return row;
        }

        private View trackRow(MediaItem item, int position, View convertView) {
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(7), dp(12), dp(7));
            row.setMinimumHeight(dp(58));

            int titleColor = position == currentIndex ? Color.rgb(143, 210, 182) : Color.rgb(230, 233, 237);
            TextView title = label(item.audio.name, 15, titleColor);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(title, new LinearLayout.LayoutParams(-1, 0, 1));

            String subtitle = (item.vtt == null ? "无字幕" : "VTT") + " · " + item.audio.parentPath;
            TextView sub = label(subtitle, 12, Color.rgb(160, 169, 178));
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(sub, new LinearLayout.LayoutParams(-1, 0, 1));
            return row;
        }
    }

    private class LyricsAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return cues.size();
        }

        @Override
        public Object getItem(int position) {
            return cues.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Cue cue = cues.get(position);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setMinimumHeight(dp(72));

            boolean active = position == currentCueIndex;
            TextView time = label(formatTime((int) cue.startMs), 12, active ? Color.rgb(143, 210, 182) : Color.rgb(137, 146, 156));
            row.addView(time, new LinearLayout.LayoutParams(-1, dp(22)));

            TextView text = label(cue.text, active ? 17 : 15, active ? Color.WHITE : Color.rgb(196, 204, 212));
            text.setGravity(Gravity.CENTER_VERTICAL);
            text.setMaxLines(4);
            text.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(text, new LinearLayout.LayoutParams(-1, -2));
            return row;
        }
    }

    private static class WorkBuilder {
        String name;
        final List<FileDoc> audio = new ArrayList<>();
        final List<FileDoc> images = new ArrayList<>();
        final Map<String, FileDoc> vttByName = new HashMap<>();
        final Map<String, FileDoc> imageByStem = new HashMap<>();

        WorkBuilder(String name) {
            this.name = name;
        }

        void add(FileDoc file) {
            String lower = file.name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".vtt")) {
                vttByName.put(lower, file);
                vttByName.put(stemStatic(file.name), file);
            } else if (lower.endsWith(".mp3") || lower.endsWith(".wav")) {
                audio.add(file);
            } else if (isImageStatic(lower)) {
                images.add(file);
                imageByStem.put(stemStatic(file.name), file);
            }
        }

        WorkItem toWork(Collator collator) {
            Collections.sort(audio, (left, right) -> collator.compare(left.relativePath, right.relativePath));
            Collections.sort(images, (left, right) -> coverRank(left.name) - coverRank(right.name));
            FileDoc cover = images.isEmpty() ? null : images.get(0);
            List<MediaItem> tracks = new ArrayList<>();
            for (FileDoc file : audio) {
                FileDoc image = imageByStem.get(stemStatic(file.name));
                FileDoc vtt = findVtt(file);
                tracks.add(new MediaItem(file, image != null ? image : cover, vtt));
            }
            return new WorkItem(name, cover, tracks);
        }

        private FileDoc findVtt(FileDoc audioFile) {
            String lower = audioFile.name.toLowerCase(Locale.ROOT);
            FileDoc exact = vttByName.get(lower + ".vtt");
            if (exact != null) {
                return exact;
            }
            exact = vttByName.get(stemStatic(audioFile.name) + ".vtt");
            if (exact != null) {
                return exact;
            }
            return vttByName.get(stemStatic(audioFile.name));
        }

        private int coverRank(String name) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.contains("cover") || lower.contains("folder") || lower.contains("main") || lower.contains("封面")) {
                return 0;
            }
            return 1;
        }
    }

    private static boolean isImageStatic(String lowerName) {
        return lowerName.endsWith(".jpg")
                || lowerName.endsWith(".jpeg")
                || lowerName.endsWith(".png")
                || lowerName.endsWith(".webp")
                || lowerName.endsWith(".bmp");
    }

    private static String stemStatic(String name) {
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name).toLowerCase(Locale.ROOT);
    }

    private static class FileDoc {
        final String name;
        final String mime;
        final Uri uri;
        final String relativePath;
        final String parentPath;

        FileDoc(String name, String mime, Uri uri, String relativePath, String parentPath) {
            this.name = name;
            this.mime = mime;
            this.uri = uri;
            this.relativePath = relativePath;
            this.parentPath = parentPath;
        }
    }

    private static class WorkItem {
        final String name;
        final FileDoc cover;
        final List<MediaItem> tracks;

        WorkItem(String name, FileDoc cover, List<MediaItem> tracks) {
            this.name = name;
            this.cover = cover;
            this.tracks = tracks;
        }
    }

    private static class MediaItem {
        final FileDoc audio;
        final FileDoc image;
        final FileDoc vtt;

        MediaItem(FileDoc audio, FileDoc image, FileDoc vtt) {
            this.audio = audio;
            this.image = image;
            this.vtt = vtt;
        }
    }

    private static class Cue {
        final long startMs;
        final long endMs;
        final String text;

        Cue(long startMs, long endMs, String text) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text;
        }
    }

    private static class TextDrawable extends android.graphics.drawable.Drawable {
        private final String text;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        TextDrawable(String text) {
            this.text = text;
            paint.setColor(Color.WHITE);
            paint.setTextAlign(android.graphics.Paint.Align.CENTER);
            paint.setFakeBoldText(true);
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect bounds = getBounds();
            paint.setTextSize(Math.max(18, bounds.height() * 0.28f));
            android.graphics.Paint.FontMetrics metrics = paint.getFontMetrics();
            float y = bounds.centerY() - (metrics.ascent + metrics.descent) / 2f;
            canvas.drawText(text, bounds.centerX(), y, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }
}
