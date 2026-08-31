package com.fongmi.android.tv.ui.web;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.databinding.ActivityNovelReaderBinding;
import com.fongmi.android.tv.ui.novel.NovelReaderHost;
import com.fongmi.android.tv.ui.novel.NovelRouter;
import com.fongmi.android.tv.ui.novel.ReaderEngine;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 原生小说阅读器：照搬影视+ ReaderActivity 的 UI 与交互。
 * 左右翻页（ViewPager2 分页）/ 上下滚动（RecyclerView）双模式、
 * 字号/行距/背景设置、进度条、章节目录、上下章。
 */
public class NovelReaderActivity extends AppCompatActivity implements ReaderEngine {

    private ActivityNovelReaderBinding b;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private String siteKey = "", flag = "", vodId = "", vodName = "";
    private ArrayList<Episode> chapters = new ArrayList<>();
    private String title = "", content = "";
    private int index = 0;
    private int mode = 0;         // 0=上下滚动 1=左右翻页
    private float fontSize = 18f;
    private float lineSpacing = 1.9f;
    private int theme = 0;        // 0=米白 1=护眼 2=浅绿 3=浅蓝 4=夜间
    private boolean switching = false;
    private boolean uiVisible = true;

    private final int[][] themes = {
            {0xFFF5F3EF, 0xFF3A3632},
            {0xFFF5ECD8, 0xFF4A3F28},
            {0xFFE3EFE0, 0xFF2E4A33},
            {0xFFDDE9F5, 0xFF2A3D55},
            {0xFF1A1A2E, 0xFFE8ECF1}
    };

    private List<String> pages = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        b = ActivityNovelReaderBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        String key = getIntent().getStringExtra(WebReaderActivity.EXTRA_CACHE_KEY);
        String payload = WebReaderActivity.getCachedPayload(key);
        ArrayList<Episode> ch = WebReaderActivity.getCachedChapters(key);
        if (ch != null) chapters = ch;
        siteKey = nvl(getIntent().getStringExtra(WebReaderActivity.EXTRA_SITE_KEY));
        flag = nvl(getIntent().getStringExtra(WebReaderActivity.EXTRA_FLAG));
        vodId = nvl(getIntent().getStringExtra(WebReaderActivity.EXTRA_VOD_ID));
        vodName = nvl(getIntent().getStringExtra(WebReaderActivity.EXTRA_VOD_NAME));
        int idx = getIntent().getIntExtra(WebReaderActivity.EXTRA_INDEX, 0);
        if (idx >= 0 && idx < chapters.size()) index = idx;

        initViews();
        applyPayload(payload);
        NovelRouter.currentEngine = this;
    }

    private void initViews() {
        b.btnBack.setOnClickListener(v -> finish());
        b.btnChapterTop.setOnClickListener(v -> toggleChapterPanel(true));
        b.btnKeep.setOnClickListener(v -> {
            String k = "novel_keep_" + vodId + vodName;
            boolean on = !"1".equals(getSharedPreferences("novel", MODE_PRIVATE).getString(k, ""));
            getSharedPreferences("novel", MODE_PRIVATE).edit().putString(k, on ? "1" : "0").apply();
            b.btnKeep.setText(on ? "★" : "☆");
            toast(on ? "已收藏" : "已取消收藏");
        });
        b.btnPrevChapter.setOnClickListener(v -> switchChapter(index - 1));
        b.btnNextChapter.setOnClickListener(v -> switchChapter(index + 1));
        b.btnMode.setOnClickListener(v -> toggleMode());
        b.btnFontMinus.setOnClickListener(v -> changeFont(-1f));
        b.btnFontPlus.setOnClickListener(v -> changeFont(1f));
        b.btnSetting.setOnClickListener(v -> {
            b.settingOverlay.setVisibility(View.VISIBLE);
            b.settingPanel.setVisibility(View.VISIBLE);
        });
        b.settingOverlay.setOnClickListener(v -> hideSetting());
        b.chapterOverlay.setOnClickListener(v -> toggleChapterPanel(false));

        b.btnBigger.setOnClickListener(v -> changeFont(1f));
        b.btnSmaller.setOnClickListener(v -> changeFont(-1f));
        b.btnLinePlus.setOnClickListener(v -> changeLine(0.1f));
        b.btnLineMinus.setOnClickListener(v -> changeLine(-0.1f));
        b.themeWhite.setOnClickListener(v -> applyTheme(0));
        b.themeYellow.setOnClickListener(v -> applyTheme(1));
        b.themeGreen.setOnClickListener(v -> applyTheme(2));
        b.themeBlue.setOnClickListener(v -> applyTheme(3));
        b.themeDark.setOnClickListener(v -> applyTheme(4));

        b.recyclerView.setLayoutManager(new LinearLayoutManager(this));
        b.seekPage.setOnSeekBarChangeListener(new androidx.appcompat.widget.AppCompatSeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && pages.size() > 1) {
                    int target = (int) (progress / 100f * (pages.size() - 1));
                    if (mode == 1) b.viewPager.setCurrentItem(target, false);
                    else {
                        LinearLayoutManager lm = (LinearLayoutManager) b.recyclerView.getLayoutManager();
                        if (lm != null) lm.scrollToPositionWithOffset(target, 0);
                    }
                }
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {}
        });
    }

    private void applyPayload(String payload) {
        if (payload == null || payload.isEmpty()) { toast("无小说数据"); finish(); return; }
        JSONObject o = parseNovel(payload);
        title = o.optString("title", chapterName());
        content = o.optString("content", "");
        b.tvTitle.setText(title);
        applyMode();
    }

    private JSONObject parseNovel(String raw) {
        JSONObject out = new JSONObject();
        String body = raw == null ? "" : raw.trim();
        if (body.startsWith("novel://")) body = body.substring("novel://".length()).trim();
        try {
            return new JSONObject(body);
        } catch (Throwable e) {
            try { out.put("content", body); } catch (Throwable ignore) {}
        }
        return out;
    }

    /** 用 StaticLayout 把章节文本按当前字号/行距/页面尺寸分页。 */
    private List<String> paginate(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isEmpty()) { result.add("（本章内容为空）"); return result; }
        int width = getResources().getDisplayMetrics().widthPixels - dp(48);
        int height = getResources().getDisplayMetrics().heightPixels - dp(120);
        TextPaint paint = new TextPaint();
        paint.setTextSize(sp(fontSize));
        paint.setColor(themes[theme][1]);
        android.text.StaticLayout layout = new android.text.StaticLayout(
                text, paint, width, Layout.Alignment.ALIGN_NORMAL, lineSpacing, 0f, false);
        if (layout.getLineCount() == 0) { result.add(text); return result; }
        android.graphics.Paint.FontMetrics fm = paint.getFontMetrics();
        int lineHeight = (int) Math.ceil((fm.descent - fm.ascent) * lineSpacing);
        int linesPerPage = Math.max(1, height / lineHeight);
        int lineCount = layout.getLineCount();
        int start = 0;
        while (start < lineCount) {
            int end = Math.min(start + linesPerPage, lineCount);
            int startChar = layout.getLineStart(start);
            int endChar = layout.getLineEnd(end - 1);
            result.add(text.substring(startChar, Math.max(startChar, endChar)));
            start = end;
        }
        if (result.isEmpty()) result.add(text);
        return result;
    }

    private void applyMode() {
        pages = paginate(content);
        if (mode == 1) {
            b.recyclerView.setVisibility(View.GONE);
            b.viewPager.setVisibility(View.VISIBLE);
            b.viewPager.setAdapter(new PageAdapter());
            b.viewPager.registerOnPageChangeCallback(pageCb);
        } else {
            b.viewPager.setVisibility(View.GONE);
            b.recyclerView.setVisibility(View.VISIBLE);
            b.recyclerView.setAdapter(new ScrollAdapter());
        }
        updatePageInfo(0);
    }

    private final ViewPager2.OnPageChangeCallback pageCb = new ViewPager2.OnPageChangeCallback() {
        @Override public void onPageSelected(int position) { updatePageInfo(position); }
    };

    private void updatePageInfo(int pos) {
        int total = pages.size();
        int cur = Math.max(0, Math.min(pos, total - 1));
        b.tvPageInfo.setText((cur + 1) + "/" + total);
        b.seekPage.setProgress(total > 1 ? (int) (cur * 100f / (total - 1)) : 0);
    }

    private void toggleMode() {
        mode = 1 - mode;
        applyMode();
        toast(mode == 1 ? "已切换为左右翻页" : "已切换为上下滚动");
    }

    private void changeFont(float delta) {
        fontSize = Math.max(14f, Math.min(30f, fontSize + delta));
        b.tvFontSize.setText(String.valueOf((int) fontSize));
        applyMode();
    }

    private void changeLine(float delta) {
        lineSpacing = Math.max(1.2f, Math.min(2.5f, lineSpacing + delta));
        b.tvLineSpacing.setText(String.format(java.util.Locale.ROOT, "%.1f", lineSpacing));
        applyMode();
    }

    private void applyTheme(int t) {
        theme = t;
        applyThemeColors();
        applyMode();
    }

    private void applyThemeColors() {
        int fg = themes[theme][1];
        b.root.setBackgroundColor(themes[theme][0]);
        b.tvTitle.setTextColor(fg);
        b.btnBack.setTextColor(fg);
        b.btnChapterTop.setTextColor(fg);
        b.btnKeep.setTextColor(fg);
        b.tvPageInfo.setTextColor((fg & 0xFFFFFF) | 0x55000000);
    }

    private void toggleChapterPanel(boolean show) {
        if (show) {
            b.chapterOverlay.setVisibility(View.VISIBLE);
            b.chapterPanel.setVisibility(View.VISIBLE);
            b.rvChapter.setLayoutManager(new LinearLayoutManager(this));
            b.rvChapter.setAdapter(new ChapterAdapter());
        } else {
            b.chapterOverlay.setVisibility(View.GONE);
            b.chapterPanel.setVisibility(View.GONE);
        }
    }

    private void hideSetting() {
        b.settingOverlay.setVisibility(View.GONE);
        b.settingPanel.setVisibility(View.GONE);
    }

    private String chapterName() {
        return index >= 0 && index < chapters.size() ? chapters.get(index).getName() : vodName;
    }

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
                    int k = 1;
                    if (u != null && (u.startsWith("pics://") || u.startsWith("manga://"))) k = 2;
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
    public void onEpisodeResolved(int kind, String payload, String chapterTitle) {
        switching = false;
        if (kind != 1 || payload == null || payload.isEmpty()) { toast("章节内容无效"); return; }
        JSONObject o = parseNovel(payload);
        title = o.optString("title", chapterTitle);
        content = o.optString("content", "");
        b.tvTitle.setText(title);
        applyMode();
    }

    private void toggleUi() {
        uiVisible = !uiVisible;
        b.topBar.setVisibility(uiVisible ? View.VISIBLE : View.GONE);
        b.bottomBar.setVisibility(uiVisible ? View.VISIBLE : View.GONE);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private static String nvl(String s) { return s == null ? "" : s; }
    private int dp(int v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }
    private float sp(float v) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics()); }

    @Override
    protected void onDestroy() {
        NovelRouter.clearCurrentEngine(this);
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }

    /* ---------------- 适配器 ---------------- */

    private class PageAdapter extends RecyclerView.Adapter<PageAdapter.VH> {
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            TextView tv = new TextView(p.getContext());
            tv.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            tv.setTextSize(fontSize);
            tv.setLineSpacing(0f, lineSpacing);
            tv.setTextColor(themes[theme][1]);
            tv.setPadding(dp(24), dp(40), dp(24), dp(40));
            return new VH(tv);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            h.tv.setText(pages.get(pos));
        }
        @Override public int getItemCount() { return pages.size(); }
        class VH extends RecyclerView.ViewHolder {
            final TextView tv;
            VH(TextView v) { super(v); tv = v; }
        }
    }

    private class ScrollAdapter extends RecyclerView.Adapter<ScrollAdapter.VH> {
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            TextView tv = new TextView(p.getContext());
            tv.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            tv.setTextSize(fontSize);
            tv.setLineSpacing(0f, lineSpacing);
            tv.setTextColor(themes[theme][1]);
            tv.setPadding(dp(24), dp(8), dp(24), dp(8));
            return new VH(tv);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            h.tv.setText(pages.get(pos));
            h.tv.setOnClickListener(v -> toggleUi());
        }
        @Override public int getItemCount() { return pages.size(); }
        class VH extends RecyclerView.ViewHolder {
            final TextView tv;
            VH(TextView v) { super(v); tv = v; }
        }
    }

    private class ChapterAdapter extends RecyclerView.Adapter<ChapterAdapter.VH> {
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            TextView tv = new TextView(p.getContext());
            tv.setPadding(dp(40), dp(24), dp(40), dp(24));
            tv.setTextSize(14);
            tv.setTextColor(themes[theme][1]);
            return new VH(tv);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            h.tv.setText(chapters.get(pos).getName());
            h.tv.setTextColor(pos == index ? 0xFFFF8C42 : themes[theme][1]);
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
