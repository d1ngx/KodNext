package com.example

import com.example.data.KodQrLoginParser
import com.example.data.KodQrPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KodQrLoginParserTest {

    @Test
    fun parsesAccountCenterUrlWithAccessToken() {
        val raw = "https://demo.box.kodcloud.com/index.php?user/index/init&accessToken=abc123xyz"
        val result = KodQrLoginParser.parse(raw)
        assertTrue(result is KodQrPayload.TokenLogin)
        val tokenLogin = result as KodQrPayload.TokenLogin
        assertEquals("https://demo.box.kodcloud.com", tokenLogin.serverUrl)
        assertEquals("abc123xyz", tokenLogin.accessToken)
    }

    @Test
    fun parsesFragmentLoginTokenUrl() {
        val raw = "https://cloud.example.com:8443/drive/#login?loginToken=tok_987654"
        val result = KodQrLoginParser.parse(raw)
        assertTrue(result is KodQrPayload.TokenLogin)
        val tokenLogin = result as KodQrPayload.TokenLogin
        assertEquals("https://cloud.example.com:8443/drive", tokenLogin.serverUrl)
        assertEquals("tok_987654", tokenLogin.accessToken)
    }

    @Test
    fun parsesJsonTokenPayload() {
        val raw = """{"url":"https://nas.local:8080/","accessToken":"secret_token_42"}"""
        val result = KodQrLoginParser.parse(raw)
        assertTrue(result is KodQrPayload.TokenLogin)
        val tokenLogin = result as KodQrPayload.TokenLogin
        assertEquals("https://nas.local:8080", tokenLogin.serverUrl)
        assertEquals("secret_token_42", tokenLogin.accessToken)
    }

    @Test
    fun parsesNestedJsonSnakeCaseTokenPayload() {
        val raw = """{"type":"appLogin","data":{"server_url":"https://box.company.cn/","access_token":"tok_nested_snake_99"}}"""
        val result = KodQrLoginParser.parse(raw)
        assertTrue(result is KodQrPayload.TokenLogin)
        val tokenLogin = result as KodQrPayload.TokenLogin
        assertEquals("https://box.company.cn", tokenLogin.serverUrl)
        assertEquals("tok_nested_snake_99", tokenLogin.accessToken)
    }

    @Test
    fun parsesWrapperUrlWithEmbeddedServerAndToken() {
        val raw = "https://kodcloud.com/app/download/?url=https%3A%2F%2Fdrive.myorg.cn&access_token=wrap_tok_77"
        val result = KodQrLoginParser.parse(raw)
        assertTrue(result is KodQrPayload.TokenLogin)
        val tokenLogin = result as KodQrPayload.TokenLogin
        assertEquals("https://drive.myorg.cn", tokenLogin.serverUrl)
        assertEquals("wrap_tok_77", tokenLogin.accessToken)
    }

    @Test
    fun parsesHashPathTokenFormat() {
        val raw = "https://demo.box.kodcloud.com/#appLogin/01dastAzFSuOLz4XCqO50r79DI0XA"
        val result = KodQrLoginParser.parse(raw)
        assertTrue(result is KodQrPayload.TokenLogin)
        val tokenLogin = result as KodQrPayload.TokenLogin
        assertEquals("https://demo.box.kodcloud.com", tokenLogin.serverUrl)
        assertEquals("01dastAzFSuOLz4XCqO50r79DI0XA", tokenLogin.accessToken)
    }

    @Test
    fun parsesCustomSchemeKodboxQr() {
        val raw = "kodbox://login?server=https%3A%2F%2Fdisk.team.io&loginToken=custom_scheme_tok"
        val result = KodQrLoginParser.parse(raw)
        assertTrue(result is KodQrPayload.TokenLogin)
        val tokenLogin = result as KodQrPayload.TokenLogin
        assertEquals("https://disk.team.io", tokenLogin.serverUrl)
        assertEquals("custom_scheme_tok", tokenLogin.accessToken)
    }

    @Test
    fun parsesNcLoginUri() {
        val raw = "nc://login/user:alice&password:pass123&server:https://drive.example.org"
        val result = KodQrLoginParser.parse(raw)
        assertTrue(result is KodQrPayload.CredentialsLogin)
        val creds = result as KodQrPayload.CredentialsLogin
        assertEquals("https://drive.example.org", creds.serverUrl)
        assertEquals("alice", creds.username)
        assertEquals("pass123", creds.password)
    }

    @Test
    fun parsesPlainServerUrlAsServerOnly() {
        val raw = "https://demo.box.kodcloud.com/"
        val result = KodQrLoginParser.parse(raw)
        assertTrue(result is KodQrPayload.ServerOnly)
        assertEquals("https://demo.box.kodcloud.com", (result as KodQrPayload.ServerOnly).serverUrl)
    }

    @Test
    fun preservesPlusAndUnderscoreCharactersInAccessToken() {
        val raw = "https://demo.box.kodcloud.com/?user/index/init&accessToken=01dastAz+FSuOLz4_XCqO50r79DI0XA-xItkja"
        val result = KodQrLoginParser.parse(raw)
        assertTrue(result is KodQrPayload.TokenLogin)
        val tokenLogin = result as KodQrPayload.TokenLogin
        assertEquals("https://demo.box.kodcloud.com", tokenLogin.serverUrl)
        assertEquals("01dastAz+FSuOLz4_XCqO50r79DI0XA-xItkja", tokenLogin.accessToken)
    }

    @Test
    fun parsesBase64EncodedJsonQrPayload() {
        val json = """{"url":"https://cloud.fastcube.cn/","accessToken":"tok_base64_ok"}"""
        val b64 = android.util.Base64.encodeToString(json.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        val result = KodQrLoginParser.parse(b64)
        assertTrue(result is KodQrPayload.TokenLogin)
        val tokenLogin = result as KodQrPayload.TokenLogin
        assertEquals("https://cloud.fastcube.cn", tokenLogin.serverUrl)
        assertEquals("tok_base64_ok", tokenLogin.accessToken)
    }

    @Test
    fun parsesKodBoxQrCallbackUrlAsAuthUrl() {
        val raw = "https://demo.box.kodcloud.com/?user/index/qrcode&code=qr_session_8899"
        val result = KodQrLoginParser.parse(raw)
        assertTrue(result is KodQrPayload.AuthUrl)
        val authUrl = result as KodQrPayload.AuthUrl
        assertEquals("https://demo.box.kodcloud.com", authUrl.serverUrl)
        assertEquals(raw, authUrl.fullUrl)
    }

    @Test
    fun treatsUnknownQueryCallbackUrlAsAuthUrlInsteadOfServerOnly() {
        val raw = "https://demo.box.kodcloud.com/?user/view/call&k=98af72bc"
        val result = KodQrLoginParser.parse(raw)
        assertTrue(result is KodQrPayload.AuthUrl)
        val authUrl = result as KodQrPayload.AuthUrl
        assertEquals("https://demo.box.kodcloud.com", authUrl.serverUrl)
        assertEquals(raw, authUrl.fullUrl)
    }

    @Test
    fun describeSafelyRedactsSecrets() {
        val raw = "https://demo.box.kodcloud.com/?user/index/init&accessToken=super_secret_token_value"
        val safe = KodQrLoginParser.describeSafely(raw)
        assertFalse(safe.contains("super_secret_token_value"))
        assertTrue(safe.contains("demo.box.kodcloud.com"))
    }

    @Test
    fun rejectsUnrelatedText() {
        val result = KodQrLoginParser.parse("hello world not a drive qr")
        assertTrue(result is KodQrPayload.Unsupported)
    }
}
