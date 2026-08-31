package com.fongmi.android.tv.ui.web;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.bumptech.glide.request.RequestOptions;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.databinding.ActivityComicReaderBinding;
import com.fongmi.android.tv.ui.novel.NovelReaderHost;
import com.fongmi.android.tv.ui.novel.NovelRouter;
import com.fongmi.android.tv.ui.novel.ReaderEngine;
import com.github.catvod.crawler.SpiderDebug;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 原生漫画阅读器：照搬影视+ PicsActivity 的 UI 与交互。
 * 支持左右翻页（ViewPager2）/ 上下滚动（RecyclerView）双模式、双击切换、
 * 底部控制栏（进度条/页码/上下章/下载/模式/自动滚动/设置）、章节抽屉、图片下载。
 */
public class ComicReaderActivity extends AppCompatActivity implements ReaderEngine {

    private static final String TAG = "ComicReader";
    private static final String UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36";
    private static final long MAX_IMAGE_BYTES = 16L * 1024 * 1024;

    private ActivityComicReaderBinding b;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    private String siteKey = "", flag = "", vodId = "", vodName = "", vodPic = "";
    private ArrayList<Episode> chapters = new ArrayList<>();
    private ArrayList<PicItem> pics = new ArrayList<>();
    private int index = 0;
    private int mode = 0;          // 0=上下滚动 1=左右翻页
    private int transitionFull = 1; // 分界图高度 1=全屏 0=半屏
    private boolean uiVisible = true;
    private boolean switching = false;
    private boolean autoScrollOn = false;
    private final Runnable autoScrollTask = new Runnable() {
        @Override
        public void run() {
            if (mode == 0) {
                if (b.recyclerView == null) return;
                b.recyclerView.smoothScrollBy(0, b.recyclerView.getHeight() / 2);
            } else {
                int cur = b.viewPager.getCurrentItem();
                if (cur < pics.size() - 1) b.viewPager.setCurrentItem(cur + 1, true);
                else stopAutoScroll();
            }
            handler.postDelayed(this, 2000);
        }
    };

    public static class PicItem {
        public final String url;
        public final String referer;
        public PicItem(String url, String referer) {
            this.url = url;
            this.referer = referer;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        b = ActivityComicReaderBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        String key = getIntent().getStringExtra(WebReaderActivity.EXTRA_CACHE_KEY);
        String payload = WebReaderActivity.getCachedPayload(key);
        ArrayList<Episode> ch = WebReaderActivity.getCachedChapters(key);
        if (ch != null) chapters = ch;
        siteKey = nvl(getIntent().getStringExtra(WebReaderActivity.EXTRA_SITE_KEY));
        flag = nvl(getIntent().getStringExtra(WebReaderActivity.EXTRA_FLAG));
        vodId = nvl(getIntent().getStringExtra(WebReaderActivity.EXTRA_VOD_ID));
        vodName = nvl(getIntent().getStringExtra(WebReaderActivity.EXTRA_VOD_NAME));
        vodPic = nvl(getIntent().getStringExtra(WebReaderActivity.EXTRA_VOD_PIC));
        int idx = getIntent().getIntExtra(WebReaderActivity.EXTRA_INDEX, 0);
        if (idx >= 0 && idx < chapters.size()) index = idx;

        initViews();
        applyPayload(payload);
        NovelRouter.currentEngine = this;
    }

    private void initViews() {
        b.btnTopChapter.setOnClickListener(v -> toggleChapterPanel(true));
        b.btnKeep.setOnClickListener(v -> {
            // 收藏：本地记录
            String key = "novel_keep_" + vodId + vodName;
            boolean on = !"1".equals(getSharedPreferences("comic", MODE_PRIVATE).getString(key, ""));
            getSharedPreferences("comic", MODE_PRIVATE).edit().putString(key, on ? "1" : "0").apply();
            b.btnKeep.setText(on ? "★" : "☆");
            toast(on ? "已收藏" : "已取消收藏");
        });
        b.btnPrevChapter.setOnClickListener(v -> switchChapter(index - 1));
        b.btnNextChapter.setOnClickListener(v -> switchChapter(index + 1));
        b.btnMode.setOnClickListener(v -> toggleMode());
        b.btnAutoScroll.setOnClickListener(v -> toggleAutoScroll());
        b.btnSetting.setOnClickListener(v -> {
            b.settingOverlay.setVisibility(View.VISIBLE);
            b.settingPanel.setVisibility(View.VISIBLE);
        });
        b.btnDownload.setOnClickListener(v -> downloadCurrent());
        b.settingOverlay.setOnClickListener(v -> hideSetting());
        b.chapterOverlay.setOnClickListener(v -> toggleChapterPanel(false));

        b.btnAnimHorizontal.setOnClickListener(v -> { mode = 1; applyMode(); hideSetting(); });
        b.btnAnimVertical.setOnClickListener(v -> { mode = 0; applyMode(); hideSetting(); });
        b.btnTransitionHeight.setOnClickListener(v -> {
            transitionFull = 1 - transitionFull;
            b.btnTransitionHeight.setText(transitionFull == 1 ? "全屏" : "半屏");
            if (mode == 0) applyMode();
        });

        b.seekPage.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && pics.size() > 1) {
                    int target = (int) (progress / 100f * (pics.size() - 1));
                    if (mode == 1) b.viewPager.setCurrentItem(target, false);
                    else {
                        LinearLayoutManager lm = (LinearLayoutManager) b.recyclerView.getLayoutManager();
                        if (lm != null) lm.scrollToPositionWithOffset(target, 0);
                    }
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        b.recyclerView.setLayoutManager(new LinearLayoutManager(this));
        b.recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (mode != 0) return;
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm == null) return;
                int first = lm.findFirstVisibleItemPosition();
                if (first >= 0) updatePageInfo(first);
            }
        });
    }

    /** 解析 pics://url1&&url2 为图片列表，保留 @Referer= / @User-Agent= 防盗链头。 */
    private ArrayList<PicItem> parsePics(String raw) {
        ArrayList<PicItem> out = new ArrayList<>();
        if (raw == null) return out;
        String s = raw.trim();
        if (s.startsWith("pics://") || s.startsWith("manga://")) s = s.substring(s.indexOf("://") + 3);
        for (String u : s.split("&&")) {
            if (u == null) continue;
            u = u.trim();
            if (u.isEmpty()) continue;
            String referer = null;
            int ref = u.indexOf("@Referer=");
            if (ref > 0) {
                referer = u.substring(ref + "@Referer=".length()).trim();
                u = u.substring(0, ref);
            }
            int ua = u.indexOf("@User-Agent=");
            if (ua > 0) u = u.substring(0, ua);
            out.add(new PicItem(u, referer));
        }
        return out;
    }

    private void applyPayload(String payload) {
        if (payload == null || payload.isEmpty()) { toast("无漫画数据"); finish(); return; }
        pics = parsePics(payload);
        if (pics.isEmpty()) { toast("无漫画图片"); finish(); return; }
        b.tvTitle.setText(chapterName());
        applyMode();
    }

    private void applyMode() {
        if (mode == 1) {
            b.recyclerView.setVisibility(View.GONE);
            b.viewPager.setVisibility(View.VISIBLE);
            b.viewPager.setAdapter(new PagerAdapter());
            b.viewPager.registerOnPageChangeCallback(pageCb);
            updatePageInfo(0);
        } else {
            b.viewPager.setVisibility(View.GONE);
            b.recyclerView.setVisibility(View.VISIBLE);
            b.recyclerView.setAdapter(new ScrollAdapter());
            updatePageInfo(0);
        }
        b.btnAnimHorizontal.setAlpha(mode == 1 ? 1f : 0.5f);
        b.btnAnimVertical.setAlpha(mode == 0 ? 1f : 0.5f);
    }

    private final ViewPager2.OnPageChangeCallback pageCb = new ViewPager2.OnPageChangeCallback() {
        @Override public void onPageSelected(int position) { updatePageInfo(position); }
    };

    private void updatePageInfo(int pos) {
        int total = pics.size();
        int cur = Math.max(0, Math.min(pos, total - 1));
        b.tvPageInfo.setText((cur + 1) + "/" + total);
        b.seekPage.setProgress(total > 1 ? (int) (cur * 100f / (total - 1)) : 0);
    }

    private void toggleMode() {
        mode = 1 - mode;
        applyMode();
        toast(mode == 1 ? "已切换为左右翻页" : "已切换为上下滚动");
    }

    private void toggleAutoScroll() {
        if (autoScrollOn) { stopAutoScroll(); return; }
        autoScrollOn = true;
        b.btnAutoScroll.setText("停止");
        handler.postDelayed(autoScrollTask, 1500);
        toast("自动滚动已开启");
    }

    private void stopAutoScroll() {
        autoScrollOn = false;
        handler.removeCallbacks(autoScrollTask);
        b.btnAutoScroll.setText("滚动");
    }

    private void toggleChapterPanel(boolean show) {
        if (show) {
            b.chapterOverlay.setVisibility(View.VISIBLE);
            b.chapterPanel.setVisibility(View.VISIBLE);
            renderChapters();
        } else {
            b.chapterOverlay.setVisibility(View.GONE);
            b.chapterPanel.setVisibility(View.GONE);
        }
    }

    private void hideSetting() {
        b.settingOverlay.setVisibility(View.GONE);
        b.settingPanel.setVisibility(View.GONE);
    }

    private void renderChapters() {
        b.rvChapter.setLayoutManager(new LinearLayoutManager(this));
        b.rvChapter.setAdapter(new ChapterAdapter());
    }

    private String chapterName() {
        return index >= 0 && index < chapters.size() ? chapters.get(index).getName() : vodName;
    }

    /** 切章：优先自行解析，无 siteKey 时交给播放器宿主。 */
    private void switchChapter(int i) {
        if (i < 0) { toast("已是第一章"); return; }
        if (i >= chapters.size()) { toast("已是最后一章"); return; }
        if (switching) return;
        switching = true;
        index = i;
        b.tvTitle.setText(chapterName());
        String url = chapters.get(i).getUrl();
        if (!siteKey.isEmpty()) {
            executor.execute(() -> {
                try {
                    Result r = SiteApi.playerContent(siteKey, flag, url);
                    String u = r == null ? null : r.getRealUrl();
                    int k = 2;
                    if (u != null && u.startsWith("novel://")) k = 1;
                    else if (u != null && u.startsWith("pics://") || (u != null && u.startsWith("manga://"))) k = 2;
                    final int fk = k;
                    final String fp = u;
                    runOnUiThread(() -> onEpisodeResolved(fk, fp, chapterName()));
                } catch (Throwable e) {
                    runOnUiThread(() -> { switching = false; toast("章节解析失败"); });
                }
            });
        } else {
            NovelReaderHost h = NovelRouter.getHost();
            if (h != null) h.labPlayEpisode(url);
            else { switching = false; toast("章节解析失败"); }
        }
    }

    @Override
    public void onEpisodeResolved(int kind, String payload, String title) {
        switching = false;
        if (kind != 2 || payload == null || payload.isEmpty()) { toast("章节内容无效"); return; }
        pics = parsePics(payload);
        if (pics.isEmpty()) { toast("无漫画图片"); return; }
        b.tvTitle.setText(title == null || title.isEmpty() ? chapterName() : title);
        applyMode();
    }

    /** 下载当前可见页到相册。 */
    private void downloadCurrent() {
        int pos = mode == 1 ? b.viewPager.getCurrentItem() : firstVisible();
        if (pos < 0 || pos >= pics.size()) { toast("没有可下载的图片"); return; }
        PicItem item = pics.get(pos);
        executor.execute(() -> {
            try {
                Request.Builder rb = new Request.Builder().url(item.url).header("User-Agent", UA);
                if (item.referer != null && !item.referer.isEmpty()) rb.header("Referer", item.referer);
                try (Response resp = http.newCall(rb.build()).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) return;
                    byte[] data = readCapped(resp.body().byteStream(), MAX_IMAGE_BYTES);
                    if (data == null || data.length == 0) return;
                    saveImageBytes(data);
                    runOnUiThread(() -> toast("图片已保存到相册"));
                }
            } catch (Throwable e) {
                runOnUiThread(() -> toast("图片保存失败"));
            }
        });
    }

    private void saveImageBytes(byte[] data) {
        String name = "webhtv_" + System.currentTimeMillis() + ".jpg";
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WebHTV");
                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return;
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os != null) os.write(data);
                }
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "WebHTV");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, name);
                try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(data); }
            }
        } catch (Throwable ignore) {}
    }

    private int firstVisible() {
        LinearLayoutManager lm = (LinearLayoutManager) b.recyclerView.getLayoutManager();
        return lm == null ? 0 : lm.findFirstVisibleItemPosition();
    }

    private static byte[] readCapped(java.io.InputStream in, long limit) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(8192);
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > limit) return null;
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (Throwable e) { return null; }
    }

    private Object loadModel(PicItem item) {
        if (item.referer != null && !item.referer.isEmpty()) {
            return new GlideUrl(item.url, new LazyHeaders.Builder()
                    .addHeader("Referer", item.referer).addHeader("User-Agent", UA).build());
        }
        return item.url;
    }

    private void loadInto(ImageView iv, PicItem item) {
        Glide.with(this).load(loadModel(item))
                .apply(new RequestOptions().placeholder(Color.TRANSPARENT)).into(iv);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private static String nvl(String s) { return s == null ? "" : s; }

    @Override
    protected void onDestroy() {
        NovelRouter.clearCurrentEngine(this);
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }

    /* ---------------- 适配器 ---------------- */

    private class PagerAdapter extends RecyclerView.Adapter<PagerAdapter.VH> {
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            ImageView iv = new ImageView(p.getContext());
            iv.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            return new VH(iv);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            loadInto(h.iv, pics.get(pos));
        }
        @Override public int getItemCount() { return pics.size(); }
        class VH extends RecyclerView.ViewHolder {
            final ImageView iv;
            VH(ImageView v) { super(v); iv = v; }
        }
    }

    private class ScrollAdapter extends RecyclerView.Adapter<ScrollAdapter.VH> {
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            LayoutInflater inf = LayoutInflater.from(p.getContext());
            View v = inf.inflate(R.layout.item_comic_scroll, p, false);
            return new VH(v);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            loadInto(h.img, pics.get(pos));
            h.label.setText((pos + 1) + "/" + pics.size());
        }
        @Override public int getItemCount() { return pics.size(); }
        class VH extends RecyclerView.ViewHolder {
            final ImageView img;
            final TextView label;
            VH(View v) { super(v); img = v.findViewById(R.id.comicImg); label = v.findViewById(R.id.comicLabel); }
        }
    }

    private class ChapterAdapter extends RecyclerView.Adapter<ChapterAdapter.VH> {
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            TextView tv = new TextView(p.getContext());
            tv.setPadding(40, 24, 40, 24);
            tv.setTextSize(14);
            return new VH(tv);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            h.tv.setText(chapters.get(pos).getName());
            h.tv.setTextColor(pos == index ? 0xFFFF6B35 : 0xFFE8ECF1);
            h.tv.setOnClickListener(v -> {
                toggleChapterPanel(false);
                switchChapter(pos);
            });
        }
        @Override public int getItemCount() { return chapters.size(); }
        class VH extends RecyclerView.ViewHolder {
            final TextView tv;
            VH(TextView v) { super(v); tv = v; }
        }
    }
}
