package org.sonora.android.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sonora.android.R
import org.sonora.android.api.HealthStatus
import org.sonora.android.api.ProviderHealth
import org.sonora.android.ui.components.LocalBottomChromeInset
import org.sonora.android.ui.theme.SonoraAmoledBlack
import org.sonora.android.ui.theme.SonoraTheme
import org.sonora.android.ui.theme.SonoraDivider
import org.sonora.android.ui.theme.SonoraEmerald
import org.sonora.android.ui.theme.SonoraPurple
import org.sonora.android.ui.theme.SonoraStatusDegraded
import org.sonora.android.ui.theme.SonoraStatusOffline
import org.sonora.android.ui.theme.SonoraStatusOnline
import org.sonora.android.ui.theme.SonoraSurfaceCard
import org.sonora.android.ui.theme.SonoraSurfaceHighlight
import org.sonora.android.ui.theme.SonoraTextPrimary
import org.sonora.android.ui.theme.SonoraTextSecondary
import org.sonora.android.ui.theme.SonoraTextTertiary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsScreen(
    healthList: List<ProviderHealth>,
    isRefreshing: Boolean,
    onRefreshAll: () -> Unit,
    modifier: Modifier = Modifier,
    onBackClick: () -> Unit = {}
) {
    val totalCount = healthList.size
    val onlineCount = healthList.count { it.status == HealthStatus.ONLINE }
    val degradedCount = healthList.count { it.status == HealthStatus.DEGRADED }
    val offlineCount = healthList.count { it.status == HealthStatus.OFFLINE }
    val avgLatency = if (onlineCount > 0) {
        healthList.filter { it.status == HealthStatus.ONLINE }.map { it.latencyMs }.average().toLong()
    } else 0L

    Surface(
        modifier = modifier.fillMaxSize(),
        color = if (SonoraTheme.colors.isLiquidGlass) Color.Transparent else SonoraAmoledBlack
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = LocalBottomChromeInset.current + 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onBackClick,
                            modifier = Modifier
                                .size(40.dp)
                                .background(SonoraSurfaceHighlight, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.diagnostics_back),
                                tint = SonoraTextPrimary
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.diagnostics_title),
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = SonoraTextPrimary
                                )
                            )
                            Text(
                                text = stringResource(R.string.diagnostics_subtitle),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = SonoraTextSecondary
                                )
                            )
                        }
                    }

                    // Refresh Button
                    val infiniteTransition = rememberInfiniteTransition(label = "rotation")
                    val rotation by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "spin"
                    )

                    Button(
                        onClick = onRefreshAll,
                        enabled = !isRefreshing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SonoraEmerald,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.diagnostics_refresh),
                            modifier = Modifier
                                .size(18.dp)
                                .then(if (isRefreshing) Modifier.rotate(rotation) else Modifier)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isRefreshing) stringResource(R.string.diagnostics_testing) else stringResource(R.string.diagnostics_test_all),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // Overview Metrics Grid
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Online Pill Card
                    StatusMetricCard(
                        title = stringResource(R.string.diagnostics_status_online),
                        count = onlineCount,
                        total = totalCount,
                        color = SonoraStatusOnline,
                        modifier = Modifier.weight(1f)
                    )
                    // Degraded Pill Card
                    StatusMetricCard(
                        title = stringResource(R.string.diagnostics_status_degraded),
                        count = degradedCount,
                        total = totalCount,
                        color = SonoraStatusDegraded,
                        modifier = Modifier.weight(1f)
                    )
                    // Offline Pill Card
                    StatusMetricCard(
                        title = stringResource(R.string.diagnostics_status_offline),
                        count = offlineCount,
                        total = totalCount,
                        color = SonoraStatusOffline,
                        modifier = Modifier.weight(1f)
                    )
                    // Avg Latency Card
                    Card(
                        modifier = Modifier.weight(1.2f),
                        colors = CardDefaults.cardColors(containerColor = SonoraSurfaceCard),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SonoraDivider),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = null,
                                    tint = SonoraPurple,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.diagnostics_avg_ping),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = SonoraTextSecondary
                                    )
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.diagnostics_latency_ms, avgLatency),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = SonoraTextPrimary
                                )
                            )
                        }
                    }
                }
            }

            // Architecture Info Banner
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = SonoraSurfaceHighlight
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SonoraDivider),
                    shape = SonoraTheme.cardShape
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(SonoraEmerald.copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = SonoraEmerald,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.diagnostics_architecture_title),
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = SonoraTextPrimary
                                )
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.diagnostics_architecture_body),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = SonoraTextSecondary,
                                    lineHeight = 16.sp
                                )
                            )
                        }
                    }
                }
            }

            // Endpoints Header
            item {
                Text(
                    text = stringResource(R.string.diagnostics_endpoints_header, healthList.size),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = SonoraTextPrimary
                    ),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // Endpoints List
            if (healthList.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = SonoraEmerald,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.diagnostics_querying),
                                color = SonoraTextSecondary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            } else {
                items(healthList) { provider ->
                    ProviderHealthCard(provider = provider)
                }
            }
        }
    }
}

@Composable
private fun StatusMetricCard(
    title: String,
    count: Int,
    total: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = SonoraSurfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, SonoraDivider),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(color, CircleShape)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = SonoraTextSecondary
                    )
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "$count",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            )
        }
    }
}

@Composable
fun ProviderHealthCard(
    provider: ProviderHealth,
    modifier: Modifier = Modifier
) {
    val statusColor = when (provider.status) {
        HealthStatus.ONLINE -> SonoraStatusOnline
        HealthStatus.DEGRADED -> SonoraStatusDegraded
        HealthStatus.OFFLINE -> SonoraStatusOffline
    }

    val statusIcon = when (provider.status) {
        HealthStatus.ONLINE -> Icons.Default.CheckCircle
        HealthStatus.DEGRADED -> Icons.Default.Warning
        HealthStatus.OFFLINE -> Icons.Default.WifiOff
    }

    val latencyColor = when {
        provider.latencyMs <= 0L -> SonoraTextTertiary
        provider.latencyMs < 300L -> SonoraStatusOnline
        provider.latencyMs < 800L -> SonoraStatusDegraded
        else -> SonoraStatusOffline
    }

    val neverText = stringResource(R.string.diagnostics_never)
    val timeFormatted = remember(provider.lastChecked) {
        if (provider.lastChecked <= 0) neverText
        else {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            sdf.format(Date(provider.lastChecked))
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SonoraSurfaceCard),
        shape = SonoraTheme.cardShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, SonoraDivider)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Top Row: Provider Name & Status Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = provider.providerName,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = SonoraTextPrimary
                    )
                )

                // Status Pill
                Row(
                    modifier = Modifier
                        .background(statusColor.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(statusColor, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = provider.status.name,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = statusColor
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Endpoint URL
            Text(
                text = provider.endpointUrl,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = SonoraTextTertiary
                ),
                maxLines = 1
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Bottom Metrics Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Latency Badge
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        tint = latencyColor,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (provider.latencyMs > 0) stringResource(R.string.diagnostics_latency_value, provider.latencyMs) else stringResource(R.string.diagnostics_timeout_failed),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = latencyColor
                        )
                    )
                }

                // Checked Timestamp
                Text(
                    text = stringResource(R.string.diagnostics_checked, timeFormatted),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = SonoraTextTertiary
                    )
                )
            }

            // Error or warning message banner if present
            if (!provider.message.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SonoraSurfaceHighlight, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = provider.message,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = SonoraStatusDegraded
                        )
                    )
                }
            }
        }
    }
}
