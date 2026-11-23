package com.example.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCodeScanner
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.KodAccount
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StatusSuccess
import com.example.viewmodel.LoginFormState
import com.example.viewmodel.LoginStep

/**
 * Modern 2-step login experience:
 * - Step 1: Clean full-width single-line server URL probe + QR code scan login
 * - Step 2: Unlocked after server connectivity verification succeeds (Account + Password + Captcha)
 */
@Composable
fun LoginScreen(
    form: LoginFormState,
    savedAccounts: List<KodAccount>,
    canCancel: Boolean,
    onUpdateForm: ((LoginFormState) -> LoginFormState) -> Unit,
    onProbeServer: () -> Unit,
    onBackToServerStep: () -> Unit,
    onOpenQrScanner: () -> Unit,
    onRefreshCaptcha: () -> Unit,
    onSubmitLogin: () -> Unit,
    onSelectSavedAccount: (KodAccount) -> Unit,
    onCancel: () -> Unit
) {
    BackHandler(enabled = form.step == LoginStep.CREDENTIALS || canCancel) {
        if (form.step == LoginStep.CREDENTIALS) {
            onBackToServerStep()
        } else if (canCancel) {
            onCancel()
        }
    }

    var passwordVisible by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0067B8),
                        Color(0xFF0082C9),
                        Color(0xFF005B9E)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top bar navigation (Back to Step 1 or Cancel Add Account)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when {
                    form.step == LoginStep.CREDENTIALS -> {
                        IconButton(
                            onClick = onBackToServerStep,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.16f))
                                .testTag("login_step_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回修改服务器地址",
                                tint = Color.White
                            )
                        }
                    }
                    canCancel -> {
                        IconButton(
                            onClick = onCancel,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.16f))
                                .testTag("login_cancel_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = Color.White
                            )
                        }
                    }
                    else -> Spacer(modifier = Modifier.height(48.dp))
                }
            }

            Spacer(modifier = Modifier.height(if (form.step == LoginStep.SERVER_URL) 42.dp else 18.dp))

            // Brand emblem & title
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Color.White.copy(alpha = 0.18f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
                modifier = Modifier.size(72.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_kodcloud_logo_clean),
                    contentDescription = "KodNext Logo",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp)
                        .clip(RoundedCornerShape(16.dp))
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "KodNext",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (form.step == LoginStep.SERVER_URL) {
                    "连接您的云端网盘"
                } else {
                    form.probeInfo?.siteName?.ifBlank { form.serverUrl } ?: form.serverUrl
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.88f)
            )

            Spacer(modifier = Modifier.height(34.dp))

            AnimatedContent(
                targetState = form.step,
                transitionSpec = {
                    if (targetState == LoginStep.CREDENTIALS) {
                        (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
                    } else {
                        (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
                    }
                },
                label = "login_step_transition",
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 460.dp)
            ) { step ->
                when (step) {
                    LoginStep.SERVER_URL -> {
                        // Step 1: Nextcloud-inspired clean wide single-line server URL input + QR scan button
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            OutlinedTextField(
                                value = form.serverUrl,
                                onValueChange = { url ->
                                    onUpdateForm { it.copy(serverUrl = url, probeInfo = null, errorMessage = null, infoMessage = null) }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("server_url_input"),
                                label = {
                                    Text(
                                        text = "服务器地址",
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                placeholder = {
                                    Text(
                                        text = "https://cloud.example.com",
                                        fontFamily = JetBrainsMonoFamily,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        color = Color.White.copy(alpha = 0.62f)
                                    )
                                },
                                trailingIcon = {
                                    when {
                                        form.isProbing || form.isLoggingIn -> {
                                            CircularProgressIndicator(
                                                color = Color.White,
                                                modifier = Modifier
                                                    .padding(end = 4.dp)
                                                    .size(20.dp),
                                                strokeWidth = 2.2.dp
                                            )
                                        }
                                        else -> {
                                            IconButton(
                                                onClick = onProbeServer,
                                                enabled = form.serverUrl.isNotBlank(),
                                                modifier = Modifier.testTag("probe_server_button")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                                    contentDescription = "检测服务器并继续",
                                                    tint = if (form.serverUrl.isNotBlank()) Color.White else Color.White.copy(alpha = 0.45f)
                                                )
                                            }
                                        }
                                    }
                                },
                                singleLine = true,
                                maxLines = 1,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    fontFamily = JetBrainsMonoFamily,
                                    color = Color.White
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    cursorColor = Color.White,
                                    focusedBorderColor = Color.White,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.78f),
                                    focusedLabelColor = Color.White,
                                    unfocusedLabelColor = Color.White.copy(alpha = 0.85f),
                                    focusedContainerColor = Color.White.copy(alpha = 0.10f),
                                    unfocusedContainerColor = Color.White.copy(alpha = 0.08f)
                                ),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Go
                                ),
                                keyboardActions = KeyboardActions(onGo = { onProbeServer() }),
                                shape = RoundedCornerShape(14.dp)
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = "输入浏览器打开网盘时显示的网址，自动检测服务器连通状态",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.84f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp)
                            )

                            AnimatedVisibility(visible = !form.infoMessage.isNullOrBlank()) {
                                Surface(
                                    color = Color.White.copy(alpha = 0.20f),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.36f)),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 12.dp)
                                        .testTag("login_step1_info_banner")
                                ) {
                                    Text(
                                        text = form.infoMessage.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }

                            AnimatedVisibility(visible = !form.errorMessage.isNullOrBlank()) {
                                Surface(
                                    color = Color(0xFF991B1B).copy(alpha = 0.88f),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 14.dp)
                                        .testTag("login_step1_error_banner")
                                ) {
                                    Text(
                                        text = form.errorMessage.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(36.dp))

                            // Prominent centered QR Code Login entry (matches Nextcloud hero + web Account Center QR)
                            Surface(
                                onClick = onOpenQrScanner,
                                shape = RoundedCornerShape(20.dp),
                                color = Color.White.copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
                                modifier = Modifier.testTag("login_qr_scan_button")
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.QrCodeScanner,
                                        contentDescription = "扫码登录",
                                        tint = Color.White,
                                        modifier = Modifier.size(44.dp)
                                    )
                                    Text(
                                        text = "扫码登录",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "扫描网页端「账号中心」右上角二维码",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.78f),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    LoginStep.CREDENTIALS -> {
                        // Step 2: Clean elevated credentials card (only shown once server is verified!)
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                // Verified server chip + switch server link
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = StatusSuccess.copy(alpha = 0.10f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.CheckCircle,
                                            contentDescription = null,
                                            tint = StatusSuccess,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = buildString {
                                                    append(form.probeInfo?.siteName?.ifBlank { "网盘服务器已连通" } ?: "网盘服务器已连通")
                                                    form.probeInfo?.version?.takeIf { it.isNotBlank() }?.let { append(" · v$it") }
                                                },
                                                style = MaterialTheme.typography.labelLarge,
                                                color = StatusSuccess,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = form.serverUrl,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = JetBrainsMonoFamily,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        TextButton(
                                            onClick = onBackToServerStep,
                                            modifier = Modifier.testTag("change_server_button")
                                        ) {
                                            Text("更换", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }

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

                                // Captcha shown only when required by server
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

                                AnimatedVisibility(visible = !form.infoMessage.isNullOrBlank()) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("login_step2_info_banner")
                                    ) {
                                        Text(
                                            text = form.infoMessage.orEmpty(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.padding(12.dp)
                                        )
                                    }
                                }

                                AnimatedVisibility(visible = !form.errorMessage.isNullOrBlank()) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.errorContainer,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("login_step2_error_banner")
                                    ) {
                                        Text(
                                            text = form.errorMessage.orEmpty(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier.padding(12.dp)
                                        )
                                    }
                                }

                                Button(
                                    onClick = onSubmitLogin,
                                    enabled = !form.isLoggingIn && form.username.isNotBlank() && form.password.isNotBlank(),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp)
                                        .testTag("login_submit_button"),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    if (form.isLoggingIn) {
                                        CircularProgressIndicator(
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.2.dp
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text("正在登录…", style = MaterialTheme.typography.labelLarge)
                                    } else {
                                        Text(
                                            text = "登录网盘",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Saved accounts quick switcher
            if (savedAccounts.isNotEmpty() && form.step == LoginStep.SERVER_URL) {
                Spacer(modifier = Modifier.height(28.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "已保存的账号",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.padding(start = 6.dp)
                    )
                    savedAccounts.forEach { acc ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color.White.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onSelectSavedAccount(acc) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.24f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = acc.displayName.take(1).uppercase(),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = acc.displayName,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = acc.hostLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.78f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

