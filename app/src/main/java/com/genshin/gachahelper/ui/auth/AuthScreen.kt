package com.genshin.gachahelper.ui.auth

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.genshin.gachahelper.auth.GameRole
import com.genshin.gachahelper.ui.GlassSurface
import com.genshin.gachahelper.ui.theme.WishShapes
import com.genshin.gachahelper.ui.theme.wishAccentGold
import com.genshin.gachahelper.ui.theme.wishOnPrimaryFill
import com.genshin.gachahelper.ui.theme.wishSkyBackground

@Composable
fun AuthScreen(
    navController: NavController,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize().wishSkyBackground()) {
        Box(modifier = Modifier.weight(1f)) {
            when (uiState.phase) {
                AuthPhase.LOADING -> LoadingView(uiState.statusText)
                AuthPhase.QR_DISPLAY -> QrCodeView(
                    bitmap = uiState.qrBitmap,
                    statusText = uiState.statusText,
                    error = uiState.error,
                    onRefresh = { viewModel.refreshQrCode() }
                )
                AuthPhase.QR_SCANNED -> ScannedView(statusText = uiState.statusText)
                AuthPhase.EXCHANGING_TOKEN -> LoadingView(uiState.statusText)
                AuthPhase.FETCHING_ROLES -> LoadingView(uiState.statusText)
                AuthPhase.ROLE_SELECT -> RoleSelectView(
                    roles = uiState.gameRoles,
                    error = uiState.error,
                    onSelect = { viewModel.selectRole(it) }
                )
                AuthPhase.GENERATING_KEY -> LoadingView(uiState.statusText)
                AuthPhase.DONE -> DoneView(
                    role = uiState.selectedRole,
                    onConfirm = { navController.navigateUp() }
                )
            }
        }
    }
}

@Composable
fun QrCodeView(
    bitmap: Bitmap?,
    statusText: String,
    error: String?,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        if (bitmap != null) {
            Card(
                modifier = Modifier.size(260.dp),
                shape = WishShapes.lg,
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "登录二维码",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "用米游社 App 扫码登录：\n" +
                    "· 同一台手机：把米游社 App 开成分屏 / 小窗，与本页并排后再点「我的 → 扫一扫」\n" +
                    "· 有另一台设备：直接用另一台设备的米游社扫码\n" +
                    "· 截图保存后再扫无效",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(onClick = onRefresh) {
                Text("刷新二维码")
            }
        } else {
            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onRefresh) {
                    Text("重新获取二维码")
                }
            } else {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }


        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun ScannedView(
    statusText: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            modifier = Modifier.padding(32.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = statusText.ifBlank { "已扫描，请在米游社中确认登录" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = wishOnPrimaryFill(),
                    textAlign = TextAlign.Center
                )
            }
        }


        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun LoadingView(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun RoleSelectView(
    roles: List<GameRole>,
    error: String?,
    onSelect: (GameRole) -> Unit
) {
    var selectedUid by remember { mutableStateOf(roles.firstOrNull()?.uid ?: "") }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "选择要同步的原神角色",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp)
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(roles) { role ->
                GlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { selectedUid = role.uid },
                    borderStroke = if (selectedUid == role.uid)
                        BorderStroke(1.5.dp, wishAccentGold())
                    else
                        null
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedUid == role.uid,
                            onClick = { selectedUid = role.uid }
                        )
                        Spacer(modifier = Modifier.padding(4.dp))
                        Column {
                            Text(
                                text = role.nickname,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "UID: ${role.uid}  等级: ${role.level}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (error != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

        }

        Button(
            onClick = {
                roles.find { it.uid == selectedUid }?.let { onSelect(it) }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text("确认选择")
        }
    }
}

@Composable
fun DoneView(
    role: GameRole?,
    onConfirm: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "授权成功",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        role?.let {
            Text(
                text = it.nickname,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "UID: ${it.uid}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "可以开始同步你的抽卡记录了",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onConfirm,
            modifier = Modifier.padding(horizontal = 48.dp)
        ) {
            Text("完成")
        }
    }
}
