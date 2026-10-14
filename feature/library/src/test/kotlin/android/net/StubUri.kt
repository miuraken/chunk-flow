package android.net

import android.os.Parcel
import sun.misc.Unsafe

/**
 * JVM 単体テスト（Robolectric なし）で `android.jar` の `Stub!` 例外を回避して [Uri] インスタンスを作る補助クラス。
 */
internal abstract class StubUriBase : Uri()

internal class StubUri private constructor() : StubUriBase() {
    override fun isHierarchical(): Boolean = true
    override fun isOpaque(): Boolean = false
    override fun isRelative(): Boolean = false
    override fun isAbsolute(): Boolean = true
    override fun getScheme(): String = "content"
    override fun getSchemeSpecificPart(): String = "//media/1"
    override fun getEncodedSchemeSpecificPart(): String = "//media/1"
    override fun getAuthority(): String = "media"
    override fun getEncodedAuthority(): String = "media"
    override fun getUserInfo(): String? = null
    override fun getEncodedUserInfo(): String? = null
    override fun getHost(): String = "media"
    override fun getPort(): Int = -1
    override fun getPath(): String = "/1"
    override fun getEncodedPath(): String = "/1"
    override fun getQuery(): String? = null
    override fun getEncodedQuery(): String? = null
    override fun getFragment(): String? = null
    override fun getEncodedFragment(): String? = null
    override fun getPathSegments(): MutableList<String> = mutableListOf("1")
    override fun getLastPathSegment(): String = "1"
    override fun buildUpon(): Builder = throw UnsupportedOperationException()
    override fun toString(): String = "content://media/1"
    override fun describeContents(): Int = 0
    override fun writeToParcel(out: Parcel, flags: Int) = Unit

    companion object {
        fun create(): Uri {
            val field = Unsafe::class.java.getDeclaredField("theUnsafe")
            field.isAccessible = true
            val unsafe = field.get(null) as Unsafe
            return unsafe.allocateInstance(StubUri::class.java) as Uri
        }
    }
}
