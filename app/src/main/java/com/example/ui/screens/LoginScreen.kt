package com.example.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.KodAccount
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import com.example.viewmodel.LoginFormState

@Composable
fun LoginScreen(
    form: LoginFormState,
    savedAccounts: List<KodAccount>,
    canCancel: Boolean,
    onUpdateForm: ((LoginFormState) -> LoginFormState) -> Unit,
    onProbeServer: () -> Unit,
    onRefreshCaptcha: () -> Unit,
    onSubmitLogin: () -> Unit,
    onSelectSavedAccount: (KodAccount) -> Unit,
    onCancel: () -> Unit
) {
    if (canCancel) {
        BackHandler(onBack = onCancel)
    }

    var passwordVisible by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Full-bleed Nextcloud-style azure hero backdrop
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_login_hero_1795282476290),
                contentDescription = "KodNext 私有云盘头图",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0x55003663),
                                Color(0xB8004C8A),
                                MaterialTheme.colorScheme.background
                            )
                        )
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Optional back button when adding another server
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (canCancel) {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.25f))
                            .testTag("login_cancel_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(if (canCancel) 16.dp else 38.dp))

            // Brand emblem & title
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Color(0xFF0074C8),
                shadowElevation = 10.dp,
                modifier = Modifier.size(68.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_kodcloud_logo_clean),
                    contentDescription = "KodNext Logo",
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "KodNext",
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "连接您的私有 KodBox 网盘服务器",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.90f)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Main login card (constrained width for tablets/foldables)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Server URL Section
                    Text(
                        text = "私有服务器地址",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    OutlinedTextField(
                        value = form.serverUrl,
                        onValueChange = { url -> onUpdateForm { it.copy(serverUrl = url, probeInfo = null) } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("server_url_input"),
                        placeholder = {
                            Text(
                                "https://cloud.yourdomain.com",
                                fontFamily = JetBrainsMonoFamily
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Public,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        trailingIcon = {
                            if (form.serverUrl.isNotBlank()) {
                                val isHttp = form.serverUrl.trim().startsWith("http://", ignoreCase = true)
                                Icon(
                                    imageVector = if (isHttp) Icons.Filled.LockOpen else Icons.Filled.Lock,
                                    contentDescription = if (isHttp) "HTTP 明文连接" else "HTTPS 加密连接",
                                    tint = if (isHttp) StatusWarning else StatusSuccess,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next
                        ),
                        shape = RoundedCornerShape(14.dp)
                    )

                    // Probe button + connection status pill
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val isHttp = form.serverUrl.trim().startsWith("http://", ignoreCase = true)
                        if (form.probeInfo != null && form.probeInfo.reachable) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = StatusSuccess,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = buildString {
                                        append(form.probeInfo.siteName)
                                        if (form.probeInfo.version.isNotBlank()) {
                                            append(" v${form.probeInfo.version}")
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StatusSuccess,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        } else if (isHttp) {
                            Text(
                                text = "局域网 HTTP 连接（公网建议使用 HTTPS）",
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusWarning,
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            Text(
                                text = "支持域名、公网 IP 或内网端口地址",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        OutlinedButton(
                            onClick = onProbeServer,
                            enabled = !form.isProbing && form.serverUrl.isNotBlank(),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("probe_server_button")
                        ) {
                            if (form.isProbing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("检测中", style = MaterialTheme.typography.labelMedium)
                            } else {
                                Text("检测地址", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    // Auth mode switcher: Password vs AccessToken
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SegmentedButton(
                            selected = !form.useTokenMode,
                            onClick = { onUpdateForm { it.copy(useTokenMode = false) } },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            modifier = Modifier.testTag("auth_mode_password")
                        ) {
                            Text("账号密码登录")
                        }
                        SegmentedButton(
                            selected = form.useTokenMode,
                            onClick = { onUpdateForm { it.copy(useTokenMode = true) } },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            modifier = Modifier.testTag("auth_mode_token")
                        ) {
                            Text("AccessToken")
                        }
                    }

                    if (!form.useTokenMode) {
                        OutlinedTextField(
                            value = form.username,
                            onValueChange = { v -> onUpdateForm { it.copy(username = v) } },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("username_input"),
                            label = { Text("账号 / 邮箱 / 手机号") },
                            leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            shape = RoundedCornerShape(14.dp)
                        )

                        OutlinedTextField(
                            value = form.password,
                            onValueChange = { v -> onUpdateForm { it.copy(password = v) } },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("password_input"),
                            label = { Text("登录密码") },
                            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                                    )
                                }
                            },
                            singleLine = true,
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { onSubmitLogin() }),
                            shape = RoundedCornerShape(14.dp)
                        )

                        // Captcha section when required by private KodBox server
                        val showCaptcha = form.probeInfo?.needCheckCode == true || form.captchaBytes != null
                        AnimatedVisibility(visible = showCaptcha) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = form.checkCode,
                                    onValueChange = { v -> onUpdateForm { it.copy(checkCode = v) } },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("captcha_input"),
                                    label = { Text("验证码") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                val captchaBitmap = remember(form.captchaBytes) {
                                    form.captchaBytes?.let { bytes ->
                                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    modifier = Modifier
                                        .height(52.dp)
                                        .width(108.dp)
                                        .clickable(onClick = onRefreshCaptcha)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        if (captchaBitmap != null) {
                                            Image(
                                                bitmap = captchaBitmap,
                                                contentDescription = "点击刷新验证码",
                                                contentScale = ContentScale.Fit,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Filled.Refresh,
                                                contentDescription = "获取验证码"
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = form.accessToken,
                            onValueChange = { v -> onUpdateForm { it.copy(accessToken = v) } },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("token_input"),
                            label = { Text("KodBox AccessToken") },
                            placeholder = { Text("粘贴您的 API 访问令牌") },
                            leadingIcon = { Icon(Icons.Filled.Key, contentDescription = null) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { onSubmitLogin() }),
                            shape = RoundedCornerShape(14.dp)
                        )
                        Text(
                            text = "适用于开启二次验证、SSO 单点登录或反向代理鉴权的私有 KodBox 实例。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Error message banner
                    AnimatedVisibility(visible = !form.errorMessage.isNullOrBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = form.errorMessage.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    // Primary Login Button
                    Button(
                        onClick = onSubmitLogin,
                        enabled = !form.isLoggingIn && form.serverUrl.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("login_submit_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (form.isLoggingIn) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.4.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("正在登录网盘…", style = MaterialTheme.typography.labelLarge)
                        } else {
                            Icon(Icons.Filled.Cloud, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("登录私有 KodBox", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }

            // Saved accounts quick switcher if available
            if (savedAccounts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(22.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "已保存的网盘账号",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                    savedAccounts.forEach { acc ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 1.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectSavedAccount(acc) }
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = acc.displayName.take(1).uppercase(),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(acc.displayName, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        acc.hostLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}
