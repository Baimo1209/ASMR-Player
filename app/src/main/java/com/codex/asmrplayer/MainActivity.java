package com.codex.asmrplayer;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int REQ_OPEN_TREE = 1001;
    private static final int REQ_OVERLAY_PERMISSION = 1002;
    private static final int MAX_SCAN_DEPTH = 8;
    private static final int PAGE_WORKS = 0;
    private static final int PAGE_TRACKS = 1;
    private static final int PAGE_PLAYER = 2;
    private static final int PAGE_LYRICS = 3;
    private static final int TAB_FIND = 0;
    private static final int TAB_HEARD = 1;
    private static final int TAB_MY = 2;
    private static final int TAB_SETTINGS = 3;
    private static final String ICON_PLAY = "▶";
    private static final String ICON_PAUSE = "⏸";
    private static final String ICON_LYRICS = "☰";
    private static final String PREFS = "asmr_pocket_prefs";
    private static final String KEY_TREE_URI = "tree_uri";
    private static final String KEY_LIBRARY_CACHE = "library_cache";
    private static final String KEY_FLOATING_LYRICS = "floating_lyrics";
    private static final String KEY_WEB_HISTORY = "web_history";
    private static final String KEY_RECENT_AUDIO = "recent_audio";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<WorkItem> works = new ArrayList<>();
    private final List<MediaItem> playlist = new ArrayList<>();
    private final List<MediaItem> playbackQueue = new ArrayList<>();
    private final List<Cue> cues = new ArrayList<>();
    private final List<String> webHistory = new ArrayList<>();
    private final List<RecentTrack> recentTracks = new ArrayList<>();
    private final ExecutorService imageExecutor = Executors.newFixedThreadPool(2);
    private final TextDrawable workPlaceholder = new TextDrawable("ASMR");
    private final LruCache<String, Bitmap> coverCache = new LruCache<String, Bitmap>(8192) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };
    private final BrowserAdapter browserAdapter = new BrowserAdapter();
    private final LyricsAdapter lyricsAdapter = new LyricsAdapter();
    private final WebHistoryAdapter webHistoryAdapter = new WebHistoryAdapter();
    private final RecentAdapter recentAdapter = new RecentAdapter();
    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            handler.postDelayed(this, 250);
        }
    };

    private MediaPlayer player;
    private WorkItem activeWork;
    private WorkItem playbackWork;
    private int currentIndex = -1;
    private int currentCueIndex = -1;
    private int currentImageIndex = 0;
    private int movingWorkIndex = -1;
    private int movingTrackIndex = -1;
    private boolean draggingListItem;
    private boolean floatingLyricsEnabled;
    private boolean userSeeking;
    private int currentTab = TAB_MY;
    private int pageMode = PAGE_WORKS;

    private FrameLayout rootLayout;
    private View drawerScrim;
    private LinearLayout settingsDrawer;
    private Button floatingLyricsButton;
    private WindowManager windowManager;
    private TextView floatingLyricView;
    private WindowManager.LayoutParams floatingLyricParams;
    private LinearLayout findArea;
    private EditText urlInput;
    private WebView webView;
    private ListView webHistoryList;
    private ListView recentList;
    private LinearLayout settingsArea;
    private LinearLayout contentPanel;
    private LinearLayout bottomNav;
    private Button findTabButton;
    private Button heardTabButton;
    private Button myTabButton;
    private Button settingsTabButton;
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
        hideSystemBars();
        floatingLyricsEnabled = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_FLOATING_LYRICS, false);
        buildUi();
        loadWebHistory();
        loadRecentTracks();
        updateFloatingLyricsButton();
        if (floatingLyricsEnabled && canDrawOverlays()) {
            showFloatingLyrics();
        }

        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TREE_URI, null);
        if (!TextUtils.isEmpty(saved) && !loadCachedLibrary()) {
            folderView.setText(saved);
            titleView.setText("已保存文件夹授权");
            lyricView.setText("请点击选择文件夹重新扫描一次，之后会使用缓存快速进入");
            sectionSubtitleView.setText("未找到预加载缓存");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(progressTick);
        hideFloatingLyrics();
        releasePlayer();
        imageExecutor.shutdownNow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        if (floatingLyricsEnabled) {
            if (canDrawOverlays()) {
                showFloatingLyrics();
            } else {
                floatingLyricsEnabled = false;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_FLOATING_LYRICS, false).apply();
                updateFloatingLyricsButton();
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    @Override
    public void onBackPressed() {
        if (settingsDrawer != null && settingsDrawer.getVisibility() == View.VISIBLE) {
            hideSettingsDrawer();
            return;
        }
        if (currentTab != TAB_MY) {
            showMainTab(TAB_MY);
            return;
        }
        if (pageMode != PAGE_WORKS) {
            goBackPage();
            return;
        }
        super.onBackPressed();
    }

    private void buildUi() {
        rootLayout = new FrameLayout(this);
        FrameLayout root = rootLayout;
        root.setBackgroundColor(Color.rgb(50, 18, 12));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, dp(20), 0, 0);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(24), 0, dp(24), 0);
        page.addView(top, new LinearLayout.LayoutParams(-1, dp(42)));

        backButton = new Button(this);
        backButton.setText("返回");
        backButton.setTextColor(Color.WHITE);
        backButton.setTextSize(14);
        backButton.setAllCaps(false);
        backButton.setVisibility(View.GONE);
        backButton.setBackgroundResource(R.drawable.button_icon);
        backButton.setOnClickListener(v -> goBackPage());
        top.addView(backButton, new LinearLayout.LayoutParams(dp(72), dp(40)));

        TextView topSpacer = new TextView(this);
        top.addView(topSpacer, new LinearLayout.LayoutParams(0, -1, 1));

        LinearLayout sectionHeader = new LinearLayout(this);
        sectionHeader.setOrientation(LinearLayout.VERTICAL);
        sectionHeader.setGravity(Gravity.CENTER_HORIZONTAL);
        sectionHeader.setPadding(dp(24), dp(18), dp(24), dp(8));
        page.addView(sectionHeader, new LinearLayout.LayoutParams(-1, dp(112)));

        sectionTitleView = label("作品列表", 22, Color.WHITE);
        sectionTitleView.setSingleLine(true);
        sectionTitleView.setEllipsize(TextUtils.TruncateAt.END);
        sectionTitleView.setGravity(Gravity.CENTER);
        sectionHeader.addView(sectionTitleView, new LinearLayout.LayoutParams(-1, 0, 1));

        sectionSubtitleView = label("请选择文件夹，自动识别 ASMR 作品", 13, Color.rgb(184, 193, 202));
        sectionSubtitleView.setSingleLine(true);
        sectionSubtitleView.setEllipsize(TextUtils.TruncateAt.END);
        sectionSubtitleView.setGravity(Gravity.CENTER);
        sectionHeader.addView(sectionSubtitleView, new LinearLayout.LayoutParams(-1, 0, 1));

        contentPanel = new LinearLayout(this);
        contentPanel.setOrientation(LinearLayout.VERTICAL);
        contentPanel.setPadding(dp(18), dp(14), dp(18), dp(10));
        contentPanel.setBackgroundResource(R.drawable.content_panel);
        page.addView(contentPanel, new LinearLayout.LayoutParams(-1, 0, 1));

        playerArea = new LinearLayout(this);
        playerArea.setOrientation(LinearLayout.VERTICAL);
        playerArea.setVisibility(View.GONE);
        contentPanel.addView(playerArea, new LinearLayout.LayoutParams(-1, 0, 1));

        coverView = new ImageView(this);
        coverView.setBackgroundColor(Color.rgb(31, 35, 41));
        coverView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        coverView.setOnClickListener(v -> showNextTrackImage());
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
        playButton.setText(ICON_PLAY);
        playButton.setTextColor(Color.WHITE);
        playButton.setTextSize(20);
        playButton.setAllCaps(false);
        playButton.setBackgroundResource(R.drawable.button_primary);
        playButton.setOnClickListener(v -> togglePlayback());
        playButton.setMinWidth(0);
        playButton.setMinimumWidth(0);
        playButton.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(0, dp(48), 1.15f);
        playParams.leftMargin = dp(3);
        playParams.rightMargin = dp(3);
        controls.addView(playButton, playParams);
        controls.addView(iconButton("+15", v -> seekBy(15000)), buttonParams());
        controls.addView(iconButton("⏭", v -> playRelative(1, true)), buttonParams());
        lyricsButton = new Button(this);
        lyricsButton.setText(ICON_LYRICS);
        lyricsButton.setContentDescription("台词");
        lyricsButton.setTextColor(Color.WHITE);
        lyricsButton.setTextSize(20);
        lyricsButton.setAllCaps(false);
        lyricsButton.setBackgroundResource(R.drawable.button_icon);
        lyricsButton.setOnClickListener(v -> showLyrics());
        lyricsButton.setMinWidth(0);
        lyricsButton.setMinimumWidth(0);
        lyricsButton.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams lyricsParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        lyricsParams.leftMargin = dp(2);
        lyricsParams.rightMargin = dp(2);
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
        browserList.setDivider(new ColorDrawable(Color.TRANSPARENT));
        browserList.setDividerHeight(dp(8));
        browserList.setCacheColorHint(Color.TRANSPARENT);
        browserList.setBackgroundColor(Color.TRANSPARENT);
        browserList.setClipToPadding(false);
        browserList.setScrollingCacheEnabled(false);
        browserList.setSmoothScrollbarEnabled(true);
        browserList.setPadding(0, dp(6), 0, dp(12));
        browserList.setAdapter(browserAdapter);
        browserList.setOnItemClickListener(this::onListItemClick);
        browserList.setOnItemLongClickListener((parent, view, position, id) -> onListItemLongClick(position));
        browserList.setOnTouchListener((v, event) -> handleListDrag(event));
        contentPanel.addView(browserList, new LinearLayout.LayoutParams(-1, 0, 1));

        lyricsList = new ListView(this);
        lyricsList.setDivider(new ColorDrawable(Color.TRANSPARENT));
        lyricsList.setDividerHeight(dp(8));
        lyricsList.setCacheColorHint(Color.TRANSPARENT);
        lyricsList.setBackgroundColor(Color.TRANSPARENT);
        lyricsList.setClipToPadding(false);
        lyricsList.setScrollingCacheEnabled(false);
        lyricsList.setSmoothScrollbarEnabled(true);
        lyricsList.setPadding(0, dp(6), 0, dp(12));
        lyricsList.setVisibility(View.GONE);
        lyricsList.setAdapter(lyricsAdapter);
        lyricsList.setOnItemClickListener((parent, view, position, id) -> seekToCue(position));
        contentPanel.addView(lyricsList, new LinearLayout.LayoutParams(-1, 0, 1));

        recentList = new ListView(this);
        recentList.setDivider(new ColorDrawable(Color.TRANSPARENT));
        recentList.setDividerHeight(dp(8));
        recentList.setCacheColorHint(Color.TRANSPARENT);
        recentList.setBackgroundColor(Color.TRANSPARENT);
        recentList.setClipToPadding(false);
        recentList.setScrollingCacheEnabled(false);
        recentList.setSmoothScrollbarEnabled(true);
        recentList.setPadding(0, dp(6), 0, dp(12));
        recentList.setVisibility(View.GONE);
        recentList.setAdapter(recentAdapter);
        recentList.setOnItemClickListener((parent, view, position, id) -> openRecentTrack(recentTracks.get(position)));
        contentPanel.addView(recentList, new LinearLayout.LayoutParams(-1, 0, 1));

        settingsArea = buildSettingsArea();
        settingsArea.setVisibility(View.GONE);
        contentPanel.addView(settingsArea, new LinearLayout.LayoutParams(-1, 0, 1));

        miniPlayer = new LinearLayout(this);
        miniPlayer.setOrientation(LinearLayout.VERTICAL);
        miniPlayer.setPadding(dp(12), dp(6), dp(12), dp(4));
        miniPlayer.setBackgroundResource(R.drawable.mini_pill);
        miniPlayer.setVisibility(View.GONE);
        miniPlayer.setOnClickListener(v -> showPlayer());
        LinearLayout.LayoutParams miniParams = new LinearLayout.LayoutParams(-1, dp(62));
        miniParams.leftMargin = dp(10);
        miniParams.rightMargin = dp(10);
        miniParams.topMargin = dp(6);
        contentPanel.addView(miniPlayer, miniParams);

        LinearLayout miniTop = new LinearLayout(this);
        miniTop.setOrientation(LinearLayout.HORIZONTAL);
        miniTop.setGravity(Gravity.CENTER_VERTICAL);
        miniPlayer.addView(miniTop, new LinearLayout.LayoutParams(-1, dp(42)));

        miniTitleView = label("未播放", 14, Color.rgb(20, 24, 35));
        miniTitleView.setSingleLine(true);
        miniTitleView.setEllipsize(TextUtils.TruncateAt.END);
        miniTop.addView(miniTitleView, new LinearLayout.LayoutParams(0, -1, 1));

        miniTop.addView(iconButton("⏮", v -> playRelative(-1, false)), new LinearLayout.LayoutParams(dp(38), dp(34)));
        miniPlayButton = new Button(this);
        miniPlayButton.setText(ICON_PAUSE);
        miniPlayButton.setTextColor(Color.WHITE);
        miniPlayButton.setTextSize(18);
        miniPlayButton.setAllCaps(false);
        miniPlayButton.setBackgroundResource(R.drawable.button_primary);
        miniPlayButton.setOnClickListener(v -> togglePlayback());
        LinearLayout.LayoutParams miniPlayParams = new LinearLayout.LayoutParams(dp(52), dp(34));
        miniPlayParams.leftMargin = dp(6);
        miniPlayParams.rightMargin = dp(6);
        miniTop.addView(miniPlayButton, miniPlayParams);
        miniTop.addView(iconButton("⏭", v -> playRelative(1, false)), new LinearLayout.LayoutParams(dp(38), dp(34)));

        miniSeekBar = new SeekBar(this);
        miniSeekBar.setProgressDrawable(getDrawable(R.drawable.seekbar_progress));
        miniSeekBar.setOnTouchListener((v, event) -> {
            showPlayer();
            return true;
        });
        miniPlayer.addView(miniSeekBar, new LinearLayout.LayoutParams(-1, dp(8)));

        miniTimeView = label("00:00 / 00:00", 1, Color.TRANSPARENT);
        miniTimeView.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        miniPlayer.addView(miniTimeView, new LinearLayout.LayoutParams(-1, 0, 0));

        bottomNav = new LinearLayout(this);
        bottomNav.setOrientation(LinearLayout.HORIZONTAL);
        bottomNav.setGravity(Gravity.CENTER);
        bottomNav.setPadding(dp(2), dp(2), dp(2), dp(2));
        bottomNav.setBackgroundColor(Color.TRANSPARENT);
        heardTabButton = navButton("听过", TAB_HEARD);
        myTabButton = navButton("我的", TAB_MY);
        settingsTabButton = navButton("设置", TAB_SETTINGS);
        bottomNav.addView(heardTabButton, navButtonParams());
        bottomNav.addView(myTabButton, navButtonParams());
        bottomNav.addView(settingsTabButton, navButtonParams());
        LinearLayout.LayoutParams navParams = new LinearLayout.LayoutParams(-1, dp(44));
        navParams.topMargin = dp(4);
        contentPanel.addView(bottomNav, navParams);

        updatePageChrome(false);
        setContentView(root);
    }

    private void hideSystemBars() {
        Window window = getWindow();
        View decorView = window.getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = decorView.getWindowInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }

    private LinearLayout buildFindArea() {
        LinearLayout area = new LinearLayout(this);
        area.setOrientation(LinearLayout.VERTICAL);
        area.setPadding(0, dp(4), 0, 0);

        LinearLayout urlRow = new LinearLayout(this);
        urlRow.setOrientation(LinearLayout.HORIZONTAL);
        urlRow.setGravity(Gravity.CENTER_VERTICAL);
        area.addView(urlRow, new LinearLayout.LayoutParams(-1, dp(42)));

        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setTextColor(Color.WHITE);
        urlInput.setHintTextColor(Color.rgb(137, 146, 156));
        urlInput.setTextSize(14);
        urlInput.setHint("输入网址，例如 https://example.com");
        urlInput.setPadding(dp(12), 0, dp(12), 0);
        urlInput.setBackgroundResource(R.drawable.button_icon);
        urlRow.addView(urlInput, new LinearLayout.LayoutParams(0, dp(38), 1));

        Button goButton = drawerButton("前往");
        goButton.setOnClickListener(v -> loadBrowserUrl(urlInput.getText().toString()));
        LinearLayout.LayoutParams goParams = new LinearLayout.LayoutParams(dp(64), dp(38));
        goParams.leftMargin = dp(8);
        urlRow.addView(goButton, goParams);

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(false);
        settings.setTextZoom(100);
        settings.setLoadsImagesAutomatically(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        webView.setBackgroundColor(Color.rgb(24, 27, 32));
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                urlInput.setText(url);
                addWebHistory(url);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                Toast.makeText(MainActivity.this, "网页加载失败: " + description, Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams webParams = new LinearLayout.LayoutParams(-1, 0, 1);
        webParams.topMargin = dp(6);
        area.addView(webView, webParams);

        TextView historyTitle = label("访问记录", 13, Color.rgb(184, 193, 202));
        LinearLayout.LayoutParams historyTitleParams = new LinearLayout.LayoutParams(-1, dp(22));
        historyTitleParams.topMargin = dp(4);
        area.addView(historyTitle, historyTitleParams);

        webHistoryList = new ListView(this);
        webHistoryList.setDivider(new ColorDrawable(Color.rgb(45, 51, 59)));
        webHistoryList.setDividerHeight(1);
        webHistoryList.setCacheColorHint(Color.TRANSPARENT);
        webHistoryList.setBackgroundColor(Color.TRANSPARENT);
        webHistoryList.setAdapter(webHistoryAdapter);
        webHistoryList.setOnItemClickListener((parent, view, position, id) -> loadBrowserUrl(webHistory.get(position)));
        area.addView(webHistoryList, new LinearLayout.LayoutParams(-1, dp(62)));
        return area;
    }

    private LinearLayout buildSettingsArea() {
        LinearLayout area = new LinearLayout(this);
        area.setOrientation(LinearLayout.VERTICAL);
        area.setPadding(0, dp(18), 0, dp(12));

        Button chooseFolder = drawerButton("选择文件");
        chooseFolder.setOnClickListener(v -> openFolderPicker());
        area.addView(chooseFolder, new LinearLayout.LayoutParams(-1, dp(50)));

        floatingLyricsButton = drawerButton("台词悬浮");
        floatingLyricsButton.setOnClickListener(v -> toggleFloatingLyrics());
        LinearLayout.LayoutParams floatingParams = new LinearLayout.LayoutParams(-1, dp(50));
        floatingParams.topMargin = dp(10);
        area.addView(floatingLyricsButton, floatingParams);

        TextView hint = label("台词悬浮开启后会显示在手机页面上层，切到后台也会继续播放并更新台词，可拖动调整位置。", 13, Color.rgb(160, 169, 178));
        hint.setMaxLines(4);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.topMargin = dp(14);
        area.addView(hint, hintParams);
        return area;
    }

    private Button navButton(String text, int tab) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(0, 0, 0, 0);
        button.setOnClickListener(v -> showMainTab(tab));
        return button;
    }

    private LinearLayout.LayoutParams navButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
        params.leftMargin = dp(2);
        params.rightMargin = dp(2);
        return params;
    }

    private void buildSettingsDrawer(FrameLayout root) {
        drawerScrim = new View(this);
        drawerScrim.setBackgroundColor(Color.argb(130, 0, 0, 0));
        drawerScrim.setVisibility(View.GONE);
        drawerScrim.setOnClickListener(v -> hideSettingsDrawer());
        root.addView(drawerScrim, new FrameLayout.LayoutParams(-1, -1));

        settingsDrawer = new LinearLayout(this);
        settingsDrawer.setOrientation(LinearLayout.VERTICAL);
        settingsDrawer.setPadding(dp(18), dp(28), dp(18), dp(18));
        settingsDrawer.setBackgroundColor(Color.rgb(24, 27, 32));
        settingsDrawer.setVisibility(View.GONE);

        TextView title = label("设置", 20, Color.WHITE);
        title.setGravity(Gravity.CENTER_VERTICAL);
        settingsDrawer.addView(title, new LinearLayout.LayoutParams(-1, dp(44)));

        Button chooseFolder = drawerButton("选择文件");
        chooseFolder.setOnClickListener(v -> {
            hideSettingsDrawer();
            openFolderPicker();
        });
        LinearLayout.LayoutParams chooseParams = new LinearLayout.LayoutParams(-1, dp(48));
        chooseParams.topMargin = dp(12);
        settingsDrawer.addView(chooseFolder, chooseParams);

        floatingLyricsButton = drawerButton("台词悬浮");
        floatingLyricsButton.setOnClickListener(v -> toggleFloatingLyrics());
        LinearLayout.LayoutParams floatingParams = new LinearLayout.LayoutParams(-1, dp(48));
        floatingParams.topMargin = dp(10);
        settingsDrawer.addView(floatingLyricsButton, floatingParams);

        TextView hint = label("开启后台词会显示在手机页面上层，可拖动位置。", 12, Color.rgb(160, 169, 178));
        hint.setMaxLines(3);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.topMargin = dp(12);
        settingsDrawer.addView(hint, hintParams);

        FrameLayout.LayoutParams drawerParams = new FrameLayout.LayoutParams(dp(260), -1, Gravity.END);
        root.addView(settingsDrawer, drawerParams);
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

    private Button drawerButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setBackgroundResource(R.drawable.button_icon);
        return button;
    }

    private void showSettingsDrawer() {
        if (settingsDrawer == null || drawerScrim == null) {
            return;
        }
        updateFloatingLyricsButton();
        drawerScrim.setVisibility(View.VISIBLE);
        settingsDrawer.setVisibility(View.VISIBLE);
        settingsDrawer.setTranslationX(dp(260));
        settingsDrawer.animate().translationX(0).setDuration(180).start();
    }

    private void hideSettingsDrawer() {
        if (settingsDrawer == null || drawerScrim == null || settingsDrawer.getVisibility() != View.VISIBLE) {
            return;
        }
        settingsDrawer.animate().translationX(dp(260)).setDuration(160).withEndAction(() -> {
            settingsDrawer.setVisibility(View.GONE);
            drawerScrim.setVisibility(View.GONE);
            settingsDrawer.setTranslationX(0);
        }).start();
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1f);
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

    private void toggleFloatingLyrics() {
        hideSettingsDrawer();
        if (!floatingLyricsEnabled) {
            floatingLyricsEnabled = true;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_FLOATING_LYRICS, true).apply();
            if (canDrawOverlays()) {
                showFloatingLyrics();
                Toast.makeText(this, "台词悬浮已开启", Toast.LENGTH_SHORT).show();
            } else {
                requestOverlayPermission();
            }
        } else {
            floatingLyricsEnabled = false;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_FLOATING_LYRICS, false).apply();
            hideFloatingLyrics();
            Toast.makeText(this, "台词悬浮已关闭", Toast.LENGTH_SHORT).show();
        }
        updateFloatingLyricsButton();
    }

    private void updateFloatingLyricsButton() {
        if (floatingLyricsButton == null) {
            return;
        }
        floatingLyricsButton.setText(floatingLyricsEnabled ? "台词悬浮  开" : "台词悬浮  关");
    }

    private boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        Toast.makeText(this, "请允许白沫播放器显示在其他应用上层", Toast.LENGTH_LONG).show();
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivityForResult(intent, REQ_OVERLAY_PERMISSION);
    }

    private void showFloatingLyrics() {
        if (!floatingLyricsEnabled || !canDrawOverlays()) {
            return;
        }
        if (floatingLyricView != null) {
            updateFloatingLyricText();
            return;
        }
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) {
            return;
        }
        floatingLyricView = new TextView(this);
        floatingLyricView.setTextColor(Color.WHITE);
        floatingLyricView.setTextSize(15);
        floatingLyricView.setGravity(Gravity.CENTER);
        floatingLyricView.setPadding(dp(14), dp(10), dp(14), dp(10));
        floatingLyricView.setMaxLines(4);
        floatingLyricView.setBackgroundColor(Color.argb(150, 16, 18, 21));
        floatingLyricView.setText("台词悬浮已开启");
        floatingLyricView.setOnTouchListener(new View.OnTouchListener() {
            private int startX;
            private int startY;
            private float downX;
            private float downY;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (floatingLyricParams == null || windowManager == null) {
                    return false;
                }
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    startX = floatingLyricParams.x;
                    startY = floatingLyricParams.y;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    floatingLyricParams.x = startX + (int) (event.getRawX() - downX);
                    floatingLyricParams.y = startY + (int) (event.getRawY() - downY);
                    windowManager.updateViewLayout(floatingLyricView, floatingLyricParams);
                    return true;
                }
                return true;
            }
        });

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        floatingLyricParams = new WindowManager.LayoutParams(
                dp(300),
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        floatingLyricParams.gravity = Gravity.TOP | Gravity.START;
        floatingLyricParams.x = dp(28);
        floatingLyricParams.y = dp(120);
        try {
            windowManager.addView(floatingLyricView, floatingLyricParams);
            updateFloatingLyricText();
        } catch (Exception ex) {
            floatingLyricView = null;
            Toast.makeText(this, "无法显示悬浮台词: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void hideFloatingLyrics() {
        if (floatingLyricView == null || windowManager == null) {
            floatingLyricView = null;
            return;
        }
        try {
            windowManager.removeView(floatingLyricView);
        } catch (Exception ignored) {
        }
        floatingLyricView = null;
        floatingLyricParams = null;
    }

    private void updateFloatingLyricText() {
        if (floatingLyricView == null) {
            return;
        }
        CharSequence text = lyricView == null ? "" : lyricView.getText();
        if (TextUtils.isEmpty(text)) {
            MediaItem current = currentPlaybackItem();
            if (current != null) {
                text = current.audio.name;
            } else {
                text = "等待播放台词";
            }
        }
        floatingLyricView.setText(text);
    }

    private void loadBrowserUrl(String rawUrl) {
        String url = normalizeUrl(rawUrl);
        if (TextUtils.isEmpty(url)) {
            Toast.makeText(this, "请输入网址", Toast.LENGTH_SHORT).show();
            return;
        }
        urlInput.setText(url);
        webView.loadUrl(url);
    }

    private String normalizeUrl(String rawUrl) {
        String url = rawUrl == null ? "" : rawUrl.trim();
        if (TextUtils.isEmpty(url)) {
            return "";
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            url = "https://" + url;
        }
        return url;
    }

    private void loadWebHistory() {
        webHistory.clear();
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_WEB_HISTORY, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                String url = array.optString(i, "");
                if (!TextUtils.isEmpty(url)) {
                    webHistory.add(url);
                }
            }
        } catch (JSONException ignored) {
        }
        webHistoryAdapter.notifyDataSetChanged();
    }

    private void addWebHistory(String url) {
        if (TextUtils.isEmpty(url) || url.startsWith("about:")) {
            return;
        }
        webHistory.remove(url);
        webHistory.add(0, url);
        while (webHistory.size() > 50) {
            webHistory.remove(webHistory.size() - 1);
        }
        saveWebHistory();
        webHistoryAdapter.notifyDataSetChanged();
    }

    private void saveWebHistory() {
        JSONArray array = new JSONArray();
        for (String url : webHistory) {
            array.put(url);
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_WEB_HISTORY, array.toString())
                .apply();
    }

    private void loadRecentTracks() {
        recentTracks.clear();
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_RECENT_AUDIO, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                recentTracks.add(new RecentTrack(
                        object.optString("workKey", ""),
                        object.optString("trackKey", ""),
                        object.optString("workName", ""),
                        object.optString("trackName", "")
                ));
            }
        } catch (JSONException ignored) {
        }
        recentAdapter.notifyDataSetChanged();
    }

    private void recordRecentTrack(MediaItem item, WorkItem work) {
        if (item == null) {
            return;
        }
        String workKey = work == null ? "" : work.key;
        String workName = work == null ? item.audio.parentPath : work.name;
        for (int i = recentTracks.size() - 1; i >= 0; i--) {
            RecentTrack recent = recentTracks.get(i);
            if (recent.trackKey.equals(item.key) && recent.workKey.equals(workKey)) {
                recentTracks.remove(i);
            }
        }
        recentTracks.add(0, new RecentTrack(workKey, item.key, workName, item.audio.name));
        while (recentTracks.size() > 80) {
            recentTracks.remove(recentTracks.size() - 1);
        }
        saveRecentTracks();
        recentAdapter.notifyDataSetChanged();
        if (currentTab == TAB_HEARD) {
            sectionSubtitleView.setText("近期播放 " + recentTracks.size() + " 条");
        }
    }

    private void saveRecentTracks() {
        JSONArray array = new JSONArray();
        try {
            for (RecentTrack recent : recentTracks) {
                JSONObject object = new JSONObject();
                object.put("workKey", recent.workKey);
                object.put("trackKey", recent.trackKey);
                object.put("workName", recent.workName);
                object.put("trackName", recent.trackName);
                array.put(object);
            }
        } catch (JSONException ignored) {
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_RECENT_AUDIO, array.toString())
                .apply();
    }

    private void openRecentTrack(RecentTrack recent) {
        WorkItem work = findWorkByKey(recent.workKey);
        if (work == null) {
            Toast.makeText(this, "未找到该音频所在作品，请重新选择文件夹扫描", Toast.LENGTH_SHORT).show();
            return;
        }
        int trackIndex = findTrackIndex(work, recent.trackKey);
        if (trackIndex < 0) {
            Toast.makeText(this, "未找到该音频文件", Toast.LENGTH_SHORT).show();
            return;
        }
        MediaItem current = currentPlaybackItem();
        if (current != null && current.key.equals(recent.trackKey) && playbackWork != null && playbackWork.key.equals(work.key)) {
            showPlayer();
            return;
        }
        showMainTab(TAB_MY);
        openWork(work);
        playAt(trackIndex, true);
    }

    private WorkItem findWorkByKey(String key) {
        for (WorkItem work : works) {
            if (work.key.equals(key)) {
                return work;
            }
        }
        return null;
    }

    private int findTrackIndex(WorkItem work, String trackKey) {
        for (int i = 0; i < work.tracks.size(); i++) {
            if (work.tracks.get(i).key.equals(trackKey)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY_PERMISSION) {
            if (floatingLyricsEnabled && canDrawOverlays()) {
                showFloatingLyrics();
                Toast.makeText(this, "台词悬浮已开启", Toast.LENGTH_SHORT).show();
            } else if (floatingLyricsEnabled) {
                floatingLyricsEnabled = false;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_FLOATING_LYRICS, false).apply();
                updateFloatingLyricsButton();
            }
            return;
        }
        if (requestCode == REQ_OPEN_TREE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) {
                return;
            }
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_TREE_URI, uri.toString()).apply();
            scanFolder(uri, new ArrayList<>(works));
        }
    }

    private void scanFolder(Uri treeUri, List<WorkItem> cachedOrder) {
        currentTab = TAB_MY;
        boolean keepPlayback = currentPlaybackItem() != null;
        works.clear();
        if (!keepPlayback) {
            playlist.clear();
            playbackQueue.clear();
            cues.clear();
            activeWork = null;
            playbackWork = null;
            currentIndex = -1;
        }
        movingWorkIndex = -1;
        movingTrackIndex = -1;
        draggingListItem = false;
        if (!keepPlayback) {
            releasePlayer();
            resetPlaybackUi();
        }
        updateMiniPlayer();

        String treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
        String selectedName = displayNameFromTreeId(treeDocumentId);
        List<FileDoc> files = listTreeFilesRecursive(treeUri, treeDocumentId);
        List<WorkBuilder> builders = buildWorks(files, selectedName);

        final Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(builders, (left, right) -> collator.compare(left.name, right.name));
        List<WorkItem> scannedWorks = new ArrayList<>();
        for (WorkBuilder builder : builders) {
            WorkItem work = builder.toWork(collator);
            if (!work.tracks.isEmpty()) {
                scannedWorks.add(work);
            }
        }
        works.addAll(mergeWorks(scannedWorks, cachedOrder));
        saveLibraryCache();

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

    private List<WorkItem> mergeWorks(List<WorkItem> scannedWorks, List<WorkItem> cachedOrder) {
        Map<String, WorkItem> scannedByKey = new LinkedHashMap<>();
        for (WorkItem work : scannedWorks) {
            scannedByKey.put(work.key, work);
        }

        Map<String, WorkItem> cachedByKey = new HashMap<>();
        for (WorkItem work : cachedOrder) {
            cachedByKey.put(work.key, work);
        }

        List<WorkItem> merged = new ArrayList<>();
        for (WorkItem work : scannedWorks) {
            if (!cachedByKey.containsKey(work.key)) {
                merged.add(work);
            }
        }
        for (WorkItem cached : cachedOrder) {
            WorkItem scanned = scannedByKey.get(cached.key);
            if (scanned != null) {
                List<MediaItem> mergedTracks = mergeTracks(scanned.tracks, cached.tracks);
                scanned.tracks.clear();
                scanned.tracks.addAll(mergedTracks);
                merged.add(scanned);
            }
        }
        return merged;
    }

    private List<MediaItem> mergeTracks(List<MediaItem> scannedTracks, List<MediaItem> cachedOrder) {
        Map<String, MediaItem> scannedByKey = new LinkedHashMap<>();
        for (MediaItem item : scannedTracks) {
            scannedByKey.put(item.key, item);
        }

        Map<String, MediaItem> cachedByKey = new HashMap<>();
        for (MediaItem item : cachedOrder) {
            cachedByKey.put(item.key, item);
        }

        List<MediaItem> merged = new ArrayList<>();
        for (MediaItem item : scannedTracks) {
            if (!cachedByKey.containsKey(item.key)) {
                merged.add(item);
            }
        }
        for (MediaItem cached : cachedOrder) {
            MediaItem scanned = scannedByKey.get(cached.key);
            if (scanned != null) {
                merged.add(scanned);
            }
        }
        return merged;
    }

    private boolean loadCachedLibrary() {
        String cache = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LIBRARY_CACHE, null);
        if (TextUtils.isEmpty(cache)) {
            return false;
        }
        try {
            JSONObject root = new JSONObject(cache);
            JSONArray array = root.optJSONArray("works");
            if (array == null) {
                return false;
            }
            works.clear();
            playlist.clear();
            cues.clear();
            activeWork = null;
            playbackWork = null;
            currentIndex = -1;
            movingWorkIndex = -1;
            movingTrackIndex = -1;
            draggingListItem = false;
            resetPlaybackUi();
            updateMiniPlayer();
            for (int i = 0; i < array.length(); i++) {
                WorkItem work = workFromJson(array.getJSONObject(i));
                if (!work.tracks.isEmpty()) {
                    works.add(work);
                }
            }
            currentTab = TAB_MY;
            pageMode = PAGE_WORKS;
            browserAdapter.notifyDataSetChanged();
            updatePageChrome(false);
            String tree = root.optString("treeUri", getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TREE_URI, ""));
            folderView.setText(tree);
            statusView.setText("已加载缓存");
            if (works.isEmpty()) {
                sectionTitleView.setText("作品列表");
                sectionSubtitleView.setText("缓存中没有可播放作品");
                titleView.setText("未找到缓存作品");
                lyricView.setText("请选择文件夹重新扫描");
            } else {
                sectionTitleView.setText("作品列表");
                sectionSubtitleView.setText("已预加载 " + works.size() + " 个作品");
                titleView.setText("已预加载 " + works.size() + " 个作品");
                lyricView.setText("点击作品查看音轨并播放");
                showWorkPreview(works.get(0));
            }
            return true;
        } catch (JSONException ex) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_LIBRARY_CACHE).apply();
            return false;
        }
    }

    private void saveLibraryCache() {
        try {
            JSONObject root = new JSONObject();
            root.put("treeUri", getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TREE_URI, ""));
            JSONArray array = new JSONArray();
            for (WorkItem work : works) {
                array.put(workToJson(work));
            }
            root.put("works", array);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_LIBRARY_CACHE, root.toString())
                    .apply();
        } catch (JSONException ignored) {
        }
    }

    private JSONObject workToJson(WorkItem work) throws JSONException {
        JSONObject object = new JSONObject();
        object.put("key", work.key);
        object.put("name", work.name);
        object.put("cover", fileToJson(work.cover));
        JSONArray tracks = new JSONArray();
        for (MediaItem item : work.tracks) {
            tracks.put(mediaToJson(item));
        }
        object.put("tracks", tracks);
        return object;
    }

    private WorkItem workFromJson(JSONObject object) throws JSONException {
        String name = object.optString("name", "");
        String key = object.optString("key", name);
        FileDoc cover = fileFromJson(object.optJSONObject("cover"));
        JSONArray trackArray = object.optJSONArray("tracks");
        List<MediaItem> tracks = new ArrayList<>();
        if (trackArray != null) {
            for (int i = 0; i < trackArray.length(); i++) {
                MediaItem item = mediaFromJson(trackArray.getJSONObject(i));
                if (item != null) {
                    tracks.add(item);
                }
            }
        }
        return new WorkItem(key, name, cover, tracks);
    }

    private JSONObject mediaToJson(MediaItem item) throws JSONException {
        JSONObject object = new JSONObject();
        object.put("audio", fileToJson(item.audio));
        object.put("vtt", fileToJson(item.vtt));
        JSONArray images = new JSONArray();
        for (FileDoc image : item.images) {
            images.put(fileToJson(image));
        }
        object.put("images", images);
        return object;
    }

    private MediaItem mediaFromJson(JSONObject object) throws JSONException {
        FileDoc audio = fileFromJson(object.optJSONObject("audio"));
        if (audio == null) {
            return null;
        }
        FileDoc vtt = fileFromJson(object.optJSONObject("vtt"));
        JSONArray imageArray = object.optJSONArray("images");
        List<FileDoc> images = new ArrayList<>();
        if (imageArray != null) {
            for (int i = 0; i < imageArray.length(); i++) {
                FileDoc image = fileFromJson(imageArray.optJSONObject(i));
                if (image != null) {
                    images.add(image);
                }
            }
        }
        return new MediaItem(audio, images, vtt);
    }

    private Object fileToJson(FileDoc file) throws JSONException {
        if (file == null) {
            return JSONObject.NULL;
        }
        JSONObject object = new JSONObject();
        object.put("name", file.name);
        object.put("mime", file.mime);
        object.put("uri", file.uri.toString());
        object.put("relativePath", file.relativePath);
        object.put("parentPath", file.parentPath);
        return object;
    }

    private FileDoc fileFromJson(JSONObject object) {
        if (object == null) {
            return null;
        }
        String uri = object.optString("uri", "");
        if (TextUtils.isEmpty(uri)) {
            return null;
        }
        return new FileDoc(
                object.optString("name", ""),
                object.optString("mime", ""),
                Uri.parse(uri),
                object.optString("relativePath", ""),
                object.optString("parentPath", "")
        );
    }

    private List<WorkBuilder> buildWorks(List<FileDoc> files, String selectedName) {
        Map<String, WorkBuilder> map = new LinkedHashMap<>();
        boolean selectedLooksLikeWork = looksLikeWorkName(selectedName);

        for (FileDoc file : files) {
            if (!isSupportedAsset(file.name)) {
                continue;
            }
            String key = selectedLooksLikeWork ? selectedName : firstPathSegment(file.relativePath);
            String name = selectedLooksLikeWork || TextUtils.isEmpty(key) ? selectedName : key;
            WorkBuilder builder = map.get(key);
            if (builder == null) {
                builder = new WorkBuilder(key, name);
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
        if (draggingListItem || movingWorkIndex >= 0 || movingTrackIndex >= 0) {
            return;
        }
        if (pageMode == PAGE_WORKS) {
            openWork(works.get(position));
        } else if (pageMode == PAGE_TRACKS) {
            playAt(position, true);
        }
    }

    private boolean onListItemLongClick(int position) {
        if (pageMode == PAGE_WORKS && position >= 0 && position < works.size()) {
            movingWorkIndex = position;
            movingTrackIndex = -1;
            draggingListItem = true;
            browserAdapter.notifyDataSetChanged();
            sectionSubtitleView.setText("按住拖动作品，松开保存位置");
            Toast.makeText(this, "拖动到目标位置后松开", Toast.LENGTH_SHORT).show();
            return true;
        }
        if (pageMode == PAGE_TRACKS && position >= 0 && position < playlist.size()) {
            movingTrackIndex = position;
            movingWorkIndex = -1;
            draggingListItem = true;
            browserAdapter.notifyDataSetChanged();
            sectionSubtitleView.setText("按住拖动音轨，松开保存位置");
            statusView.setText("拖动排序中");
            Toast.makeText(this, "拖动到目标位置后松开", Toast.LENGTH_SHORT).show();
            return true;
        }
        return false;
    }

    private boolean handleListDrag(MotionEvent event) {
        if (!draggingListItem) {
            return false;
        }
        if (event.getAction() == MotionEvent.ACTION_MOVE) {
            int target = browserList.pointToPosition((int) event.getX(), (int) event.getY());
            if (target != AdapterView.INVALID_POSITION) {
                if (pageMode == PAGE_WORKS && movingWorkIndex >= 0 && target != movingWorkIndex) {
                    moveWorkDuringDrag(movingWorkIndex, target);
                } else if (pageMode == PAGE_TRACKS && movingTrackIndex >= 0 && target != movingTrackIndex) {
                    moveTrackDuringDrag(movingTrackIndex, target);
                }
            }
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            finishListDrag();
            return true;
        }
        return true;
    }

    private void finishListDrag() {
        if (!draggingListItem) {
            return;
        }
        boolean movedWork = movingWorkIndex >= 0;
        boolean movedTrack = movingTrackIndex >= 0;
        movingWorkIndex = -1;
        movingTrackIndex = -1;
        draggingListItem = false;
        browserAdapter.notifyDataSetChanged();
        saveLibraryCache();
        if (movedWork) {
            sectionSubtitleView.setText("已保存作品顺序");
            statusView.setText("已移动作品");
        } else if (movedTrack) {
            sectionSubtitleView.setText("已保存音轨顺序");
            statusView.setText("已移动音轨");
        }
    }

    private void moveWorkDuringDrag(int from, int to) {
        if (from < 0 || from >= works.size() || to < 0 || to >= works.size()) {
            movingWorkIndex = -1;
            return;
        }
        WorkItem item = works.remove(from);
        works.add(to, item);
        movingWorkIndex = to;
        browserAdapter.notifyDataSetChanged();
        sectionSubtitleView.setText("作品移动到第 " + (to + 1) + " 位");
        statusView.setText("拖动排序中");
    }

    private void moveTrackDuringDrag(int from, int to) {
        if (from < 0 || from >= playlist.size() || to < 0 || to >= playlist.size()) {
            movingTrackIndex = -1;
            return;
        }
        MediaItem item = playlist.remove(from);
        playlist.add(to, item);
        if (activeWork != null) {
            activeWork.tracks.clear();
            activeWork.tracks.addAll(playlist);
        }
        if (activeWork != null && activeWork == playbackWork && playbackQueue.size() == playlist.size()) {
            playbackQueue.clear();
            playbackQueue.addAll(playlist);
            if (currentIndex == from) {
                currentIndex = to;
            } else if (from < currentIndex && to >= currentIndex) {
                currentIndex--;
            } else if (from > currentIndex && to <= currentIndex) {
                currentIndex++;
            }
        }
        movingTrackIndex = to;
        browserAdapter.notifyDataSetChanged();
        sectionSubtitleView.setText("音轨移动到第 " + (to + 1) + " 位");
        statusView.setText("拖动排序中");
    }

    private void openWork(WorkItem work) {
        currentTab = TAB_MY;
        activeWork = work;
        movingWorkIndex = -1;
        movingTrackIndex = -1;
        draggingListItem = false;
        playlist.clear();
        playlist.addAll(work.tracks);
        pageMode = PAGE_TRACKS;
        updatePageChrome(true);
        browserAdapter.notifyDataSetChanged();
        if (player == null) {
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
        currentTab = TAB_MY;
        pageMode = PAGE_WORKS;
        movingWorkIndex = -1;
        movingTrackIndex = -1;
        draggingListItem = false;
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
        currentTab = TAB_MY;
        if (activeWork == null) {
            showWorks();
            return;
        }
        pageMode = PAGE_TRACKS;
        movingWorkIndex = -1;
        movingTrackIndex = -1;
        draggingListItem = false;
        updatePageChrome(true);
        browserAdapter.notifyDataSetChanged();
        sectionTitleView.setText(activeWork.name);
        sectionSubtitleView.setText("音轨列表");
        titleView.setText(activeWork.name);
        folderView.setText(activeWork.tracks.size() + " 首音轨");
        MediaItem current = currentPlaybackItem();
        lyricView.setText(current == null ? "点击音轨进入播放页" : "正在播放: " + current.audio.name);
        statusView.setText(currentIndex >= 0 && player != null && player.isPlaying() ? "播放中" : "音轨列表");
        if (activeWork.cover != null) {
            coverView.setImageURI(activeWork.cover.uri);
        }
        updateMiniPlayer();
    }

    private void showPlayer() {
        currentTab = TAB_MY;
        pageMode = PAGE_PLAYER;
        updatePageChrome(true);
        browserAdapter.notifyDataSetChanged();
        sectionTitleView.setText("正在播放");
        MediaItem current = currentPlaybackItem();
        sectionSubtitleView.setText(playbackWork == null ? "" : playbackWork.name);
        if (current != null) {
            titleView.setText(current.audio.name);
            folderView.setText(playbackWork == null ? current.audio.parentPath : playbackWork.name);
            showCurrentTrackImage();
        }
        updateMiniPlayer();
    }

    private void showLyrics() {
        if (cues.isEmpty()) {
            Toast.makeText(this, "当前音轨没有可查看的 .vtt 台词", Toast.LENGTH_SHORT).show();
            return;
        }
        currentTab = TAB_MY;
        pageMode = PAGE_LYRICS;
        updatePageChrome(true);
        lyricsAdapter.notifyDataSetChanged();
        sectionTitleView.setText("台词");
        MediaItem current = currentPlaybackItem();
        sectionSubtitleView.setText(current == null ? "点击台词跳转播放位置" : current.audio.name);
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

    private void showMainTab(int tab) {
        if (tab == TAB_MY) {
            restoreMyTabFast();
            return;
        }
        if (currentTab == tab) {
            return;
        }
        currentTab = tab;
        updatePageChrome(false);
        if (tab == TAB_HEARD) {
            sectionTitleView.setText("听过");
            sectionSubtitleView.setText(recentTracks.isEmpty() ? "还没有播放记录" : "近期播放 " + recentTracks.size() + " 条");
            recentAdapter.notifyDataSetChanged();
        } else if (tab == TAB_SETTINGS) {
            sectionTitleView.setText("设置");
            sectionSubtitleView.setText("文件夹选择与台词悬浮");
            updateFloatingLyricsButton();
        }
    }

    private void restoreMyTabFast() {
        currentTab = TAB_MY;
        if ((pageMode == PAGE_TRACKS && activeWork == null)
                || (pageMode == PAGE_PLAYER && currentPlaybackItem() == null)
                || (pageMode == PAGE_LYRICS && cues.isEmpty())) {
            pageMode = PAGE_WORKS;
        }
        updateMyHeaderFast();
        updatePageChrome(false);
    }

    private void updateMyHeaderFast() {
        if (pageMode == PAGE_WORKS) {
            sectionTitleView.setText("作品列表");
            sectionSubtitleView.setText(works.size() + " 个作品");
            titleView.setText(works.isEmpty() ? "没有识别到 ASMR 音频作品" : "作品列表");
            folderView.setText(works.size() + " 个作品");
            lyricView.setText(works.isEmpty() ? "请选择包含 RJ 文件夹或音频文件的目录" : "点击作品查看音轨并播放");
        } else if (pageMode == PAGE_TRACKS && activeWork != null) {
            sectionTitleView.setText(activeWork.name);
            sectionSubtitleView.setText("音轨列表");
            titleView.setText(activeWork.name);
            folderView.setText(activeWork.tracks.size() + " 首音轨");
            MediaItem current = currentPlaybackItem();
            lyricView.setText(current == null ? "点击音轨进入播放页" : "正在播放: " + current.audio.name);
        } else if (pageMode == PAGE_PLAYER) {
            MediaItem current = currentPlaybackItem();
            sectionTitleView.setText("正在播放");
            sectionSubtitleView.setText(playbackWork == null ? "" : playbackWork.name);
            if (current != null) {
                titleView.setText(current.audio.name);
                folderView.setText(playbackWork == null ? current.audio.parentPath : playbackWork.name);
            }
        } else if (pageMode == PAGE_LYRICS) {
            MediaItem current = currentPlaybackItem();
            sectionTitleView.setText("台词");
            sectionSubtitleView.setText(current == null ? "点击台词跳转播放位置" : current.audio.name);
        }
    }

    private void updateBottomNav() {
        configureNavButton(heardTabButton, currentTab == TAB_HEARD);
        configureNavButton(myTabButton, currentTab == TAB_MY);
        configureNavButton(settingsTabButton, currentTab == TAB_SETTINGS);
    }

    private void configureNavButton(Button button, boolean active) {
        if (button == null) {
            return;
        }
        button.setTextColor(active ? Color.rgb(18, 24, 38) : Color.rgb(124, 132, 144));
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setTextSize(active ? 15 : 13);
    }

    private void updatePageChrome(boolean animate) {
        boolean myTab = currentTab == TAB_MY;
        boolean playerVisible = myTab && pageMode == PAGE_PLAYER;
        boolean lyricsVisible = myTab && pageMode == PAGE_LYRICS;
        boolean listVisible = myTab && (pageMode == PAGE_WORKS || pageMode == PAGE_TRACKS);
        backButton.setVisibility(myTab && pageMode != PAGE_WORKS ? View.VISIBLE : View.GONE);
        playerArea.setVisibility(playerVisible ? View.VISIBLE : View.GONE);
        browserList.setVisibility(listVisible ? View.VISIBLE : View.GONE);
        lyricsList.setVisibility(lyricsVisible ? View.VISIBLE : View.GONE);
        recentList.setVisibility(currentTab == TAB_HEARD ? View.VISIBLE : View.GONE);
        settingsArea.setVisibility(currentTab == TAB_SETTINGS ? View.VISIBLE : View.GONE);
        updateBottomNav();
        updateMiniPlayer();
        if (animate) {
            View target = playerVisible ? playerArea
                    : (lyricsVisible ? lyricsList
                    : (listVisible ? browserList
                    : (currentTab == TAB_HEARD ? recentList : settingsArea)));
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
        playbackQueue.clear();
        playbackQueue.addAll(playlist);
        playbackWork = activeWork;
        playFromQueue(index, openPlayerPage);
    }

    private void playFromQueue(int index, boolean openPlayerPage) {
        if (index < 0 || index >= playbackQueue.size()) {
            return;
        }
        currentIndex = index;
        if (openPlayerPage) {
            showPlayer();
        }
        browserAdapter.notifyDataSetChanged();
        MediaItem item = playbackQueue.get(index);
        recordRecentTrack(item, playbackWork);
        titleView.setText(item.audio.name);
        folderView.setText(playbackWork == null ? item.audio.parentPath : playbackWork.name);
        lyricView.setText(item.vtt == null ? "未找到同名 .vtt 字幕" : "字幕已载入");
        updateFloatingLyricText();
        currentImageIndex = 0;
        showCurrentTrackImage();
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
                playButton.setText(ICON_PAUSE);
                miniPlayButton.setText(ICON_PAUSE);
                statusView.setText("播放中");
                handler.removeCallbacks(progressTick);
                handler.post(progressTick);
                updateMiniPlayer();
                updateFloatingLyricText();
            });
            player.setOnCompletionListener(mp -> playRelative(1, pageMode == PAGE_PLAYER));
            player.prepareAsync();
            statusView.setText("加载中");
            updateMiniPlayer();
        } catch (Exception ex) {
            Toast.makeText(this, "无法播放: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            statusView.setText("播放失败");
            updateMiniPlayer();
            updateFloatingLyricText();
        }
    }

    private void togglePlayback() {
        if (player == null) {
            if (!playbackQueue.isEmpty()) {
                playFromQueue(currentIndex >= 0 ? currentIndex : 0, true);
            } else if (!playlist.isEmpty()) {
                playAt(0, true);
            } else if (!works.isEmpty() && pageMode == PAGE_WORKS) {
                openWork(works.get(0));
            }
            return;
        }
        if (player.isPlaying()) {
            player.pause();
            playButton.setText(ICON_PLAY);
            miniPlayButton.setText(ICON_PLAY);
            statusView.setText("已暂停");
        } else {
            player.start();
            playButton.setText(ICON_PAUSE);
            miniPlayButton.setText(ICON_PAUSE);
            statusView.setText("播放中");
            handler.post(progressTick);
        }
        updateMiniPlayer();
        updateFloatingLyricText();
    }

    private void playRelative(int delta) {
        playRelative(delta, true);
    }

    private void playRelative(int delta, boolean openPlayerPage) {
        if (playbackQueue.isEmpty()) {
            return;
        }
        int next = currentIndex + delta;
        if (next < 0) {
            next = playbackQueue.size() - 1;
        } else if (next >= playbackQueue.size()) {
            next = 0;
        }
        playFromQueue(next, openPlayerPage);
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
        int activeIndex = -1;
        for (int i = 0; i < cues.size(); i++) {
            Cue cue = cues.get(i);
            if (positionMs >= cue.startMs) {
                activeIndex = i;
            } else {
                break;
            }
        }
        if (activeIndex < 0) {
            lyricView.setText("");
            updateFloatingLyricText();
            if (currentCueIndex != -1) {
                currentCueIndex = -1;
                lyricsAdapter.notifyDataSetChanged();
            }
            return;
        }
        Cue activeCue = cues.get(activeIndex);
        if (!activeCue.text.contentEquals(lyricView.getText())) {
            lyricView.setText(activeCue.text);
            updateFloatingLyricText();
        }
        if (currentCueIndex != activeIndex) {
            currentCueIndex = activeIndex;
            lyricsAdapter.notifyDataSetChanged();
            if (pageMode == PAGE_LYRICS) {
                lyricsList.smoothScrollToPosition(activeIndex);
            }
        }
    }

    private void clearLyricState() {
        lyricView.setText("");
        updateFloatingLyricText();
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
        updateFloatingLyricText();
        playButton.setText(ICON_PAUSE);
        miniPlayButton.setText(ICON_PAUSE);
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
        updateFloatingLyricText();
        currentImageIndex = 0;
        seekBar.setProgress(0);
        seekBar.setMax(0);
        miniSeekBar.setProgress(0);
        miniSeekBar.setMax(0);
        miniTimeView.setText("00:00 / 00:00");
        timeView.setText("00:00 / 00:00");
        playButton.setText(ICON_PLAY);
        miniPlayButton.setText(ICON_PLAY);
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
        MediaItem item = currentPlaybackItem();
        boolean hasPlayback = player != null && item != null;
        boolean showMini = hasPlayback && !(currentTab == TAB_MY && pageMode == PAGE_PLAYER);
        miniPlayer.setVisibility(showMini ? View.VISIBLE : View.GONE);
        if (!hasPlayback) {
            return;
        }
        miniTitleView.setText(item.audio.name);
        boolean playing = false;
        try {
            playing = player.isPlaying();
        } catch (IllegalStateException ignored) {
        }
        miniPlayButton.setText(playing ? ICON_PAUSE : ICON_PLAY);
        playButton.setText(playing ? ICON_PAUSE : ICON_PLAY);
    }

    private void showCurrentTrackImage() {
        MediaItem item = currentPlaybackItem();
        if (item == null) {
            coverView.setImageDrawable(null);
            return;
        }
        if (!item.images.isEmpty()) {
            currentImageIndex = Math.max(0, Math.min(currentImageIndex, item.images.size() - 1));
            coverView.setImageURI(item.images.get(currentImageIndex).uri);
        } else if (playbackWork != null && playbackWork.cover != null) {
            coverView.setImageURI(playbackWork.cover.uri);
        } else {
            coverView.setImageDrawable(null);
        }
    }

    private void showNextTrackImage() {
        MediaItem item = currentPlaybackItem();
        if (pageMode != PAGE_PLAYER || item == null) {
            return;
        }
        if (item.images.size() <= 1) {
            return;
        }
        currentImageIndex = (currentImageIndex + 1) % item.images.size();
        showCurrentTrackImage();
        statusView.setText("图片 " + (currentImageIndex + 1) + "/" + item.images.size());
    }

    private MediaItem currentPlaybackItem() {
        if (currentIndex < 0 || currentIndex >= playbackQueue.size()) {
            return null;
        }
        return playbackQueue.get(currentIndex);
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

    private void loadCoverInto(ImageView imageView, FileDoc cover) {
        if (cover == null) {
            imageView.setTag(null);
            imageView.setImageDrawable(workPlaceholder);
            return;
        }
        String key = cover.uri.toString();
        imageView.setTag(key);
        Bitmap cached = coverCache.get(key);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }
        imageView.setImageDrawable(workPlaceholder);
        int targetSize = dp(72);
        imageExecutor.execute(() -> {
            Bitmap bitmap = decodeThumbnail(cover.uri, targetSize);
            if (bitmap == null) {
                return;
            }
            coverCache.put(key, bitmap);
            handler.post(() -> {
                Object tag = imageView.getTag();
                if (key.equals(tag)) {
                    imageView.setImageBitmap(bitmap);
                }
            });
        });
    }

    private Bitmap decodeThumbnail(Uri uri, int targetSize) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) {
                    return null;
                }
                BitmapFactory.decodeStream(input, null, bounds);
            }
            int sampleSize = 1;
            while ((bounds.outWidth / sampleSize) > targetSize * 2
                    || (bounds.outHeight / sampleSize) > targetSize * 2) {
                sampleSize *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, sampleSize);
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) {
                    return null;
                }
                return BitmapFactory.decodeStream(input, null, options);
            }
        } catch (Exception ignored) {
            return null;
        }
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
                return workRow(works.get(position), position, convertView);
            }
            return trackRow(playlist.get(position), position, convertView);
        }

        private View workRow(WorkItem work, int position, View convertView) {
            WorkRowHolder holder;
            LinearLayout row;
            if (convertView instanceof LinearLayout && convertView.getTag() instanceof WorkRowHolder) {
                row = (LinearLayout) convertView;
                holder = (WorkRowHolder) row.getTag();
            } else {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), dp(8), dp(10), dp(8));
                row.setMinimumHeight(dp(88));
                row.setBackgroundResource(R.drawable.list_card);

                holder = new WorkRowHolder();
                holder.thumb = new ImageView(MainActivity.this);
                holder.thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                holder.thumb.setBackgroundColor(Color.rgb(226, 232, 238));
                row.addView(holder.thumb, new LinearLayout.LayoutParams(dp(72), dp(72)));

                LinearLayout texts = new LinearLayout(MainActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.setGravity(Gravity.CENTER_VERTICAL);
                texts.setPadding(dp(12), 0, 0, 0);
                row.addView(texts, new LinearLayout.LayoutParams(0, -1, 1));

                holder.title = label("", 16, Color.rgb(24, 28, 40));
                holder.title.setSingleLine(true);
                holder.title.setEllipsize(TextUtils.TruncateAt.END);
                texts.addView(holder.title, new LinearLayout.LayoutParams(-1, 0, 1));

                holder.subtitle = label("", 13, Color.rgb(118, 127, 139));
                holder.subtitle.setSingleLine(true);
                holder.subtitle.setEllipsize(TextUtils.TruncateAt.END);
                texts.addView(holder.subtitle, new LinearLayout.LayoutParams(-1, 0, 1));
                row.setTag(holder);
            }

            loadCoverInto(holder.thumb, work.cover);
            boolean moving = position == movingWorkIndex;
            holder.title.setText(work.name);
            holder.title.setTextColor(moving ? Color.rgb(163, 111, 28) : Color.rgb(24, 28, 40));
            String coverState = work.cover == null ? "无封面" : "有封面";
            String subtitle = moving ? "拖动中 · 松开保存位置" : work.tracks.size() + " 首音轨 · " + coverState;
            holder.subtitle.setText(subtitle);
            holder.subtitle.setTextColor(moving ? Color.rgb(163, 111, 28) : Color.rgb(118, 127, 139));
            return row;
        }

        private View trackRow(MediaItem item, int position, View convertView) {
            TrackRowHolder holder;
            LinearLayout row;
            if (convertView instanceof LinearLayout && convertView.getTag() instanceof TrackRowHolder) {
                row = (LinearLayout) convertView;
                holder = (TrackRowHolder) row.getTag();
            } else {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(8), dp(12), dp(8));
                row.setMinimumHeight(dp(58));
                row.setBackgroundResource(R.drawable.list_card);

                holder = new TrackRowHolder();
                holder.title = label("", 15, Color.rgb(24, 28, 40));
                holder.title.setSingleLine(true);
                holder.title.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(holder.title, new LinearLayout.LayoutParams(-1, 0, 1));

                holder.subtitle = label("", 12, Color.rgb(118, 127, 139));
                holder.subtitle.setSingleLine(true);
                holder.subtitle.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(holder.subtitle, new LinearLayout.LayoutParams(-1, 0, 1));
                row.setTag(holder);
            }

            MediaItem current = currentPlaybackItem();
            boolean sameWork = playbackWork != null && activeWork != null && playbackWork.key.equals(activeWork.key);
            boolean isCurrent = sameWork && current != null && current.key.equals(item.key);
            int titleColor = position == movingTrackIndex
                    ? Color.rgb(163, 111, 28)
                    : (isCurrent ? Color.rgb(69, 128, 99) : Color.rgb(24, 28, 40));
            holder.title.setText(item.audio.name);
            holder.title.setTextColor(titleColor);

            String subtitle = position == movingTrackIndex
                    ? "拖动中 · 松开保存位置"
                    : (item.vtt == null ? "无字幕" : "VTT") + " · " + item.audio.parentPath;
            holder.subtitle.setText(subtitle);
            holder.subtitle.setTextColor(position == movingTrackIndex ? Color.rgb(163, 111, 28) : Color.rgb(118, 127, 139));
            return row;
        }
    }

    private static class WorkRowHolder {
        ImageView thumb;
        TextView title;
        TextView subtitle;
    }

    private static class TrackRowHolder {
        TextView title;
        TextView subtitle;
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
            row.setBackgroundResource(R.drawable.list_card);

            boolean active = position == currentCueIndex;
            TextView time = label(formatTime((int) cue.startMs), 12, active ? Color.rgb(69, 128, 99) : Color.rgb(118, 127, 139));
            row.addView(time, new LinearLayout.LayoutParams(-1, dp(22)));

            TextView text = label(cue.text, active ? 17 : 15, active ? Color.rgb(24, 28, 40) : Color.rgb(78, 86, 98));
            text.setGravity(Gravity.CENTER_VERTICAL);
            text.setMaxLines(4);
            text.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(text, new LinearLayout.LayoutParams(-1, -2));
            return row;
        }
    }

    private class WebHistoryAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return webHistory.size();
        }

        @Override
        public Object getItem(int position) {
            return webHistory.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return pageMode == PAGE_WORKS ? 0 : 1;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView row = label(webHistory.get(position), 13, Color.rgb(54, 63, 76));
            row.setSingleLine(true);
            row.setEllipsize(TextUtils.TruncateAt.END);
            row.setPadding(dp(10), 0, dp(10), 0);
            row.setMinimumHeight(dp(44));
            row.setBackgroundResource(R.drawable.list_card);
            return row;
        }
    }

    private class RecentAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return recentTracks.size();
        }

        @Override
        public Object getItem(int position) {
            return recentTracks.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            RecentTrack recent = recentTracks.get(position);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(8), dp(12), dp(8));
            row.setMinimumHeight(dp(68));
            row.setBackgroundResource(R.drawable.list_card);

            TextView title = label(recent.trackName, 15, Color.rgb(24, 28, 40));
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(title, new LinearLayout.LayoutParams(-1, 0, 1));

            TextView sub = label(TextUtils.isEmpty(recent.workName) ? "未知作品" : recent.workName, 12, Color.rgb(118, 127, 139));
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(sub, new LinearLayout.LayoutParams(-1, 0, 1));
            return row;
        }
    }

    private static class WorkBuilder {
        final String key;
        String name;
        final List<FileDoc> audio = new ArrayList<>();
        final List<FileDoc> images = new ArrayList<>();
        final Map<String, FileDoc> vttByName = new HashMap<>();
        final Map<String, FileDoc> imageByStem = new HashMap<>();

        WorkBuilder(String key, String name) {
            this.key = TextUtils.isEmpty(key) ? name : key;
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
                List<FileDoc> trackImages = collectImagesFor(file, cover);
                FileDoc vtt = findVtt(file);
                tracks.add(new MediaItem(file, trackImages, vtt));
            }
            return new WorkItem(key, name, cover, tracks);
        }

        private List<FileDoc> collectImagesFor(FileDoc audioFile, FileDoc cover) {
            List<FileDoc> result = new ArrayList<>();
            FileDoc sameName = imageByStem.get(stemStatic(audioFile.name));
            addUnique(result, sameName);
            for (FileDoc image : images) {
                if (image.parentPath.equals(audioFile.parentPath)) {
                    addUnique(result, image);
                }
            }
            for (FileDoc image : images) {
                addUnique(result, image);
            }
            addUnique(result, cover);
            return result;
        }

        private void addUnique(List<FileDoc> result, FileDoc image) {
            if (image == null) {
                return;
            }
            for (FileDoc existing : result) {
                if (existing.uri.equals(image.uri)) {
                    return;
                }
            }
            result.add(image);
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
        final String key;
        final String name;
        final FileDoc cover;
        final List<MediaItem> tracks;

        WorkItem(String key, String name, FileDoc cover, List<MediaItem> tracks) {
            this.key = TextUtils.isEmpty(key) ? name : key;
            this.name = name;
            this.cover = cover;
            this.tracks = tracks;
        }
    }

    private static class MediaItem {
        final String key;
        final FileDoc audio;
        final FileDoc image;
        final List<FileDoc> images;
        final FileDoc vtt;

        MediaItem(FileDoc audio, List<FileDoc> images, FileDoc vtt) {
            this.key = audio.relativePath;
            this.audio = audio;
            this.images = images;
            this.image = images.isEmpty() ? null : images.get(0);
            this.vtt = vtt;
        }
    }

    private static class RecentTrack {
        final String workKey;
        final String trackKey;
        final String workName;
        final String trackName;

        RecentTrack(String workKey, String trackKey, String workName, String trackName) {
            this.workKey = workKey;
            this.trackKey = trackKey;
            this.workName = workName;
            this.trackName = trackName;
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
