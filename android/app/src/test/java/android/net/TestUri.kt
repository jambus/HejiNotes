package android.net

import android.os.Parcel

class TestUri(private val raw: String) : Uri() {
    override fun toString(): String = raw
    override fun isHierarchical(): Boolean = true
    override fun isRelative(): Boolean = false
    override fun getScheme(): String = "content"
    override fun getSchemeSpecificPart(): String = raw
    override fun getEncodedSchemeSpecificPart(): String = raw
    override fun getAuthority(): String = "test.authority"
    override fun getEncodedAuthority(): String = "test.authority"
    override fun getUserInfo(): String? = null
    override fun getEncodedUserInfo(): String? = null
    override fun getHost(): String = "test"
    override fun getPort(): Int = -1
    override fun getPath(): String = raw
    override fun getEncodedPath(): String = raw
    override fun getQuery(): String? = null
    override fun getEncodedQuery(): String? = null
    override fun getFragment(): String? = null
    override fun getEncodedFragment(): String? = null
    override fun getPathSegments(): List<String> = listOf(raw)
    override fun getLastPathSegment(): String = raw
    override fun equals(other: Any?): Boolean = other is TestUri && other.raw == raw
    override fun hashCode(): Int = raw.hashCode()
    override fun compareTo(other: Uri?): Int = raw.compareTo(other?.toString() ?: "")
    override fun buildUpon(): Builder? = null
    override fun describeContents(): Int = 0
    override fun writeToParcel(dest: Parcel, flags: Int) {}
}
