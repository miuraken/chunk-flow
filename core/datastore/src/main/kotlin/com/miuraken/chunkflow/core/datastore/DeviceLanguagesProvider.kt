package com.miuraken.chunkflow.core.datastore

import android.content.res.Resources
import javax.inject.Inject

/**
 * 端末の設定言語（BCP-47 タグ。優先順）を返す（言語設定の既定に使う。ADR-0011 §2.1）。
 *
 * テストで差し替えられるように、インターフェースにしている。
 */
fun interface DeviceLanguagesProvider {
    /** 端末の設定言語の BCP-47 タグ（例: `"ja-JP"`、`"zh-Hant-TW"`）を、端末の優先順に返す。 */
    fun deviceLanguageTags(): List<String>
}

/**
 * Android のシステムの言語設定（設定アプリの「言語」）から読む [DeviceLanguagesProvider]。
 *
 * アプリごとの言語設定ではなく、端末全体の言語の一覧を使う。
 */
class SystemDeviceLanguagesProvider @Inject constructor() : DeviceLanguagesProvider {
    override fun deviceLanguageTags(): List<String> {
        val locales = Resources.getSystem().configuration.locales
        return (0 until locales.size()).map { index -> locales[index].toLanguageTag() }
    }
}
