package com.fongmi.android.tv.ui.novel;

/**
 * 阅读器引擎统一接口：原生漫画 / 原生小说 / WebView 阅读器都实现它，
 * 供 NovelRouter 在「阅读器已在前台、解析结果回传」场景统一回调。
 */
public interface ReaderEngine {

    /**
     * 播放器/自解析完成后回传章节内容。
     *
     * @param kind    1=小说 2=漫画 3=PDF
     * @param payload novel:// 或 pics:// 原始内容
     * @param title   章节名
     */
    void onEpisodeResolved(int kind, String payload, String title);
}
