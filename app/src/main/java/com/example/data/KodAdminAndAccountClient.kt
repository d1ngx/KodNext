package com.example.data

import com.example.model.KodAccount
import com.example.model.KodAdminGroup
import com.example.model.KodAdminMember
import com.example.model.KodAdminOverview
import com.example.model.KodAdminPlugin
import com.example.model.KodAdminStorage
import com.example.model.KodUserDeviceItem
import com.example.model.KodUserLogItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dedicated repository client for KodBox Account Center (user/setting endpoints) and
 * Administrator Console (admin endpoints), reusing [KodBoxApiClient]'s connection pool and session.
 */
class KodAdminAndAccountClient(
    private val apiClient: KodBoxApiClient
) {
    suspend fun updateUserProfileField(
        account: KodAccount,
        key: String,
        value: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        apiClient.hydrateAccountSession(account)
        runCatching {
            val json = apiClient.executeKodPost(
                account = account,
                route = "user/setting/setUserInfo",
                params = mapOf("key" to key, "value" to value)
            )
            if (!isSuccess(json)) {
                error(extractError(json, "保存个人资料失败"))
            }
        }
    }

    suspend fun changePassword(
        account: KodAccount,
        oldPassword: String,
        newPassword: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        apiClient.hydrateAccountSession(account)
        runCatching {
            val json = apiClient.executeKodPost(
                account = account,
                route = "user/setting/setUserInfo",
                params = mapOf(
                    "key" to "password",
                    "password" to oldPassword,
                    "value" to newPassword
                )
            )
            if (!isSuccess(json)) {
                error(extractError(json, "修改密码失败，请检查原密码是否正确"))
            }
        }
    }

    suspend fun fetchUserDevices(account: KodAccount): Result<List<KodUserDeviceItem>> = withContext(Dispatchers.IO) {
        apiClient.hydrateAccountSession(account)
        runCatching {
            val json = apiClient.executeKodGet(account, "user/setting/userDevice", emptyMap())
            if (!isSuccess(json)) error(extractError(json, "加载登录设备失败"))
            val arr = json.optJSONArray("data") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val desc = obj.optJSONObject("desc")
                    add(
                        KodUserDeviceItem(
                            ip = obj.optString("ip"),
                            address = obj.optString("address"),
                            userAgent = desc?.optString("ua").orEmpty().ifBlank { obj.optString("ua") },
                            action = desc?.optString("reqAction").orEmpty(),
                            createTime = obj.optLong("createTime", 0L)
                        )
                    )
                }
            }
        }
    }

    suspend fun fetchUserLogs(account: KodAccount, pageNum: Int = 30): Result<List<KodUserLogItem>> = withContext(Dispatchers.IO) {
        apiClient.hydrateAccountSession(account)
        runCatching {
            val json = apiClient.executeKodGet(
                account = account,
                route = "user/setting/userLog",
                params = mapOf("page" to "1", "pageNum" to pageNum.toString())
            )
            if (!isSuccess(json)) error(extractError(json, "加载个人日志失败"))
            val dataObj = json.optJSONObject("data")
            val arr = dataObj?.optJSONArray("list") ?: json.optJSONArray("data") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val descObj = obj.optJSONObject("desc")
                    val detail = descObj?.optString("pathDisplay").orEmpty()
                        .ifBlank { descObj?.optString("name").orEmpty() }
                        .ifBlank { obj.optString("address") }
                    add(
                        KodUserLogItem(
                            id = obj.optString("id").ifBlank { "${i}_${obj.optString("createTime")}" },
                            type = obj.optString("type"),
                            title = obj.optString("title").ifBlank { obj.optString("type") },
                            detail = detail,
                            createTime = obj.optLong("createTime", 0L)
                        )
                    )
                }
            }
        }
    }

    /**
     * Loads the full KodBox Admin Console overview in parallel across 5 admin endpoints.
     */
    suspend fun fetchAdminOverview(account: KodAccount): Result<KodAdminOverview> = withContext(Dispatchers.IO) {
        apiClient.hydrateAccountSession(account)
        runCatching {
            coroutineScope {
                val settingJob = async { runCatching { apiClient.executeKodGet(account, "admin/setting/get", emptyMap()) }.getOrNull() }
                val membersJob = async {
                    runCatching {
                        apiClient.executeKodGet(
                            account,
                            "admin/member/get",
                            mapOf("groupID" to "1", "page" to "1", "pageNum" to "60")
                        )
                    }.getOrNull()
                }
                val groupsJob = async { runCatching { apiClient.executeKodGet(account, "admin/group/get", emptyMap()) }.getOrNull() }
                val storagesJob = async { runCatching { apiClient.executeKodGet(account, "admin/storage/get", emptyMap()) }.getOrNull() }
                val pluginsJob = async { runCatching { apiClient.executeKodGet(account, "admin/plugin/appList", emptyMap()) }.getOrNull() }

                val settingJson = settingJob.await()
                val membersJson = membersJob.await()
                val groupsJson = groupsJob.await()
                val storagesJson = storagesJob.await()
                val pluginsJson = pluginsJob.await()

                val allUnauthorized = listOfNotNull(settingJson, membersJson, groupsJson, storagesJson, pluginsJson)
                    .all { !isSuccess(it) }
                if (allUnauthorized) {
                    error("当前账号无后台管理权限，请使用系统管理员账号登录")
                }

                val settingData = settingJson?.optJSONObject("data")
                val systemName = settingData?.optString("systemName").orEmpty().ifBlank { account.siteName }
                val systemDesc = settingData?.optString("systemDesc").orEmpty()

                val membersData = membersJson?.optJSONObject("data")
                val totalMembers = membersData?.optJSONObject("pageInfo")?.optInt("totalNum", 0) ?: 0
                val membersArr = membersData?.optJSONArray("list") ?: JSONArray()
                val members = buildList {
                    for (i in 0 until membersArr.length()) {
                        val obj = membersArr.optJSONObject(i) ?: continue
                        val rawMax = obj.optString("sizeMax").toDoubleOrNull() ?: 0.0
                        val maxBytes = if (rawMax > 0 && rawMax < 100_000.0) (rawMax * 1024 * 1024 * 1024).toLong() else rawMax.toLong()
                        add(
                            KodAdminMember(
                                userId = obj.optString("userID"),
                                name = obj.optString("name"),
                                nickName = obj.optString("nickName"),
                                roleId = obj.optString("roleID"),
                                email = obj.optString("email"),
                                phone = obj.optString("phone"),
                                sizeUse = obj.optString("sizeUse").toLongOrNull() ?: obj.optLong("sizeUse", 0L),
                                sizeMax = maxBytes,
                                status = obj.optInt("status", 1),
                                lastLogin = obj.optString("lastLogin").toLongOrNull() ?: obj.optLong("lastLogin", 0L)
                            )
                        )
                    }
                }

                val groupsArr = groupsJson?.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
                val groups = buildList {
                    for (i in 0 until groupsArr.length()) {
                        val obj = groupsArr.optJSONObject(i) ?: continue
                        val rawMax = obj.optString("sizeMax").toDoubleOrNull() ?: 0.0
                        val maxBytes = if (rawMax > 0 && rawMax < 100_000.0) (rawMax * 1024 * 1024 * 1024).toLong() else rawMax.toLong()
                        add(
                            KodAdminGroup(
                                groupId = obj.optString("groupID"),
                                name = obj.optString("name"),
                                parentId = obj.optString("parentID"),
                                sizeUse = obj.optString("sizeUse").toLongOrNull() ?: obj.optLong("sizeUse", 0L),
                                sizeMax = maxBytes
                            )
                        )
                    }
                }

                val storagesArr = storagesJson?.optJSONArray("data") ?: JSONArray()
                val storages = buildList {
                    for (i in 0 until storagesArr.length()) {
                        val obj = storagesArr.optJSONObject(i) ?: continue
                        val rawMax = obj.optString("sizeMax").toDoubleOrNull() ?: 0.0
                        val maxBytes = if (rawMax > 0 && rawMax < 1_000_000.0) (rawMax * 1024 * 1024 * 1024).toLong() else rawMax.toLong()
                        add(
                            KodAdminStorage(
                                id = obj.optString("id"),
                                name = obj.optString("name"),
                                driver = obj.optString("driver"),
                                isDefault = obj.optString("default") == "1" || obj.optInt("default", 0) == 1,
                                sizeUse = obj.optString("sizeUse").toLongOrNull() ?: obj.optLong("sizeUse", 0L),
                                sizeMax = maxBytes
                            )
                        )
                    }
                }

                val pluginsData = pluginsJson?.optJSONObject("data")
                val plugins = buildList {
                    if (pluginsData != null) {
                        val keys = pluginsData.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val obj = pluginsData.optJSONObject(k) ?: continue
                            add(
                                KodAdminPlugin(
                                    id = obj.optString("id").ifBlank { k },
                                    name = obj.optString("name").ifBlank { k },
                                    title = obj.optString("title"),
                                    version = obj.optString("version"),
                                    category = obj.optString("category"),
                                    enabled = obj.optInt("status", 0) == 1
                                )
                            )
                        }
                    }
                }.sortedWith(compareByDescending<KodAdminPlugin> { it.enabled }.thenBy { it.name })

                KodAdminOverview(
                    systemName = systemName,
                    systemDesc = systemDesc,
                    totalMembers = totalMembers.coerceAtLeast(members.size),
                    members = members,
                    groups = groups,
                    storages = storages,
                    plugins = plugins
                )
            }
        }
    }

    suspend fun toggleAdminPlugin(
        account: KodAccount,
        pluginId: String,
        enable: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        apiClient.hydrateAccountSession(account)
        runCatching {
            val json = apiClient.executeKodGet(
                account = account,
                route = "admin/plugin/changeStatus",
                params = mapOf("app" to pluginId, "status" to if (enable) "1" else "0")
            )
            if (!isSuccess(json)) {
                error(extractError(json, "切换插件状态失败"))
            }
        }
    }

    private fun isSuccess(json: JSONObject): Boolean {
        val code = json.opt("code")
        return when (code) {
            is Boolean -> code
            is Number -> code.toInt() == 1 || code.toInt() == 200
            is String -> code.equals("true", true) || code == "1" || code == "200"
            else -> false
        }
    }

    private fun extractError(json: JSONObject, fallback: String): String {
        val info = json.optString("info").trim()
        if (info.isNotBlank() && info != "null" && info != "false") return info
        val dataStr = json.opt("data") as? String
        if (!dataStr.isNullOrBlank() && dataStr != "false") return dataStr
        return fallback
    }
}
