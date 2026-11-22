package com.example

import com.example.data.KodBoxApiClient
import com.example.data.KodSyncEngine
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KodSessionAndSyncGuardTest {

    @Test
    fun isKodAuthErrorJson_detectsKodBoxAuthCodesAndMessages() {
        assertTrue(KodBoxApiClient.isKodAuthErrorJson(JSONObject("""{"code":10001,"data":"请先登录"}""")))
        assertTrue(KodBoxApiClient.isKodAuthErrorJson(JSONObject("""{"code":"10001","data":false}""")))
        assertTrue(KodBoxApiClient.isKodAuthErrorJson(JSONObject("""{"code":10002,"info":"token expired"}""")))
        assertTrue(KodBoxApiClient.isKodAuthErrorJson(JSONObject("""{"code":false,"data":"登录已过期，请重新登录"}""")))
        assertTrue(KodBoxApiClient.isKodAuthErrorJson(JSONObject("""{"code":false,"info":"accessToken invalid"}""")))
    }

    @Test
    fun isKodAuthErrorJson_ignoresSuccessAndNormalBusinessErrors() {
        assertFalse(KodBoxApiClient.isKodAuthErrorJson(JSONObject("""{"code":true,"data":{"items":[]}}""")))
        assertFalse(KodBoxApiClient.isKodAuthErrorJson(JSONObject("""{"code":false,"data":"文件名已存在"}""")))
        assertFalse(KodBoxApiClient.isKodAuthErrorJson(JSONObject("""{"code":false,"data":"当前目录没有写入权限"}""")))
        assertFalse(KodBoxApiClient.isKodAuthErrorJson(null))
    }

    @Test
    fun isAuthFailure_distinguishesExpiredSessionFromNetworkOrPermissionErrors() {
        assertTrue(KodBoxApiClient.isAuthFailure(IllegalStateException("服务器请求错误 (HTTP 401)")))
        assertTrue(KodBoxApiClient.isAuthFailure(IllegalStateException("会话未生效或二维码凭证已失效")))
        assertTrue(KodBoxApiClient.isAuthFailure(IllegalStateException("User not login")))
        assertTrue(KodBoxApiClient.isAuthFailure(IllegalStateException("access_token已失效，请重新登录")))

        assertFalse(KodBoxApiClient.isAuthFailure(IllegalStateException("网络连接失败，无法连接服务器")))
        assertFalse(KodBoxApiClient.isAuthFailure(IllegalStateException("HTTP 403 Forbidden")))
        assertFalse(KodBoxApiClient.isAuthFailure(IllegalStateException("没有权限访问此文件夹")))
    }

    @Test
    fun isWebLoginRedirectUrl_detectsSameHostLoginPagesOnly() {
        val server = "https://demo.box.kodcloud.com"
        assertTrue(KodBoxApiClient.isWebLoginRedirectUrl("$server/?user/login", server))
        assertTrue(KodBoxApiClient.isWebLoginRedirectUrl("$server/index.php?user/view/login&link=abc", server))
        assertTrue(KodBoxApiClient.isWebLoginRedirectUrl("$server/#user/login", server))
        assertTrue(KodBoxApiClient.isWebLoginRedirectUrl("$server/#login", server))

        assertFalse(KodBoxApiClient.isWebLoginRedirectUrl("$server/?plugin/onlyoffice/index&path=1", server))
        assertFalse(KodBoxApiClient.isWebLoginRedirectUrl("$server/#admin", server))
        assertFalse(KodBoxApiClient.isWebLoginRedirectUrl("https://other.example.com/?user/login", server))
    }

    @Test
    fun looksLikeHtmlLoginPage_identifiesHtmlLoginRedirects() {
        assertTrue(
            KodBoxApiClient.looksLikeHtmlLoginPage(
                "<!DOCTYPE html><html><head><title>Login</title></head><body><form action=\"?user/index/loginSubmit\"></form></body></html>"
            )
        )
        assertFalse(KodBoxApiClient.looksLikeHtmlLoginPage("""{"code":true,"data":"ok"}"""))
        assertFalse(KodBoxApiClient.looksLikeHtmlLoginPage("<!DOCTYPE html><html><body><h1>Readme</h1></body></html>"))
    }

    @Test
    fun syncEngineRuleLock_preventsConcurrentDuplicateExecutionPerRule() {
        val ruleA = "test-rule-a"
        val ruleB = "test-rule-b"
        try {
            assertFalse(KodSyncEngine.isRuleRunning(ruleA))
            assertTrue(KodSyncEngine.tryAcquireRuleLock(ruleA))
            assertTrue(KodSyncEngine.isRuleRunning(ruleA))
            assertTrue(KodSyncEngine.hasAnyRunningSync())

            // Second concurrent attempt on the same rule must be rejected immediately
            assertFalse(KodSyncEngine.tryAcquireRuleLock(ruleA))

            // Different rule can still acquire its own lock independently
            assertTrue(KodSyncEngine.tryAcquireRuleLock(ruleB))
            assertTrue(KodSyncEngine.isRuleRunning(ruleB))

            KodSyncEngine.releaseRuleLock(ruleA)
            assertFalse(KodSyncEngine.isRuleRunning(ruleA))
            assertTrue(KodSyncEngine.tryAcquireRuleLock(ruleA))
        } finally {
            KodSyncEngine.releaseRuleLock(ruleA)
            KodSyncEngine.releaseRuleLock(ruleB)
        }
    }
}
