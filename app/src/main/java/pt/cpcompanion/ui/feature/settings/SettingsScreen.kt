@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package pt.cpcompanion.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import pt.cpcompanion.R
import pt.cpcompanion.domain.StationTimeZoneResolver
import pt.cpcompanion.model.PassengerPhase
import pt.cpcompanion.model.Station
import pt.cpcompanion.model.StationBoardEntry
import pt.cpcompanion.model.Ticket
import pt.cpcompanion.model.TicketDirection
import pt.cpcompanion.model.ThemeMode
import pt.cpcompanion.model.TicketActivationMethod
import pt.cpcompanion.model.TicketAutomationState
import pt.cpcompanion.model.TicketSource
import pt.cpcompanion.model.TrackingSnapshot
import pt.cpcompanion.model.TrainServiceEntry
import pt.cpcompanion.model.TrainTrip
import pt.cpcompanion.model.isPastPassengerSegment
import pt.cpcompanion.ui.feature.tickets.TicketsScreen
import pt.cpcompanion.ui.theme.CpExpressiveTheme

@Composable
internal fun SettingsScreen(
    state: MainUiState,
    setSmsAutoScan: (Boolean) -> Unit,
    setThemeMode: (ThemeMode) -> Unit,
    refreshDiagnostics: () -> Unit,
) {
    val context = LocalContext.current
    var notificationAccessEnabled by remember { mutableStateOf(isNotificationListenerEnabled(context)) }
    var liveUpdatePromotionAllowed by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 36 ||
                context.getSystemService(android.app.NotificationManager::class.java)
                    .canPostPromotedNotifications(),
        )
    }
    var showNotificationDisclosure by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationAccessEnabled = isNotificationListenerEnabled(context)
        if (Build.VERSION.SDK_INT >= 36) {
            liveUpdatePromotionAllowed =
                context.getSystemService(android.app.NotificationManager::class.java)
                    .canPostPromotedNotifications()
        }
    }

    fun openNotificationSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            },
        )
    }

    fun openLiveUpdateSettings() {
        if (Build.VERSION.SDK_INT < 36) return

        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS,
                ).apply {
                    putExtra(
                        Settings.EXTRA_APP_PACKAGE,
                        context.packageName,
                    )
                },
            )
        }.onFailure {
            openNotificationSettings()
        }
    }

    fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = "package:${context.packageName}".toUri()
                    },
                )
            }.onFailure {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = "package:${context.packageName}".toUri()
                })
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(stringResource(R.string.appearance), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.appearance_body),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.themeMode == mode,
                        onClick = { setThemeMode(mode) },
                        label = {
                            Text(
                                stringResource(
                                    when (mode) {
                                        ThemeMode.SYSTEM -> R.string.system
                                        ThemeMode.LIGHT -> R.string.light
                                        ThemeMode.DARK -> R.string.dark
                                    },
                                ),
                            )
                        },
                    )
                }
            }
        }
        item {
            Text(stringResource(R.string.automatic_tracking), style = MaterialTheme.typography.headlineMedium)
        }
        item {
            Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.t60_activation), style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (state.exactAlarmAllowed) {
                            stringResource(R.string.exact_alarm_access_available)
                        } else {
                            stringResource(R.string.exact_alarm_access_unavailable)
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!state.exactAlarmAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        OutlinedButton(onClick = ::openExactAlarmSettings) { Text(stringResource(R.string.allow_exact_alarms)) }
                    }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.notification_permission), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.notification_tracking_requirement),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = ::openNotificationSettings) { Text(stringResource(R.string.open_notification_settings)) }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= 36) {
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(18.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            stringResource(
                                R.string.live_update_promotion,
                            ),
                            style =
                                MaterialTheme.typography.titleMedium,
                        )

                        Text(
                            stringResource(
                                if (liveUpdatePromotionAllowed) {
                                    R.string.promotion_allowed
                                } else {
                                    R.string.promotion_unavailable
                                },
                            ),
                            color =
                                MaterialTheme.colorScheme
                                    .onSurfaceVariant,
                        )

                        Text(
                            stringResource(
                                R.string.live_update_promotion_body,
                            ),
                            color =
                                MaterialTheme.colorScheme
                                    .onSurfaceVariant,
                        )

                        OutlinedButton(
                            onClick = ::openLiveUpdateSettings,
                        ) {
                            Text(
                                stringResource(
                                    R.string.manage_live_updates,
                                ),
                            )
                        }
                    }
                }
            }
        }

        item {
            Text(stringResource(R.string.automatic_cp_access), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.automatic_cp_access_detailed),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { Text(stringResource(R.string.sms_ticket_import), style = MaterialTheme.typography.headlineMedium) }
        item {
            Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.automatic_inbox_import), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.automatic_inbox_import_detailed),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.smsImportSettings.automaticSmsImportEnabled,
                        onCheckedChange = setSmsAutoScan,
                    )
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.detect_cp_sms_notifications), style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (notificationAccessEnabled) {
                            stringResource(R.string.notification_detection_enabled)
                        } else {
                            stringResource(R.string.notification_detection_optional)
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { showNotificationDisclosure = true }) {
                        Text(if (notificationAccessEnabled) stringResource(R.string.manage_access) else stringResource(R.string.enable_detection))
                    }
                }
            }
        }
        item {
            ExpressiveInfoCard(
                stringResource(R.string.live_tracking_behavior),
                stringResource(R.string.live_tracking_behavior_body),
            )
        }
        item {
            Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.private_diagnostics), style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (state.requestCounters.isEmpty()) stringResource(R.string.no_request_counters) else state.requestCounters.entries
                            .sortedBy { it.key }
                            .joinToString("\n") { "${it.key}: ${it.value}" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = refreshDiagnostics) { Text(stringResource(R.string.refresh_counters)) }
                }
            }
        }
    }
    if (showNotificationDisclosure) {
        AlertDialog(
            onDismissRequest = { showNotificationDisclosure = false },
            title = { Text(stringResource(R.string.automatic_cp_sms_detection)) },
            text = {
                Text(
                    stringResource(R.string.notification_access_disclosure),
                )
            },
            confirmButton = {
                Button(onClick = {
                    showNotificationDisclosure = false
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }) { Text(stringResource(R.string.open_settings)) }
            },
            dismissButton = { TextButton(onClick = { showNotificationDisclosure = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

